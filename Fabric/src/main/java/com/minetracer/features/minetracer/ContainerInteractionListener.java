package com.minetracer.features.minetracer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
public class ContainerInteractionListener {
    private static class ContainerSnapshot {
        final List<ItemStack> items;
        final World world;
        
        ContainerSnapshot(List<ItemStack> items, World world) {
            this.items = items;
            this.world = world;
        }
    }
    
    private static final Map<UUID, Map<BlockPos, ContainerSnapshot>> containerSnapshots = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> lastCheckTime = new ConcurrentHashMap<>();
    private static final long CHECK_INTERVAL = 1000;
    /**
     * Check if a block is a trackable container.
     * This method dynamically detects containers including modded ones like More Chest Variants.
     * 
     * @param block The block to check
     * @param world The world instance  
     * @param pos The block position
     * @return true if this block should be tracked as a container
     */
    private static boolean isTrackableContainer(Block block, World world, BlockPos pos) {
        // Fast path: Check vanilla containers first
        if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || 
            block == Blocks.BARREL || block == Blocks.ENDER_CHEST) {
            return true;
        }
        
        // Check shulker boxes
        if (block == Blocks.WHITE_SHULKER_BOX || block == Blocks.ORANGE_SHULKER_BOX ||
            block == Blocks.MAGENTA_SHULKER_BOX || block == Blocks.LIGHT_BLUE_SHULKER_BOX ||
            block == Blocks.YELLOW_SHULKER_BOX || block == Blocks.LIME_SHULKER_BOX ||
            block == Blocks.PINK_SHULKER_BOX || block == Blocks.GRAY_SHULKER_BOX ||
            block == Blocks.LIGHT_GRAY_SHULKER_BOX || block == Blocks.CYAN_SHULKER_BOX ||
            block == Blocks.PURPLE_SHULKER_BOX || block == Blocks.BLUE_SHULKER_BOX ||
            block == Blocks.BROWN_SHULKER_BOX || block == Blocks.GREEN_SHULKER_BOX ||
            block == Blocks.RED_SHULKER_BOX || block == Blocks.BLACK_SHULKER_BOX) {
            return true;
        }
        
        // Dynamic detection for modded containers
        try {
            // Check if block entity implements inventory (for chests, barrels, etc.)
            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity instanceof Inventory) {
                // Additional checks for specific mod compatibility
                String blockId = net.minecraft.registry.Registries.BLOCK.getId(block).toString();
                
                // More Chest Variants compatibility - check multiple possible mod IDs
                if ((blockId.startsWith("morechestVariants:") || 
                     blockId.startsWith("more-chest-variants:") ||
                     blockId.startsWith("mcv:")) && 
                    (blockId.endsWith("_chest") || blockId.endsWith("_trapped_chest"))) {
                    return true;
                }
                
                // Generic chest detection (for any mod)
                if (blockId.contains("chest") || blockId.contains("barrel") || 
                    blockId.contains("storage") || blockId.contains("container")) {
                    return true;
                }
                
                // Check if it's a ChestBlock (common base class for chest-like blocks)
                if (block instanceof net.minecraft.block.ChestBlock) {
                    return true;
                }
            }
        } catch (Exception e) {
            // Silently ignore errors in dynamic detection
        }
        
        return false;
    }
    public static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (hand != Hand.MAIN_HAND || !(player instanceof ServerPlayerEntity)) {
                return ActionResult.PASS;
            }
            BlockPos pos = hitResult.getBlockPos();
            Block block = world.getBlockState(pos).getBlock();
            
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
    private static void takeContainerSnapshot(ServerPlayerEntity player, World world, BlockPos pos) {
        try {
            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (!(blockEntity instanceof Inventory)) {
                return;
            }
            Inventory inventory = (Inventory) blockEntity;
            UUID playerId = player.getUuid();
            List<ItemStack> snapshot = new ArrayList<>();
            for (int i = 0; i < inventory.size(); i++) {
                snapshot.add(inventory.getStack(i).copy());
            }
            containerSnapshots.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                             .put(pos, new ContainerSnapshot(snapshot, world));
        } catch (Exception e) {
            System.err.println("[MineTracer] Error in takeContainerSnapshot: " + e.getMessage());
            e.printStackTrace();
        }
    }
    private static void scheduleContainerCheck(ServerPlayerEntity player, BlockPos pos) {
        UUID playerId = player.getUuid();
        lastCheckTime.put(playerId, System.currentTimeMillis());
        
        // Schedule the check to run on the server thread after a delay
        new Thread(() -> {
            try {
                Thread.sleep(2000); // Wait 2 seconds for interaction
                // Execute the check on the server thread
                ((com.minetracer.mixin.ServerPlayerEntityAccessor)player).getServer().execute(() -> {
                    checkContainerChanges(player, pos);
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }
    private static void checkContainerChanges(ServerPlayerEntity player, BlockPos pos) {
        try {
            UUID playerId = player.getUuid();
            Map<BlockPos, ContainerSnapshot> playerSnapshots = containerSnapshots.get(playerId);
            if (playerSnapshots == null || !playerSnapshots.containsKey(pos)) {
                return;
            }
            ContainerSnapshot snapshot = playerSnapshots.get(pos);
            List<ItemStack> oldSnapshot = snapshot.items;
            World world = snapshot.world;
            
            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (!(blockEntity instanceof Inventory)) {
                return;
            }
            Inventory inventory = (Inventory) blockEntity;
            
            for (int i = 0; i < Math.min(oldSnapshot.size(), inventory.size()); i++) {
                ItemStack oldStack = oldSnapshot.get(i);
                ItemStack newStack = inventory.getStack(i);
                
                if (!ItemStack.areEqual(oldStack, newStack)) {
                    if (oldStack.isEmpty() && !newStack.isEmpty()) {
                        NewOptimizedLogStorage.logContainerAction("deposited", player, pos, newStack);
                    } else if (!oldStack.isEmpty() && newStack.isEmpty()) {
                        NewOptimizedLogStorage.logContainerAction("withdrew", player, pos, oldStack);
                    } else if (!oldStack.isEmpty() && !newStack.isEmpty()) {
                        if (newStack.getCount() > oldStack.getCount()) {
                            ItemStack diff = newStack.copy();
                            diff.setCount(newStack.getCount() - oldStack.getCount());
                            NewOptimizedLogStorage.logContainerAction("deposited", player, pos, diff);
                        } else if (newStack.getCount() < oldStack.getCount()) {
                            ItemStack diff = oldStack.copy();
                            diff.setCount(oldStack.getCount() - newStack.getCount());
                            NewOptimizedLogStorage.logContainerAction("withdrew", player, pos, diff);
                        }
                    }
                }
            }
            
            playerSnapshots.remove(pos);
            if (playerSnapshots.isEmpty()) {
                containerSnapshots.remove(playerId);
            }
        } catch (Exception e) {
            System.err.println("[MineTracer] Error in checkContainerChanges: " + e.getMessage());
            e.printStackTrace();
        }
    }
    public static void cleanupPlayerSnapshots(UUID playerId) {
        containerSnapshots.remove(playerId);
        lastCheckTime.remove(playerId);
    }
}
