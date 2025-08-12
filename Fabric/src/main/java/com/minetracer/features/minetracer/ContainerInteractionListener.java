package com.minetracer.features.minetracer;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.ArrayList;
public class ContainerInteractionListener {
    private static final Map<UUID, Map<BlockPos, List<ItemStack>>> containerSnapshots = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> lastCheckTime = new ConcurrentHashMap<>();
    private static final long CHECK_INTERVAL = 1000;
    private static final java.util.Set<Block> TRACKED_CONTAINERS = java.util.Set.of(
        Blocks.CHEST,
        Blocks.TRAPPED_CHEST,
        Blocks.BARREL,
        Blocks.ENDER_CHEST,
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
        Blocks.BLACK_SHULKER_BOX
    );
    public static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (hand != Hand.MAIN_HAND || !(player instanceof ServerPlayerEntity)) {
                return ActionResult.PASS;
            }
            BlockPos pos = hitResult.getBlockPos();
            Block block = world.getBlockState(pos).getBlock();
            if (TRACKED_CONTAINERS.contains(block)) {
                takeContainerSnapshot((ServerPlayerEntity) player, world, pos);
                scheduleContainerCheck((ServerPlayerEntity) player, pos);
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
                             .put(pos, snapshot);
        } catch (Exception e) {
        }
    }
    private static void scheduleContainerCheck(ServerPlayerEntity player, BlockPos pos) {
        UUID playerId = player.getUuid();
        lastCheckTime.put(playerId, System.currentTimeMillis());
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(2000); // Wait 2 seconds for interaction
                checkContainerChanges(player, pos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
    private static void checkContainerChanges(ServerPlayerEntity player, BlockPos pos) {
        try {
            UUID playerId = player.getUuid();
            Map<BlockPos, List<ItemStack>> playerSnapshots = containerSnapshots.get(playerId);
            if (playerSnapshots == null || !playerSnapshots.containsKey(pos)) {
                return;
            }
            List<ItemStack> oldSnapshot = playerSnapshots.get(pos);
            World world = player.getServerWorld();
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
                        OptimizedLogStorage.logContainerAction("deposited", player, pos, newStack);
                    } else if (!oldStack.isEmpty() && newStack.isEmpty()) {
                        OptimizedLogStorage.logContainerAction("withdrew", player, pos, oldStack);
                    } else if (!oldStack.isEmpty() && !newStack.isEmpty()) {
                        if (newStack.getCount() > oldStack.getCount()) {
                            ItemStack diff = newStack.copy();
                            diff.setCount(newStack.getCount() - oldStack.getCount());
                            OptimizedLogStorage.logContainerAction("deposited", player, pos, diff);
                        } else if (newStack.getCount() < oldStack.getCount()) {
                            ItemStack diff = oldStack.copy();
                            diff.setCount(oldStack.getCount() - newStack.getCount());
                            OptimizedLogStorage.logContainerAction("withdrew", player, pos, diff);
                        }
                    }
                }
            }
            playerSnapshots.remove(pos);
            if (playerSnapshots.isEmpty()) {
                containerSnapshots.remove(playerId);
            }
        } catch (Exception e) {
        }
    }
    public static void cleanupPlayerSnapshots(UUID playerId) {
        containerSnapshots.remove(playerId);
        lastCheckTime.remove(playerId);
    }
}
