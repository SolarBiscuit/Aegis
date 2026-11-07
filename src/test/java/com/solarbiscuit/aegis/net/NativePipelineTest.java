package com.solarbiscuit.aegis.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.SecureRandom;
import java.util.Random;
import java.util.zip.Deflater;

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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.protocol.PacketFlow;

/**
 * The same checks as PipelineTest, but with whatever implementation NativeSupport picks on this machine: Java on
 * Windows, libdeflate/OpenSSL on Linux and macOS arm64 (run in a Linux container to cover the natives).
 */
class NativePipelineTest {
    private static final Random RANDOM = new Random(99);

    private static EmbeddedChannel pooled(io.netty.channel.ChannelHandler... handlers) {
        EmbeddedChannel ch = new EmbeddedChannel(handlers);
        ch.config().setAllocator(PooledByteBufAllocator.DEFAULT);
        return ch;
    }

    private static ByteBuf direct(byte[] data) {
        ByteBuf b = PooledByteBufAllocator.DEFAULT.directBuffer(data.length);
        b.writeBytes(data);
        return b;
    }

    @Test
    void compressionRoundTripAndBombs() {
        System.out.println("NativePipelineTest: compression = " + NativeSupport.compressionName() + ", encryption = " + NativeSupport.encryptionName());
        SharedCompressor shared = new SharedCompressor(NativeSupport.newCompressor(), 2);
        EmbeddedChannel ch = pooled(new AegisCompressDecoder(shared, PacketFlow.CLIENTBOUND, 256, true), new AegisCompressEncoder(shared, 256));
        for (int i = 0; i < 300; i++) {
            int size = i % 10 == 0 ? 100_000 + RANDOM.nextInt(400_000) : 1 + RANDOM.nextInt(4000);
            byte[] data = new byte[size];
            for (int j = 0; j < size; j++) {
                data[j] = (byte) (RANDOM.nextInt(4) == 0 ? RANDOM.nextInt() : j % 11);
            }
            ch.writeOutbound(direct(data));
            ByteBuf wire = ch.readOutbound();
            ch.writeInbound(wire);
            ByteBuf back = ch.readInbound();
            assertArrayEquals(data, ByteBufUtil.getBytes(back));
            back.release();
        }
        // incompressible data larger than the initial output estimate (libdeflate has to grow the buffer)
        byte[] noise = new byte[300_000];
        RANDOM.nextBytes(noise);
        ch.writeOutbound(direct(noise));
        ch.writeInbound((ByteBuf) ch.readOutbound());
        ByteBuf back = ch.readInbound();
        assertArrayEquals(noise, ByteBufUtil.getBytes(back));
        back.release();

        assertThrows(DecoderException.class, () -> ch.writeInbound(packet(300, new byte[1 << 20])), "stream larger than claimed");
        assertThrows(DecoderException.class, () -> ch.writeInbound(packet(9000, new byte[4000])), "stream shorter than claimed");
        assertThrows(DecoderException.class, () -> ch.writeInbound(packet(64 * 1024 * 1024, new byte[300])), "impossible ratio");
        assertFalse(ch.finishAndReleaseAll());
    }

    private static ByteBuf packet(int claimed, byte[] data) {
        Deflater d = new Deflater();
        d.setInput(data);
        d.finish();
        byte[] out = new byte[data.length + 1024];
        int n = d.deflate(out);
        d.end();
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer();
        VarInts.write(buf, claimed);
        buf.writeBytes(out, 0, n);
        return buf;
    }

    @Test
    void cipherMatchesVanilla() throws Exception {
        byte[] keyBytes = new byte[16];
        new SecureRandom().nextBytes(keyBytes);
        SecretKey key = new SecretKeySpec(keyBytes, "AES");
        Cipher vanilla = Cipher.getInstance("AES/CFB8/NoPadding");
        vanilla.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(keyBytes));
        EmbeddedChannel enc = pooled(new AegisCipherEncoder(NativeSupport.newCipher(true, key)));
        EmbeddedChannel dec = pooled(new AegisCipherDecoder(NativeSupport.newCipher(false, key)));
        for (int i = 0; i < 200; i++) {
            byte[] plain = new byte[1 + RANDOM.nextInt(70_000)];
            RANDOM.nextBytes(plain);
            enc.writeOutbound(i % 2 == 0 ? direct(plain) : Unpooled.wrappedBuffer(plain.clone()));
            ByteBuf cipherText = enc.readOutbound();
            assertArrayEquals(vanilla.update(plain), ByteBufUtil.getBytes(cipherText));
            dec.writeInbound(cipherText);
            ByteBuf back = dec.readInbound();
            assertArrayEquals(plain, ByteBufUtil.getBytes(back));
            back.release();
        }
        assertFalse(enc.finish());
        assertFalse(dec.finish());
    }
}
