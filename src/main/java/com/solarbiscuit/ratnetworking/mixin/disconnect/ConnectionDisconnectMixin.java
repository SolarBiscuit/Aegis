package com.solarbiscuit.ratnetworking.mixin.disconnect;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import io.netty.channel.ChannelFuture;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;

/**
 * Connection.disconnect blocks the calling thread (usually the server thread) until the socket is closed, which waits
 * for the connection's Netty thread to get to it; with a busy Netty thread (compressing a chunk burst for another
 * player) that stalls the tick, once per kicked player (VMP avoid_deadlocks).
 * <p>
 * Only remote server-side connections skip the wait. Nothing depends on it there: the disconnect is handled on the next
 * network tick once the channel reports closed, exactly as it already is for disconnects that start with a packet.
 * Client and in-memory (singleplayer) connections keep waiting, because world exit relies on that ordering. VMP also
 * redirected every Channel.isOpen() call in the class, which other mods' redirects of the same calls conflict with;
 * that part is not needed.
 * <p>
 * require = 0: Connectivity redirects the same call (a 10 s wait); a second redirect is skipped by Mixin.
 */
@Mixin(Connection.class)
public abstract class ConnectionDisconnectMixin {
    @Shadow
    @Final
    private PacketFlow receiving;

    @Shadow
    public abstract boolean isMemoryConnection();

    @Redirect(method = "disconnect", at = @At(value = "INVOKE", target = "Lio/netty/channel/ChannelFuture;awaitUninterruptibly()Lio/netty/channel/ChannelFuture;", remap = false), require = 0)
    private ChannelFuture ratnetworking$dontWait(ChannelFuture future) {
        if (this.receiving == PacketFlow.SERVERBOUND && !this.isMemoryConnection()) {
            return future;
        }
        return future.awaitUninterruptibly();
    }
}
