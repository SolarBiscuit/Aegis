package com.solarbiscuit.aegis.mixin.login;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.solarbiscuit.aegis.net.LoginCompressionOrder;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;

/**
 * Turns compression on right after ClientboundLoginCompressionPacket is written instead of when the write completes
 * (see {@link LoginCompressionOrder}). Vanilla's listener ran on success and on failure alike, so dropping it loses
 * nothing. require = 0: if another mod already redirects this call, its version runs and vanilla's order applies.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginCompressionOrderMixin {
    @Redirect(method = "handleAcceptedLogin", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V"))
    private void aegis$sendCompressionInOrder(Connection connection, Packet<?> packet, PacketSendListener listener) {
        Channel channel = connection.channel();
        if (!(packet instanceof ClientboundLoginCompressionPacket compression) || channel == null) {
            connection.send(packet, listener);
            return;
        }
        int threshold = compression.getCompressionThreshold();
        connection.send(packet);
        LoginCompressionOrder.afterQueuedWrites(channel, () -> connection.setupCompression(threshold, true));
    }
}
