package com.solarbiscuit.aegis.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

/** VarInts must produce exactly vanilla's bytes (the test classpath has the unpatched FriendlyByteBuf). */
class VarIntsTest {
    @Test
    void matchesVanilla() {
        Random random = new Random(42);
        int[] edges = {0, 1, 127, 128, 255, 16383, 16384, 2097151, 2097152, 268435455, 268435456, Integer.MAX_VALUE, -1, Integer.MIN_VALUE};
        for (int v : edges) {
            check(v);
        }
        for (int i = 0; i < 200_000; i++) {
            check(random.nextInt() >>> random.nextInt(32));
            check(random.nextInt());
        }
    }

    private static void check(int value) {
        assertEquals(FriendlyByteBuf.getVarIntSize(value), VarInts.size(value), "size of " + value);
        ByteBuf vanilla = Unpooled.buffer();
        new FriendlyByteBuf(vanilla).writeVarInt(value);
        ByteBuf ours = Unpooled.buffer();
        VarInts.write(ours, value);
        assertArrayEquals(ByteBufUtil.getBytes(vanilla), ByteBufUtil.getBytes(ours), "bytes of " + value);
        assertEquals(value, VarInts.read(ours));
    }
}
