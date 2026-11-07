package com.solarbiscuit.aegis.net.compression;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Array;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.DataFormatException;

import com.velocitypowered.natives.compression.VelocityCompressor;
import com.velocitypowered.natives.compression.VelocityCompressorFactory;
import com.velocitypowered.natives.util.BufferPreference;

import io.netty.buffer.ByteBuf;

/**
 * libdeflate through Java 22+'s Foreign Function &amp; Memory API, for Windows x64, where velocity-native has no
 * library and compression would otherwise use java.util.zip.
 * <p>
 * Compiled for Java 17 like the rest of the mod: the FFM classes are only touched by reflection while the library is
 * loaded. The per-packet calls go through method handles that take and return plain {@code long}/{@code int}: pointers
 * are passed as 64-bit integers, which is how the x64 calling convention passes them anyway. So there are no Java 22
 * class files in the jar, and no allocation per call.
 * <p>
 * Same semantics as velocity-native's libdeflate compressor: direct buffers only, inflate must produce exactly the
 * claimed size, preset dictionaries are rejected by libdeflate.
 */
public final class FfmLibdeflate implements VelocityCompressor {
    /** libdeflate's level range (velocity-native allows the same). */
    public static final int MAX_LEVEL = 12;
    private static final String VERSION = "1.26";

    private static final int SUCCESS = 0;
    private static final int BAD_DATA = 1;
    private static final int SHORT_OUTPUT = 2;
    private static final int INSUFFICIENT_SPACE = 3;

    private final long compressor;
    private final long decompressor;
    private boolean closed;

    private FfmLibdeflate(int level) {
        long c = Lib.allocCompressor(level);
        if (c == 0) {
            throw new IllegalStateException("libdeflate_alloc_compressor(" + level + ") failed");
        }
        long d = Lib.allocDecompressor();
        if (d == 0) {
            Lib.freeCompressor(c);
            throw new IllegalStateException("libdeflate_alloc_decompressor failed");
        }
        this.compressor = c;
        this.decompressor = d;
    }

    /** Loads the library (once) and returns a factory, or throws if this JVM/platform cannot use it. */
    public static VelocityCompressorFactory load() {
        Lib.ensureLoaded();
        return level -> new FfmLibdeflate(Math.max(0, Math.min(level, MAX_LEVEL)));
    }

    /** Whether this platform has a bundled library and the JVM has the FFM API (Java 22+). Does not load anything. */
    public static boolean platformSupported() {
        return Runtime.version().feature() >= 22 && resource() != null;
    }

