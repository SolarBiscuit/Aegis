package com.solarbiscuit.aegis.mixin.security;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.solarbiscuit.aegis.net.security.Security;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.PacketFlow;

/**
 * Runs the pre-login throttle when a real server connection becomes active, and takes the connection off its address's
 * pending count once it reaches PLAY. At TAIL of channelActive, so Connection.channel is set: a connection closed here is
 * then cleaned up by ServerConnectionListener.tick like any other closed connection.
 */
@Mixin(Connection.class)
public abstract class ConnectionThrottleMixin {
    @Shadow
    private Channel channel;

    @Shadow
    @Final
    private PacketFlow receiving;

    @Shadow
    public abstract boolean isMemoryConnection();

    @Inject(method = "channelActive", at = @At("TAIL"), remap = false)
    private void aegis$throttle(ChannelHandlerContext ctx, CallbackInfo ci) {
        if (this.channel != null && this.receiving == PacketFlow.SERVERBOUND && !this.isMemoryConnection()) {
            Security.onConnect(this.channel);
        }
    }

    @Inject(method = "setProtocol", at = @At("TAIL"))
    private void aegis$loggedIn(ConnectionProtocol protocol, CallbackInfo ci) {
        if (protocol == ConnectionProtocol.PLAY && this.channel != null && this.receiving == PacketFlow.SERVERBOUND) {
            Security.loggedIn(this.channel);
        }
    }
}
