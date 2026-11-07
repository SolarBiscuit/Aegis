package com.solarbiscuit.aegis.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import com.solarbiscuit.aegis.net.cipher.AegisCipherDecoder;
import com.solarbiscuit.aegis.net.cipher.AegisCipherEncoder;
import com.solarbiscuit.aegis.net.compression.AegisCompressDecoder;
import com.solarbiscuit.aegis.net.compression.AegisCompressEncoder;
import com.solarbiscuit.aegis.net.compression.SharedCompressor;
import com.solarbiscuit.aegis.net.flush.FlushControl;
import com.velocitypowered.natives.compression.JavaVelocityCompressor;
import com.velocitypowered.natives.encryption.JavaVelocityCipher;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.protocol.PacketFlow;

/** Netty-level tests of the replacement handlers. Every test checks that no buffer is left unreleased. */
class PipelineTest {
    private static final Random RANDOM = new Random(1234);

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    private static byte[] compressible(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) ((i / 64) % 7);
        }
        return b;
    }

    private static byte[] readAll(EmbeddedChannel ch, boolean inbound) {
        ByteBuf all = Unpooled.buffer();
        Object o;
        while ((o = inbound ? ch.readInbound() : ch.readOutbound()) != null) {
            ByteBuf b = (ByteBuf) o;
            all.writeBytes(b);
            b.release();
        }
        return ByteBufUtil.getBytes(all);
    }

    @Test
    void framesRoundTripInPiecesAndSkipNullRuns() {
        EmbeddedChannel out = new EmbeddedChannel(new AegisLengthPrepender(PacketFlow.CLIENTBOUND, true));
        byte[][] packets = {randomBytes(1), randomBytes(127), randomBytes(128), randomBytes(70_000), randomBytes(5)};
        for (byte[] p : packets) {
            assertTrue(out.writeOutbound(Unpooled.wrappedBuffer(p)));
        }
        byte[] wire = readAll(out, false);
        // A run of zero bytes (null frames / "nullping") in the middle of the stream must be skipped.
        byte[] withZeros = new byte[wire.length + 300];
        int split = 1 + packets[0].length; // right after the first frame
        System.arraycopy(wire, 0, withZeros, 0, split);
        System.arraycopy(wire, split, withZeros, split + 300, wire.length - split);

        EmbeddedChannel in = new EmbeddedChannel(new AegisFrameDecoder(PacketFlow.CLIENTBOUND));
        int pos = 0;
        while (pos < withZeros.length) {
            int n = Math.min(withZeros.length - pos, 1 + RANDOM.nextInt(4000));
            in.writeInbound(Unpooled.wrappedBuffer(withZeros, pos, n));
            pos += n;
        }
        for (byte[] p : packets) {
            ByteBuf frame = in.readInbound();
            assertArrayEquals(p, ByteBufUtil.getBytes(frame));
            frame.release();
        }
        assertNull(in.readInbound());
        assertFalse(in.finish());
    }

    @Test
    void largeFrameNeedsFourByteHeaderAndIsAcceptedClientbound() {
        byte[] big = randomBytes(3 * 1024 * 1024); // > 2^21 - 1: does not fit a vanilla 3 byte length
        EmbeddedChannel out = new EmbeddedChannel(new AegisLengthPrepender(PacketFlow.CLIENTBOUND, true));
        out.writeOutbound(Unpooled.wrappedBuffer(big));
        byte[] wire = readAll(out, false);
        assertEquals(4, wire.length - big.length);

        EmbeddedChannel in = new EmbeddedChannel(new AegisFrameDecoder(PacketFlow.CLIENTBOUND));
        in.writeInbound(Unpooled.wrappedBuffer(wire));
        ByteBuf frame = in.readInbound();
        assertArrayEquals(big, ByteBufUtil.getBytes(frame));
        frame.release();
        assertFalse(in.finish());
    }

    @Test
    void oversizedFrameIsRejectedFromTheHeaderAlone() {
        // A 5 byte header claiming ~2 GiB, with no body: rejected immediately, nothing is buffered.
        ByteBuf header = Unpooled.buffer();
        VarInts.write(header, Integer.MAX_VALUE - 1);
        EmbeddedChannel in = new EmbeddedChannel(new AegisFrameDecoder(PacketFlow.CLIENTBOUND));
        assertThrows(CorruptedFrameException.class, () -> in.writeInbound(header));
        assertNull(in.readInbound());
        in.finishAndReleaseAll();
    }

    @Test
    void varIntLongerThanFiveBytesIsRejected() {
        EmbeddedChannel in = new EmbeddedChannel(new AegisFrameDecoder(PacketFlow.CLIENTBOUND));
        assertThrows(CorruptedFrameException.class,
                () -> in.writeInbound(Unpooled.wrappedBuffer(new byte[]{(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 1})));
        in.finishAndReleaseAll();
    }

    @Test
    void prependerRejectsFramesAboveTheLimitWithoutLeaking() {
        EmbeddedChannel out = new EmbeddedChannel(new AegisLengthPrepender(PacketFlow.CLIENTBOUND, true));
        ByteBuf huge = Unpooled.buffer(Limits.maxOutboundFrame(PacketFlow.CLIENTBOUND) + 1);
        huge.writerIndex(huge.capacity());
        assertThrows(EncoderException.class, () -> out.writeOutbound(huge));
        assertEquals(0, huge.refCnt());
        out.finishAndReleaseAll();
    }

    private static EmbeddedChannel compressionChannel(int threshold, PacketFlow receiving) {
        SharedCompressor shared = new SharedCompressor(JavaVelocityCompressor.FACTORY.create(6), 2);
        return new EmbeddedChannel(new AegisCompressDecoder(shared, receiving, threshold, true), new AegisCompressEncoder(shared, threshold));
    }

    @Test
    void compressionRoundTrip() {
        byte[][] packets = {randomBytes(10), compressible(300), randomBytes(5000), compressible(1_000_000), new byte[256]};
        EmbeddedChannel enc = compressionChannel(256, PacketFlow.CLIENTBOUND);
        EmbeddedChannel dec = compressionChannel(256, PacketFlow.CLIENTBOUND);
        for (byte[] p : packets) {
            enc.writeOutbound(Unpooled.wrappedBuffer(p));
            ByteBuf wire = enc.readOutbound();
            if (p.length >= 256 && p == packets[3]) {
                assertTrue(wire.readableBytes() < p.length / 10, "compressible data must compress");
            }
            dec.writeInbound(wire);
            ByteBuf back = dec.readInbound();
            assertArrayEquals(p, ByteBufUtil.getBytes(back));
            back.release();
        }
        assertFalse(enc.finish());
        assertFalse(dec.finish());
    }

    /** A compressed packet as vanilla would send it: VarInt claimed size + zlib stream. */
    private static ByteBuf compressedPacket(int claimed, byte[] data) {
        java.util.zip.Deflater d = new java.util.zip.Deflater();
        d.setInput(data);
        d.finish();
        byte[] out = new byte[data.length + 1024];
        int n = d.deflate(out);
        d.end();
        ByteBuf buf = Unpooled.buffer();
        VarInts.write(buf, claimed);
        buf.writeBytes(out, 0, n);
        return buf;
    }

    @Test
    void claimedSizeBeyondDeflateRatioIsRejectedBeforeAllocating() {
        EmbeddedChannel dec = compressionChannel(256, PacketFlow.CLIENTBOUND);
        // ~20 bytes of compressed data claiming 100 MiB: impossible for deflate, rejected up front.
        DecoderException e = assertThrows(DecoderException.class, () -> dec.writeInbound(compressedPacket(100 * 1024 * 1024, new byte[300])));
        assertTrue(e.getMessage().contains("cannot inflate"), e.getMessage());
        dec.finishAndReleaseAll();
    }

    @Test
    void streamLargerThanClaimedIsRejected() {
        // Decompression bomb against Velocity's Java fallback: claims 300 bytes, inflates to 1 MiB.
        EmbeddedChannel dec = compressionChannel(256, PacketFlow.CLIENTBOUND);
        assertThrows(DecoderException.class, () -> dec.writeInbound(compressedPacket(300, new byte[1024 * 1024])));
        dec.finishAndReleaseAll();
    }

    @Test
    void streamShorterThanClaimedIsRejected() {
        EmbeddedChannel dec = compressionChannel(256, PacketFlow.CLIENTBOUND);
        assertThrows(DecoderException.class, () -> dec.writeInbound(compressedPacket(5000, compressible(4000))));
        dec.finishAndReleaseAll();
    }

    @Test
    void presetDictionaryStreamIsRejectedInsteadOfSpinning() {
        // A zlib stream that asks for a preset dictionary (FDICT): Velocity's Java inflate loop made no progress on it
        // and spun the Netty thread forever.
        java.util.zip.Deflater d = new java.util.zip.Deflater();
        d.setDictionary(compressible(64));
        d.setInput(compressible(4000));
        d.finish();
        byte[] out = new byte[8192];
        int n = d.deflate(out);
        d.end();
        ByteBuf packet = Unpooled.buffer();
        VarInts.write(packet, 4000);
        packet.writeBytes(out, 0, n);

        EmbeddedChannel dec = compressionChannel(256, PacketFlow.CLIENTBOUND);
        DecoderException e = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> assertThrows(DecoderException.class, () -> dec.writeInbound(packet)));
        assertTrue(e.getMessage().contains("preset dictionary"), e.getMessage());
        dec.finishAndReleaseAll();
    }

    @Test
    void claimedSizeAboveTheLimitIsRejected() {
        EmbeddedChannel dec = compressionChannel(256, PacketFlow.CLIENTBOUND);
        DecoderException e = assertThrows(DecoderException.class,
                () -> dec.writeInbound(compressedPacket(Limits.clientbound() + 1, new byte[300])));
        assertTrue(e.getMessage().contains("larger than the maximum"), e.getMessage());
        dec.finishAndReleaseAll();
    }

    @Test
    void sharedCompressorIsFreedOnlyAfterBothHandlers() {
        SharedCompressor shared = new SharedCompressor(JavaVelocityCompressor.FACTORY.create(6), 2);
        AegisCompressDecoder decoder = new AegisCompressDecoder(shared, PacketFlow.CLIENTBOUND, 256, true);
        AegisCompressEncoder encoder = new AegisCompressEncoder(shared, 256);
        EmbeddedChannel ch = new EmbeddedChannel(decoder, encoder);
        ch.pipeline().remove(encoder);
        // The decoder still works after the encoder was removed (Krypton freed the shared native contexts here).
        ch.writeInbound(compressedPacket(4000, compressible(4000)));
        ByteBuf back = ch.readInbound();
        assertEquals(4000, back.readableBytes());
        back.release();
        assertFalse(ch.finish());
    }

    @Test
    void cipherMatchesVanillaAesCfb8() throws Exception {
        byte[] keyBytes = new byte[16];
        new SecureRandom().nextBytes(keyBytes);
        SecretKey key = new SecretKeySpec(keyBytes, "AES");
        Cipher vanilla = Cipher.getInstance("AES/CFB8/NoPadding");
        vanilla.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(keyBytes));

        EmbeddedChannel enc = new EmbeddedChannel(new AegisCipherEncoder(JavaVelocityCipher.FACTORY.forEncryption(key)));
        EmbeddedChannel dec = new EmbeddedChannel(new AegisCipherDecoder(JavaVelocityCipher.FACTORY.forDecryption(key)));
        for (int i = 0; i < 50; i++) {
            byte[] plain = randomBytes(1 + RANDOM.nextInt(20000));
            ByteBuf direct = Unpooled.directBuffer(plain.length);
            direct.writeBytes(plain);
            enc.writeOutbound(direct);
            ByteBuf cipherText = enc.readOutbound();
            assertArrayEquals(vanilla.update(plain), ByteBufUtil.getBytes(cipherText));
            dec.writeInbound(cipherText);
            ByteBuf back = dec.readInbound();
            assertArrayEquals(plain, ByteBufUtil.getBytes(back));
            back.release();
        }
        // Read-only input must not be modified in place.
        byte[] plain = randomBytes(100);
        byte[] copy = plain.clone();
        enc.writeOutbound(Unpooled.wrappedBuffer(plain).asReadOnly());
        ((ByteBuf) enc.readOutbound()).release();
        assertArrayEquals(copy, plain);
        assertFalse(enc.finish());
        assertFalse(dec.finish());
    }

    @Test
    void flushHandlerHoldsAndReleases() {
        FlushControl.Handler handler = new FlushControl.Handler();
        EmbeddedChannel ch = new EmbeddedChannel(handler);
        assertTrue(handler.hold());
        assertFalse(handler.hold(), "a second hold of the same connection is not taken");
        ch.writeAndFlush(Unpooled.wrappedBuffer(randomBytes(100)));
        ch.writeAndFlush(Unpooled.wrappedBuffer(randomBytes(100)));
        assertNull(ch.readOutbound(), "held: nothing flushed yet");
        handler.release(ch);
        assertEquals(200, readAll(ch, false).length);

        // While held, a flush still goes through once 64 KiB are waiting.
        assertTrue(handler.hold());
        ch.writeAndFlush(Unpooled.wrappedBuffer(randomBytes(70 * 1024)));
        assertEquals(70 * 1024, readAll(ch, false).length);
        // Closing flushes what is held.
        ch.writeAndFlush(Unpooled.wrappedBuffer(randomBytes(10)));
        assertNull(ch.readOutbound());
        ch.close();
        assertEquals(10, readAll(ch, false).length);
        assertFalse(ch.finish());
    }
}
