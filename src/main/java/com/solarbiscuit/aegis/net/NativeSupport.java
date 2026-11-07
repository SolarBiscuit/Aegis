package com.solarbiscuit.aegis.net;

import javax.crypto.SecretKey;
import java.security.GeneralSecurityException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.solarbiscuit.aegis.config.AegisConfig;
import com.solarbiscuit.aegis.net.compression.FfmLibdeflate;
import com.velocitypowered.natives.Native;
import com.velocitypowered.natives.compression.JavaVelocityCompressor;
import com.velocitypowered.natives.compression.VelocityCompressor;
import com.velocitypowered.natives.compression.VelocityCompressorFactory;
import com.velocitypowered.natives.encryption.JavaVelocityCipher;
import com.velocitypowered.natives.encryption.VelocityCipher;
import com.velocitypowered.natives.encryption.VelocityCipherFactory;
import com.velocitypowered.natives.util.BufferPreference;
import com.velocitypowered.natives.util.Natives;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

/**
 * Picks the compression and encryption implementation once: the bundled native libraries where available
 * (libdeflate / OpenSSL on Linux x86_64 + aarch64, macOS arm64 through velocity-native's JNI; libdeflate on Windows x64
 * through the FFM API on Java 22+), otherwise Velocity's Java implementations. Natives that fail to load fall back to
 * Java with a warning instead of breaking connections.
 */
public final class NativeSupport {
    private static final Logger LOGGER = LogManager.getLogger("Aegis");

    private static volatile VelocityCompressorFactory compressorFactory;
    private static volatile String compressorName;
    private static volatile boolean compressorNative;
    private static volatile VelocityCipherFactory cipherFactory;
    private static volatile String cipherName;

    static {
        AegisConfig.load(); // normally already loaded by the mixin plugin; idempotent
    }

    private NativeSupport() {
    }

    /** Loads the natives on a background thread, so the first player to join does not pay for it on a Netty thread. */
    public static void warmUpAsync() {
        Thread t = new Thread(() -> {
            try {
                compressor();
                cipher();
            } catch (Throwable e) {
                LOGGER.warn("Native warm-up failed: {}", e.toString());
            }
        }, "Aegis native loader");
        t.setDaemon(true);
        t.start();
    }

    private static synchronized void compressor() {
        if (compressorFactory != null) {
            return;
        }
        VelocityCompressorFactory factory = JavaVelocityCompressor.FACTORY;
        String name = "Java";
        if (AegisConfig.natives) {
            try {
                factory = Natives.compress.get();
                name = Natives.compress.getLoadedVariant();
            } catch (Throwable e) {
                LOGGER.warn("Native compression unavailable, using Java: {}", e.toString());
            }
            if ("Java".equals(name)) {
                try {
                    if (FfmLibdeflate.platformSupported()) {
                        if (directMemoryAddresses()) {
                            factory = FfmLibdeflate.load();
                            name = FfmLibdeflate.name();
                        } else {
                            LOGGER.warn("Native compression unavailable, using Java: Netty cannot give native code the address of its buffers (no sun.misc.Unsafe)");
                        }
                    }
                } catch (Throwable e) {
                    LOGGER.warn("Native compression (libdeflate through FFM) unavailable, using Java: {}", rootCause(e));
                }
            }
        }
        compressorNative = !"Java".equals(name);
        compressorName = name;
        compressorFactory = factory;
        LOGGER.info("Compression: {}", name);
        if (!compressorNative && AegisConfig.natives && nativeAccessDenied()) {
            // velocity-native falls back to Java silently when the JVM refuses to load its library.
            LOGGER.info("Native libraries are blocked by --illegal-native-access=deny; using the Java implementations");
        }
    }

    private static synchronized void cipher() {
        if (cipherFactory != null) {
            return;
        }
        VelocityCipherFactory factory = JavaVelocityCipher.FACTORY;
        String name = "Java";
        if (AegisConfig.natives) {
            try {
                factory = Natives.cipher.get();
                name = Natives.cipher.getLoadedVariant();
            } catch (Throwable e) {
                LOGGER.warn("Native encryption unavailable, using Java: {}", e.toString());
            }
        }
        cipherName = name;
        cipherFactory = factory;
        LOGGER.info("Encryption: {}", name);
    }

    private static boolean nativeAccessDenied() {
        try {
            return java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().contains("--illegal-native-access=deny");
        } catch (Throwable e) {
            return false;
        }
    }

    /** Reflection and class initialisation wrap the interesting exception (e.g. IllegalCallerException) several times. */
    private static String rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.toString();
    }

    /** The native code reads and writes Netty's direct buffers by address, which Netty only provides with Unsafe. */
    private static boolean directMemoryAddresses() {
        ByteBuf probe = Unpooled.directBuffer(1);
        try {
            return probe.hasMemoryAddress();
        } finally {
            probe.release();
        }
    }

    public static VelocityCompressor newCompressor() {
        compressor();
        int level = AegisConfig.compressionLevel;
        if (!compressorNative) {
            level = Math.min(level, 9);
        }
        try {
            return compressorFactory.create(level);
        } catch (Throwable e) {
            if (compressorNative) {
                LOGGER.warn("Native compressor failed to initialise, falling back to Java for this connection: {}", e.toString());
                return JavaVelocityCompressor.FACTORY.create(Math.min(level, 9));
            }
            throw e;
        }
    }

    /**
     * Whether the native cipher loaded. Velocity's Java cipher works in place, which makes the JDK copy every input
     * (measured: same speed as vanilla, 3x the allocation), so without natives vanilla's cipher handlers are kept.
     */
    public static boolean nativeCipher() {
        cipher();
        return !"Java".equals(cipherName);
    }

    public static VelocityCipher newCipher(boolean encrypt, SecretKey key) throws GeneralSecurityException {
        cipher();
        return encrypt ? cipherFactory.forEncryption(key) : cipherFactory.forDecryption(key);
    }

    public static String compressionName() {
        compressor();
        return compressorName;
    }

    public static String encryptionName() {
        cipher();
        return cipherName;
    }

    /**
     * Returns {@code buf} (retained) if the native code can work on it directly, otherwise a copy in a compatible buffer.
     * Unlike Velocity's helper this also copies read-only buffers, because the cipher works in place.
     */
    public static ByteBuf ensureCompatible(ByteBufAllocator alloc, Native nativeStuff, ByteBuf buf, boolean inPlace) {
        boolean compatible = switch (nativeStuff.preferredBufferType()) {
            case DIRECT_PREFERRED, HEAP_PREFERRED -> true;
            case DIRECT_REQUIRED -> buf.hasMemoryAddress();
            case HEAP_REQUIRED -> buf.hasArray();
        };
        if (compatible && buf.nioBufferCount() == 1 && !(inPlace && buf.isReadOnly())) {
            return buf.retain();
        }
        ByteBuf copy = preferredBuffer(alloc, nativeStuff, buf.readableBytes(), Integer.MAX_VALUE);
        copy.writeBytes(buf, buf.readerIndex(), buf.readableBytes());
        return copy;
    }

    public static ByteBuf preferredBuffer(ByteBufAllocator alloc, Native nativeStuff, int initialCapacity, int maxCapacity) {
        BufferPreference pref = nativeStuff.preferredBufferType();
        return pref == BufferPreference.HEAP_PREFERRED || pref == BufferPreference.HEAP_REQUIRED
                ? alloc.heapBuffer(initialCapacity, maxCapacity)
                : alloc.directBuffer(initialCapacity, maxCapacity);
    }
}
