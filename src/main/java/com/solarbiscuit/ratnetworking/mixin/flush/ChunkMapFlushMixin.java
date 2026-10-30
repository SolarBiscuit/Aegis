package com.solarbiscuit.ratnetworking.mixin.flush;

import java.util.ArrayDeque;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.solarbiscuit.ratnetworking.net.flush.FlushControl;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;

/**
 * Holds a player's flushes while their chunks are (re)sent: on joining or changing dimension (updatePlayerStatus) and
 * when moving into another chunk (move). Krypton did this by @Overwrite-ing both methods with its own chunk sending;
 * here vanilla's (or Lithium's, or C2ME's) chunk sending runs unchanged. Priority 1100 so this also wraps Radium's
 * overwritten move().
 */
@Mixin(value = ChunkMap.class, priority = 1100)
public abstract class ChunkMapFlushMixin {
    @Unique
    private final ArrayDeque<FlushControl.Batch> ratnetworking$batches = new ArrayDeque<>();

    @Inject(method = "updatePlayerStatus", at = @At("HEAD"))
    private void ratnetworking$holdOnStatus(ServerPlayer player, boolean added, CallbackInfo ci) {
        this.ratnetworking$batches.push(FlushControl.hold(player));
    }

    @Inject(method = "updatePlayerStatus", at = @At("RETURN"))
    private void ratnetworking$releaseOnStatus(ServerPlayer player, boolean added, CallbackInfo ci) {
        FlushControl.Batch batch = this.ratnetworking$batches.poll();
        if (batch != null) {
            batch.release();
        }
    }

    @Inject(method = "move", at = @At("HEAD"))
    private void ratnetworking$holdOnMove(ServerPlayer player, CallbackInfo ci) {
        // Only a chunk change sends chunks; holding on every movement packet would add a flush instead of saving one.
        SectionPos last = player.getLastSectionPos();
        boolean crossesChunk = last.x() != SectionPos.blockToSectionCoord(player.getBlockX())
                || last.z() != SectionPos.blockToSectionCoord(player.getBlockZ());
        this.ratnetworking$batches.push(crossesChunk ? FlushControl.hold(player) : FlushControl.Batch.none());
    }

    @Inject(method = "move", at = @At("RETURN"))
    private void ratnetworking$releaseOnMove(ServerPlayer player, CallbackInfo ci) {
        FlushControl.Batch batch = this.ratnetworking$batches.poll();
        if (batch != null) {
            batch.release();
        }
    }

    /**
     * A movement packet whose handling threw (logged and skipped by the server) never reached RETURN; release whatever
     * it left behind once per tick, so no batch (and the channel it references) is kept.
     */
    @Inject(method = "tick()V", at = @At("HEAD"))
    private void ratnetworking$releaseLeftovers(CallbackInfo ci) {
        FlushControl.Batch batch;
        while ((batch = this.ratnetworking$batches.poll()) != null) {
            batch.release();
        }
    }
}
