package com.minetracer.mixin;
import com.minetracer.features.minetracer.ItemPickupDropEventListener;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.UUID;
@Mixin(ItemEntity.class)
public abstract class MixinItemEntity {
    @Shadow public abstract net.minecraft.entity.Entity getOwner();
    
    private ItemStack minetracer$originalStack = null;
    private boolean minetracer$dropLogged = false;
    
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
            minetracer$originalStack = null;
        }
    }
    
    @Inject(method = "tick", at = @At("HEAD"))
    private void onItemTick(CallbackInfo ci) {
        ItemEntity itemEntity = (ItemEntity) (Object) this;
        
        if (itemEntity.age > 5 || minetracer$dropLogged) {
            return;
        }
        
        if (!(((EntityAccessor)itemEntity).getWorld() instanceof ServerWorld)) {
            return;
        }
        
        try {
            net.minecraft.entity.Entity owner = this.getOwner();
            
            if (owner instanceof ServerPlayerEntity && !minetracer$dropLogged) {
                ServerPlayerEntity player = (ServerPlayerEntity) owner;
                ItemPickupDropEventListener.logItemDrop(player, itemEntity);
                minetracer$dropLogged = true;
            }
        } catch (Exception e) {
            System.err.println("[MineTracer] Error getting item owner: " + e.getMessage());
        }
    }
}
