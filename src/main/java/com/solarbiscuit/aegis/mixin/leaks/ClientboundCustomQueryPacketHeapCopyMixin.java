package com.solarbiscuit.aegis.mixin.leaks;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.solarbiscuit.aegis.util.HeapCopy;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;

/** Payload data in a GC-managed heap buffer (see {@link HeapCopy}). */
@Mixin(ClientboundCustomQueryPacket.class)
public abstract class ClientboundCustomQueryPacketHeapCopyMixin {
    @Redirect(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/FriendlyByteBuf;readBytes(I)Lio/netty/buffer/ByteBuf;", remap = false))
    private ByteBuf aegis$heapCopy(FriendlyByteBuf buf, int length) {
        return HeapCopy.read(buf, length);
    }
}
