package com.solarbiscuit.ratnetworking.net;

import java.lang.management.ManagementFactory;
import java.security.SecureRandom;
import java.util.Random;
import java.util.function.Supplier;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.solarbiscuit.ratnetworking.net.cipher.RNCipherEncoder;
import com.solarbiscuit.ratnetworking.net.compression.RNCompressDecoder;
import com.solarbiscuit.ratnetworking.net.compression.RNCompressEncoder;
import com.solarbiscuit.ratnetworking.net.compression.SharedCompressor;
import com.velocitypowered.natives.compression.JavaVelocityCompressor;
import com.velocitypowered.natives.encryption.JavaVelocityCipher;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.CipherEncoder;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.Varint21FrameDecoder;
import net.minecraft.network.Varint21LengthFieldPrepender;
import net.minecraft.network.protocol.PacketFlow;

/**
 * Vanilla handlers vs RatNetworking's (Java implementations; the natives only exist for Linux/macOS arm64).
 * Run with {@code gradlew test --tests *PipelineBench -Dratnetworking.bench=true}.
 */
@EnabledIfSystemProperty(named = "ratnetworking.bench", matches = "true")
class PipelineBench {
    private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final Random RANDOM = new Random(7);

    /** Packet sizes like a busy server's mix: many small entity packets, some block/chunk-sized ones. */
    private static byte[][] packets(int count) {
        byte[][] out = new byte[count][];
        for (int i = 0; i < count; i++) {
            int size = i % 50 == 0 ? 8000 + RANDOM.nextInt(24000) : 8 + RANDOM.nextInt(120);
            byte[] p = new byte[size];
            for (int j = 0; j < size; j++) {
                p[j] = (byte) (RANDOM.nextInt(100) < 70 ? (j / 16) % 5 : RANDOM.nextInt()); // partly compressible
            }
            out[i] = p;
        }
        return out;
    }

    private record Result(double nsPerOp, double bytesPerOp) {
    }

    private static Result measure(int ops, Runnable run) {
        for (int w = 0; w < 5; w++) {
            run.run();
        }
        long tid = Thread.currentThread().getId();
        long best = Long.MAX_VALUE;
        long alloc = 0;
        for (int r = 0; r < 7; r++) {
            long a0 = THREADS.getThreadAllocatedBytes(tid);
            long t0 = System.nanoTime();
            run.run();
            long t = System.nanoTime() - t0;
            alloc = THREADS.getThreadAllocatedBytes(tid) - a0;
            best = Math.min(best, t);
        }
        return new Result(best / (double) ops, alloc / (double) ops);
    }

    private static void report(String what, Result vanilla, Result ours) {
        System.out.printf("%-34s vanilla %8.0f ns/op %8.0f B/op | RatNetworking %8.0f ns/op %8.0f B/op | %.2fx faster, %.0f%% less allocation%n",
                what, vanilla.nsPerOp, vanilla.bytesPerOp, ours.nsPerOp, ours.bytesPerOp, vanilla.nsPerOp / ours.nsPerOp,
                vanilla.bytesPerOp <= 0 ? 0 : 100 * (1 - ours.bytesPerOp / vanilla.bytesPerOp));
    }

    private static EmbeddedChannel channel(ChannelHandler... handlers) {
        EmbeddedChannel ch = new EmbeddedChannel(handlers);
        ch.config().setAllocator(PooledByteBufAllocator.DEFAULT);
        return ch;
    }

    /** Frames the packets with a vanilla prepender into a stream, cut into 16 KiB reads. */
    private static ByteBuf[] wire(byte[][] packets) {
        EmbeddedChannel enc = channel(new Varint21LengthFieldPrepender());
        for (byte[] p : packets) {
            ByteBuf b = PooledByteBufAllocator.DEFAULT.directBuffer(p.length);
            b.writeBytes(p);
            enc.writeOutbound(b);
        }
        ByteBuf all = PooledByteBufAllocator.DEFAULT.directBuffer();
        Object o;
        while ((o = enc.readOutbound()) != null) {
            all.writeBytes((ByteBuf) o);
            ((ByteBuf) o).release();
        }
        int chunks = (all.readableBytes() + 16383) / 16384;
        ByteBuf[] out = new ByteBuf[chunks];
        for (int i = 0; i < chunks; i++) {
            out[i] = all.retainedSlice(i * 16384, Math.min(16384, all.readableBytes() - i * 16384));
        }
        all.release();
        return out;
    }