    /**
     * The bundled library for this platform, or null. Lives outside {@link Lib}: touching Lib loads the library, and
     * this must stay a plain check.
     */
    static String resource() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows") && (arch.equals("amd64") || arch.equals("x86_64"))) {
            return "/natives/aegis/windows_x86_64/libdeflate.dll";
        }
        return null;
    }

    public static String name() {
        return "libdeflate " + VERSION + " (FFM)";
    }

    @Override
    public void inflate(ByteBuf source, ByteBuf destination, int uncompressedSize) throws DataFormatException {
        ensureOpen();
        destination.ensureWritable(uncompressedSize);
        long in = source.memoryAddress() + source.readerIndex();
        long out = destination.memoryAddress() + destination.writerIndex();
        int result = Lib.zlibDecompress(this.decompressor, in, source.readableBytes(), out, uncompressedSize);
        switch (result) {
            case SUCCESS -> destination.writerIndex(destination.writerIndex() + uncompressedSize);
            case BAD_DATA -> throw new DataFormatException("invalid zlib data");
            case SHORT_OUTPUT -> throw new DataFormatException("decompressed to fewer than " + uncompressedSize + " bytes");
            case INSUFFICIENT_SPACE -> throw new DataFormatException("decompressed to more than " + uncompressedSize + " bytes");
            default -> throw new DataFormatException("libdeflate error " + result);
        }
    }

    @Override
    public void deflate(ByteBuf source, ByteBuf destination) throws DataFormatException {
        ensureOpen();
        int length = source.readableBytes();
        long in = source.memoryAddress() + source.readerIndex();
        for (int attempt = 0; ; attempt++) {
            long out = destination.memoryAddress() + destination.writerIndex();
            long written = Lib.zlibCompress(this.compressor, in, length, out, destination.writableBytes());
            if (written > 0) {
                destination.writerIndex(destination.writerIndex() + (int) written);
                return;
            }
            // 0 = did not fit. The callers size the buffer for the worst case, so this is rare; grow to the bound once.
            long bound = Lib.zlibCompressBound(this.compressor, length);
            if (attempt > 0 || bound > Integer.MAX_VALUE || destination.writableBytes() >= bound) {
                throw new DataFormatException("libdeflate could not compress " + length + " bytes");
            }
            destination.ensureWritable((int) bound);
        }
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("compressor already closed");
        }
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            Lib.freeCompressor(this.compressor);
            Lib.freeDecompressor(this.decompressor);
        }
    }

    @Override
    public BufferPreference preferredBufferType() {
        return BufferPreference.DIRECT_REQUIRED;
    }

    /** The loaded library. Class initialisation fails (and keeps failing) if it cannot be loaded. */
    static final class Lib {
        private static final MethodHandle ALLOC_COMPRESSOR;
        private static final MethodHandle ALLOC_DECOMPRESSOR;
        private static final MethodHandle FREE_COMPRESSOR;
        private static final MethodHandle FREE_DECOMPRESSOR;
        private static final MethodHandle ZLIB_COMPRESS;
        private static final MethodHandle ZLIB_COMPRESS_BOUND;
        private static final MethodHandle ZLIB_DECOMPRESS;

        static {
            try {
                Ffm ffm = new Ffm(extract());
                ALLOC_COMPRESSOR = ffm.downcall("libdeflate_alloc_compressor", ffm.jlong, ffm.jint);
                ALLOC_DECOMPRESSOR = ffm.downcall("libdeflate_alloc_decompressor", ffm.jlong);
                FREE_COMPRESSOR = ffm.downcall("libdeflate_free_compressor", null, ffm.jlong);
                FREE_DECOMPRESSOR = ffm.downcall("libdeflate_free_decompressor", null, ffm.jlong);
                // size_t libdeflate_zlib_compress(compressor, const void *in, size_t in_nbytes, void *out, size_t avail)
                ZLIB_COMPRESS = ffm.downcall("libdeflate_zlib_compress", ffm.jlong, ffm.jlong, ffm.jlong, ffm.jlong, ffm.jlong, ffm.jlong);
                ZLIB_COMPRESS_BOUND = ffm.downcall("libdeflate_zlib_compress_bound", ffm.jlong, ffm.jlong, ffm.jlong);
                // enum libdeflate_result libdeflate_zlib_decompress(d, in, in_nbytes, out, avail, size_t *actual_out = NULL)
                ZLIB_DECOMPRESS = ffm.downcall("libdeflate_zlib_decompress", ffm.jint, ffm.jlong, ffm.jlong, ffm.jlong, ffm.jlong, ffm.jlong, ffm.jlong);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException(t.toString(), t);
            }
        }

        private Lib() {
        }

        static void ensureLoaded() {
            // Triggers the static initialiser.
        }

        /**
         * Copies the library out of the jar into the temp directory under a name that contains its hash, and reuses an
         * existing copy only if its bytes are identical. A loaded DLL cannot be deleted on Windows, so a random name per
         * start (velocity-native's approach) would leave one file behind per launch.
         */
        private static Path extract() throws Exception {
            String resource = resource();
            if (resource == null) {
                throw new UnsupportedOperationException("no bundled libdeflate for " + System.getProperty("os.name") + "/" + System.getProperty("os.arch"));
            }
            byte[] bytes;
            try (InputStream in = FfmLibdeflate.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IOException(resource + " missing from the jar");
                }
                bytes = in.readAllBytes();
            }
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 8);
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "aegis-natives");
            Files.createDirectories(dir);
            Path target = dir.resolve("libdeflate-" + VERSION + "-" + hash + ".dll");
            if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), bytes)) {
                return target;
            }
            Path tmp = Files.createTempFile(dir, "libdeflate-", ".tmp");
            try {
                Files.write(tmp, bytes);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
            if (!Arrays.equals(Files.readAllBytes(target), bytes)) {
                throw new IOException(target + " does not match the bundled library");
            }
            return target;
        }

        static long allocCompressor(int level) {
            try {
                return (long) ALLOC_COMPRESSOR.invokeExact(level);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        static long allocDecompressor() {
            try {
                return (long) ALLOC_DECOMPRESSOR.invokeExact();
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        static void freeCompressor(long c) {
            try {
                FREE_COMPRESSOR.invokeExact(c);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        static void freeDecompressor(long d) {
            try {
                FREE_DECOMPRESSOR.invokeExact(d);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        static long zlibCompress(long c, long in, long inLength, long out, long outAvailable) {
            try {
                return (long) ZLIB_COMPRESS.invokeExact(c, in, inLength, out, outAvailable);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        static long zlibCompressBound(long c, long inLength) {
            try {
                return (long) ZLIB_COMPRESS_BOUND.invokeExact(c, inLength);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        static int zlibDecompress(long d, long in, long inLength, long out, long outAvailable) {
            try {
                return (int) ZLIB_DECOMPRESS.invokeExact(d, in, inLength, out, outAvailable, 0L);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        private static RuntimeException rethrow(Throwable t) {
            if (t instanceof RuntimeException r) {
                throw r;
            }
            if (t instanceof Error e) {
                throw e;
            }
            throw new IllegalStateException(t);
        }
    }

    /** The few java.lang.foreign calls needed to bind functions, made by reflection so this compiles for Java 17. */
    private static final class Ffm {
        final Object jlong;
        final Object jint;
        private final Object linker;
        private final Object lookup;
        // Methods are looked up on the public interfaces: the implementations are JDK-internal classes.
        private final Class<?> linkerClass;
        private final Class<?> symbolLookup;
        private final Class<?> memoryLayout;
        private final Class<?> functionDescriptor;
        private final Class<?> memorySegment;
        private final Class<?> linkerOption;

        Ffm(Path library) throws Exception {
            this.linkerClass = Class.forName("java.lang.foreign.Linker");
            Class<?> arena = Class.forName("java.lang.foreign.Arena");
            this.symbolLookup = Class.forName("java.lang.foreign.SymbolLookup");
            Class<?> valueLayout = Class.forName("java.lang.foreign.ValueLayout");
            this.memoryLayout = Class.forName("java.lang.foreign.MemoryLayout");
            this.functionDescriptor = Class.forName("java.lang.foreign.FunctionDescriptor");
            this.memorySegment = Class.forName("java.lang.foreign.MemorySegment");
            this.linkerOption = Class.forName("java.lang.foreign.Linker$Option");
            this.jlong = valueLayout.getField("JAVA_LONG").get(null);
            this.jint = valueLayout.getField("JAVA_INT").get(null);
            this.linker = this.linkerClass.getMethod("nativeLinker").invoke(null);
            // The global arena: the library stays loaded for the life of the JVM, like a JNI library.
            Object global = arena.getMethod("global").invoke(null);
            this.lookup = this.symbolLookup.getMethod("libraryLookup", Path.class, arena).invoke(null, library, global);
        }

        /** A downcall handle for {@code name}; {@code ret == null} means void. */
        MethodHandle downcall(String name, Object ret, Object... args) throws Exception {
            Optional<?> symbol = (Optional<?>) this.symbolLookup.getMethod("find", String.class).invoke(this.lookup, name);
            Object address = symbol.orElseThrow(() -> new NoSuchMethodException("libdeflate has no " + name));
            Object layouts = Array.newInstance(this.memoryLayout, args.length);
            for (int i = 0; i < args.length; i++) {
                Array.set(layouts, i, args[i]);
            }
            Object descriptor = ret == null
                    ? this.functionDescriptor.getMethod("ofVoid", layouts.getClass()).invoke(null, layouts)
                    : this.functionDescriptor.getMethod("of", this.memoryLayout, layouts.getClass()).invoke(null, ret, layouts);
            Object noOptions = Array.newInstance(this.linkerOption, 0);
            return (MethodHandle) this.linkerClass
                    .getMethod("downcallHandle", this.memorySegment, this.functionDescriptor, noOptions.getClass())
                    .invoke(this.linker, address, descriptor, noOptions);
        }
    }
}
