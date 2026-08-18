package com.minetracer.features.minetracer;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

public class ContainerInteractionListener {
    public static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (hand != Hand.MAIN_HAND || !(player instanceof ServerPlayerEntity)) {
                return ActionResult.PASS;
            }
            BlockPos pos = hitResult.getBlockPos();

            if (ContainerPositionTracker.isTrackableContainer(world, pos)) {
                // Get canonical container position using CoreProtect's approach
                BlockPos canonicalPos = ContainerPositionTracker.getContainerPosition(world, pos);
                if (canonicalPos != null) {
                    ContainerPositionTracker.setLastOpenedContainer(player.getUuid(), canonicalPos);
                }
            }
            return ActionResult.PASS;
        });
    }
}
