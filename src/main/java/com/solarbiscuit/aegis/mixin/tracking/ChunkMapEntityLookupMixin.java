package com.solarbiscuit.aegis.mixin.tracking;

import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.solarbiscuit.aegis.server.ChunkEntityLookup;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Krypton's "avoid work": when a chunk is sent, vanilla walks every tracked entity of the dimension to find the ones in
 * that chunk. Here the loop is handed only the entities of that chunk (from the level's entity sections) and vanilla's
 * own loop still does the rest (its chunk check, spawn, leash and passenger packets), so the packets are the same.
 * Krypton instead emptied the loop and re-implemented it in a second injection, which sends no entities at all if only
 * one of the two applies. Here a missing piece only means the full map is used.
 * <p>
 * The chunk is handed from the HEAD injection to the redirect through a plain field, which is only safe on the server
 * thread. A call from any other thread (a mod sending chunks off-thread) is left to vanilla's full scan and never
 * touches the field.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapEntityLookupMixin {
    @Shadow
    @Final
    ServerLevel level;

    @Shadow
    @Final
    private Int2ObjectMap<ChunkMap.TrackedEntity> entityMap;

    @Unique
    private LevelChunk aegis$sendingChunk;

    @Inject(method = "playerLoadedChunk", at = @At("HEAD"), require = 0)
    private void aegis$rememberChunk(ServerPlayer player, MutableObject<ClientboundLevelChunkWithLightPacket> packet, LevelChunk chunk, CallbackInfo ci) {
        if (this.level.getServer().isSameThread()) {
            this.aegis$sendingChunk = chunk;
        }
    }

    @Redirect(method = "playerLoadedChunk", require = 0,
            at = @At(value = "FIELD", target = "Lnet/minecraft/server/level/ChunkMap;entityMap:Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;", opcode = 180))
    private Int2ObjectMap<ChunkMap.TrackedEntity> aegis$entitiesInChunk(ChunkMap self) {
        if (!this.level.getServer().isSameThread()) {
            return this.entityMap;
        }
        LevelChunk chunk = this.aegis$sendingChunk;
        this.aegis$sendingChunk = null;
        if (chunk == null) {
            return this.entityMap;
        }
        Int2ObjectMap<ChunkMap.TrackedEntity> inChunk = ChunkEntityLookup.trackedInChunk(this.level, this.entityMap, chunk.getPos());
        return inChunk != null ? inChunk : this.entityMap;
    }
}
