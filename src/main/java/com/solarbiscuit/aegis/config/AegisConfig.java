package com.solarbiscuit.aegis.config;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * config/aegis.properties. Read once, very early (from the mixin plugin, before any game class loads), because
 * most options decide whether a mixin is applied at all. Changes need a restart.
 * <p>
 * Unknown keys are dropped and missing keys are added with their defaults on every start; values the user set are kept.
 * Every stream is closed (Packet Fixer leaked a file handle on both the read and the write).
 */
public final class AegisConfig {
    private static final Logger LOGGER = LogManager.getLogger("Aegis");
    private static final int MIB = 1024 * 1024;
    /**
     * Config file names under the mod's earlier names, newest first: Axial (0.2.0-0.4.0), RatNetworking (up to 0.1.2).
     * The keys never changed.
     */
    private static final String[] LEGACY_FILES = {"axial.properties", "ratnetworking.properties"};

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static final Map<String, String> VALUES = new LinkedHashMap<>();
    private static boolean loaded;

    // --- pipeline (Krypton) ---
    public static boolean frameDecoder;
    public static boolean zeroCopyPrepender;
    public static boolean compression;
    public static int compressionLevel;
    public static boolean encryption;
    public static boolean natives;
    public static boolean fastVarInt;
    // --- leak fixes ---
    public static boolean heapCopyPayloads;
    public static boolean legacyQueryFix;
    // --- flushing (Krypton + VMP) ---
    public static boolean flushConsolidation;
    // --- server (VMP + Krypton) ---
    public static boolean nonBlockingDisconnect;
    public static boolean trackerFilter;
    public static boolean chunkEntityLookup;
    public static boolean loginCompressionOrder;
    // --- limits (Packet Fixer) ---
    public static boolean limits;
    public static int clientboundMaxPacket;
    public static int serverboundMaxPacket;
    public static long nbtQuota;
    public static int maxStringLength;
    // --- timeouts (Packet Fixer, fixed) ---
    public static boolean timeouts;
    public static int readTimeoutSeconds;
    public static int loginTimeoutSeconds;
    public static int keepAliveTimeoutSeconds;
    // --- security ---
    public static boolean connectionThrottle;
    public static int throttleConnections;
    public static int throttleWindowSeconds;
    public static int maxPendingPerAddress;
    public static int offenceBanSeconds;
    public static String trustedAddresses;
    public static boolean blockLog;

    private AegisConfig() {
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path file = configDir().resolve("aegis.properties");
        Path source = file;
        if (!Files.isRegularFile(file)) {
            for (String name : LEGACY_FILES) {
                Path legacy = configDir().resolve(name);
                if (Files.isRegularFile(legacy)) {
                    source = legacy;
                    LOGGER.info("Migrating settings from {} to {} (the old file is left in place and no longer read)", legacy, file);
                    break;
                }
            }
        }
        Properties props = new Properties();
        if (Files.isRegularFile(source)) {
            try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
                props.load(reader);
            } catch (IOException | IllegalArgumentException e) {
                LOGGER.warn("Could not read {}, using defaults: {}", source, e.toString());
            }
        }

        section("Pipeline (from Krypton). Replaces the Netty handlers of every real network connection. Singleplayer worlds use an in-memory connection without these handlers.");
        frameDecoder = bool(props, "pipeline.frameDecoder", true,
                "Zero-copy packet splitter: no copy and no allocation per packet, blocks 'nullping' floods, and allows frames larger than 2 MiB up to the limits below.");
        zeroCopyPrepender = bool(props, "pipeline.zeroCopyPrepender", true,
                "Writes the frame length as a separate small buffer instead of copying every outgoing packet once more.");
        compression = bool(props, "pipeline.compression", true,
                "Replace the vanilla compression handlers (libdeflate on Linux x86_64/aarch64 and macOS arm64, and on Windows x64 with Java 22+; java.util.zip with direct buffers elsewhere). Also bounds every decompression to the size limits (decompression-bomb fix) and frees native zlib memory as soon as a connection closes.");
        compressionLevel = integer(props, "pipeline.compressionLevel", 6, 1, 12,
                "Compression level. libdeflate: 1-12, Java: 1-9 (higher values are clamped). 6 matches vanilla's ratio; higher values send fewer bytes for more CPU.");
        encryption = bool(props, "pipeline.encryption", false,
                "Use native OpenSSL AES/CFB8 (online-mode only, Linux x86_64/aarch64 and macOS arm64) instead of vanilla's cipher. Off by default: measured on JDK 17 it is no faster than vanilla (CFB8 needs one AES operation per byte, and the JDK's AES intrinsics match OpenSSL), it only saves a copy. Never used without the native library.");
        natives = bool(props, "pipeline.natives", true,
                "Allow the bundled native libraries (velocity-native; libdeflate for Windows x64 on Java 22+). false = always use the Java implementations.");
        fastVarInt = bool(props, "pipeline.fastVarInt", true,
                "VarInt size lookup table (about 10x faster than vanilla's loop; used for every packet and frame length).");

