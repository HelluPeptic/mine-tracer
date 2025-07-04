package com.minetracer.mixin;

import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.minetracer.features.minetracer.LogStorage;

import java.util.Objects;

@Mixin(ScreenHandler.class)
public abstract class MixinScreenHandler {
    // Store the previous stack for slot click detection
    private ItemStack minetracer$prevStack = null;
    // Store the previous state of the entire container
    private ItemStack[] minetracer$prevContainerState = null;

    @Inject(method = "onSlotClick", at = @At("HEAD"))
    private void minetracer$logSlotClickHead(int slotIndex, int button,
            net.minecraft.screen.slot.SlotActionType actionType, PlayerEntity player, CallbackInfo ci) {
        ScreenHandler self = (ScreenHandler) (Object) this;
        // Only track if interacting with a container (not player inventory)
        if (self != null && self.slots.size() > 0 && self.getSlot(0).inventory != player.getInventory()) {
            int size = self.slots.size();
            minetracer$prevContainerState = new ItemStack[size];
            for (int i = 0; i < size; i++) {
                Slot slot = self.getSlot(i);
                minetracer$prevContainerState[i] = slot.getStack().copy();
            }
        } else {
            minetracer$prevContainerState = null;
        }
    }

    @Inject(method = "onSlotClick", at = @At("RETURN"))
    private void minetracer$logSlotClickReturn(int slotIndex, int button,
            net.minecraft.screen.slot.SlotActionType actionType, PlayerEntity player, CallbackInfo ci) {
        ScreenHandler self = (ScreenHandler) (Object) this;
        if (minetracer$prevContainerState != null && self != null
                && self.slots.size() == minetracer$prevContainerState.length) {
            for (int i = 0; i < self.slots.size(); i++) {
                Slot slot = self.getSlot(i);
                // Only log if this slot belongs to the container, not the player inventory
                if (slot.inventory == player.getInventory()) {
                    continue;
                }
                // Try to get the container position from the inventory if possible
                BlockPos pos = null;
                if (slot.inventory instanceof net.minecraft.block.entity.BlockEntity be) {
                    pos = be.getPos();
                } else {
                    pos = player.getBlockPos(); // fallback, but should rarely happen
                }
                ItemStack before = minetracer$prevContainerState[i];
                ItemStack after = slot.getStack();
                boolean sameItem = ItemStack.areItemsEqual(before, after)
                        && Objects.equals(before.getNbt(), after.getNbt());
                int diff = after.getCount() - before.getCount();
                if (sameItem && diff > 0) {
                    ItemStack deposited = after.copy();
                    deposited.setCount(diff);
                    LogStorage.logContainerAction("deposited", player, pos, deposited);
                } else if (sameItem && diff < 0) {
                    ItemStack withdrew = before.copy();
                    withdrew.setCount(-diff);
                    LogStorage.logContainerAction("withdrew", player, pos, withdrew);
                }
                if (!before.isEmpty() && !after.isEmpty() && !sameItem) {
                    LogStorage.logContainerAction("withdrew", player, pos, before.copy());
                    LogStorage.logContainerAction("deposited", player, pos, after.copy());
                }
                // Log deposit if slot went from empty to non-empty
                if (before.isEmpty() && !after.isEmpty()) {
                    LogStorage.logContainerAction("deposited", player, pos, after.copy());
                }
                // Log withdraw if slot went from non-empty to empty
                else if (!before.isEmpty() && after.isEmpty()) {
                    LogStorage.logContainerAction("withdrew", player, pos, before.copy());
                }
            }
        }
        minetracer$prevContainerState = null;
    }
}
