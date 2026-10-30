package com.solarbiscuit.ratnetworking.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public final class HeapCopy {
    private HeapCopy() {
    }

    /**
     * Reads {@code length} bytes into a plain heap buffer. FriendlyByteBuf#readBytes(int) allocates from the source
     * buffer's allocator: a pooled direct buffer (Krypton/RatNetworking pipelines) or an unpooled direct buffer without
     * a cleaner (vanilla's decompressed packets). Either way the memory only comes back on release(), which Forge never
     * calls for login queries and skips for payloads that are dropped. A heap buffer is reclaimed by the GC regardless.
     */
    public static ByteBuf read(ByteBuf source, int length) {
        byte[] data = new byte[length];
        source.readBytes(data);
        return Unpooled.wrappedBuffer(data);
    }
}
