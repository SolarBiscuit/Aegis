package com.solarbiscuit.ratnetworking.net;

import io.netty.buffer.ByteBuf;

/**
 * VarInt helpers (from Krypton / Velocity). See https://steinborn.me/posts/performance/how-fast-can-you-write-a-varint/
 */
public final class VarInts {
    private static final int[] EXACT_BYTE_LENGTHS = new int[33];

    static {
        for (int i = 0; i <= 32; ++i) {
            EXACT_BYTE_LENGTHS[i] = (int) Math.ceil((31d - (i - 1)) / 7d);
        }
        EXACT_BYTE_LENGTHS[32] = 1; // 0 needs one byte
    }

    private VarInts() {
    }

    public static int size(int value) {
        return EXACT_BYTE_LENGTHS[Integer.numberOfLeadingZeros(value)];
    }

    public static void write(ByteBuf buf, int value) {
        // The one and two byte cases are by far the most common; peeling them keeps this small enough to inline.
        if ((value & (0xFFFFFFFF << 7)) == 0) {
            buf.writeByte(value);
        } else if ((value & (0xFFFFFFFF << 14)) == 0) {
            buf.writeShort((value & 0x7F | 0x80) << 8 | (value >>> 7));
        } else {
            writeFull(buf, value);
        }
    }

    private static void writeFull(ByteBuf buf, int value) {
        if ((value & (0xFFFFFFFF << 21)) == 0) {
            buf.writeMedium((value & 0x7F | 0x80) << 16 | ((value >>> 7) & 0x7F | 0x80) << 8 | (value >>> 14));
        } else if ((value & (0xFFFFFFFF << 28)) == 0) {
            buf.writeInt((value & 0x7F | 0x80) << 24 | ((value >>> 7) & 0x7F | 0x80) << 16
                    | ((value >>> 14) & 0x7F | 0x80) << 8 | (value >>> 21));
        } else {
            buf.writeInt((value & 0x7F | 0x80) << 24 | ((value >>> 7) & 0x7F | 0x80) << 16
                    | ((value >>> 14) & 0x7F | 0x80) << 8 | ((value >>> 21) & 0x7F | 0x80));
            buf.writeByte(value >>> 28);
        }
    }

    /**
     * Reads a VarInt of at most 5 bytes from {@code buf}, or throws {@link QuietDecoderException#VARINT_TOO_BIG}.
     */
    public static int read(ByteBuf buf) {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            byte b = buf.readByte();
            value |= (b & 0x7F) << (i * 7);
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw QuietDecoderException.VARINT_TOO_BIG;
    }
}
