package com.minetracer.features.minetracer.listeners;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.level.block.Block;

/**
 * Handles explosion events from various sources like TNT, Creepers, etc.
 * Based on CoreProtect's EntityExplodeListener and BlockExplodeListener
 */
public class ExplosionEventListener {
    
    public static void register() {
        // Register explosion tracking via mixin or other mechanisms
        // This will be called when explosions occur
    }
    
    /**
     * Process explosion blocks - called by mixin when explosions happen
     * @param entity The entity causing the explosion (can be null for block explosions)
     * @param world The world where explosion occurred
     * @param destroyedBlocks List of blocks destroyed by the explosion
     */
    public static void processExplosion(Entity entity, ServerLevel world, java.util.List<BlockPos> destroyedBlocks) {
        String user = getExplosionUser(entity);
        
        // Log each destroyed block using string-based logging
        for (BlockPos pos : destroyedBlocks) {
            Block block = world.getBlockState(pos).getBlock();
            String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString();
            String nbt = createBlockStateNbt(world.getBlockState(pos));
            
            // Log the block break with string user
            logExplosionBlockBreak(user, pos, blockId, nbt, world);
        }
    }
    
    /**
     * Process individual explosion block - alternative method for block-by-block processing
     * @param entity The entity causing the explosion (can be null for block explosions)
     * @param world The world where explosion occurred
     * @param pos Position of the block being destroyed
     * @param state BlockState of the block being destroyed
     */
    public static void processExplosionBlock(Entity entity, ServerLevel world, BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        String user = getExplosionUser(entity);
        String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        String nbt = buildNbtString(world, pos, state);

        logExplosionBlockBreak(user, pos, blockId, nbt, world);
    }

    /**
     * Build the NBT string for a block, including block entity data for containers.
     * When the block has a block entity (chest, barrel, etc.) this produces the same
     * SNBT format as player breaks: {Properties:{...}, BlockEntityTag:{...}}
     * so that rollback can restore inventory contents.
     * Otherwise returns the lightweight bracket format: [key=val,...]
     *
     * Called on the main server thread, so world.getBlockEntity() is safe here.
     */
    private static String buildNbtString(ServerLevel world, BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        try {
            net.minecraft.world.level.block.entity.BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity != null) {
                net.minecraft.nbt.CompoundTag fullNbt = new net.minecraft.nbt.CompoundTag();
                if (!state.getProperties().isEmpty()) {
                    net.minecraft.nbt.CompoundTag propertiesNbt = new net.minecraft.nbt.CompoundTag();
                    for (net.minecraft.world.level.block.state.properties.Property<?> prop : state.getProperties()) {
                        propertiesNbt.putString(prop.getName(), state.getValue(prop).toString());
                    }
                    fullNbt.put("Properties", propertiesNbt);
                }
                fullNbt.put("BlockEntityTag", blockEntity.saveWithoutMetadata(world.registryAccess()));
                return fullNbt.toString();
            }
        } catch (Exception e) {
            // Fall through to simple format
        }
        return createBlockStateNbt(state);
    }
    
    /**
     * Process explosion event - logs the explosion center point for tracking
     * @param entity The entity causing the explosion (can be null for block explosions)
     * @param world The world where explosion occurred
     * @param center Center position of the explosion
     */
    public static void processExplosionEvent(Entity entity, ServerLevel world, BlockPos center) {
        String user = getExplosionUser(entity);
        String worldName = getWorldName(world);
        
        // Log explosion event to indicate where an explosion occurred
        // This provides context even if individual block tracking fails
    }
    
    /**
     * Log explosion block breaks directly to database
     */
    private static void logExplosionBlockBreak(String user, BlockPos pos, String blockId, String nbt, ServerLevel world) {
        String worldName = getWorldName(world);
        Object[] data = new Object[]{"broke", user, pos, blockId, nbt, worldName};
        
        com.minetracer.features.minetracer.database.MineTracerConsumer.queueEntry(
            com.minetracer.features.minetracer.database.MineTracerConsumer.PROCESS_BLOCK, data, null);
    }
    
    /**
     * Get world name for logging
     */
    private static String getWorldName(ServerLevel world) {
        if (world == null) return "world";
        return world.dimension().identifier().toString();
    }
    
    /**
     * Determine the user string for explosion logging based on entity type
     * Mirrors CoreProtect's logic
     */
    private static String getExplosionUser(Entity entity) {
        if (entity == null) {
            return "#explosion";
        }
        
        if (entity instanceof PrimedTnt) {
            return "#tnt";
        }
        else if (entity instanceof MinecartTNT) {
            return "#tnt";
        }
        else if (entity instanceof Creeper) {
            return "#creeper";
        }
        else if (entity instanceof EnderDragon) {
            return "#enderdragon";
        }
        else if (entity instanceof WitherSkull) {
            return "#wither";
        }
        else if (entity instanceof EndCrystal) {
            return "#end_crystal";
        }
        
        // Check for wither by class name since direct import might not work
        String entityType = entity.getClass().getSimpleName();
        if (entityType.contains("Wither") && !entityType.contains("Skull")) {
            return "#wither";
        }
        
        return "#explosion";
    }
    
    /**
     * Create a CoreProtect-style block state properties string.
     * Format: "[key=value,key=value]" or "" for blocks with no properties.
     * Equivalent to Bukkit's blockData.getAsString() properties section.
     */
    private static String createBlockStateNbt(net.minecraft.world.level.block.state.BlockState state) {
        try {
            if (state.getProperties().isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (net.minecraft.world.level.block.state.properties.Property<?> property : state.getProperties()) {
                if (!first) sb.append(',');
                sb.append(property.getName()).append('=').append(state.getValue(property).toString());
                first = false;
            }
            sb.append(']');
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}