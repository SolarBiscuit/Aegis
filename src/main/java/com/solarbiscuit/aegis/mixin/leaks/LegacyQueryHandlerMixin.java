package com.solarbiscuit.aegis.mixin.leaks;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.server.network.LegacyQueryHandler;

/**
 * Data that arrives after the channel closed is dropped (Krypton). Krypton cleared the buffer but never released it,
 * leaking a pooled buffer each time; this releases it.
 */
@Mixin(LegacyQueryHandler.class)
public abstract class LegacyQueryHandlerMixin {
    @Inject(method = "channelRead", at = @At("HEAD"), cancellable = true, remap = false)
    private void aegis$dropWhenInactive(ChannelHandlerContext ctx, Object msg, CallbackInfo ci) {
        if (!ctx.channel().isActive()) {
            ReferenceCountUtil.release(msg);
            ci.cancel();
        }
    }
}
