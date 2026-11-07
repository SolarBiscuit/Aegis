package com.solarbiscuit.aegis.mixin.access;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;

@Mixin(PersistentEntitySectionManager.class)
public interface PersistentEntitySectionManagerAccessor {
    @Accessor("sectionStorage")
    EntitySectionStorage<?> aegis$sectionStorage();
}
