package com.minetracer.features.minetracer.listeners;

import com.minetracer.features.minetracer.database.MineTracerConsumer;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Handles natural block changes like water/lava flow, fire spread, etc.
 * Based on CoreProtect's natural block change listeners.
 *
 * All process* methods accept the full BlockState (not just Block) so that
 * property values are captured before the world state changes.  The NBT string
 * is produced in the standard [key=val,...] bracket format used throughout the
 * rest of the logging code.
 */
public class NaturalEventListener {

    /**
     * Process water or lava flow block changes.
     *
     * @param world       The world where the change occurred
     * @param pos         Position of the block being changed
     * @param oldState    The BlockState that existed BEFORE the fluid changed it
     *                    (captured at HEAD of the mixin, before world mutates)
     * @param newState    The BlockState that will be placed by the fluid
     * @param isFromWater Whether the source fluid is water
     */
    public static void processFluidFlow(ServerWorld world, BlockPos pos,
            BlockState oldState, BlockState newState, boolean isFromWater) {
        String user = isFromWater ? "#water" : "#lava";
        String worldName = getWorldName(world);

        if (newState.isAir()) {
            // A pre-existing (non-air) block was destroyed by the fluid
            String blockId = net.minecraft.registry.Registries.BLOCK.getId(oldState.getBlock()).toString();
            String nbt = createBlockStateNbt(oldState);
            MineTracerConsumer.queueEntry(MineTracerConsumer.PROCESS_BLOCK,
                    new Object[]{"broke", user, pos, blockId, nbt, worldName}, null);
        } else if (oldState.isAir()) {
            // Fluid produced a solid block in an air space (e.g. cobblestone generation)
            String newBlockId = net.minecraft.registry.Registries.BLOCK.getId(newState.getBlock()).toString();
            String nbt = createBlockStateNbt(newState);
            MineTracerConsumer.queueEntry(MineTracerConsumer.PROCESS_BLOCK,
                    new Object[]{"placed", user, pos, newBlockId, nbt, worldName}, null);
        } else if (oldState.getBlock() != Blocks.WATER && oldState.getBlock() != Blocks.LAVA
                && oldState.getBlock() != Blocks.BUBBLE_COLUMN) {
            // Fluid replaced a non-fluid, non-air block (e.g. lava burning through a block)
            String blockId = net.minecraft.registry.Registries.BLOCK.getId(oldState.getBlock()).toString();
            String nbt = createBlockStateNbt(oldState);
            MineTracerConsumer.queueEntry(MineTracerConsumer.PROCESS_BLOCK,
                    new Object[]{"broke", user, pos, blockId, nbt, worldName}, null);
        }
    }

    /**
     * Process fire spread and fire-out events.
     *
     * @param world    The world where the change occurred
     * @param pos      Position of the block being changed
     * @param oldState The BlockState that existed BEFORE the fire changed it
     * @param newState The BlockState placed by fire (fire block, or air when extinguished)
     */
    public static void processFireSpread(ServerWorld world, BlockPos pos,
            BlockState oldState, BlockState newState) {
        String user = "#fire";
        String worldName = getWorldName(world);

        net.minecraft.block.Block newBlock = newState.getBlock();
        net.minecraft.block.Block oldBlock = oldState.getBlock();

        if (newBlock == Blocks.FIRE || newBlock == Blocks.SOUL_FIRE) {
            // Fire placed at a position (spread or initial ignition via scheduledTick)
            String newBlockId = net.minecraft.registry.Registries.BLOCK.getId(newBlock).toString();
            String nbt = createBlockStateNbt(newState);
            MineTracerConsumer.queueEntry(MineTracerConsumer.PROCESS_BLOCK,
                    new Object[]{"placed", user, pos, newBlockId, nbt, worldName}, null);
        } else if (oldBlock == Blocks.FIRE || oldBlock == Blocks.SOUL_FIRE) {
            // Fire went out / was extinguished  
            String blockId = net.minecraft.registry.Registries.BLOCK.getId(oldBlock).toString();
            String nbt = createBlockStateNbt(oldState);
            MineTracerConsumer.queueEntry(MineTracerConsumer.PROCESS_BLOCK,
                    new Object[]{"broke", user, pos, blockId, nbt, worldName}, null);
        } else if (newState.isAir()) {
            // Fire burned down a block (block replaced by air)
            String blockId = net.minecraft.registry.Registries.BLOCK.getId(oldBlock).toString();
            String nbt = createBlockStateNbt(oldState);
            MineTracerConsumer.queueEntry(MineTracerConsumer.PROCESS_BLOCK,
                    new Object[]{"broke", user, pos, blockId, nbt, worldName}, null);
        }
    }

    /**
     * Process ice/snow melt events.
     *
     * @param world    The world where the change occurred
     * @param pos      Position of the block being changed
     * @param oldState The BlockState of the ice/snow BEFORE it melted
     */
    public static void processIceMelt(ServerWorld world, BlockPos pos, BlockState oldState) {
        String user = "#melt";
        String worldName = getWorldName(world);

        String blockId = net.minecraft.registry.Registries.BLOCK.getId(oldState.getBlock()).toString();
        String nbt = createBlockStateNbt(oldState);
        MineTracerConsumer.queueEntry(MineTracerConsumer.PROCESS_BLOCK,
                new Object[]{"broke", user, pos, blockId, nbt, worldName}, null);
    }

    /**
     * Process grass/mycelium/dirt-path spread events.
     *
     * @param world    The world where the change occurred
     * @param pos      Position of the block being changed
     * @param oldState The BlockState BEFORE the spread
     * @param newState The BlockState AFTER the spread
     */
    public static void processGrassSpread(ServerWorld world, BlockPos pos,
            BlockState oldState, BlockState newState) {
        String user = "#grow";
        String worldName = getWorldName(world);

        String newBlockId = net.minecraft.registry.Registries.BLOCK.getId(newState.getBlock()).toString();
        String nbt = createBlockStateNbt(newState);
        MineTracerConsumer.queueEntry(MineTracerConsumer.PROCESS_BLOCK,
                new Object[]{"placed", user, pos, newBlockId, nbt, worldName}, null);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Produce the [key=val,...] bracket NBT string for a BlockState. */
    static String createBlockStateNbt(BlockState state) {
        try {
            if (state == null || state.getProperties().isEmpty()) return "";
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (net.minecraft.state.property.Property<?> property : state.getProperties()) {
                if (!first) sb.append(',');
                sb.append(property.getName()).append('=').append(state.get(property).toString());
                first = false;
            }
            sb.append(']');
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String getWorldName(ServerWorld world) {
        if (world == null) return "world";
        return world.getRegistryKey().getValue().toString();
    }
}