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
import com.minetracer.features.minetracer.OptimizedLogStorage;

import java.util.Objects;
import java.util.HashMap;
import java.util.Map;

@Mixin(ScreenHandler.class)
public abstract class MixinScreenHandler {
    // Ultra-optimized: Minimal state tracking with sampling
    private Map<Integer, ItemStack> minetracer$trackedSlots = null;
    private boolean minetracer$isContainerInteraction = false;
    private BlockPos minetracer$containerPos = null;
    private boolean minetracer$hasRelevantSlots = false;
    private static long minetracer$lastInteractionTime = 0;
    private static final long INTERACTION_COOLDOWN_MS = 50; // Only track every 50ms

    // Helper method to check if this is an Inmis backpack
    private boolean minetracer$isInmisBackpack(ScreenHandler handler) {
        if (handler == null)
            return false;
        String className = handler.getClass().getName().toLowerCase();
        // Check for Inmis mod package or backpack-related classes
        return className.contains("inmis") || className.contains("backpack");
    }

    @Inject(method = "onSlotClick", at = @At("HEAD"))
    private void minetracer$logSlotClickHead(int slotIndex, int button,
            SlotActionType actionType, PlayerEntity player, CallbackInfo ci) {
        System.out.println("[MineTracer DEBUG] MixinScreenHandler onSlotClick called - slotIndex: " + slotIndex + ", button: " + button + ", actionType: " + actionType);
        
        // Ultra-aggressive optimization: Rate limiting
        long currentTime = System.currentTimeMillis();
        if (currentTime - minetracer$lastInteractionTime < INTERACTION_COOLDOWN_MS) {
            minetracer$isContainerInteraction = false;
            return;
        }
        minetracer$lastInteractionTime = currentTime;

        ScreenHandler self = (ScreenHandler) (Object) this;

        // Ultra-fast early exit
        if (self == null || self.slots.size() <= 3) {
            minetracer$isContainerInteraction = false;
            return;
        }

        // Exclude Inmis backpacks by handler class name
        if (minetracer$isInmisBackpack(self)) {
            minetracer$isContainerInteraction = false;
            return;
        }

        // Skip if clicking on player inventory slots, EXCEPT for QUICK_MOVE actions
        // QUICK_MOVE (shift+click) from player inventory to container should still be tracked
        if (slotIndex >= 0 && slotIndex < self.slots.size()) {
            Slot clickedSlot = self.getSlot(slotIndex);
            if (clickedSlot.inventory == player.getInventory()) {
                // Only skip if it's NOT a QUICK_MOVE action
                if (actionType != SlotActionType.QUICK_MOVE) {
                    minetracer$isContainerInteraction = false;
                    return;
                }
                // For QUICK_MOVE, we continue to track the interaction
            } else {
                // Exclude Inmis backpacks by inventory class name
                String invClass = clickedSlot.inventory.getClass().getName().toLowerCase();
                if (invClass.contains("inmis") || invClass.contains("backpack")) {
                    minetracer$isContainerInteraction = false;
                    return;
                }
            }
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
                if (minetracer$containerPos == null
                        && slot.inventory instanceof net.minecraft.block.entity.BlockEntity be) {
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
            SlotActionType actionType, PlayerEntity player, CallbackInfo ci) {

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

    // Ultra-optimized helper method - all change processing logic in one place
    private void minetracer$processSlotChange(PlayerEntity player, ItemStack before, ItemStack after) {
        // Ultra-fast early exit for no-change scenarios
        if (before == after)
            return; // Same object reference
        if (before.isEmpty() && after.isEmpty())
            return; // Both empty

        boolean sameItem = ItemStack.areItemsEqual(before, after) && Objects.equals(before.getNbt(), after.getNbt());

        if (sameItem) {
            // Same item type, check quantity change
            int diff = after.getCount() - before.getCount();
            if (diff != 0) { // Only log if quantity actually changed
                if (diff > 0) {
                    // Items were deposited
                    ItemStack deposited = after.copy();
                    deposited.setCount(diff);
                    OptimizedLogStorage.logContainerAction("deposited", player, minetracer$containerPos, deposited);
                } else {
                    // Items were withdrawn
                    ItemStack withdrew = before.copy();
                    withdrew.setCount(-diff);
                    OptimizedLogStorage.logContainerAction("withdrew", player, minetracer$containerPos, withdrew);
                }
            }
        } else {
            // Different items: handle as separate withdraw and deposit
            if (!before.isEmpty()) {
                OptimizedLogStorage.logContainerAction("withdrew", player, minetracer$containerPos, before.copy());
            }
            if (!after.isEmpty()) {
                OptimizedLogStorage.logContainerAction("deposited", player, minetracer$containerPos, after.copy());
            }
        }
    }
}
