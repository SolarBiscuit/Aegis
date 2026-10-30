package com.solarbiscuit.ratnetworking.mixin.limits;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import com.solarbiscuit.ratnetworking.net.Limits;

import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket;

/**
 * Login query answers from the client. Before login the vanilla frame (2 MiB) and decompression (8 MiB) limits apply,
 * so this is raised to the vanilla decompression limit, not further.
 */
@Mixin(ServerboundCustomQueryPacket.class)
public abstract class ServerboundCustomQueryPacketLimitMixin {
    @ModifyConstant(method = {"lambda$new$0", "m_238038_"}, remap = false, constant = @Constant(intValue = 1048576))
    private static int ratnetworking$maxPayload(int original) {
        return Math.max(original, Limits.VANILLA_MAX_UNCOMPRESSED);
    }

    @ModifyConstant(method = {"lambda$new$0", "m_238038_"}, remap = false, constant = @Constant(stringValue = "Payload may not be larger than 1048576 bytes"), require = 0)
    private static String ratnetworking$message(String original) {
        return "Payload may not be larger than " + Limits.VANILLA_MAX_UNCOMPRESSED + " bytes";
    }
}
