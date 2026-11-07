package com.solarbiscuit.aegis.mixin.compression;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.solarbiscuit.aegis.mixin.access.CompressionDecoderAccessor;
import com.solarbiscuit.aegis.mixin.access.CompressionEncoderAccessor;
import com.solarbiscuit.aegis.net.NativeSupport;
import com.solarbiscuit.aegis.net.compression.CompressionInstaller;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;

/**
 * Installs Aegis's compression handlers (Krypton) through {@link CompressionInstaller}. A handler from another mod
 * under "compress"/"decompress" makes this step fall back to vanilla's code.
 */
@Mixin(Connection.class)
public abstract class ConnectionCompressionMixin {
    @Shadow
    private Channel channel;

    @Shadow
    @Final
    private PacketFlow receiving;

    @Inject(method = "setupCompression", at = @At("HEAD"), cancellable = true)
    private void aegis$setupCompression(int threshold, boolean validate, CallbackInfo ci) {
        if (this.channel != null && CompressionInstaller.install(this.channel, this.receiving, threshold, validate,
                NativeSupport::newCompressor, ConnectionCompressionMixin::aegis$endVanilla)) {
            ci.cancel();
        }
    }

    /** Frees a replaced vanilla handler's native zlib memory now instead of whenever the GC runs its cleaner. */
    private static void aegis$endVanilla(ChannelHandler handler) {
        if (handler instanceof CompressionDecoder d) {
            ((CompressionDecoderAccessor) d).aegis$inflater().end();
        } else if (handler instanceof CompressionEncoder e) {
            ((CompressionEncoderAccessor) e).aegis$deflater().end();
        }
    }
}
