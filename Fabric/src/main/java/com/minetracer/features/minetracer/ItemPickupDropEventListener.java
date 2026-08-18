package com.minetracer.features.minetracer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
public class ItemPickupDropEventListener {
    private static final String PICKUP_ACTION = "pickup";
    private static final String DROP_ACTION = "drop";
    public static void register() {
    }
    public static void logItemPickup(ServerPlayer player, ItemEntity itemEntity, ItemStack originalStack) {
        if (player == null || itemEntity == null || originalStack == null || originalStack.isEmpty()) {
            return;
        }
        BlockPos pos = itemEntity.blockPosition();
        String world = ((com.minetracer.mixin.EntityAccessor)player).getWorld().dimension().identifier().toString();
        NewOptimizedLogStorage.logItemPickupDropAction(PICKUP_ACTION, player, pos, originalStack.copy(), world);
    }
    public static void logItemDrop(ServerPlayer player, ItemEntity itemEntity) {
        if (player == null || itemEntity == null || itemEntity.getItem().isEmpty()) {
            return;
        }
        ItemStack stack = itemEntity.getItem().copy();
        BlockPos pos = itemEntity.blockPosition();
        String world = ((com.minetracer.mixin.EntityAccessor)player).getWorld().dimension().identifier().toString();
        NewOptimizedLogStorage.logItemPickupDropAction(DROP_ACTION, player, pos, stack, world);
    }
}
