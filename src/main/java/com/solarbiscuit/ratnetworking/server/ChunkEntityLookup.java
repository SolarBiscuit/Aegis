package com.solarbiscuit.ratnetworking.server;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.solarbiscuit.ratnetworking.mixin.access.EntitySectionAccessor;
import com.solarbiscuit.ratnetworking.mixin.access.EntitySectionStorageAccessor;
import com.solarbiscuit.ratnetworking.mixin.access.PersistentEntitySectionManagerAccessor;
import com.solarbiscuit.ratnetworking.mixin.access.ServerLevelAccessor;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntitySection;

/** Finds the tracked entities of one chunk through the level's entity sections (one lookup per section). */
public final class ChunkEntityLookup {
    private static final Logger LOGGER = LogManager.getLogger("RatNetworking");
    private static boolean failed;

    private ChunkEntityLookup() {
    }

    /**
     * The tracked entities whose entity section is in {@code pos}, or null if the lookup is not possible (the caller
     * then uses the full tracker map, which is always correct).
     */
    public static Int2ObjectMap<ChunkMap.TrackedEntity> trackedInChunk(ServerLevel level, Int2ObjectMap<ChunkMap.TrackedEntity> trackers, ChunkPos pos) {
        if (failed) {
            return null;
        }
        try {
            EntitySectionStorageAccessor storage = (EntitySectionStorageAccessor)
                    ((PersistentEntitySectionManagerAccessor) ((ServerLevelAccessor) level).ratnetworking$entityManager()).ratnetworking$sectionStorage();
            LongSortedSet sectionKeys = storage.ratnetworking$getChunkSections(pos.x, pos.z);
            if (sectionKeys.isEmpty()) {
                return Int2ObjectMaps.emptyMap();
            }
            Long2ObjectMap<EntitySection<?>> sections = storage.ratnetworking$sections();
            Int2ObjectMap<ChunkMap.TrackedEntity> result = null;
            for (LongIterator it = sectionKeys.iterator(); it.hasNext(); ) {
                EntitySection<?> section = sections.get(it.nextLong());
                if (section == null) {
                    continue;
                }
                for (Object o : ((EntitySectionAccessor) section).ratnetworking$storage()) {
                    if (o instanceof Entity entity) {
                        ChunkMap.TrackedEntity tracked = trackers.get(entity.getId());
                        if (tracked != null && tracked.entity == entity) {
                            if (result == null) {
                                result = new Int2ObjectOpenHashMap<>();
                            }
                            result.put(entity.getId(), tracked);
                        }
                    }
                }
            }
            return result != null ? result : Int2ObjectMaps.emptyMap();
        } catch (RuntimeException e) {
            failed = true;
            LOGGER.warn("Chunk entity lookup failed, falling back to vanilla's full scan from now on", e);
            return null;
        }
    }
}
