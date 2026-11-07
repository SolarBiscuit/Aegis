package com.solarbiscuit.aegis.mixin.access;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.world.level.entity.EntitySection;

@Mixin(EntitySection.class)
public interface EntitySectionAccessor {
    @Accessor("storage")
    ClassInstanceMultiMap<?> aegis$storage();
}
