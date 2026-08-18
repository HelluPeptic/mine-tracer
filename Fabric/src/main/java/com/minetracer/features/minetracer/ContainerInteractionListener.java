package com.minetracer.features.minetracer;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

public class ContainerInteractionListener {
    public static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer)) {
                return InteractionResult.PASS;
            }
            BlockPos pos = hitResult.getBlockPos();

            if (ContainerPositionTracker.isTrackableContainer(world, pos)) {
                // Get canonical container position using CoreProtect's approach
                BlockPos canonicalPos = ContainerPositionTracker.getContainerPosition(world, pos);
                if (canonicalPos != null) {
                    ContainerPositionTracker.setLastOpenedContainer(player.getUUID(), canonicalPos);
                }
            }
            return InteractionResult.PASS;
        });
    }
}
