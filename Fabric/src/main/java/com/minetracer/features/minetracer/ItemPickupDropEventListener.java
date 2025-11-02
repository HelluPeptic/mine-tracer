package com.minetracer.features.minetracer;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
public class ItemPickupDropEventListener {
    private static final String PICKUP_ACTION = "pickup";
    private static final String DROP_ACTION = "drop";
    public static void register() {
    }
    public static void logItemPickup(ServerPlayerEntity player, ItemEntity itemEntity, ItemStack originalStack) {
        if (player == null || itemEntity == null || originalStack == null || originalStack.isEmpty()) {
            return;
        }
        BlockPos pos = itemEntity.getBlockPos();
        String world = player.getWorld().getRegistryKey().getValue().toString();
        NewOptimizedLogStorage.logItemPickupDropAction(PICKUP_ACTION, player, pos, originalStack.copy(), world);
    }
    public static void logItemDrop(ServerPlayerEntity player, ItemEntity itemEntity) {
        if (player == null || itemEntity == null || itemEntity.getStack().isEmpty()) {
            return;
        }
        ItemStack stack = itemEntity.getStack();
        BlockPos pos = itemEntity.getBlockPos();
        String world = player.getWorld().getRegistryKey().getValue().toString();
        NewOptimizedLogStorage.logItemPickupDropAction(DROP_ACTION, player, pos, stack, world);
    }
}