        section("Memory leak fixes");
        heapCopyPayloads = bool(props, "leaks.heapCopyCustomPayloads", true,
                "Custom payload / login query packets copy their data into a heap buffer instead of a pooled or no-cleaner direct buffer. Forge never releases login query buffers and only sometimes releases play payloads, so each join leaked direct memory (megabytes in big packs).");
        legacyQueryFix = bool(props, "leaks.legacyQueryFix", true,
                "Drop (and release) data that arrives on an already-closed connection in the legacy server-list ping handler.");

        section("Flush consolidation (from Krypton and VMP). Server only.");
        flushConsolidation = bool(props, "flush.consolidate", true,
                "Hold back socket flushes while a level ticks its chunks and entity trackers, and while a player's chunks are being sent, then flush each connection once. Turns hundreds of write syscalls per player per tick into one or two. Also removes the extra per-tick flush (one event loop wake-up per connection per tick).");

        section("Server (from VMP and Krypton)");
        nonBlockingDisconnect = bool(props, "server.nonBlockingDisconnect", true,
                "Do not block the server thread until a kicked/disconnected player's socket is closed.");
        trackerFilter = bool(props, "server.entityTrackerFilter", true,
                "Skip the per-player entity tracking check for players that are certainly out of range and not tracking the entity (exactly the same result as vanilla, without a Vec3 allocation and a passenger walk per entity per player per movement).");
        chunkEntityLookup = bool(props, "server.chunkEntityLookup", true,
                "When a chunk is sent to a player, look up only the entities in that chunk instead of iterating every tracked entity of the dimension.");
        loginCompressionOrder = bool(props, "server.loginCompressionOrder", true,
                "Turn compression on right after the login compression packet is written, instead of when the socket has taken it. Vanilla sends the next login packet uncompressed if the socket was busy, and the client then disconnects with 'Badly compressed packet'.");

        section("Packet size limits (from Packet Fixer, fixed). Sizes in bytes. Vanilla: 2 MiB frames, 8 MiB decompressed, 1 MiB payloads, 2 MiB NBT.");
        limits = bool(props, "limits.enabled", true,
                "Raise the vanilla packet size limits. Large frames need Aegis (or Packet Fixer) on the receiving side.");
        clientboundMaxPacket = integer(props, "limits.clientboundMaxPacketSize", 128 * MIB, 8 * MIB, Integer.MAX_VALUE - 8,
                "Largest packet a server may send / a client accepts (frame, decompressed size, custom payloads, chunk data). Server->client packets are trusted by the client.");
        serverboundMaxPacket = integer(props, "limits.serverboundMaxPacketSize", 16 * MIB, 8 * MIB, Integer.MAX_VALUE - 8,
                "Largest packet a client may send to the server once logged in. Before login (handshake, status, login) the vanilla limits always apply, so unauthenticated connections cannot make the server buffer large frames.");
        nbtQuota = longValue(props, "limits.nbtQuota", 64L * MIB, 2L * MIB, Long.MAX_VALUE,
                "NBT size quota for item stacks and other NBT read from packets (\"Tried to read NBT tag that was too big\"), both sides. Bounded by the packet limits above anyway.");
        maxStringLength = integer(props, "limits.maxStringLength", 32767, 32767, 1 << 20,
                "Default maximum length of strings read/written with readUtf()/writeUtf().");

        section("Timeouts (from Packet Fixer, fixed)");
        timeouts = bool(props, "timeouts.enabled", true,
                "Apply the timeouts below.");
        readTimeoutSeconds = integer(props, "timeouts.readTimeoutSeconds", 120, 30, 3600,
                "Seconds without any data before a connection that is logging in or playing is closed (vanilla 30), server and client. Handshakes and server-list pings keep vanilla's 30 s. On the server, a -Dforge.readTimeout JVM flag wins. Packet Fixer never applied this on servers (it called getProperty instead of setProperty).");
        loginTimeoutSeconds = integer(props, "timeouts.loginTimeoutSeconds", 120, 30, 3600,
                "Seconds a player may take to log in (vanilla 30). Large packs need longer for the Forge handshake.");
        keepAliveTimeoutSeconds = integer(props, "timeouts.keepAliveTimeoutSeconds", 60, 15, 3600,
                "Seconds to wait for a keep-alive answer (vanilla 15-30). Keep-alives are still sent every 15 s, so the ping in the player list stays current (Packet Fixer stretched the send interval instead, so the ping only updated every 2 minutes).");

