package com.minetracer.features.minetracer.listeners;

import com.minetracer.features.minetracer.database.MineTracerConsumer;

import net.minecraft.block.BlockState;
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
 * Handles entity block interactions (breaking/placing blocks).
 * Based on CoreProtect's EntityChangeBlockListener.
 *
 * Accepts full BlockState objects (not just Block) so property values are
 * captured before the world changes, and NBT is serialised in the correct
 * [key=val,...] bracket format.
 */
public class EntityBlockChangeListener {

    /**
     * Process entity block change - called by mixin when entities modify blocks.
     *
     * @param entity   The entity causing the change
     * @param world    The world where change occurred
     * @param pos      Position of the block being changed
     * @param oldState The BlockState that existed BEFORE the entity changed it
     * @param newState The BlockState that the entity placed (AIR-equivalent if broken)
     */
    public static void processEntityBlockChange(Entity entity, ServerWorld world, BlockPos pos,
            BlockState oldState, BlockState newState) {
        String user = getEntityUser(entity);

        if (!user.isEmpty()) {
            String worldName = getWorldName(world);

            if (newState.isAir()) {
                // Block was broken by the entity
                String blockId = net.minecraft.registry.Registries.BLOCK.getId(oldState.getBlock()).toString();
                String nbt = NaturalEventListener.createBlockStateNbt(oldState);
                MineTracerConsumer.queueEntry(MineTracerConsumer.PROCESS_BLOCK,
                        new Object[]{"broke", user, pos, blockId, nbt, worldName}, null);
            } else {
                // Block was placed / replaced by the entity — log the new block being placed
                String newBlockId = net.minecraft.registry.Registries.BLOCK.getId(newState.getBlock()).toString();
                String nbt = NaturalEventListener.createBlockStateNbt(newState);
                MineTracerConsumer.queueEntry(MineTracerConsumer.PROCESS_BLOCK,
                        new Object[]{"placed", user, pos, newBlockId, nbt, worldName}, null);
            }
        }
    }

    private static String getWorldName(ServerWorld world) {
        if (world == null) return "world";
        return world.getRegistryKey().getValue().toString();
    }

    private static String getEntityUser(Entity entity) {
        if (entity == null) return "";

        if (entity instanceof EndermanEntity) return "#enderman";
        if (entity instanceof EnderDragonEntity) return "#enderdragon";
        if (entity instanceof FoxEntity) return "#fox";
        if (entity instanceof TurtleEntity) return "#turtle";
        if (entity instanceof RavagerEntity) return "#ravager";
        if (entity instanceof SilverfishEntity) return "#silverfish";

        String entityType = entity.getClass().getSimpleName();
        if (entityType.contains("Wither") && !entityType.contains("Skull")) {
            return "#wither";
        }

        return "";
    }
}