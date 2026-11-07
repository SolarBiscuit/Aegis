package com.solarbiscuit.aegis.net.compression;

import java.util.function.Consumer;
import java.util.function.Supplier;

import com.velocitypowered.natives.compression.VelocityCompressor;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.protocol.PacketFlow;

/**
 * Puts Aegis's compression handlers in a pipeline (Connection.setupCompression). Unlike Krypton this never casts
 * whatever sits under "compress"/"decompress": vanilla handlers are replaced, Aegis's handlers get the new threshold,
 * and a handler from another mod makes the caller fall back to vanilla's code.
 * <p>
 * The new compressor's native memory is freed when the last handler using it is removed. Nothing is installed on a
 * closed channel (vanilla's compression listener also runs when the write failed because the connection closed): its
 * pipeline may already be torn down, so a handler added now would never be removed and its native compressor never
 * freed. If installing throws half-way, the compressor's unattached uses are released before the exception goes on.
 */
public final class CompressionInstaller {
    private CompressionInstaller() {
    }

    /**
     * Returns false if another mod's handler is in the way (the caller then lets vanilla's code run), true if handled.
     * {@code endVanilla} is called for every vanilla handler that was replaced, to free its zlib state right away.
     */
    public static boolean install(Channel channel, PacketFlow receiving, int threshold, boolean validate,
            Supplier<VelocityCompressor> compressors, Consumer<ChannelHandler> endVanilla) {
        ChannelPipeline pipeline = channel.pipeline();
        ChannelHandler decoder = pipeline.get("decompress");
        ChannelHandler encoder = pipeline.get("compress");
        if (!known(decoder) || !known(encoder)) {
            return false;
        }
        if (threshold < 0) {
            if (decoder != null) {
                pipeline.remove("decompress");
            }
            if (encoder != null) {
                pipeline.remove("compress");
            }
            return true;
        }
        if (!channel.isOpen() || !channel.isRegistered()) {
            return true;
        }

        boolean newDecoder = !(decoder instanceof AegisCompressDecoder);
        boolean newEncoder = !(encoder instanceof AegisCompressEncoder);
        int unattached = (newDecoder ? 1 : 0) + (newEncoder ? 1 : 0);
        SharedCompressor shared = unattached > 0 ? new SharedCompressor(compressors.get(), unattached) : null;
        try {
            if (newDecoder) {
                AegisCompressDecoder replacement = new AegisCompressDecoder(shared, receiving, threshold, validate);
                if (decoder != null) {
                    pipeline.replace("decompress", "decompress", replacement);
                } else {
                    pipeline.addBefore("decoder", "decompress", replacement);
                }
                unattached--;
                if (decoder instanceof CompressionDecoder) {
                    endVanilla.accept(decoder);
                }
            } else {
                ((AegisCompressDecoder) decoder).setThreshold(threshold, validate);
            }

            if (newEncoder) {
                AegisCompressEncoder replacement = new AegisCompressEncoder(shared, threshold);
                if (encoder != null) {
                    pipeline.replace("compress", "compress", replacement);
                } else {
                    pipeline.addBefore("encoder", "compress", replacement);
                }
                unattached--;
                if (encoder instanceof CompressionEncoder) {
                    endVanilla.accept(encoder);
                }
            } else {
                ((AegisCompressEncoder) encoder).setThreshold(threshold);
            }
        } finally {
            for (; unattached > 0; unattached--) {
                shared.release();
            }
        }
        return true;
    }

    private static boolean known(ChannelHandler handler) {
        return handler == null || handler instanceof AegisCompressDecoder || handler instanceof AegisCompressEncoder
                || handler.getClass() == CompressionDecoder.class || handler.getClass() == CompressionEncoder.class;
    }
}