        section("Security. Server only; never applies to singleplayer's in-memory connection or to loopback (127.0.0.1, ::1). IPv6 addresses count per /64.");
        connectionThrottle = bool(props, "security.connectionThrottle", true,
                "Limit connections per address that have not logged in yet (handshakes, server-list pings, logins), and ban addresses that break the protocol (see offenceBanSeconds). Over a limit the connection is closed at once. Turned off automatically when a proxy-forwarding mod is installed (every player would share the proxy's address).");
        throttleConnections = integer(props, "security.connectionsPerWindow", 10, 1, 10000,
                "New connections one address may open per throttleWindowSeconds (a burst of this many, then refilled evenly). The server list of one client opens one connection per refresh.");
        throttleWindowSeconds = integer(props, "security.throttleWindowSeconds", 3, 1, 3600,
                "Window for connectionsPerWindow, in seconds.");
        maxPendingPerAddress = integer(props, "security.maxPendingPerAddress", 8, 1, 10000,
                "Connections one address may have open at once that have not reached the game yet (pings and logins in progress; a Forge login in a big pack takes several seconds).");
        offenceBanSeconds = integer(props, "security.offenceBanSeconds", 60, 0, 86400,
                "Refuse every connection from an address for this long after it sent something no real client sends (a malformed or oversized frame, a decompression bomb, a preset-dictionary zlib stream). 0 = never ban.");
        trustedAddresses = string(props, "security.trustedAddresses", "",
                "Comma-separated addresses or CIDR blocks that are never limited or banned, e.g. 203.0.113.7, 10.0.0.0/8, 2001:db8::/32. Add your proxy or load balancer here if every player comes from its address.");
        blockLog = bool(props, "security.blockLog", false,
                "Also write refused connections and offences to logs/aegis-security.log in a fixed format that fail2ban or CrowdSec can read (at most one line per address and reason every 10 s). Do not use behind a proxy: the address would be the proxy's.");

        write(file);
    }

    /** FML's config directory (the game directory's config/, which is not always the working directory). */
    private static Path configDir() {
        try {
            Path dir = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get();
            if (dir != null) {
                return dir;
            }
        } catch (Throwable ignored) {
            // outside of FML (unit tests)
        }
        return Paths.get("config");
    }

    private static void section(String comment) {
        ENTRIES.add(new Entry(null, null, comment));
    }

    private static boolean bool(Properties p, String key, boolean def, String comment) {
        String raw = p.getProperty(key);
        boolean value = def;
        if (raw != null) {
            String t = raw.trim();
            if (t.equalsIgnoreCase("true") || t.equalsIgnoreCase("false")) {
                value = Boolean.parseBoolean(t);
            } else {
                LOGGER.warn("Config {}: '{}' is not true/false, using {}", key, raw, def);
            }
        }
        put(key, String.valueOf(value), comment + " (default " + def + ")");
        return value;
    }

    private static String string(Properties p, String key, String def, String comment) {
        String raw = p.getProperty(key);
        String value = raw == null ? def : raw.trim();
        put(key, value, comment + " (default \"" + def + "\")");
        return value;
    }

    private static int integer(Properties p, String key, int def, int min, int max, String comment) {
        return (int) longValue(p, key, def, min, max, comment);
    }

    private static long longValue(Properties p, String key, long def, long min, long max, String comment) {
        String raw = p.getProperty(key);
        long value = def;
        if (raw != null) {
            try {
                value = Long.parseLong(raw.trim());
                if (value < min || value > max) {
                    LOGGER.warn("Config {}: {} is outside [{}, {}], clamping", key, value, min, max);
                    value = Math.max(min, Math.min(max, value));
                }
            } catch (NumberFormatException e) {
                LOGGER.warn("Config {}: '{}' is not a number, using {}", key, raw, def);
                value = def;
            }
        }
        put(key, String.valueOf(value), comment + " (default " + def + ")");
        return value;
    }

    private static void put(String key, String value, String comment) {
        VALUES.put(key, value);
        ENTRIES.add(new Entry(key, value, comment));
    }

    private static void write(Path file) {
        try {
            Files.createDirectories(file.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write("# Aegis configuration. Restart the game/server after changing it.\n");
                for (Entry e : ENTRIES) {
                    if (e.key == null) {
                        w.write("\n# ===== " + e.comment + "\n");
                    } else {
                        w.write("# " + e.comment + "\n");
                        w.write(e.key + "=" + e.value + "\n");
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not write {}: {}", file, e.toString());
        }
    }

    public static Map<String, String> values() {
        return VALUES;
    }

    private record Entry(String key, String value, String comment) {
    }
}
