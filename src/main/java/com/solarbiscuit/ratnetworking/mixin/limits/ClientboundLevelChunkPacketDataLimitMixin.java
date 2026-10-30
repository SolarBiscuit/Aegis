package com.solarbiscuit.ratnetworking.mixin.limits;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import com.solarbiscuit.ratnetworking.net.Limits;

import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;

/** "Chunk Packet trying to allocate too much memory on read." (client side, chunks with lots of block data). */
@Mixin(ClientboundLevelChunkPacketData.class)
public abstract class ClientboundLevelChunkPacketDataLimitMixin {
    @ModifyConstant(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;II)V", constant = @Constant(intValue = 2097152))
    private int ratnetworking$maxChunkData(int original) {
        return Math.max(original, Limits.clientbound());
    }
}
