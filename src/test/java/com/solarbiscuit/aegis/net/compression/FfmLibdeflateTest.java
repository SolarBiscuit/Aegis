package com.solarbiscuit.aegis.net.compression;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import com.solarbiscuit.aegis.net.NativeSupport;
import com.solarbiscuit.aegis.net.VarInts;
import com.velocitypowered.natives.compression.VelocityCompressor;
import com.velocitypowered.natives.compression.VelocityCompressorFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.protocol.PacketFlow;

/**
 * libdeflate through FFM (Windows x64, Java 22+). Run with {@code gradlew test -PtestJava=25}; skipped on Java 17.
 */
@EnabledIf("available")
class FfmLibdeflateTest {
    private static final Random RANDOM = new Random(1234);

    /** Java 22+ on Windows x64, unless the run denies native access on purpose (then NativePipelineTest checks the fallback). */
    static boolean available() {
        return FfmLibdeflate.platformSupported()
                && !java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().contains("--illegal-native-access=deny");
    }

    private static ByteBuf direct(byte[] data) {
        ByteBuf b = PooledByteBufAllocator.DEFAULT.directBuffer(data.length);
        b.writeBytes(data);
        return b;
    }

    private static byte[] sample(int size) {
        byte[] data = new byte[size];
        for (int j = 0; j < size; j++) {
            data[j] = (byte) (RANDOM.nextInt(4) == 0 ? RANDOM.nextInt() : j % 13);
        }
        return data;
    }

    @Test
    void nativeSupportPicksIt() {
        assertEquals(FfmLibdeflate.name(), NativeSupport.compressionName());
    }

    @Test
    void interoperatesWithJavaUtilZip() throws Exception {
        VelocityCompressorFactory factory = FfmLibdeflate.load();
        for (int level = 1; level <= FfmLibdeflate.MAX_LEVEL; level++) {
            try (VelocityCompressor c = factory.create(level)) {
                for (int size : new int[] {1, 255, 4096, 70_000, 1 << 20}) {
                    byte[] data = sample(size);
                    // libdeflate -> java.util.zip
                    ByteBuf in = direct(data);
                    ByteBuf out = PooledByteBufAllocator.DEFAULT.directBuffer(16); // too small: must grow
                    c.deflate(in, out);
                    Inflater inflater = new Inflater();
                    inflater.setInput(ByteBufUtil.getBytes(out));
                    byte[] back = new byte[size];
                    assertEquals(size, inflater.inflate(back));
                    assertTrue(inflater.finished(), "level " + level + ": complete zlib stream");
                    inflater.end();
                    assertArrayEquals(data, back);
                    in.release();
                    out.release();

                    // java.util.zip -> libdeflate
                    ByteBuf z = direct(zlib(data, Deflater.DEFAULT_COMPRESSION, null));
                    ByteBuf dst = PooledByteBufAllocator.DEFAULT.directBuffer(size + 1);
                    c.inflate(z, dst, size);
                    assertArrayEquals(data, ByteBufUtil.getBytes(dst));
                    z.release();
                    dst.release();
                }
            }
        }
    }

    @Test
    void rejectsWrongSizesBadDataAndPresetDictionaries() throws Exception {
        try (VelocityCompressor c = FfmLibdeflate.load().create(6)) {
            byte[] data = sample(10_000);
            byte[] z = zlib(data, 6, null);
            assertInflateFails(c, z, 9_999, "more data than claimed");
            assertInflateFails(c, z, 10_001, "less data than claimed");
            byte[] corrupt = z.clone();
            corrupt[z.length / 2] ^= 0x5a;
            assertInflateFails(c, corrupt, 10_000, "corrupt stream");
            assertInflateFails(c, new byte[] {0x78, (byte) 0x9c}, 100, "truncated stream");
            assertInflateFails(c, zlib(data, 6, new byte[] {1, 2, 3, 4}), 10_000, "preset dictionary");
        }
    }

    private static void assertInflateFails(VelocityCompressor c, byte[] stream, int claimed, String what) {
        ByteBuf in = direct(stream);
        ByteBuf out = PooledByteBufAllocator.DEFAULT.directBuffer(claimed + 1);
        try {
            assertThrows(DataFormatException.class, () -> c.inflate(in, out, claimed), what);
            assertEquals(0, out.writerIndex(), what + ": nothing written");
        } finally {
            in.release();
            out.release();
        }
    }

    @Test
    void closeIsIdempotentAndUseAfterCloseFails() {
        VelocityCompressor c = FfmLibdeflate.load().create(6);
        c.close();
        c.close();
        ByteBuf in = direct(new byte[] {1, 2, 3});
        ByteBuf out = PooledByteBufAllocator.DEFAULT.directBuffer(64);
        assertThrows(IllegalStateException.class, () -> c.deflate(in, out));
        assertThrows(IllegalStateException.class, () -> c.inflate(in, out, 3));
        in.release();
        out.release();
    }

    @Test
    void aegisHandlersRoundTripAndBombs() {
        SharedCompressor shared = new SharedCompressor(FfmLibdeflate.load().create(6), 2);
        EmbeddedChannel ch = new EmbeddedChannel(new AegisCompressDecoder(shared, PacketFlow.CLIENTBOUND, 256, true), new AegisCompressEncoder(shared, 256));
        ch.config().setAllocator(PooledByteBufAllocator.DEFAULT);
        for (int i = 0; i < 300; i++) {
            byte[] data = sample(i % 10 == 0 ? 100_000 + RANDOM.nextInt(400_000) : 1 + RANDOM.nextInt(4000));
            ch.writeOutbound(direct(data));
            ch.writeInbound((ByteBuf) ch.readOutbound());
            ByteBuf back = ch.readInbound();
            assertArrayEquals(data, ByteBufUtil.getBytes(back));
            back.release();
        }
        byte[] noise = new byte[300_000];
        RANDOM.nextBytes(noise);
        ch.writeOutbound(direct(noise));
        ch.writeInbound((ByteBuf) ch.readOutbound());
        ByteBuf back = ch.readInbound();
        assertArrayEquals(noise, ByteBufUtil.getBytes(back));
        back.release();

        assertThrows(DecoderException.class, () -> ch.writeInbound(packet(300, zlib(new byte[1 << 20], 6, null))), "stream larger than claimed");
        assertThrows(DecoderException.class, () -> ch.writeInbound(packet(9000, zlib(new byte[4000], 6, null))), "stream shorter than claimed");
        assertThrows(DecoderException.class, () -> ch.writeInbound(packet(64 * 1024 * 1024, zlib(new byte[300], 6, null))), "impossible ratio");
        assertFalse(ch.finishAndReleaseAll());
    }

    private static ByteBuf packet(int claimed, byte[] stream) {
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer();
        VarInts.write(buf, claimed);
        buf.writeBytes(stream);
        return buf;
    }

    private static byte[] zlib(byte[] data, int level, byte[] dictionary) {
        Deflater d = new Deflater(level);
        if (dictionary != null) {
            d.setDictionary(dictionary);
        }
        d.setInput(data);
        d.finish();
        byte[] out = new byte[data.length + 1024];
        int n = d.deflate(out);
        d.end();
        return java.util.Arrays.copyOf(out, n);
    }
}
