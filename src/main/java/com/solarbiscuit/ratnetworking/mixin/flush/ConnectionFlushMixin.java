package com.solarbiscuit.ratnetworking.mixin.flush;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.solarbiscuit.ratnetworking.net.flush.FlushControl;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;

/**
 * Installs the flush handler on real server connections, and replaces the unconditional per-tick flush (VMP's
 * no_flush): every send already flushes, held sends are flushed when their hold ends, so the tick flush only woke the
 * Netty thread of every connection once per tick for nothing. It is kept as the safety net that releases a leaked hold.
 */
@Mixin(Connection.class)
public abstract class ConnectionFlushMixin {
    @Shadow
    private Channel channel;

    @Shadow
    @Final
    private PacketFlow receiving;

    @Shadow
    public abstract boolean isMemoryConnection();

    @Unique
    private boolean ratnetworking$flushHandled;

    @Inject(method = "channelActive", at = @At("TAIL"), remap = false)
    private void ratnetworking$addFlushHandler(ChannelHandlerContext ctx, CallbackInfo ci) {
        if (this.channel != null && this.receiving == PacketFlow.SERVERBOUND && !this.isMemoryConnection()
                && this.channel.pipeline().get(FlushControl.HANDLER_NAME) == null) {
            this.channel.pipeline().addFirst(FlushControl.HANDLER_NAME, new FlushControl.Handler());
            this.ratnetworking$flushHandled = true;
        }
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lio/netty/channel/Channel;flush()Lio/netty/channel/Channel;", remap = false))
    private Channel ratnetworking$tickFlush(Channel channel) {
        if (this.ratnetworking$flushHandled) {
            FlushControl.onConnectionTick(channel);
            return channel;
        }
        return channel.flush();
    }
}
