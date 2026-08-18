package com.minetracer.mixin;
import com.minetracer.features.minetracer.ItemPickupDropEventListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
@Mixin(ItemEntity.class)
public abstract class MixinItemEntity {
    @Shadow public abstract net.minecraft.world.entity.Entity getOwner();
    
    private ItemStack minetracer$originalStack = null;
    private boolean minetracer$dropLogged = false;
    
    @Inject(method = "playerTouch", at = @At("HEAD"))
    private void onPlayerCollisionHead(Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer) {
            ItemEntity itemEntity = (ItemEntity)(Object)this;
            if (!itemEntity.getItem().isEmpty()) {
                minetracer$originalStack = itemEntity.getItem().copy();
            }
        }
    }
    
    @Inject(method = "playerTouch", at = @At(value = "INVOKE", 
            target = "Lnet/minecraft/world/entity/item/ItemEntity;discard()V"))
    private void onItemPickup(Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer && minetracer$originalStack != null) {
            ServerPlayer serverPlayer = (ServerPlayer) player;
            ItemEntity itemEntity = (ItemEntity)(Object)this;
            ItemPickupDropEventListener.logItemPickup(serverPlayer, itemEntity, minetracer$originalStack);
            minetracer$originalStack = null;
        }
    }
    
    @Inject(method = "tick", at = @At("HEAD"))
    private void onItemTick(CallbackInfo ci) {
        ItemEntity itemEntity = (ItemEntity) (Object) this;
        
        if (itemEntity.tickCount > 5 || minetracer$dropLogged) {
            return;
        }
        
        if (!(((EntityAccessor)itemEntity).getWorld() instanceof ServerLevel)) {
            return;
        }
        
        try {
            net.minecraft.world.entity.Entity owner = this.getOwner();
            
            if (owner instanceof ServerPlayer && !minetracer$dropLogged) {
                ServerPlayer player = (ServerPlayer) owner;
                ItemPickupDropEventListener.logItemDrop(player, itemEntity);
                minetracer$dropLogged = true;
            }
        } catch (Exception e) {
            System.err.println("[MineTracer] Error getting item owner: " + e.getMessage());
        }
    }
}
