package com.solarbiscuit.aegis.mixin.timeouts;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import com.solarbiscuit.aegis.config.AegisConfig;

import net.minecraft.server.network.ServerLoginPacketListenerImpl;

/** "Took too long to log in" (vanilla 30 s). require = 0 for the same reason as KeepAliveTimeoutMixin. */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginTimeoutMixin {
    @ModifyConstant(method = "tick", constant = @Constant(intValue = 600), require = 0)
    private int aegis$loginTicks(int original) {
        return AegisConfig.loginTimeoutSeconds * 20;
    }
}
