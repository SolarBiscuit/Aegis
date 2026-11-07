package com.solarbiscuit.aegis;

import com.solarbiscuit.aegis.compat.ModCompat;
import com.solarbiscuit.aegis.config.AegisConfig;

/**
 * The effective on/off state of every module: the config, minus anything another installed mod already patches.
 * Resolved once, during mixin bootstrap.
 */
public final class Features {
    public static final boolean FRAME_DECODER;
    public static final boolean PREPENDER;
    public static final boolean COMPRESSION;
    public static final boolean ENCRYPTION;
    public static final boolean FAST_VARINT;
    public static final boolean HEAP_COPY_PAYLOADS;
    public static final boolean LEGACY_QUERY_FIX;
    public static final boolean FLUSH;
    public static final boolean NON_BLOCKING_DISCONNECT;
    public static final boolean TRACKER_FILTER;
    public static final boolean CHUNK_ENTITY_LOOKUP;
    public static final boolean LIMITS;
    /** The PacketEncoder part of LIMITS (Connectivity modifies the same constant). */
    public static final boolean OUTBOUND_PACKET_LIMIT;
    public static final boolean TIMEOUTS;
    public static final boolean LOGIN_COMPRESSION_ORDER;
    public static final boolean CONNECTION_THROTTLE;
    public static final boolean BLOCK_LOG;
    /** Whether offences are looked at at all (console log, bans, block log). */
    public static final boolean SECURITY_LOG_ANY;

    static {
        AegisConfig.load();
        boolean pf = ModCompat.PACKET_FIXER;
        boolean krypton = ModCompat.KRYPTON;
        boolean vmp = ModCompat.VMP;
        boolean connectivity = ModCompat.CONNECTIVITY;

        FRAME_DECODER = AegisConfig.frameDecoder && !pf && !krypton;
        PREPENDER = AegisConfig.zeroCopyPrepender && !pf && !krypton;
        COMPRESSION = AegisConfig.compression && !pf && !krypton;
        ENCRYPTION = AegisConfig.encryption && !krypton;
        FAST_VARINT = AegisConfig.fastVarInt && !krypton;
        HEAP_COPY_PAYLOADS = AegisConfig.heapCopyPayloads && !krypton;
        LEGACY_QUERY_FIX = AegisConfig.legacyQueryFix && !krypton;
        FLUSH = AegisConfig.flushConsolidation && !krypton && !vmp;
        NON_BLOCKING_DISCONNECT = AegisConfig.nonBlockingDisconnect && !vmp && !ModCompat.RAKNETIFY && !connectivity;
        TRACKER_FILTER = AegisConfig.trackerFilter && !vmp;
        CHUNK_ENTITY_LOOKUP = AegisConfig.chunkEntityLookup && !krypton && !ModCompat.IMMERSIVE_PORTALS;
        LIMITS = AegisConfig.limits && !pf;
        OUTBOUND_PACKET_LIMIT = LIMITS && !connectivity;
        TIMEOUTS = AegisConfig.timeouts && !pf && !connectivity;
        LOGIN_COMPRESSION_ORDER = AegisConfig.loginCompressionOrder;
        CONNECTION_THROTTLE = AegisConfig.connectionThrottle && !ModCompat.PROXY_FORWARDING;
        BLOCK_LOG = AegisConfig.blockLog && !ModCompat.PROXY_FORWARDING;
        SECURITY_LOG_ANY = CONNECTION_THROTTLE || BLOCK_LOG;
    }

    private Features() {
    }

    public static void init() {
    }

    public static String describe() {
        StringBuilder sb = new StringBuilder();
        flag(sb, "frameDecoder", Features.FRAME_DECODER);
        flag(sb, "prepender", Features.PREPENDER);
        flag(sb, "compression", Features.COMPRESSION);
        flag(sb, "encryption", Features.ENCRYPTION);
        flag(sb, "fastVarInt", Features.FAST_VARINT);
        flag(sb, "heapCopyPayloads", Features.HEAP_COPY_PAYLOADS);
        flag(sb, "legacyQueryFix", Features.LEGACY_QUERY_FIX);
        flag(sb, "flush", Features.FLUSH);
        flag(sb, "nonBlockingDisconnect", Features.NON_BLOCKING_DISCONNECT);
        flag(sb, "trackerFilter", Features.TRACKER_FILTER);
        flag(sb, "chunkEntityLookup", Features.CHUNK_ENTITY_LOOKUP);
        flag(sb, "limits", Features.LIMITS);
        if (Features.LIMITS && !Features.OUTBOUND_PACKET_LIMIT) {
            sb.append(" (except outgoing packets)");
        }
        flag(sb, "timeouts", Features.TIMEOUTS);
        flag(sb, "loginCompressionOrder", Features.LOGIN_COMPRESSION_ORDER);
        flag(sb, "connectionThrottle", Features.CONNECTION_THROTTLE);
        flag(sb, "blockLog", Features.BLOCK_LOG);
        return sb.toString();
    }

    private static void flag(StringBuilder sb, String name, boolean on) {
        if (sb.length() > 0) {
            sb.append(", ");
        }
        sb.append(name).append(on ? "=on" : "=off");
    }
}
