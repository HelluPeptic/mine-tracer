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
public class OptimizedChestEventListener {
    public static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                BlockPos pos = hitResult.getBlockPos();
                Block block = world.getBlockState(pos).getBlock();
                if (isTrackedContainer(block)) {
                }
            });
            return ActionResult.PASS;
        });
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            Block block = state.getBlock();
            if (isTrackedContainer(block) && blockEntity instanceof Inventory inv) {
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    BlockPos entityPos = blockEntity.getPos();
                    java.util.stream.IntStream.range(0, inv.size())
                            .parallel()
                            .forEach(i -> {
                                ItemStack stack = inv.getStack(i);
                                if (!stack.isEmpty()) {
                                    NewOptimizedLogStorage.logContainerAction("withdrew", player, entityPos, stack);
                                }
                            });
                });
            }
            return true;
        });
    }
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
        if (TRACKED_CONTAINERS.contains(block)) {
            return true;
        }
        String translationKey = block.getTranslationKey();
        return translationKey.contains("shulker_box") ||
                translationKey.contains("backpack") ||
                translationKey.contains("chest");
    }
}
