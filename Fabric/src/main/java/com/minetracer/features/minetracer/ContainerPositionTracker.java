package com.minetracer.features.minetracer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.inventory.DoubleInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * Handles container position tracking and canonical location resolution.
 * Based on CoreProtect's reliable container detection methods.
 */
public class ContainerPositionTracker {
    private static final Map<UUID, BlockPos> lastOpenedContainerPos = new ConcurrentHashMap<>();
    
    /**
     * Gets the canonical container location following CoreProtect's approach.
     * For double chests, this returns the "center" position.
     * For single containers, returns the actual position.
     * 
     * @param world The world
     * @param clickedPos The position that was clicked
     * @param inventory The inventory being accessed
     * @return The canonical position for logging
     */
    public static BlockPos getCanonicalContainerLocation(World world, BlockPos clickedPos, Inventory inventory) {
        if (world == null || clickedPos == null || inventory == null) {
            return clickedPos;
        }
        
        // Check if this is a double chest inventory
        if (inventory instanceof DoubleInventory) {
            DoubleInventory doubleInv = (DoubleInventory) inventory;
            
            // For double chests, we need to find both halves and determine canonical position
            // Since we can't access private fields, we'll check the clicked position and adjacent blocks
            BlockState clickedState = world.getBlockState(clickedPos);
            
            if (clickedState.getBlock() instanceof ChestBlock) {
                ChestBlock chestBlock = (ChestBlock) clickedState.getBlock();
                ChestType chestType = clickedState.get(ChestBlock.CHEST_TYPE);
                
                if (chestType != ChestType.SINGLE) {
                    // This is part of a double chest, find the canonical position
                    Direction facing = clickedState.get(ChestBlock.FACING);
                    BlockPos otherHalf = getOtherChestHalf(clickedPos, facing, chestType);
                    
                    if (otherHalf != null) {
                        // Return canonical position (leftmost, then northmost)
                        return getCanonicalDoubleChestPosition(clickedPos, otherHalf);
                    }
                }
            }
        }
        
        // For single containers, verify it's actually a container block
        BlockState state = world.getBlockState(clickedPos);
        BlockEntity blockEntity = world.getBlockEntity(clickedPos);
        
        if (blockEntity instanceof Inventory) {
            return clickedPos;
        }
        
        return clickedPos;
    }
    
    /**
     * Determines the canonical position for a double chest.
     * Based on CoreProtect's logic for consistent double chest positioning.
     * 
     * @param pos1 First chest position
     * @param pos2 Second chest position  
     * @return The canonical position
     */
    private static BlockPos getCanonicalDoubleChestPosition(BlockPos pos1, BlockPos pos2) {
        // CoreProtect uses a consistent ordering for double chests
        // We'll use the position with the smaller coordinates as canonical
        
        int compare = comparePositions(pos1, pos2);
        return (compare <= 0) ? pos1 : pos2;
    }
    
    /**
     * Compares two positions for canonical ordering.
     */
    private static int comparePositions(BlockPos pos1, BlockPos pos2) {
        if (pos1.getX() != pos2.getX()) {
            return Integer.compare(pos1.getX(), pos2.getX());
        }
        if (pos1.getZ() != pos2.getZ()) {
            return Integer.compare(pos1.getZ(), pos2.getZ());
        }
        return Integer.compare(pos1.getY(), pos2.getY());
    }
    
    /**
     * Gets container position from block entity, following CoreProtect's validation.
     */
    public static BlockPos getContainerPosition(World world, BlockPos pos) {
        if (world == null || pos == null) {
            return null;
        }
        
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity instanceof Inventory) {
            // For chest blocks, check if it's part of a double chest
            if (blockEntity instanceof ChestBlockEntity) {
                ChestBlockEntity chestEntity = (ChestBlockEntity) blockEntity;
                BlockState state = world.getBlockState(pos);
                
                if (state.getBlock() instanceof ChestBlock) {
                    ChestType chestType = state.get(ChestBlock.CHEST_TYPE);
                    
                    if (chestType != ChestType.SINGLE) {
                        // This is part of a double chest, get the double inventory
                        Inventory inventory = ChestBlock.getInventory((ChestBlock) state.getBlock(), state, world, pos, true);
                        return getCanonicalContainerLocation(world, pos, inventory);
                    }
                }
            }
            
            return pos;
        }
        
        return null;
    }
    
    /**
     * Check if a block is a trackable container using CoreProtect's validation approach.
     */
    public static boolean isTrackableContainer(World world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (!(blockEntity instanceof Inventory)) {
            return false;
        }
        
        BlockState state = world.getBlockState(pos);
        String blockId = net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).toString();
        
        // CoreProtect's container validation logic adapted for Fabric
        
        // Vanilla containers
        if (blockId.equals("minecraft:chest") || blockId.equals("minecraft:trapped_chest") || 
            blockId.equals("minecraft:barrel") || blockId.equals("minecraft:ender_chest") ||
            blockId.contains("shulker_box")) {
            return true;
        }
        
        // More Chest Variants compatibility
        if ((blockId.startsWith("morechestVariants:") || 
             blockId.startsWith("more-chest-variants:") ||
             blockId.startsWith("mcv:")) && 
            (blockId.endsWith("_chest") || blockId.endsWith("_trapped_chest"))) {
            return true;
        }
        
        // ChestBlock check (covers modded chests that extend ChestBlock)
        if (state.getBlock() instanceof ChestBlock) {
            return true;
        }
        
        // Generic container detection for other mods
        if (blockId.contains("chest") || blockId.contains("barrel") || 
            blockId.contains("storage") || blockId.contains("container")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Finds the position of the other half of a double chest.
     * Based on facing direction and chest type (left/right).
     * 
     * @param chestPos Current chest position
     * @param facing The facing direction of the chest
     * @param chestType Whether this is the left or right part
     * @return Position of the other half, or null if not found
     */
    private static BlockPos getOtherChestHalf(BlockPos chestPos, Direction facing, ChestType chestType) {
        Direction otherHalfDirection;
        
        // Determine where the other half should be based on facing and type
        if (chestType == ChestType.LEFT) {
            // Left chest, other half is to the right relative to facing
            otherHalfDirection = facing.rotateYClockwise();
        } else if (chestType == ChestType.RIGHT) {
            // Right chest, other half is to the left relative to facing
            otherHalfDirection = facing.rotateYCounterclockwise();
        } else {
            // Single chest, no other half
            return null;
        }
        
        return chestPos.offset(otherHalfDirection);
    }
    
    // Legacy method for backward compatibility
    public static void setLastOpenedContainer(UUID playerId, BlockPos pos) {
        lastOpenedContainerPos.put(playerId, pos);
    }
    
    public static BlockPos getLastOpenedContainer(UUID playerId) {
        return lastOpenedContainerPos.get(playerId);
    }
    
    public static void clearPlayerData(UUID playerId) {
        lastOpenedContainerPos.remove(playerId);
    }
}
