package com.minetracer.features.minetracer.listeners;

import com.minetracer.features.minetracer.database.MineTracerConsumer;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.RavagerEntity;
import net.minecraft.entity.mob.SilverfishEntity;
import net.minecraft.entity.passive.FoxEntity;
import net.minecraft.entity.passive.TurtleEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Handles entity block interactions (breaking/placing blocks)
 * Based on CoreProtect's EntityChangeBlockListener
 */
public class EntityBlockChangeListener {
    
    /**
     * Process entity block change - called by mixin when entities modify blocks
     * @param entity The entity causing the change
     * @param world The world where change occurred
     * @param pos Position of the block being changed
     * @param oldBlock The block that was there before
     * @param newBlock The block that is placed (Air if broken)
     */
    public static void processEntityBlockChange(Entity entity, ServerWorld world, BlockPos pos, Block oldBlock, Block newBlock) {
        String user = getEntityUser(entity);
        
        if (user.length() > 0) {
            String worldName = getWorldName(world);
            
            if (newBlock == Blocks.AIR || newBlock == Blocks.CAVE_AIR) {
                // Block was broken
                String blockId = net.minecraft.registry.Registries.BLOCK.getId(oldBlock).toString();
                String nbt = world.getBlockState(pos).toString();
                
                Object[] data = new Object[]{"broke", user, pos, blockId, nbt, worldName};
                MineTracerConsumer.queueEntry(MineTracerConsumer.PROCESS_BLOCK, data, null);
            } else {
                // Block was placed
                String oldBlockId = net.minecraft.registry.Registries.BLOCK.getId(oldBlock).toString();
                String newBlockId = net.minecraft.registry.Registries.BLOCK.getId(newBlock).toString();
                String nbt = world.getBlockState(pos).toString();
                
                Object[] data = new Object[]{"placed", user, pos, newBlockId, nbt, worldName};
                MineTracerConsumer.queueEntry(MineTracerConsumer.PROCESS_BLOCK, data, null);
            }
        }
    }
    
    /**
     * Get world name for logging
     */
    private static String getWorldName(ServerWorld world) {
        if (world == null) return "world";
        return world.getRegistryKey().getValue().toString();
    }
    
    /**
     * Determine the user string for entity logging based on entity type
     * Mirrors CoreProtect's logic
     */
    private static String getEntityUser(Entity entity) {
        if (entity == null) {
            return "";
        }
        
        if (entity instanceof EndermanEntity) {
            return "#enderman";
        }
        else if (entity instanceof EnderDragonEntity) {
            return "#enderdragon";
        }
        else if (entity instanceof FoxEntity) {
            return "#fox";
        }
        else if (entity instanceof TurtleEntity) {
            return "#turtle";
        }
        else if (entity instanceof RavagerEntity) {
            return "#ravager";
        }
        else if (entity instanceof SilverfishEntity) {
            return "#silverfish";
        }
        
        // Check for wither by class name since direct import might not work
        String entityType = entity.getClass().getSimpleName();
        if (entityType.contains("Wither") && !entityType.contains("Skull")) {
            return "#wither";
        }
        
        return "";
    }
}