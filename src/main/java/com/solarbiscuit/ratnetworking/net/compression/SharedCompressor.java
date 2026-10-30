package com.solarbiscuit.ratnetworking.net.compression;

import java.util.concurrent.atomic.AtomicInteger;

import com.velocitypowered.natives.compression.VelocityCompressor;

/**
 * One compressor (it holds both the inflate and the deflate context) shared by a connection's encoder and decoder,
 * freed when the last of the two is removed. Krypton gave both handlers the same compressor and let each one close it:
 * removing only one of them (e.g. a mod replacing "compress") freed the native contexts the other was still using.
 */
public final class SharedCompressor {
    private final VelocityCompressor compressor;
    private final AtomicInteger users;

    public SharedCompressor(VelocityCompressor compressor, int users) {
        this.compressor = compressor;
        this.users = new AtomicInteger(users);
    }

    public VelocityCompressor get() {
        return this.compressor;
    }

    public void release() {
        int left = this.users.decrementAndGet();
        if (left == 0) {
            this.compressor.close();
        }
    }
}
