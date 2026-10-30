package com.solarbiscuit.ratnetworking;

import com.solarbiscuit.ratnetworking.compat.ModCompat;
import com.solarbiscuit.ratnetworking.config.RNConfig;

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

    static {
        RNConfig.load();
        boolean pf = ModCompat.PACKET_FIXER;
        boolean krypton = ModCompat.KRYPTON;
        boolean vmp = ModCompat.VMP;
        boolean connectivity = ModCompat.CONNECTIVITY;

        FRAME_DECODER = RNConfig.frameDecoder && !pf && !krypton;
        PREPENDER = RNConfig.zeroCopyPrepender && !pf && !krypton;
        COMPRESSION = RNConfig.compression && !pf && !krypton;
        ENCRYPTION = RNConfig.encryption && !krypton;
        FAST_VARINT = RNConfig.fastVarInt && !krypton;
        HEAP_COPY_PAYLOADS = RNConfig.heapCopyPayloads && !krypton;
        LEGACY_QUERY_FIX = RNConfig.legacyQueryFix && !krypton;
        FLUSH = RNConfig.flushConsolidation && !krypton && !vmp;
        NON_BLOCKING_DISCONNECT = RNConfig.nonBlockingDisconnect && !vmp && !ModCompat.RAKNETIFY && !connectivity;
        TRACKER_FILTER = RNConfig.trackerFilter && !vmp;
        CHUNK_ENTITY_LOOKUP = RNConfig.chunkEntityLookup && !krypton && !ModCompat.IMMERSIVE_PORTALS;
        LIMITS = RNConfig.limits && !pf;
        OUTBOUND_PACKET_LIMIT = LIMITS && !connectivity;
        TIMEOUTS = RNConfig.timeouts && !pf && !connectivity;
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
        return sb.toString();
    }

    private static void flag(StringBuilder sb, String name, boolean on) {
        if (sb.length() > 0) {
            sb.append(", ");
        }
        sb.append(name).append(on ? "=on" : "=off");
    }
}
