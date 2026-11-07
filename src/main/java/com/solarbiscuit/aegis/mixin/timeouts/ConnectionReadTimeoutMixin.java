package com.solarbiscuit.aegis.mixin.timeouts;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.solarbiscuit.aegis.config.AegisConfig;
import com.solarbiscuit.aegis.net.ReadTimeouts;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.PacketFlow;

/**
 * Raises the read timeout (vanilla 30 s, server and client) once a connection switches to LOGIN or PLAY. Both sides
 * call setProtocol(LOGIN) when a join starts: the server when it handles the handshake, the client when it sends its
 * hello. Server-list pings (STATUS) keep 30 s.
 * <p>
 * Up to 0.2.0 the client constant was raised for every connection (pings included) and the server used the
 * forge.readTimeout property for every connection. A forge.readTimeout set by the user on the command line still wins
 * on the server.
 */
@Mixin(Connection.class)
public abstract class ConnectionReadTimeoutMixin {
    @Shadow
    private Channel channel;

    @Shadow
    @Final
    private PacketFlow receiving;

    @Inject(method = "setProtocol", at = @At("TAIL"))
    private void aegis$raiseReadTimeout(ConnectionProtocol protocol, CallbackInfo ci) {
        if (this.channel == null || (protocol != ConnectionProtocol.LOGIN && protocol != ConnectionProtocol.PLAY)) {
            return;
        }
        if (this.receiving == PacketFlow.SERVERBOUND && System.getProperty("forge.readTimeout") != null) {
            return;
        }
        ReadTimeouts.raise(this.channel, AegisConfig.readTimeoutSeconds);
    }
}
