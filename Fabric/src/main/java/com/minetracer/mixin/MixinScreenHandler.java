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
    private Map<Integer, ItemStack> minetracer$trackedSlots = null;
    private boolean minetracer$isContainerInteraction = false;
    private BlockPos minetracer$containerPos = null;
    private boolean minetracer$hasRelevantSlots = false;
    private static long minetracer$lastInteractionTime = 0;
    private static final long INTERACTION_COOLDOWN_MS = 10; // Only track every X ms
    private boolean minetracer$isInmisBackpack(ScreenHandler handler) {
        if (handler == null)
            return false;
        String className = handler.getClass().getName().toLowerCase();
        return className.contains("inmis") || className.contains("backpack");
    }
    @Inject(method = "onSlotClick", at = @At("HEAD"))
    private void minetracer$logSlotClickHead(int slotIndex, int button,
            SlotActionType actionType, PlayerEntity player, CallbackInfo ci) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - minetracer$lastInteractionTime < INTERACTION_COOLDOWN_MS) {
            minetracer$isContainerInteraction = false;
            return;
        }
        minetracer$lastInteractionTime = currentTime;
        ScreenHandler self = (ScreenHandler) (Object) this;
        if (self == null || self.slots.size() <= 3) {
            minetracer$isContainerInteraction = false;
            return;
        }
        if (minetracer$isInmisBackpack(self)) {
            minetracer$isContainerInteraction = false;
            return;
        }
        if (slotIndex >= 0 && slotIndex < self.slots.size()) {
            Slot clickedSlot = self.getSlot(slotIndex);
            if (clickedSlot.inventory == player.getInventory()) {
                if (actionType != SlotActionType.QUICK_MOVE) {
                    minetracer$isContainerInteraction = false;
                    return;
                }
            } else {
                String invClass = clickedSlot.inventory.getClass().getName().toLowerCase();
                if (invClass.contains("inmis") || invClass.contains("backpack")) {
                    minetracer$isContainerInteraction = false;
                    return;
                }
            }
        }
        minetracer$isContainerInteraction = self.getSlot(0).inventory != player.getInventory();
        if (!minetracer$isContainerInteraction) {
            return;
        }
        if (minetracer$trackedSlots == null) {
            minetracer$trackedSlots = new HashMap<>(27); // Pre-size for typical chest
        } else {
            minetracer$trackedSlots.clear();
        }
        minetracer$containerPos = null;
        minetracer$hasRelevantSlots = false;
        for (int i = 0; i < self.slots.size(); i++) {
            Slot slot = self.getSlot(i);
            if (slot.inventory != player.getInventory()) {
                if (minetracer$containerPos == null
                        && slot.inventory instanceof net.minecraft.block.entity.BlockEntity be) {
                    minetracer$containerPos = be.getPos();
                }
                ItemStack stack = slot.getStack();
                if (!stack.isEmpty()) {
                    minetracer$trackedSlots.put(i, stack.copy());
                    minetracer$hasRelevantSlots = true;
                }
            }
        }
        if (!minetracer$hasRelevantSlots) {
            minetracer$isContainerInteraction = false;
            return;
        }
        if (minetracer$containerPos == null) {
            minetracer$containerPos = player.getBlockPos();
        }
    }
    @Inject(method = "onSlotClick", at = @At("RETURN"))
    private void minetracer$logSlotClickReturn(int slotIndex, int button,
            SlotActionType actionType, PlayerEntity player, CallbackInfo ci) {
        if (!minetracer$isContainerInteraction || minetracer$trackedSlots == null) {
            return;
        }
        ScreenHandler self = (ScreenHandler) (Object) this;
        for (int i = 0; i < self.slots.size(); i++) {
            Slot slot = self.getSlot(i);
            if (slot.inventory == player.getInventory()) {
                continue;
            }
            ItemStack before = minetracer$trackedSlots.getOrDefault(i, ItemStack.EMPTY);
            ItemStack after = slot.getStack();
            if (before.isEmpty() && after.isEmpty()) {
                continue;
            }
            minetracer$processSlotChange(player, before, after);
        }
        minetracer$trackedSlots.clear();
        minetracer$isContainerInteraction = false;
    }
    private void minetracer$processSlotChange(PlayerEntity player, ItemStack before, ItemStack after) {
        if (before == after)
            return;
        if (before.isEmpty() && after.isEmpty())
            return;
        boolean sameItem = ItemStack.areItemsEqual(before, after) && Objects.equals(before.getNbt(), after.getNbt());
        if (sameItem) {
            int diff = after.getCount() - before.getCount();
            if (diff != 0) {
                if (diff > 0) {
                    ItemStack deposited = after.copy();
                    deposited.setCount(diff);
                    OptimizedLogStorage.logContainerAction("deposited", player, minetracer$containerPos, deposited);
                } else {
                    ItemStack withdrew = before.copy();
                    withdrew.setCount(-diff);
                    OptimizedLogStorage.logContainerAction("withdrew", player, minetracer$containerPos, withdrew);
                }
            }
        } else {
            if (!before.isEmpty()) {
                OptimizedLogStorage.logContainerAction("withdrew", player, minetracer$containerPos, before.copy());
            }
            if (!after.isEmpty()) {
                OptimizedLogStorage.logContainerAction("deposited", player, minetracer$containerPos, after.copy());
            }
        }
    }
}
