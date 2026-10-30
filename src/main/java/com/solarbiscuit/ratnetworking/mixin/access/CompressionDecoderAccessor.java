package com.solarbiscuit.ratnetworking.mixin.access;

import java.util.zip.Inflater;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.network.CompressionDecoder;

@Mixin(CompressionDecoder.class)
public interface CompressionDecoderAccessor {
    @Accessor("inflater")
    Inflater ratnetworking$inflater();
}
