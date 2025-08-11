package com.minetracer.features.minetracer;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import java.util.Set;
import java.util.HashSet;

// Listens for chest and container events
public class ChestEventListener {
    // Pre-compiled set for faster container lookups
    private static final Set<Block> TRACKED_CONTAINERS = createTrackedContainers();
    
    private static Set<Block> createTrackedContainers() {
        Set<Block> containers = new HashSet<>();
        containers.add(Blocks.CHEST);
        containers.add(Blocks.TRAPPED_CHEST);
        containers.add(Blocks.BARREL);
        containers.add(Blocks.ENDER_CHEST);
        containers.add(Blocks.WHITE_SHULKER_BOX);
        containers.add(Blocks.ORANGE_SHULKER_BOX);
        containers.add(Blocks.MAGENTA_SHULKER_BOX);
        containers.add(Blocks.LIGHT_BLUE_SHULKER_BOX);
        containers.add(Blocks.YELLOW_SHULKER_BOX);
        containers.add(Blocks.LIME_SHULKER_BOX);
        containers.add(Blocks.PINK_SHULKER_BOX);
        containers.add(Blocks.GRAY_SHULKER_BOX);
        containers.add(Blocks.LIGHT_GRAY_SHULKER_BOX);
        containers.add(Blocks.CYAN_SHULKER_BOX);
        containers.add(Blocks.PURPLE_SHULKER_BOX);
        containers.add(Blocks.BLUE_SHULKER_BOX);
        containers.add(Blocks.BROWN_SHULKER_BOX);
        containers.add(Blocks.GREEN_SHULKER_BOX);
        containers.add(Blocks.RED_SHULKER_BOX);
        containers.add(Blocks.BLACK_SHULKER_BOX);
        return containers;
    }
    
    public static void register() {
        // Listen for block use (opening containers) - simplified for performance
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            // Only process main hand interactions to avoid duplicate events
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
            
            BlockPos pos = hitResult.getBlockPos();
            Block block = world.getBlockState(pos).getBlock();
            if (TRACKED_CONTAINERS.contains(block)) {
                // Container opened - could add logging here if needed
            }
            return ActionResult.PASS;
        });

        // Listen for block break (removal of containers) - optimized inventory processing
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            Block block = state.getBlock();
            // Quick check using pre-compiled set instead of string comparisons
            if (!TRACKED_CONTAINERS.contains(block)) return true;
            
            if (blockEntity instanceof Inventory) {
                Inventory inv = (Inventory) blockEntity;
                // Batch process inventory items to reduce individual logging calls
                int size = inv.size();
                for (int i = 0; i < size; i++) {
                    ItemStack stack = inv.getStack(i);
                    if (!stack.isEmpty()) {
                        OptimizedLogStorage.logContainerAction("withdrew", player, pos, stack);
                    }
                }
            }
            return true;
        });
    }

    private static boolean isTrackedContainer(Block block) {
        return TRACKED_CONTAINERS.contains(block);
    }
}
