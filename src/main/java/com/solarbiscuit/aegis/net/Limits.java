package com.solarbiscuit.aegis.net;

import com.solarbiscuit.aegis.Features;
import com.solarbiscuit.aegis.config.AegisConfig;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.PacketFlow;

/**
 * Packet size limits, by direction. Packet Fixer raised every limit to "unlimited" for both directions, which let any
 * unauthenticated client make a server buffer up to 2 GiB per connection (frame length) or allocate the claimed
 * decompressed size of a tiny packet up front. Here:
 * <ul>
 * <li>Clientbound (what a server sends, what a client accepts) is raised to {@code clientboundMaxPacketSize} in every
 * protocol state, because Forge syncs registries during login.</li>
 * <li>Serverbound is only raised once the connection is in the PLAY state; handshake/status/login keep the vanilla
 * limits.</li>
 * </ul>
 */
public final class Limits {
    /** Vanilla frame length limit: a 3 byte VarInt. */
    public static final int VANILLA_MAX_FRAME = (1 << 21) - 1;
    /** Vanilla CompressionDecoder.MAXIMUM_UNCOMPRESSED_LENGTH. */
    public static final int VANILLA_MAX_UNCOMPRESSED = 8 * 1024 * 1024;
    /**
     * Upper bound of the deflate expansion ratio (a stream of repeated bytes inflates ~1032x). A packet claiming a
     * larger decompressed size than this allows is malformed or a decompression bomb, and is rejected before anything
     * is allocated.
     */
    public static final long MAX_DEFLATE_RATIO = 1032;

    private Limits() {
    }

    public static int clientbound() {
        return Features.LIMITS ? AegisConfig.clientboundMaxPacket : VANILLA_MAX_UNCOMPRESSED;
    }

    public static int serverboundPlay() {
        return Features.LIMITS ? AegisConfig.serverboundMaxPacket : VANILLA_MAX_UNCOMPRESSED;
    }

    /**
     * Largest frame (compressed packet incl. its header) a pipeline that receives {@code receiving} packets accepts.
     */
    public static int maxInboundFrame(PacketFlow receiving, Channel channel) {
        if (!Features.LIMITS) {
            return VANILLA_MAX_FRAME;
        }
        if (receiving == PacketFlow.CLIENTBOUND) {
            return Math.max(VANILLA_MAX_FRAME, clientbound());
        }
        return isPlay(channel) ? Math.max(VANILLA_MAX_FRAME, serverboundPlay()) : VANILLA_MAX_FRAME;
    }

    /** Largest decompressed packet a pipeline that receives {@code receiving} packets accepts. */
    public static int maxInboundUncompressed(PacketFlow receiving, Channel channel) {
        if (!Features.LIMITS) {
            return VANILLA_MAX_UNCOMPRESSED;
        }
        if (receiving == PacketFlow.CLIENTBOUND) {
            return Math.max(VANILLA_MAX_UNCOMPRESSED, clientbound());
        }
        return isPlay(channel) ? Math.max(VANILLA_MAX_UNCOMPRESSED, serverboundPlay()) : VANILLA_MAX_UNCOMPRESSED;
    }

    /** Largest packet that may be sent in direction {@code sending}. */
    public static int maxOutbound(PacketFlow sending) {
        if (!Features.LIMITS) {
            return VANILLA_MAX_UNCOMPRESSED;
        }
        return sending == PacketFlow.CLIENTBOUND ? clientbound() : serverboundPlay();
    }

    /** Largest frame that may be sent in direction {@code sending}. */
    public static int maxOutboundFrame(PacketFlow sending) {
        if (!Features.LIMITS) {
            return VANILLA_MAX_FRAME;
        }
        return Math.max(VANILLA_MAX_FRAME, maxOutbound(sending));
    }

    private static boolean isPlay(Channel channel) {
        return channel != null && channel.attr(Connection.ATTRIBUTE_PROTOCOL).get() == ConnectionProtocol.PLAY;
    }
}
