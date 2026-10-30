package com.solarbiscuit.ratnetworking.mixin.leaks;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.solarbiscuit.ratnetworking.util.HeapCopy;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket;

/**
 * Payload data in a GC-managed heap buffer (see {@link HeapCopy}). The lambda has no refmap entry; it is lambda$new$0 in
 * development and m_238038_ in production (Packet Fixer targeted only the first name, so its fix never applied outside
 * development).
 */
@Mixin(ServerboundCustomQueryPacket.class)
public abstract class ServerboundCustomQueryPacketHeapCopyMixin {
    @Redirect(method = {"lambda$new$0", "m_238038_"}, remap = false,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/FriendlyByteBuf;readBytes(I)Lio/netty/buffer/ByteBuf;"))
    private static ByteBuf ratnetworking$heapCopy(FriendlyByteBuf buf, int length) {
        return HeapCopy.read(buf, length);
    }
}
