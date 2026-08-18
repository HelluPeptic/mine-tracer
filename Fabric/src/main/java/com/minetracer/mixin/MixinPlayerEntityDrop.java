package com.minetracer.mixin;
import com.minetracer.features.minetracer.ItemPickupDropEventListener;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// DISABLED FOR 1.21.11 - All PlayerEntity drop methods have changed or been removed
// Attempted methods that don't exist:
// - dropItem(ItemStack, boolean, boolean) -> doesn't exist
// - dropStack(ItemStack) -> doesn't exist  
// - dropStack(ItemStack, boolean) -> doesn't exist
//
// In 1.21.11, PlayerEntity no longer has public drop methods.
// Possible workaround: Track ItemEntity spawning from MixinItemEntity instead,
// but this would catch ALL item entities, not just player drops.
@Mixin(Player.class)
public class MixinPlayerEntityDrop {
    // DISABLED - See minetracer.mixins.json
    // All tested signatures fail to match any method in PlayerEntity for 1.21.11
}
