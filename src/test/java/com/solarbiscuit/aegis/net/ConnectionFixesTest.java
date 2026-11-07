package com.solarbiscuit.aegis.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.zip.DataFormatException;

import org.junit.jupiter.api.Test;

import com.solarbiscuit.aegis.net.compression.AegisCompressDecoder;
import com.solarbiscuit.aegis.net.compression.AegisCompressEncoder;
import com.solarbiscuit.aegis.net.compression.CompressionInstaller;
import com.solarbiscuit.aegis.net.flush.FlushControl;
import com.velocitypowered.natives.compression.JavaVelocityCompressor;
import com.velocitypowered.natives.compression.VelocityCompressor;
import com.velocitypowered.natives.util.BufferPreference;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.network.protocol.PacketFlow;

/** Tests for the 0.2.1 connection fixes. Each one failed (or could not be written) before its fix. */
class ConnectionFixesTest {

    // ---- login compression order ----

    /** Swallows writes and keeps their promises open, like a socket whose send buffer is full. */
    private static final class StalledSocket extends ChannelOutboundHandlerAdapter {
        final List<ChannelPromise> promises = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ReferenceCountUtil.release(msg);
            this.promises.add(promise);
        }

        @Override
        public void flush(ChannelHandlerContext ctx) {
        }
    }

    /** Stands in for the compression encoder: records each packet together with whether compression was on. */
    private static final class Recorder extends ChannelOutboundHandlerAdapter {
        final AtomicBoolean compressionOn = new AtomicBoolean();
        final List<String> written = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            this.written.add(msg + (this.compressionOn.get() ? ":compressed" : ":plain"));
            ctx.write(msg, promise);
        }
    }

    private static List<String> login(boolean fixed) throws Exception {
        DefaultEventLoopGroup group = new DefaultEventLoopGroup(1);
        try {
            Channel ch = new LocalChannel();
            StalledSocket socket = new StalledSocket();
            Recorder recorder = new Recorder();
            ch.pipeline().addLast(socket, recorder);
            group.register(ch).sync();

            // What ServerLoginPacketListenerImpl.handleAcceptedLogin does from the server thread: each Connection.send
            // becomes one event loop task.
            if (fixed) {
                ch.eventLoop().execute(() -> ch.writeAndFlush("compression"));
                LoginCompressionOrder.afterQueuedWrites(ch, () -> recorder.compressionOn.set(true));
            } else {
                ch.eventLoop().execute(() -> ch.writeAndFlush("compression").addListener(f -> recorder.compressionOn.set(true)));
            }
            ch.eventLoop().execute(() -> ch.writeAndFlush("profile"));
            ch.eventLoop().submit(() -> {
            }).sync();
            ch.eventLoop().submit(() -> socket.promises.forEach(ChannelPromise::setSuccess)).sync();
            ch.close().sync();
            return new ArrayList<>(recorder.written);
        } finally {
            group.shutdownGracefully().sync();
        }
    }

    @Test
    void vanillaOrderSendsTheProfileUncompressedWhenTheSocketIsBusy() throws Exception {
        assertEquals(List.of("compression:plain", "profile:plain"), login(false));
    }

    @Test
    void compressionIsOnBeforeTheProfilePacket() throws Exception {
        assertEquals(List.of("compression:plain", "profile:compressed"), login(true));
    }

    @Test
    void onTheEventLoopCompressionIsTurnedOnInline() {
        EmbeddedChannel ch = new EmbeddedChannel();
        AtomicBoolean on = new AtomicBoolean();
        LoginCompressionOrder.afterQueuedWrites(ch, () -> on.set(true));
        assertTrue(on.get());
        assertFalse(ch.finish());
    }

    // ---- compressor never leaked by setupCompression ----

    /** Java compressor that counts how often it is created and closed. */
    private static final class Counting implements Supplier<VelocityCompressor> {
        final AtomicInteger created = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();

        @Override
        public VelocityCompressor get() {
            this.created.incrementAndGet();
            VelocityCompressor delegate = JavaVelocityCompressor.FACTORY.create(6);
            return new VelocityCompressor() {
                @Override
                public void inflate(ByteBuf source, ByteBuf destination, int uncompressedSize) throws DataFormatException {
                    delegate.inflate(source, destination, uncompressedSize);
                }

                @Override
                public void deflate(ByteBuf source, ByteBuf destination) throws DataFormatException {
                    delegate.deflate(source, destination);
                }

                @Override
                public BufferPreference preferredBufferType() {
                    return delegate.preferredBufferType();
                }

                @Override
                public void close() {
                    Counting.this.closed.incrementAndGet();
                    delegate.close();
                }
            };
        }
    }

    private static EmbeddedChannel connection(boolean withEncoder) {
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast("decoder", new ChannelDuplexHandler());
        if (withEncoder) {
            ch.pipeline().addLast("encoder", new ChannelDuplexHandler());
        }
        return ch;
    }

    @Test
    void compressionIsInstalledAndFreedOnClose() {
        Counting compressors = new Counting();
        EmbeddedChannel ch = connection(true);
        assertTrue(CompressionInstaller.install(ch, PacketFlow.SERVERBOUND, 256, true, compressors, h -> {
        }));
        assertInstanceOf(AegisCompressDecoder.class, ch.pipeline().get("decompress"));
        assertInstanceOf(AegisCompressEncoder.class, ch.pipeline().get("compress"));
        // A new threshold reuses the handlers.
        assertTrue(CompressionInstaller.install(ch, PacketFlow.SERVERBOUND, 512, true, compressors, h -> {
        }));
        assertEquals(1, compressors.created.get());
        ch.close();
        assertEquals(1, compressors.closed.get());
        assertFalse(ch.finish());
    }

    @Test
    void closedConnectionGetsNoCompressor() {
        Counting compressors = new Counting();
        EmbeddedChannel ch = connection(true);
        ch.close(); // the pipeline is torn down: a handler added now would never be removed
        assertTrue(CompressionInstaller.install(ch, PacketFlow.SERVERBOUND, 256, true, compressors, h -> {
        }));
        assertEquals(compressors.created.get(), compressors.closed.get());
        assertFalse(ch.finish());
    }

    @Test
    void failedInstallReleasesTheUnattachedHalf() {
        Counting compressors = new Counting();
        EmbeddedChannel ch = connection(false); // no "encoder": adding "compress" before it throws
        assertThrows(NoSuchElementException.class, () -> CompressionInstaller.install(ch, PacketFlow.SERVERBOUND, 256, true, compressors, h -> {
        }));
        assertEquals(1, compressors.created.get());
        assertEquals(0, compressors.closed.get(), "the decoder that did get added still uses it");
        ch.close();
        assertEquals(1, compressors.closed.get());
        assertFalse(ch.finish());
    }

    // ---- read timeout only raised for logins ----

    @Test
    void readTimeoutIsRaisedOnceAndForeignHandlersAreKept() {
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast("timeout", new ReadTimeoutHandler(30));
        assertTrue(ReadTimeouts.raise(ch, 120));
        ReadTimeoutHandler raised = (ReadTimeoutHandler) ch.pipeline().get("timeout");
        assertInstanceOf(ReadTimeouts.Raised.class, raised);
        assertEquals(120_000L, raised.getReaderIdleTimeInMillis());
        assertFalse(ReadTimeouts.raise(ch, 120), "already raised");
        assertSame(raised, ch.pipeline().get("timeout"));
        ch.finishAndReleaseAll();

        EmbeddedChannel other = new EmbeddedChannel();
        ReadTimeoutHandler foreign = new ReadTimeoutHandler(45) {
        };
        other.pipeline().addLast("timeout", foreign);
        assertFalse(ReadTimeouts.raise(other, 120));
        assertSame(foreign, other.pipeline().get("timeout"));
        other.finishAndReleaseAll();
    }

    // ---- per-tick flush of writes nobody flushed ----

    @Test
    void tickFlushesWritesThatWereNeverFlushed() {
        FlushControl.Handler handler = new FlushControl.Handler();
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addFirst(FlushControl.HANDLER_NAME, handler);

        FlushControl.onConnectionTick(ch);
        assertNull(ch.readOutbound(), "nothing written, nothing to flush");

        ch.write(Unpooled.wrappedBuffer(new byte[100])); // a mod writing without flushing
        assertNull(ch.readOutbound());
        assertTrue(handler.hasUnflushedWrites());
        FlushControl.onConnectionTick(ch);
        ByteBuf out = ch.readOutbound();
        assertEquals(100, out.readableBytes());
        out.release();
        assertFalse(handler.hasUnflushedWrites());

        ch.writeAndFlush(Unpooled.wrappedBuffer(new byte[10]));
        assertFalse(handler.hasUnflushedWrites(), "vanilla's writeAndFlush leaves nothing for the tick");
        ((ByteBuf) ch.readOutbound()).release();
        assertFalse(ch.finish());
    }
}
