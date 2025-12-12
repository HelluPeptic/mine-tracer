package com.minetracer.mixin;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.minetracer.features.minetracer.OptimizedLogStorage;
import com.minetracer.features.minetracer.ContainerPositionTracker;
import java.util.HashMap;
import java.util.Map;
@Mixin(ScreenHandler.class)
public class MixinScreenHandler {
    private static final long DRAG_TIMEOUT_MS = 150; // ACCURACY UPDATE: Shorter timeout for better drag detection
    private static final long SLOT_999_DELAY_MS = 25; // ACCURACY UPDATE: Faster response time
    private boolean minetracer$isContainerInteraction = false;
    private long minetracer$lastInteractionTime = 0;
    private Map<Integer, ItemStack> minetracer$trackedSlots = null;
    private Map<Integer, ItemStack> minetracer$trackedPlayerSlots = null;
    private BlockPos minetracer$containerPos = null;
    private boolean minetracer$hasRelevantSlots = false;
    private boolean minetracer$isDragOperation = false;
    private long minetracer$lastClickTime = 0;
    private long minetracer$lastSlot999Time = 0;
    private Map<String, Integer> minetracer$accumulatedContainerChanges = new HashMap<>();
    private Map<String, Integer> minetracer$accumulatedPlayerChanges = new HashMap<>();
    private boolean minetracer$isInmisBackpack(ScreenHandler handler) {
        if (handler.slots.isEmpty())
            return false;
        String invClass = handler.getSlot(0).inventory.getClass().getName().toLowerCase();
        return invClass.contains("inmis") || invClass.contains("backpack");
    }
    @Inject(method = "onSlotClick", at = @At("HEAD"))
    private void minetracer$logSlotClickHead(int slotIndex, int button,
            SlotActionType actionType, PlayerEntity player, CallbackInfo ci) {
        ScreenHandler self = (ScreenHandler) (Object) this;
        if (self == null || self.slots.size() <= 3) {
            minetracer$isContainerInteraction = false;
            return;
        }
        if (minetracer$isInmisBackpack(self)) {
            minetracer$isContainerInteraction = false;
            return;
        }
        minetracer$isContainerInteraction = self.getSlot(0).inventory != player.getInventory();
        if (!minetracer$isContainerInteraction) {
            return;
        }
        if (slotIndex == -999 || actionType == SlotActionType.QUICK_CRAFT) {
            if (!minetracer$isDragOperation) {
                minetracer$isDragOperation = true;
                if (minetracer$trackedSlots == null) {
                    minetracer$trackedSlots = new HashMap<>(27);
                    minetracer$trackedPlayerSlots = new HashMap<>(36);
                } else {
                    minetracer$trackedSlots.clear();
                    minetracer$trackedPlayerSlots.clear();
                }
                minetracer$accumulatedContainerChanges.clear();
                minetracer$accumulatedPlayerChanges.clear();
                minetracer$containerPos = null;
                minetracer$hasRelevantSlots = false;
                for (int i = 0; i < self.slots.size(); i++) {
                    Slot slot = self.getSlot(i);
                    ItemStack stack = slot.getStack();
                    if (slot.inventory != player.getInventory()) {
                        if (minetracer$containerPos == null
                                && slot.inventory instanceof net.minecraft.block.entity.BlockEntity be) {
                            minetracer$containerPos = be.getPos();
                        }
                        minetracer$trackedSlots.put(i, stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
                        if (!stack.isEmpty()) {
                            minetracer$hasRelevantSlots = true;
                        }
                    } else {
                        minetracer$trackedPlayerSlots.put(i, stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
                    }
                }
                // Fallback: Use last opened container position for SimpleInventory chests
                if (minetracer$containerPos == null) {
                    minetracer$containerPos = ContainerPositionTracker.getLastOpenedContainer(player.getUuid());
                }
            }
            minetracer$lastSlot999Time = System.currentTimeMillis();
            return;
        }
        if (slotIndex >= 0 && slotIndex < self.slots.size()) {
            Slot clickedSlot = self.getSlot(slotIndex);
            String invClass = clickedSlot.inventory.getClass().getName().toLowerCase();
            if (invClass.contains("inmis") || invClass.contains("backpack")) {
                minetracer$isContainerInteraction = false;
                return;
            }
        }
        if (minetracer$trackedSlots == null) {
            minetracer$trackedSlots = new HashMap<>(27);
            minetracer$trackedPlayerSlots = new HashMap<>(36);
        } else if (!minetracer$isDragOperation) {
            minetracer$trackedSlots.clear();
            minetracer$trackedPlayerSlots.clear();
        }
        if (minetracer$trackedSlots.isEmpty() && minetracer$trackedPlayerSlots.isEmpty()) {
            minetracer$containerPos = null;
            minetracer$hasRelevantSlots = false;
            for (int i = 0; i < self.slots.size(); i++) {
                Slot slot = self.getSlot(i);
                ItemStack stack = slot.getStack();
                if (slot.inventory != player.getInventory()) {
                    if (minetracer$containerPos == null
                            && slot.inventory instanceof net.minecraft.block.entity.BlockEntity be) {
                        minetracer$containerPos = be.getPos();
                    }
                    minetracer$trackedSlots.put(i, stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
                    if (!stack.isEmpty()) {
                        minetracer$hasRelevantSlots = true;
                    }
                } else {
                    minetracer$trackedPlayerSlots.put(i, stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
                }
            }
            // Fallback: Use last opened container position for SimpleInventory chests
            if (minetracer$containerPos == null) {
                minetracer$containerPos = ContainerPositionTracker.getLastOpenedContainer(player.getUuid());
            }
        }
        minetracer$lastClickTime = System.currentTimeMillis();
    }
    @Inject(method = "onSlotClick", at = @At("RETURN"))
    private void minetracer$logSlotClickReturn(int slotIndex, int button,
            SlotActionType actionType, PlayerEntity player, CallbackInfo ci) {
        if (!minetracer$isContainerInteraction || minetracer$trackedSlots == null) {
            return;
        }
        
        // Skip logging if we couldn't determine container position
        if (minetracer$containerPos == null) {
            minetracer$trackedSlots.clear();
            minetracer$trackedPlayerSlots.clear();
            minetracer$isContainerInteraction = false;
            return;
        }
        
        ScreenHandler self = (ScreenHandler) (Object) this;
        minetracer$detectInventoryTransfers(self, player);
        if (!minetracer$isDragOperation) {
            minetracer$trackedSlots.clear();
            minetracer$trackedPlayerSlots.clear();
            minetracer$isContainerInteraction = false;
        } else {
            long currentTime = System.currentTimeMillis();
            if (minetracer$lastSlot999Time > 0 && currentTime - minetracer$lastSlot999Time > SLOT_999_DELAY_MS) {
                minetracer$analyzeAccumulatedTransfers(player);
                minetracer$isDragOperation = false;
                minetracer$lastSlot999Time = 0;
                minetracer$trackedSlots.clear();
                minetracer$trackedPlayerSlots.clear();
                minetracer$isContainerInteraction = false;
            } else if (currentTime - minetracer$lastClickTime > DRAG_TIMEOUT_MS) {
                minetracer$analyzeAccumulatedTransfers(player);
                minetracer$isDragOperation = false;
                minetracer$lastSlot999Time = 0;
                minetracer$trackedSlots.clear();
                minetracer$trackedPlayerSlots.clear();
                minetracer$isContainerInteraction = false;
            }
        }
    }
    private void minetracer$detectInventoryTransfers(ScreenHandler handler, PlayerEntity player) {
        Map<String, Integer> containerItemChanges = new HashMap<>();
        Map<String, Integer> playerItemChanges = new HashMap<>();
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.getSlot(i);
            if (slot.inventory != player.getInventory()) {
                ItemStack before = minetracer$trackedSlots.getOrDefault(i, ItemStack.EMPTY);
                ItemStack after = slot.getStack();
                if (!ItemStack.areItemsEqual(before, after) || before.getCount() != after.getCount()) {
                    String itemKey = minetracer$getItemKey(before);
                    containerItemChanges.merge(itemKey, -before.getCount(), Integer::sum);
                    itemKey = minetracer$getItemKey(after);
                    containerItemChanges.merge(itemKey, after.getCount(), Integer::sum);
                }
            }
        }
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.getSlot(i);
            if (slot.inventory == player.getInventory()) {
                ItemStack before = minetracer$trackedPlayerSlots.getOrDefault(i, ItemStack.EMPTY);
                ItemStack after = slot.getStack();
                if (!ItemStack.areItemsEqual(before, after) || before.getCount() != after.getCount()) {
                    String itemKey = minetracer$getItemKey(before);
                    playerItemChanges.merge(itemKey, -before.getCount(), Integer::sum);
                    itemKey = minetracer$getItemKey(after);
                    playerItemChanges.merge(itemKey, after.getCount(), Integer::sum);
                }
            }
        }
        if (minetracer$isDragOperation) {
            for (Map.Entry<String, Integer> entry : containerItemChanges.entrySet()) {
                minetracer$accumulatedContainerChanges.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
            for (Map.Entry<String, Integer> entry : playerItemChanges.entrySet()) {
                minetracer$accumulatedPlayerChanges.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        } else {
            minetracer$analyzeCurrentTransfers(containerItemChanges, playerItemChanges, player);
        }
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.getSlot(i);
            ItemStack stack = slot.getStack();
            if (slot.inventory != player.getInventory()) {
                minetracer$trackedSlots.put(i, stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
            } else {
                minetracer$trackedPlayerSlots.put(i, stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
            }
        }
    }
    private void minetracer$analyzeCurrentTransfers(Map<String, Integer> containerItemChanges,
            Map<String, Integer> playerItemChanges, PlayerEntity player) {
        System.out.println("[MineTracer-DEBUG] Analyzing current transfers. Container changes: " + containerItemChanges.size());
        for (String itemKey : containerItemChanges.keySet()) {
            if (itemKey.equals("air"))
                continue;
            int containerChange = containerItemChanges.get(itemKey);
            System.out.println("[MineTracer-DEBUG] Item " + itemKey + " change: " + containerChange + " at position: " + minetracer$containerPos);
            if (containerChange > 0) {
                ItemStack itemStack = minetracer$createItemStackFromKey(itemKey);
                if (!itemStack.isEmpty()) {
                    itemStack.setCount(containerChange);
                    System.out.println("[MineTracer-DEBUG] Logging DEPOSIT: " + itemStack + " x" + containerChange + " at " + minetracer$containerPos);
                    OptimizedLogStorage.logContainerAction("deposited", player, minetracer$containerPos, itemStack);
                }
            } else if (containerChange < 0) {
                ItemStack itemStack = minetracer$createItemStackFromKey(itemKey);
                if (!itemStack.isEmpty()) {
                    itemStack.setCount(-containerChange);
                    System.out.println("[MineTracer-DEBUG] Logging WITHDRAWAL: " + itemStack + " x" + (-containerChange) + " at " + minetracer$containerPos);
                    OptimizedLogStorage.logContainerAction("withdrew", player, minetracer$containerPos, itemStack);
                }
            }
        }
    }
    private void minetracer$analyzeAccumulatedTransfers(PlayerEntity player) {
        System.out.println("[MineTracer-DEBUG] Analyzing accumulated transfers. Container changes: " + minetracer$accumulatedContainerChanges.size());
        for (String itemKey : minetracer$accumulatedContainerChanges.keySet()) {
            int containerChange = minetracer$accumulatedContainerChanges.getOrDefault(itemKey, 0);
            int playerChange = minetracer$accumulatedPlayerChanges.getOrDefault(itemKey, 0);
            System.out.println("[MineTracer-DEBUG] Accumulated - Item: " + itemKey + ", Container: " + containerChange + ", Player: " + playerChange + " at " + minetracer$containerPos);
            if (containerChange > 0 && playerChange < 0) {
                ItemStack itemStack = minetracer$createItemStackFromKey(itemKey);
                if (!itemStack.isEmpty()) {
                    itemStack.setCount(containerChange); // Use actual deposited amount
                    System.out.println("[MineTracer-DEBUG] Logging ACCUMULATED DEPOSIT: " + itemStack + " x" + containerChange + " at " + minetracer$containerPos);
                    OptimizedLogStorage.logContainerAction("deposited", player, minetracer$containerPos, itemStack);
                }
            } else if (containerChange < 0 && playerChange > 0) {
                ItemStack itemStack = minetracer$createItemStackFromKey(itemKey);
                if (!itemStack.isEmpty()) {
                    itemStack.setCount(-containerChange); // Use actual withdrawn amount
                    System.out.println("[MineTracer-DEBUG] Logging ACCUMULATED WITHDRAWAL: " + itemStack + " x" + (-containerChange) + " at " + minetracer$containerPos);
                    OptimizedLogStorage.logContainerAction("withdrew", player, minetracer$containerPos, itemStack);
                }
            }
        }
        minetracer$accumulatedContainerChanges.clear();
        minetracer$accumulatedPlayerChanges.clear();
    }
    private String minetracer$getItemKey(ItemStack stack) {
        if (stack.isEmpty())
            return "air";
        String itemId = Registries.ITEM.getId(stack.getItem()).toString();
        return stack.hasNbt() ? itemId + stack.getNbt().toString() : itemId;
    }
    private ItemStack minetracer$createItemStackFromKey(String key) {
        if (key.equals("air"))
            return ItemStack.EMPTY;
        String itemId = key.contains("{") ? key.substring(0, key.indexOf("{")) : key;
        Item item = Registries.ITEM.get(new Identifier(itemId));
        return new ItemStack(item, 1);
    }
}
