package com.solarbiscuit.aegis.mixin.limits;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import com.solarbiscuit.aegis.config.AegisConfig;

import net.minecraft.network.FriendlyByteBuf;

@Mixin(FriendlyByteBuf.class)
public abstract class FriendlyByteBufLimitsMixin {
    /** "Tried to read NBT tag that was too big" (item stacks with large NBT, e.g. backpacks). */
    @ModifyConstant(method = "readNbt()Lnet/minecraft/nbt/CompoundTag;", constant = @Constant(longValue = 2097152L), require = 0)
    private long aegis$nbtQuota(long original) {
        return Math.max(original, AegisConfig.nbtQuota);
    }

    @ModifyConstant(method = "readUtf()Ljava/lang/String;", constant = @Constant(intValue = 32767), require = 0)
    private int aegis$readUtfLength(int original) {
        return Math.max(original, AegisConfig.maxStringLength);
    }

    @ModifyConstant(method = "writeUtf(Ljava/lang/String;)Lnet/minecraft/network/FriendlyByteBuf;", constant = @Constant(intValue = 32767), require = 0)
    private int aegis$writeUtfLength(int original) {
        return Math.max(original, AegisConfig.maxStringLength);
    }
}
