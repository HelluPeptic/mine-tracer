package com.minetracer.mixin;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.minetracer.features.minetracer.ContainerPositionTracker;
import com.minetracer.features.minetracer.OptimizedLogStorage;
@Mixin(AbstractContainerMenu.class)
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
    private boolean minetracer$isInmisBackpack(AbstractContainerMenu handler) {
        if (handler.slots.isEmpty())
            return false;
        String invClass = handler.getSlot(0).container.getClass().getName().toLowerCase();
        return invClass.contains("inmis") || invClass.contains("backpack");
    }
    @Inject(method = "clicked", at = @At("HEAD"))
    private void minetracer$logSlotClickHead(int slotIndex, int button,
            ContainerInput actionType, Player player, CallbackInfo ci) {
        AbstractContainerMenu self = (AbstractContainerMenu) (Object) this;
        if (self == null || self.slots.size() <= 3) {
            minetracer$isContainerInteraction = false;
            return;
        }
        if (minetracer$isInmisBackpack(self)) {
            minetracer$isContainerInteraction = false;
            return;
        }
        minetracer$isContainerInteraction = self.getSlot(0).container != player.getInventory();
        if (!minetracer$isContainerInteraction) {
            return;
        }
        if (slotIndex == -999 || actionType == ContainerInput.QUICK_CRAFT) {
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
                    ItemStack stack = slot.getItem();
                    if (slot.container != player.getInventory()) {
                        if (minetracer$containerPos == null
                                && slot.container instanceof net.minecraft.world.level.block.entity.BlockEntity be) {
                            // Use canonical position for consistent double chest handling
                            minetracer$containerPos = ContainerPositionTracker.getContainerPosition(
                                be.getLevel(), be.getBlockPos());
                            // Fallback to raw position if canonical detection fails
                            if (minetracer$containerPos == null) {
                                minetracer$containerPos = be.getBlockPos();
                            }
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
                    minetracer$containerPos = ContainerPositionTracker.getLastOpenedContainer(player.getUUID());
                }
            }
            minetracer$lastSlot999Time = System.currentTimeMillis();
            return;
        }
        if (slotIndex >= 0 && slotIndex < self.slots.size()) {
            Slot clickedSlot = self.getSlot(slotIndex);
            String invClass = clickedSlot.container.getClass().getName().toLowerCase();
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
                ItemStack stack = slot.getItem();
                if (slot.container != player.getInventory()) {
                    if (minetracer$containerPos == null
                            && slot.container instanceof net.minecraft.world.level.block.entity.BlockEntity be) {
                        // Use canonical position for consistent double chest handling
                        minetracer$containerPos = ContainerPositionTracker.getContainerPosition(
                            be.getLevel(), be.getBlockPos());
                        // Fallback to raw position if canonical detection fails
                        if (minetracer$containerPos == null) {
                            minetracer$containerPos = be.getBlockPos();
                        }
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
                minetracer$containerPos = ContainerPositionTracker.getLastOpenedContainer(player.getUUID());
            }
        }
        minetracer$lastClickTime = System.currentTimeMillis();
    }
    @Inject(method = "clicked", at = @At("RETURN"))
    private void minetracer$logSlotClickReturn(int slotIndex, int button,
            ContainerInput actionType, Player player, CallbackInfo ci) {
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
        
        AbstractContainerMenu self = (AbstractContainerMenu) (Object) this;
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
    private void minetracer$detectInventoryTransfers(AbstractContainerMenu handler, Player player) {
        Map<String, Integer> containerItemChanges = new HashMap<>();
        Map<String, Integer> playerItemChanges = new HashMap<>();
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.getSlot(i);
            if (slot.container != player.getInventory()) {
                ItemStack before = minetracer$trackedSlots.getOrDefault(i, ItemStack.EMPTY);
                ItemStack after = slot.getItem();
                if (!ItemStack.isSameItem(before, after) || before.getCount() != after.getCount()) {
                    String itemKey = minetracer$getItemKey(before);
                    containerItemChanges.merge(itemKey, -before.getCount(), Integer::sum);
                    itemKey = minetracer$getItemKey(after);
                    containerItemChanges.merge(itemKey, after.getCount(), Integer::sum);
                }
            }
        }
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.getSlot(i);
            if (slot.container == player.getInventory()) {
                ItemStack before = minetracer$trackedPlayerSlots.getOrDefault(i, ItemStack.EMPTY);
                ItemStack after = slot.getItem();
                if (!ItemStack.isSameItem(before, after) || before.getCount() != after.getCount()) {
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
            ItemStack stack = slot.getItem();
            if (slot.container != player.getInventory()) {
                minetracer$trackedSlots.put(i, stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
            } else {
                minetracer$trackedPlayerSlots.put(i, stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
            }
        }
    }
    private void minetracer$analyzeCurrentTransfers(Map<String, Integer> containerItemChanges,
            Map<String, Integer> playerItemChanges, Player player) {
        // Analyze accumulated changes and log final transfers
        for (String itemKey : containerItemChanges.keySet()) {
            if (itemKey.equals("air"))
                continue;
            int containerChange = containerItemChanges.get(itemKey);
            if (containerChange > 0) {
                ItemStack itemStack = minetracer$createItemStackFromKey(itemKey);
                if (!itemStack.isEmpty()) {
                    itemStack.setCount(containerChange);
                    OptimizedLogStorage.logContainerAction("deposited", player, minetracer$containerPos, itemStack);
                }
            } else if (containerChange < 0) {
                ItemStack itemStack = minetracer$createItemStackFromKey(itemKey);
                if (!itemStack.isEmpty()) {
                    itemStack.setCount(-containerChange);
                    OptimizedLogStorage.logContainerAction("withdrew", player, minetracer$containerPos, itemStack);
                }
            }
        }
    }
    private void minetracer$analyzeAccumulatedTransfers(Player player) {
        // Process accumulated changes
        for (String itemKey : minetracer$accumulatedContainerChanges.keySet()) {
            int containerChange = minetracer$accumulatedContainerChanges.getOrDefault(itemKey, 0);
            int playerChange = minetracer$accumulatedPlayerChanges.getOrDefault(itemKey, 0);
            if (containerChange > 0 && playerChange < 0) {
                ItemStack itemStack = minetracer$createItemStackFromKey(itemKey);
                if (!itemStack.isEmpty()) {
                    itemStack.setCount(containerChange); // Use actual deposited amount
                    OptimizedLogStorage.logContainerAction("deposited", player, minetracer$containerPos, itemStack);
                }
            } else if (containerChange < 0 && playerChange > 0) {
                ItemStack itemStack = minetracer$createItemStackFromKey(itemKey);
                if (!itemStack.isEmpty()) {
                    itemStack.setCount(-containerChange); // Use actual withdrawn amount
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
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        // In 1.21+, use component string representation instead of NBT
        return itemId + stack.getComponents().toString();
    }
    private ItemStack minetracer$createItemStackFromKey(String key) {
        if (key.equals("air"))
            return ItemStack.EMPTY;
        String itemId = key.contains("{") ? key.substring(0, key.indexOf("{")) : key;
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
        return new ItemStack(item, 1);
    }
}
