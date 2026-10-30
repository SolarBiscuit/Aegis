package com.solarbiscuit.ratnetworking.mixin.varint;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import com.solarbiscuit.ratnetworking.net.VarInts;

import net.minecraft.network.FriendlyByteBuf;

/**
 * VarInt size lookup (Krypton). Krypton also overwrote writeVarInt and writeUtf; measured on JDK 17+ those are not
 * faster (writeVarInt ~15% slower, writeUtf ~40% slower in exchange for one short-lived byte[]), so vanilla's are kept.
 */
@Mixin(FriendlyByteBuf.class)
public abstract class FriendlyByteBufVarIntMixin {
    /**
     * @author SolarBiscuit (from Krypton)
     * @reason A table lookup instead of a loop (about 10x faster); used for every packet and frame length.
     */
    @Overwrite
    public static int getVarIntSize(int value) {
        return VarInts.size(value);
    }
}
