package com.solarbiscuit.ratnetworking.mixin.limits;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import com.solarbiscuit.ratnetworking.net.Limits;

import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;

/**
 * "Payload may not be larger than 32767 bytes" for mod packets sent by clients. Only reachable in the PLAY state, and
 * the frame/decompression limits already bound how much a client can send.
 */
@Mixin(ServerboundCustomPayloadPacket.class)
public abstract class ServerboundCustomPayloadPacketLimitMixin {
    @ModifyConstant(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V", constant = @Constant(intValue = 32767))
    private int ratnetworking$maxPayload(int original) {
        return Math.max(original, Limits.serverboundPlay());
    }

    @ModifyConstant(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V", constant = @Constant(stringValue = "Payload may not be larger than 32767 bytes"), require = 0)
    private String ratnetworking$message(String original) {
        return "Payload may not be larger than " + Limits.serverboundPlay() + " bytes (RatNetworking limits.serverboundMaxPacketSize)";
    }
}
