package com.solarbiscuit.ratnetworking.mixin.compression;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.solarbiscuit.ratnetworking.net.NativeSupport;
import com.solarbiscuit.ratnetworking.net.compression.RNCompressDecoder;
import com.solarbiscuit.ratnetworking.net.compression.RNCompressEncoder;
import com.solarbiscuit.ratnetworking.net.compression.SharedCompressor;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;

/**
 * Installs RatNetworking's compression handlers (Krypton). Unlike Krypton this never casts whatever sits under
 * "compress"/"decompress": vanilla handlers are replaced (their native zlib memory is freed right away), RatNetworking's
 * handlers get the new threshold, and a handler from another mod makes this step fall back to vanilla's code.
 */
@Mixin(Connection.class)
public abstract class ConnectionCompressionMixin {
    @Shadow
    private Channel channel;

    @Shadow
    @Final
    private PacketFlow receiving;

    @Inject(method = "setupCompression", at = @At("HEAD"), cancellable = true)
    private void ratnetworking$setupCompression(int threshold, boolean validate, CallbackInfo ci) {
        if (this.channel == null) {
            return;
        }
        ChannelPipeline pipeline = this.channel.pipeline();
        ChannelHandler decoder = pipeline.get("decompress");
        ChannelHandler encoder = pipeline.get("compress");
        if (!known(decoder) || !known(encoder)) {
            return; // somebody else's handler: let vanilla's code deal with it
        }
        if (threshold < 0) {
            if (decoder != null) {
                pipeline.remove("decompress");
            }
            if (encoder != null) {
                pipeline.remove("compress");
            }
            ci.cancel();
            return;
        }

        boolean newDecoder = !(decoder instanceof RNCompressDecoder);
        boolean newEncoder = !(encoder instanceof RNCompressEncoder);
        SharedCompressor shared = newDecoder || newEncoder
                ? new SharedCompressor(NativeSupport.newCompressor(), (newDecoder ? 1 : 0) + (newEncoder ? 1 : 0))
                : null;

        if (newDecoder) {
            RNCompressDecoder replacement = new RNCompressDecoder(shared, this.receiving, threshold, validate);
            if (decoder != null) {
                pipeline.replace("decompress", "decompress", replacement);
            } else {
                pipeline.addBefore("decoder", "decompress", replacement);
            }
            if (decoder instanceof CompressionDecoder) {
                ratnetworking$endVanilla(decoder);
            }
        } else {
            ((RNCompressDecoder) decoder).setThreshold(threshold, validate);
        }

        if (newEncoder) {
            RNCompressEncoder replacement = new RNCompressEncoder(shared, threshold);
            if (encoder != null) {
                pipeline.replace("compress", "compress", replacement);
            } else {
                pipeline.addBefore("encoder", "compress", replacement);
            }
            if (encoder instanceof CompressionEncoder) {
                ratnetworking$endVanilla(encoder);
            }
        } else {
            ((RNCompressEncoder) encoder).setThreshold(threshold);
        }
        ci.cancel();
    }

    private static boolean known(ChannelHandler handler) {
        return handler == null || handler instanceof RNCompressDecoder || handler instanceof RNCompressEncoder
                || handler.getClass() == CompressionDecoder.class || handler.getClass() == CompressionEncoder.class;
    }

    private static void ratnetworking$endVanilla(ChannelHandler handler) {
        if (handler instanceof CompressionDecoder d) {
            ((com.solarbiscuit.ratnetworking.mixin.access.CompressionDecoderAccessor) d).ratnetworking$inflater().end();
        } else if (handler instanceof CompressionEncoder e) {
            ((com.solarbiscuit.ratnetworking.mixin.access.CompressionEncoderAccessor) e).ratnetworking$deflater().end();
        }
    }
}
