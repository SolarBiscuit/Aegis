package com.solarbiscuit.ratnetworking.server;

import java.util.List;
import java.util.Set;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;

/**
 * Pre-check for ChunkMap.TrackedEntity#updatePlayer (the VMP entity tracking hot spot, without replacing the tracker).
 * <p>
 * Vanilla calls updatePlayer for every tracked entity of a dimension each time a player moves, and for every player
 * each time an entity changes section. Each call allocates a Vec3, walks the entity's passengers (a stream) to get its
 * tracking range and calls broadcastToPlayer. When the player is not tracking the entity and is horizontally farther
 * away than the server view distance, updatePlayer can only decide "not visible" and then do nothing (it removes the
 * player from a set it is not in), because the range it compares against is min(entity range, view distance * 16).
 * Such calls are skipped here, so the result is exactly vanilla's.
 */
public final class TrackingFilter {
    private TrackingFilter() {
    }

    /** True if updatePlayer(player) would certainly be a no-op. */
    public static boolean canSkip(Entity entity, Set<ServerPlayerConnection> seenBy, ServerPlayer player, int viewDistance) {
        if (player == entity) {
            return true;
        }
        double dx = player.getX() - entity.getX();
        double dz = player.getZ() - entity.getZ();
        double max = viewDistance * 16.0;
        return dx * dx + dz * dz > max * max && !seenBy.contains(player.connection);
    }

    public static boolean canSkipAll(Entity entity, Set<ServerPlayerConnection> seenBy, List<ServerPlayer> players, int viewDistance) {
        for (int i = 0, n = players.size(); i < n; i++) {
            if (!canSkip(entity, seenBy, players.get(i), viewDistance)) {
                return false;
            }
        }
        return true;
    }
}
