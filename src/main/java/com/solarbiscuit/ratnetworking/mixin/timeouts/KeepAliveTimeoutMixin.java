package com.solarbiscuit.ratnetworking.mixin.timeouts;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import com.solarbiscuit.ratnetworking.config.RNConfig;

import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * Vanilla uses one constant (15 s) as both the keep-alive interval and the timeout. Packet Fixer raised the constant,
 * which also made the server send keep-alives only every 2 minutes, so player latency (tab list) went stale. Here the
 * interval stays 15 s and only the wait for an answer is longer.
 * <p>
 * require = 0: other mods modify the same constant (Packet Fixer, Connectivity), and Mixin skips the second
 * {@code @ModifyConstant} with a warning. Losing a timeout tweak must not stop the game from starting.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class KeepAliveTimeoutMixin {
    @Shadow
    private boolean keepAlivePending;

    @ModifyConstant(method = "tick", constant = @Constant(longValue = 15000L), require = 0)
    private long ratnetworking$keepAlive(long original) {
        return this.keepAlivePending ? Math.max(original, RNConfig.keepAliveTimeoutSeconds * 1000L) : original;
    }
}
