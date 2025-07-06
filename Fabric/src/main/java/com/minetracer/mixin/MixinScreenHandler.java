package com.minetracer.mixin;

import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.minetracer.features.minetracer.LogStorage;

import java.util.Objects;
import java.util.HashMap;
import java.util.Map;

@Mixin(ScreenHandler.class)
public abstract class MixinScreenHandler {
    // Ultra-optimized: Minimal state tracking
    private Map<Integer, ItemStack> minetracer$trackedSlots = null;
    private boolean minetracer$isContainerInteraction = false;
    private BlockPos minetracer$containerPos = null;
    private boolean minetracer$hasRelevantSlots = false;

    @Inject(method = "onSlotClick", at = @At("HEAD"))
    private void minetracer$logSlotClickHead(int slotIndex, int button,
            net.minecraft.screen.slot.SlotActionType actionType, PlayerEntity player, CallbackInfo ci) {
        ScreenHandler self = (ScreenHandler) (Object) this;
        
        // Ultra-fast early exit
        if (self == null || self.slots.size() <= 0) {
            minetracer$isContainerInteraction = false;
            return;
        }
        
        // Quick check: only proceed if first slot is not player inventory
        minetracer$isContainerInteraction = self.getSlot(0).inventory != player.getInventory();
        
        if (!minetracer$isContainerInteraction) {
            return;
        }

        // Lazy initialization - only create map when needed
        if (minetracer$trackedSlots == null) {
            minetracer$trackedSlots = new HashMap<>(27); // Pre-size for typical chest
        } else {
            minetracer$trackedSlots.clear();
        }
        
        // Reset state
        minetracer$containerPos = null;
        minetracer$hasRelevantSlots = false;
        
        // Ultra-optimized: Only track slots that actually have items
        // Skip empty slots entirely to minimize memory and processing
        for (int i = 0; i < self.slots.size(); i++) {
            Slot slot = self.getSlot(i);
            if (slot.inventory != player.getInventory()) {
                // Cache container position from first container slot
                if (minetracer$containerPos == null && slot.inventory instanceof net.minecraft.block.entity.BlockEntity be) {
                    minetracer$containerPos = be.getPos();
                }
                
                // Only store non-empty stacks - this is the key optimization
                ItemStack stack = slot.getStack();
                if (!stack.isEmpty()) {
                    minetracer$trackedSlots.put(i, stack.copy());
                    minetracer$hasRelevantSlots = true;
                }
            }
        }
        
        // If no relevant slots, disable tracking entirely
        if (!minetracer$hasRelevantSlots) {
            minetracer$isContainerInteraction = false;
            return;
        }
        
        // Fallback position
        if (minetracer$containerPos == null) {
            minetracer$containerPos = player.getBlockPos();
        }
    }

    @Inject(method = "onSlotClick", at = @At("RETURN"))
    private void minetracer$logSlotClickReturn(int slotIndex, int button,
            net.minecraft.screen.slot.SlotActionType actionType, PlayerEntity player, CallbackInfo ci) {
        
        // Quick exit if this wasn't a container interaction
        if (!minetracer$isContainerInteraction || minetracer$trackedSlots == null) {
            return;
        }
        
        ScreenHandler self = (ScreenHandler) (Object) this;

        // Optimized: Only check container slots, and only those that changed
        for (int i = 0; i < self.slots.size(); i++) {
            Slot slot = self.getSlot(i);
            
            // Skip player inventory slots
            if (slot.inventory == player.getInventory()) {
                continue;
            }

            ItemStack before = minetracer$trackedSlots.getOrDefault(i, ItemStack.EMPTY);
            ItemStack after = slot.getStack();
            
            // Optimization: skip if both are empty (most common case)
            if (before.isEmpty() && after.isEmpty()) {
                continue;
            }

            // Process the actual change
            minetracer$processSlotChange(player, before, after);
        }
        
        // Clear for next interaction to free memory
        minetracer$trackedSlots.clear();
        minetracer$isContainerInteraction = false;
    }
    
    // Optimized helper method - all change processing logic in one place
    private void minetracer$processSlotChange(PlayerEntity player, ItemStack before, ItemStack after) {
        boolean sameItem = ItemStack.areItemsEqual(before, after) && Objects.equals(before.getNbt(), after.getNbt());
        
        if (sameItem) {
            // Same item type, check quantity change
            int diff = after.getCount() - before.getCount();
            if (diff > 0) {
                // Items were deposited
                ItemStack deposited = after.copy();
                deposited.setCount(diff);
                LogStorage.logContainerAction("deposited", player, minetracer$containerPos, deposited);
            } else if (diff < 0) {
                // Items were withdrawn
                ItemStack withdrew = before.copy();
                withdrew.setCount(-diff);
                LogStorage.logContainerAction("withdrew", player, minetracer$containerPos, withdrew);
            }
            // If diff == 0, no actual change occurred (shouldn't happen but just in case)
        } else {
            // Different items: handle as separate withdraw and deposit
            if (!before.isEmpty()) {
                LogStorage.logContainerAction("withdrew", player, minetracer$containerPos, before.copy());
            }
            if (!after.isEmpty()) {
                LogStorage.logContainerAction("deposited", player, minetracer$containerPos, after.copy());
            }
        }
    }
}
