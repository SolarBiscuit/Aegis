package com.solarbiscuit.aegis.mixin.limits;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import com.solarbiscuit.aegis.net.Limits;

import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;

/** "Payload may not be larger than 1048576 bytes", when a server builds one and when a client reads one. */
@Mixin(ClientboundCustomPayloadPacket.class)
public abstract class ClientboundCustomPayloadPacketLimitMixin {
    @ModifyConstant(method = {"<init>(Lnet/minecraft/network/FriendlyByteBuf;)V", "<init>(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/network/FriendlyByteBuf;)V"},
            constant = @Constant(intValue = 1048576))
    private int aegis$maxPayload(int original) {
        return Math.max(original, Limits.clientbound());
    }

    @ModifyConstant(method = {"<init>(Lnet/minecraft/network/FriendlyByteBuf;)V", "<init>(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/network/FriendlyByteBuf;)V"},
            constant = @Constant(stringValue = "Payload may not be larger than 1048576 bytes"), require = 0)
    private String aegis$message(String original) {
        return "Payload may not be larger than " + Limits.clientbound() + " bytes (Aegis limits.clientboundMaxPacketSize)";
    }
}
