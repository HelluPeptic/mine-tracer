package com.minetracer.mixin;
import com.minetracer.features.minetracer.ItemPickupDropEventListener;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(PlayerEntity.class)
public class MixinPlayerEntityDrop {
    @Inject(method = "dropItem(Lnet/minecraft/item/ItemStack;ZZ)Lnet/minecraft/entity/ItemEntity;", 
            at = @At("RETURN"))
    private void onItemDrop(ItemStack stack, boolean throwRandomly, boolean retainOwnership, 
                           CallbackInfoReturnable<ItemEntity> cir) {
        if (((PlayerEntity)(Object)this) instanceof ServerPlayerEntity serverPlayer) {
            ItemEntity droppedItem = cir.getReturnValue();
            if (droppedItem != null && !droppedItem.getStack().isEmpty()) {
                ItemPickupDropEventListener.logItemDrop(serverPlayer, droppedItem);
            }
        }
    }
}
