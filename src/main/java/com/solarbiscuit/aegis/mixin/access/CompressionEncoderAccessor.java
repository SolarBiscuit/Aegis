package com.solarbiscuit.aegis.mixin.access;

import java.util.zip.Deflater;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.network.CompressionEncoder;

@Mixin(CompressionEncoder.class)
public interface CompressionEncoderAccessor {
    @Accessor("deflater")
    Deflater aegis$deflater();
}
