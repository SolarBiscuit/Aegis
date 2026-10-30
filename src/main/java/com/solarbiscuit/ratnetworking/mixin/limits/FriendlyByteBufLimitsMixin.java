package com.solarbiscuit.ratnetworking.mixin.limits;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import com.solarbiscuit.ratnetworking.config.RNConfig;

import net.minecraft.network.FriendlyByteBuf;

@Mixin(FriendlyByteBuf.class)
public abstract class FriendlyByteBufLimitsMixin {
    /** "Tried to read NBT tag that was too big" (item stacks with large NBT, e.g. backpacks). */
    @ModifyConstant(method = "readNbt()Lnet/minecraft/nbt/CompoundTag;", constant = @Constant(longValue = 2097152L), require = 0)
    private long ratnetworking$nbtQuota(long original) {
        return Math.max(original, RNConfig.nbtQuota);
    }

    @ModifyConstant(method = "readUtf()Ljava/lang/String;", constant = @Constant(intValue = 32767), require = 0)
    private int ratnetworking$readUtfLength(int original) {
        return Math.max(original, RNConfig.maxStringLength);
    }

    @ModifyConstant(method = "writeUtf(Ljava/lang/String;)Lnet/minecraft/network/FriendlyByteBuf;", constant = @Constant(intValue = 32767), require = 0)
    private int ratnetworking$writeUtfLength(int original) {
        return Math.max(original, RNConfig.maxStringLength);
    }
}
