package com.solarbiscuit.ratnetworking.mixin.flush;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.solarbiscuit.ratnetworking.net.flush.FlushControl;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;

/**
 * One hold per level per tick around the chunk tick: it broadcasts every block change of the tick and then runs the
 * entity tracker (ChunkMap.tick), which together send most of a server's packets.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheFlushMixin {
    @Shadow
    @Final
    ServerLevel level;

    @Unique
    private FlushControl.Batch ratnetworking$batch;

    @Inject(method = "tickChunks", at = @At("HEAD"))
    private void ratnetworking$holdFlushes(CallbackInfo ci) {
        FlushControl.Batch previous = this.ratnetworking$batch;
        if (previous != null) {
            previous.release(); // the previous tick threw half-way; never leave a hold behind
        }
        this.ratnetworking$batch = FlushControl.hold(this.level.players());
    }

    @Inject(method = "tickChunks", at = @At("RETURN"))
    private void ratnetworking$releaseFlushes(CallbackInfo ci) {
        FlushControl.Batch batch = this.ratnetworking$batch;
        this.ratnetworking$batch = null;
        if (batch != null) {
            batch.release();
        }
    }
}