    private static Runnable decodeFrames(Supplier<ChannelHandler> decoder, ByteBuf[] reads) {
        return () -> {
            EmbeddedChannel ch = channel(decoder.get());
            for (ByteBuf read : reads) {
                ch.writeInbound(read.retainedDuplicate());
                Object o;
                while ((o = ch.readInbound()) != null) {
                    ((ByteBuf) o).release();
                }
            }
            ch.finishAndReleaseAll();
        };
    }

    private static Runnable encode(Supplier<ChannelHandler[]> handlers, byte[][] packets) {
        return () -> {
            EmbeddedChannel ch = channel(handlers.get());
            for (byte[] p : packets) {
                ByteBuf b = PooledByteBufAllocator.DEFAULT.directBuffer(p.length);
                b.writeBytes(p);
                ch.writeOutbound(b);
                Object o;
                while ((o = ch.readOutbound()) != null) {
                    ((ByteBuf) o).release();
                }
            }
            ch.finishAndReleaseAll();
        };
    }

    @Test
    void bench() throws Exception {
        int n = 20_000;
        byte[][] packets = packets(n);

        ByteBuf[] reads = wire(packets);
        report("frame decoder (splitter)", measure(n, decodeFrames(Varint21FrameDecoder::new, reads)),
                measure(n, decodeFrames(() -> new RNFrameDecoder(PacketFlow.SERVERBOUND), reads)));
        for (ByteBuf r : reads) {
            r.release();
        }

        report("length prepender", measure(n, encode(() -> new ChannelHandler[]{new Varint21LengthFieldPrepender()}, packets)),
                measure(n, encode(() -> new ChannelHandler[]{new RNLengthPrepender(PacketFlow.CLIENTBOUND, true)}, packets)));

        report("compression encoder (threshold 256)", measure(n, encode(() -> new ChannelHandler[]{new CompressionEncoder(256)}, packets)),
                measure(n, encode(() -> {
                    SharedCompressor shared = new SharedCompressor(JavaVelocityCompressor.FACTORY.create(6), 1);
                    return new ChannelHandler[]{new RNCompressEncoder(shared, 256)};
                }, packets)));

        // Compressed stream for the decoders, produced once with the vanilla encoder.
        EmbeddedChannel enc = channel(new CompressionEncoder(256));
        ByteBuf[] compressed = new ByteBuf[n];
        for (int i = 0; i < n; i++) {
            ByteBuf b = PooledByteBufAllocator.DEFAULT.directBuffer();
            b.writeBytes(packets[i]);
            enc.writeOutbound(b);
            compressed[i] = enc.readOutbound();
        }
        Runnable vanillaDecode = () -> {
            EmbeddedChannel ch = channel(new CompressionDecoder(256, true));
            for (ByteBuf c : compressed) {
                ch.writeInbound(c.retainedDuplicate());
                ((ByteBuf) ch.readInbound()).release();
            }
            ch.finishAndReleaseAll();
        };
        Runnable oursDecode = () -> {
            SharedCompressor shared = new SharedCompressor(JavaVelocityCompressor.FACTORY.create(6), 1);
            EmbeddedChannel ch = channel(new RNCompressDecoder(shared, PacketFlow.SERVERBOUND, 256, true));
            for (ByteBuf c : compressed) {
                ch.writeInbound(c.retainedDuplicate());
                ((ByteBuf) ch.readInbound()).release();
            }
            ch.finishAndReleaseAll();
        };
        report("compression decoder", measure(n, vanillaDecode), measure(n, oursDecode));
        boolean nativeCompression = !"Java".equals(NativeSupport.compressionName());
        if (nativeCompression) {
            report("compression encoder (" + NativeSupport.compressionName() + ")",
                    measure(n, encode(() -> new ChannelHandler[]{new CompressionEncoder(256)}, packets)),
                    measure(n, encode(() -> new ChannelHandler[]{new RNCompressEncoder(new SharedCompressor(NativeSupport.newCompressor(), 1), 256)}, packets)));
            report("compression decoder (native)", measure(n, vanillaDecode), measure(n, () -> {
                EmbeddedChannel ch = channel(new RNCompressDecoder(new SharedCompressor(NativeSupport.newCompressor(), 1), PacketFlow.SERVERBOUND, 256, true));
                for (ByteBuf c : compressed) {
                    ch.writeInbound(c.retainedDuplicate());
                    ((ByteBuf) ch.readInbound()).release();
                }
                ch.finishAndReleaseAll();
            }));
        }
        for (ByteBuf c : compressed) {
            c.release();
        }

        byte[] keyBytes = new byte[16];
        new SecureRandom().nextBytes(keyBytes);
        SecretKey key = new SecretKeySpec(keyBytes, "AES");
        report("encryption (AES/CFB8, Java)", measure(n, encode(() -> {
            try {
                Cipher c = Cipher.getInstance("AES/CFB8/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(keyBytes));
                return new ChannelHandler[]{new CipherEncoder(c)};
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, packets)), measure(n, encode(() -> {
            try {
                return new ChannelHandler[]{new RNCipherEncoder(JavaVelocityCipher.FACTORY.forEncryption(key))};
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, packets)));
        if (NativeSupport.nativeCipher()) {
            report("encryption (" + NativeSupport.encryptionName() + ")", measure(n, encode(() -> {
                try {
                    Cipher c = Cipher.getInstance("AES/CFB8/NoPadding");
                    c.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(keyBytes));
                    return new ChannelHandler[]{new CipherEncoder(c)};
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, packets)), measure(n, encode(() -> {
                try {
                    return new ChannelHandler[]{new RNCipherEncoder(NativeSupport.newCipher(true, key))};
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, packets)));
        }

        // Online mode with RatNetworking's cipher off (the default): the zero-copy prepender writes the length header as a
        // separate buffer, so vanilla's CipherEncoder runs twice per packet. Compared against the copying mode too.
        Supplier<ChannelHandler> vanillaCipher = () -> {
            try {
                Cipher c = Cipher.getInstance("AES/CFB8/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(keyBytes));
                return new CipherEncoder(c);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        Result vanillaFramed = measure(n, encode(() -> new ChannelHandler[]{vanillaCipher.get(), new Varint21LengthFieldPrepender()}, packets));
        report("prepender (zero-copy) + vanilla AES", vanillaFramed,
                measure(n, encode(() -> new ChannelHandler[]{vanillaCipher.get(), new RNLengthPrepender(PacketFlow.CLIENTBOUND, true)}, packets)));
        report("prepender (copying) + vanilla AES", vanillaFramed,
                measure(n, encode(() -> new ChannelHandler[]{vanillaCipher.get(), new RNLengthPrepender(PacketFlow.CLIENTBOUND, false)}, packets)));

        int[] values = new int[1_000_000];
        for (int i = 0; i < values.length; i++) {
            values[i] = RANDOM.nextInt(1 << (1 + RANDOM.nextInt(20)));
        }
        ByteBuf target = PooledByteBufAllocator.DEFAULT.directBuffer(values.length * 5);
        FriendlyByteBuf fbb = new FriendlyByteBuf(target);
        report("VarInt write", measure(values.length, () -> {
            target.clear();
            for (int v : values) {
                fbb.writeVarInt(v);
            }
        }), measure(values.length, () -> {
            target.clear();
            for (int v : values) {
                VarInts.write(fbb, v);
            }
        }));
        int[] sink = new int[1];
        report("VarInt size", measure(values.length, () -> {
            int s = 0;
            for (int v : values) {
                s += FriendlyByteBuf.getVarIntSize(v);
            }
            sink[0] += s;
        }), measure(values.length, () -> {
            int s = 0;
            for (int v : values) {
                s += VarInts.size(v);
            }
            sink[0] += s;
        }));

        String[] strings = new String[20_000];
        for (int i = 0; i < strings.length; i++) {
            StringBuilder sb = new StringBuilder();
            int len = 4 + RANDOM.nextInt(60);
            for (int j = 0; j < len; j++) {
                sb.append(j % 17 == 0 ? 'é' : (char) ('a' + RANDOM.nextInt(26)));
            }
            strings[i] = i % 3 == 0 ? "minecraft:" + sb : sb.toString();
        }
        report("String write (writeUtf)", measure(strings.length, () -> {
            target.clear();
            for (String s : strings) {
                fbb.writeUtf(s);
            }
        }), measure(strings.length, () -> {
            target.clear();
            for (String s : strings) {
                int utf8 = io.netty.buffer.ByteBufUtil.utf8Bytes(s);
                VarInts.write(target, utf8);
                target.writeCharSequence(s, java.nio.charset.StandardCharsets.UTF_8);
            }
        }));
        target.release();
    }
}
