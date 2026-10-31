package com.solarbiscuit.ratnetworking.gametest;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.solarbiscuit.ratnetworking.RatNetworking;
import com.solarbiscuit.ratnetworking.server.TrackingFilter;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** The tracker pre-check must not skip entities that a camera mod's updatePlayer would track. */
@GameTestHolder(RatNetworking.MOD_ID)
@PrefixGameTestTemplate(false)
public class TrackingFilterGameTest {
    private static final int VIEW_DISTANCE = 10; // range cap 160 blocks

    @GameTest(template = "empty")
    public static void cameraKeepsEntitiesTracked(GameTestHelper helper) throws ReflectiveOperationException {
        Vec3 origin = helper.absoluteVec(new Vec3(0.5, 1, 0.5));
        Entity target = helper.spawn(EntityType.ARMOR_STAND, new Vec3(0.5, 1, 0.5));
        Entity camera = helper.spawn(EntityType.ARMOR_STAND, new Vec3(0.5, 1, 0.5));
        // Not placed in the player list: Forge 1.20.1's makeMockServerPlayerInLevel fails on its channel-less Connection.
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), new GameProfile(UUID.randomUUID(), "rn-test"));
        Set<ServerPlayerConnection> seenBy = new HashSet<>();

        player.setPos(origin.x + 400, origin.y, origin.z);
        check(helper, TrackingFilter.canSkip(target, seenBy, player, VIEW_DISTANCE), "far player, no camera: skipped");

        // Camera mods set the camera without moving the player (vanilla setCamera would teleport it).
        Field field = ServerPlayer.class.getDeclaredField("camera");
        field.setAccessible(true);
        field.set(player, camera);
        camera.setPos(origin.x + 2, origin.y, origin.z);
        check(helper, !TrackingFilter.canSkip(target, seenBy, player, VIEW_DISTANCE), "far player, camera next to the entity: not skipped");

        camera.setPos(origin.x - 400, origin.y, origin.z);
        check(helper, TrackingFilter.canSkip(target, seenBy, player, VIEW_DISTANCE), "far player, far camera: skipped");

        player.setPos(origin.x + 3, origin.y, origin.z);
        check(helper, !TrackingFilter.canSkip(target, seenBy, player, VIEW_DISTANCE), "near player: not skipped");
        helper.succeed();
    }

    private static void check(GameTestHelper helper, boolean ok, String what) {
        if (!ok) {
            helper.fail("Expected: " + what);
        }
    }
}
