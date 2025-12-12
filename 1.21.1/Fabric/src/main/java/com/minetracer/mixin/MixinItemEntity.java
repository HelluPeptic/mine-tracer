package com.minetracer.mixin;
import com.minetracer.features.minetracer.ItemPickupDropEventListener;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ItemEntity.class)
public class MixinItemEntity {
    private ItemStack minetracer$originalStack = null;
    @Inject(method = "onPlayerCollision", at = @At("HEAD"))
    private void onPlayerCollisionHead(PlayerEntity player, CallbackInfo ci) {
        if (player instanceof ServerPlayerEntity) {
            ItemEntity itemEntity = (ItemEntity)(Object)this;
            if (!itemEntity.getStack().isEmpty()) {
                minetracer$originalStack = itemEntity.getStack().copy();
            }
        }
    }
    @Inject(method = "onPlayerCollision", at = @At(value = "INVOKE", 
            target = "Lnet/minecraft/entity/ItemEntity;discard()V"))
    private void onItemPickup(PlayerEntity player, CallbackInfo ci) {
        if (player instanceof ServerPlayerEntity && minetracer$originalStack != null) {
            ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
            ItemEntity itemEntity = (ItemEntity)(Object)this;
            ItemPickupDropEventListener.logItemPickup(serverPlayer, itemEntity, minetracer$originalStack);
            minetracer$originalStack = null; // Clean up
        }
    }
}
