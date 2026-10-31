package com.solarbiscuit.ratnetworking.server;

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
 * <p>
 * Camera mods (SecurityCraft cameras, Ars Nouveau scrying, Steam 'n' Rails conductor possession) make updatePlayer
 * measure from {@code player.getCamera()} instead of the player, so a call is only skipped when the entity is out of
 * range of both. In vanilla the camera is the player itself (a spectator is moved to the entity it spectates).
 */
public final class TrackingFilter {
    private TrackingFilter() {
    }

    /** True if updatePlayer(player) would certainly be a no-op. */
    public static boolean canSkip(Entity entity, Set<ServerPlayerConnection> seenBy, ServerPlayer player, int viewDistance) {
        if (player == entity) {
            return true;
        }
        double max = viewDistance * 16.0;
        if (!outOfRange(player, entity, max)) {
            return false;
        }
        Entity camera = player.getCamera();
        if (camera != player && camera != null && !outOfRange(camera, entity, max)) {
            return false;
        }
        return !seenBy.contains(player.connection);
    }

    private static boolean outOfRange(Entity viewer, Entity entity, double max) {
        double dx = viewer.getX() - entity.getX();
        double dz = viewer.getZ() - entity.getZ();
        return dx * dx + dz * dz > max * max;
    }
}
