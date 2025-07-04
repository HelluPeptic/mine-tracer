package com.minetracer.features.minetracer;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

// High-performance container event listener with async operations
public class OptimizedChestEventListener {

    public static void register() {
        // Listen for block use (opening containers) with async processing
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            // Non-blocking container detection
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                BlockPos pos = hitResult.getBlockPos();
                Block block = world.getBlockState(pos).getBlock();
                if (isTrackedContainer(block)) {
                    // Optionally: log open event or prepare for inventory change tracking
                }
            });
            return ActionResult.PASS;
        });

        // Listen for block break (removal of containers) with async inventory
        // processing
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            Block block = state.getBlock();
            if (isTrackedContainer(block) && blockEntity instanceof Inventory inv) {
                // Process inventory contents asynchronously to avoid blocking
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    BlockPos entityPos = blockEntity.getPos();

                    // Use parallel stream for faster processing of large inventories
                    java.util.stream.IntStream.range(0, inv.size())
                            .parallel()
                            .forEach(i -> {
                                ItemStack stack = inv.getStack(i);
                                if (!stack.isEmpty()) {
                                    LogStorage.logContainerAction("withdrew", player, entityPos, stack);
                                }
                            });
                });
            }
            return true;
        });
    }

    // Optimized container detection with caching
    private static final java.util.Set<Block> TRACKED_CONTAINERS = java.util.Set.of(
            Blocks.CHEST,
            Blocks.TRAPPED_CHEST,
            Blocks.BARREL,
            Blocks.ENDER_CHEST,
            Blocks.SHULKER_BOX,
            Blocks.WHITE_SHULKER_BOX,
            Blocks.ORANGE_SHULKER_BOX,
            Blocks.MAGENTA_SHULKER_BOX,
            Blocks.LIGHT_BLUE_SHULKER_BOX,
            Blocks.YELLOW_SHULKER_BOX,
            Blocks.LIME_SHULKER_BOX,
            Blocks.PINK_SHULKER_BOX,
            Blocks.GRAY_SHULKER_BOX,
            Blocks.LIGHT_GRAY_SHULKER_BOX,
            Blocks.CYAN_SHULKER_BOX,
            Blocks.PURPLE_SHULKER_BOX,
            Blocks.BLUE_SHULKER_BOX,
            Blocks.BROWN_SHULKER_BOX,
            Blocks.GREEN_SHULKER_BOX,
            Blocks.RED_SHULKER_BOX,
            Blocks.BLACK_SHULKER_BOX);

    private static boolean isTrackedContainer(Block block) {
        // O(1) lookup instead of string comparisons
        if (TRACKED_CONTAINERS.contains(block)) {
            return true;
        }

        // Fallback for modded containers (cached via translation key)
        String translationKey = block.getTranslationKey();
        return translationKey.contains("shulker_box") ||
                translationKey.contains("backpack") ||
                translationKey.contains("chest");
    }
}
