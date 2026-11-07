package com.solarbiscuit.aegis.mixin.tracking;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.solarbiscuit.aegis.server.TrackingFilter;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;

/**
 * Entity tracker pre-check (see {@link TrackingFilter}) at the call sites that loop over every tracked entity or every
 * player: move (each player movement) and both loops of tick. Priority 1100 so the redirects also land in Radium's
 * overwritten move(), which keeps the same calls. If another mod replaced these loops without the calls, the
 * optimization is simply skipped (require = 0).
 */
@Mixin(value = ChunkMap.class, priority = 1100)
public abstract class ChunkMapTrackingMixin {
    @Shadow
    int viewDistance;

    @Redirect(method = "move", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap$TrackedEntity;updatePlayer(Lnet/minecraft/server/level/ServerPlayer;)V"))
    private void aegis$updatePlayer(ChunkMap.TrackedEntity tracked, ServerPlayer player) {
        if (!TrackingFilter.canSkip(tracked.entity, tracked.seenBy, player, this.viewDistance)) {
            tracked.updatePlayer(player);
        }
    }

    @Redirect(method = {"move", "tick()V"}, require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap$TrackedEntity;updatePlayers(Ljava/util/List;)V"))
    private void aegis$updatePlayers(ChunkMap.TrackedEntity tracked, List<ServerPlayer> players) {
        int viewDistance = this.viewDistance;
        for (int i = 0, n = players.size(); i < n; i++) {
            ServerPlayer player = players.get(i);
            if (!TrackingFilter.canSkip(tracked.entity, tracked.seenBy, player, viewDistance)) {
                tracked.updatePlayer(player);
            }
        }
    }
}
