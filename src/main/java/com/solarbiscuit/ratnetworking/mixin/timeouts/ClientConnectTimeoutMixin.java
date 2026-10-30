package com.solarbiscuit.ratnetworking.mixin.timeouts;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import com.solarbiscuit.ratnetworking.config.RNConfig;

/**
 * Client-side read timeout (Connection.connect's channel initializer). The server side reads Forge's
 * forge.readTimeout property, which the mixin plugin sets. require = 0 for the same reason as KeepAliveTimeoutMixin.
 */
@Mixin(targets = "net.minecraft.network.Connection$1")
public abstract class ClientConnectTimeoutMixin {
    @ModifyConstant(method = "initChannel", constant = @Constant(intValue = 30), remap = false, require = 0)
    private int ratnetworking$readTimeout(int original) {
        return RNConfig.readTimeoutSeconds;
    }
}
