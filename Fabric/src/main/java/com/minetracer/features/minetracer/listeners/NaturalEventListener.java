package com.minetracer.features.minetracer.listeners;

import com.minetracer.features.minetracer.database.MineTracerConsumer;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Handles natural block changes like water/lava flow, fire spread, etc.
 * Based on CoreProtect's natural block change listeners
 */
public class NaturalEventListener {
    
    /**
     * Process water or lava flow block changes
     * @param world The world where change occurred
     * @param pos Position of the block being changed
     * @param oldBlock The block that was there before
     * @param newBlock The block that is placed
     * @param isFromWater Whether the change is from water flow
     */
    public static void processFluidFlow(ServerWorld world, BlockPos pos, Block oldBlock, Block newBlock, boolean isFromWater) {
        String user = isFromWater ? "#water" : "#lava";
        String worldName = getWorldName(world);
        
        if (newBlock == Blocks.AIR || newBlock == Blocks.CAVE_AIR) {
            // Block was broken by fluid
            String blockId = net.minecraft.registry.Registries.BLOCK.getId(oldBlock).toString();
            String nbt = world.getBlockState(pos).toString();
            
            Object[] data = new Object[]{"broke", user, pos, blockId, nbt, worldName};
            MineTracerConsumer.queueEntry(MineTracerConsumer.PROCESS_BLOCK, data, null);
        } else if (oldBlock == Blocks.AIR || oldBlock == Blocks.CAVE_AIR) {
            // Fluid placed a block (like cobblestone generation)
            String newBlockId = net.minecraft.registry.Registries.BLOCK.getId(newBlock).toString();
            String nbt = world.getBlockState(pos).toString();
            
            Object[] data = new Object[]{"placed", user, pos, newBlockId, nbt, worldName};
            MineTracerConsumer.queueEntry(MineTracerConsumer.PROCESS_BLOCK, data, null);
        }
    }
    
    /**
     * Process fire spread events
     * @param world The world where change occurred
     * @param pos Position of the block being changed
     * @param oldBlock The block that was there before
     * @param newBlock The block that is placed (usually fire or air)
     */
    public static void processFireSpread(ServerWorld world, BlockPos pos, Block oldBlock, Block newBlock) {
        String user = "#fire";
        String worldName = getWorldName(world);
        
        if (newBlock == Blocks.FIRE || newBlock == Blocks.SOUL_FIRE) {
            // Fire spread to a new location
            String newBlockId = net.minecraft.registry.Registries.BLOCK.getId(newBlock).toString();
            String nbt = world.getBlockState(pos).toString();
            
            Object[] data = new Object[]{"placed", user, pos, newBlockId, nbt, worldName};
            MineTracerConsumer.queueEntry(MineTracerConsumer.PROCESS_BLOCK, data, null);
        } else if (oldBlock == Blocks.FIRE || oldBlock == Blocks.SOUL_FIRE) {
            // Fire went out
            String blockId = net.minecraft.registry.Registries.BLOCK.getId(oldBlock).toString();
            String nbt = world.getBlockState(pos).toString();
            
            Object[] data = new Object[]{"broke", user, pos, blockId, nbt, worldName};
            MineTracerConsumer.queueEntry(MineTracerConsumer.PROCESS_BLOCK, data, null);
        } else if (newBlock == Blocks.AIR || newBlock == Blocks.CAVE_AIR) {
            // Fire burned down a block
            String blockId = net.minecraft.registry.Registries.BLOCK.getId(oldBlock).toString();
            String nbt = world.getBlockState(pos).toString();
            
            Object[] data = new Object[]{"broke", user, pos, blockId, nbt, worldName};
            MineTracerConsumer.queueEntry(MineTracerConsumer.PROCESS_BLOCK, data, null);
        }
    }
    
    /**
     * Process ice/snow melt events
     * @param world The world where change occurred
     * @param pos Position of the block being changed
     * @param oldBlock The block that was there before (ice/snow)
     */
    public static void processIceMelt(ServerWorld world, BlockPos pos, Block oldBlock) {
        String user = "#melt";
        String worldName = getWorldName(world);
        
        String blockId = net.minecraft.registry.Registries.BLOCK.getId(oldBlock).toString();
        String nbt = world.getBlockState(pos).toString();
        
        Object[] data = new Object[]{"broke", user, pos, blockId, nbt, worldName};
        MineTracerConsumer.queueEntry(MineTracerConsumer.PROCESS_BLOCK, data, null);
    }
    
    /**
     * Process grass/mycelium spread
     * @param world The world where change occurred
     * @param pos Position of the block being changed
     * @param oldBlock The block that was there before
     * @param newBlock The block that is placed
     */
    public static void processGrassSpread(ServerWorld world, BlockPos pos, Block oldBlock, Block newBlock) {
        String user = "#grow";
        String worldName = getWorldName(world);
        
        String oldBlockId = net.minecraft.registry.Registries.BLOCK.getId(oldBlock).toString();
        String newBlockId = net.minecraft.registry.Registries.BLOCK.getId(newBlock).toString();
        String nbt = world.getBlockState(pos).toString();
        
        Object[] data = new Object[]{"placed", user, pos, newBlockId, nbt, worldName};
        MineTracerConsumer.queueEntry(MineTracerConsumer.PROCESS_BLOCK, data, null);
    }
    
    /**
     * Get world name for logging
     */
    private static String getWorldName(ServerWorld world) {
        if (world == null) return "world";
        return world.getRegistryKey().getValue().toString();
    }
}