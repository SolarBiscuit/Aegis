package com.solarbiscuit.ratnetworking.mixin.access;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;

@Mixin(EntitySectionStorage.class)
public interface EntitySectionStorageAccessor {
    @Accessor("sections")
    Long2ObjectMap<EntitySection<?>> ratnetworking$sections();

    @Invoker("getChunkSections")
    LongSortedSet ratnetworking$getChunkSections(int chunkX, int chunkZ);
}
