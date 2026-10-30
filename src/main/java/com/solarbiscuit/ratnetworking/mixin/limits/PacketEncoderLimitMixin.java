package com.solarbiscuit.ratnetworking.mixin.limits;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import com.solarbiscuit.ratnetworking.net.Limits;

import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.PacketFlow;

/**
 * "Packet too big (is X, should be less than 8388608)", per sending direction. require = 0: other mods modify the same
 * constant (Packet Fixer, Connectivity) and Mixin skips the second one; their limit then applies.
 */
@Mixin(PacketEncoder.class)
public abstract class PacketEncoderLimitMixin {
    @Shadow
    @Final
    private PacketFlow flow;

    @ModifyConstant(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V", constant = @Constant(intValue = 8388608), require = 0)
    private int ratnetworking$maxPacket(int original) {
        return Math.max(original, Limits.maxOutbound(this.flow));
    }
}
