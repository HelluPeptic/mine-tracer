package com.minetracer.features.minetracer.util;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * Compatibility layer for NBT operations in Minecraft 1.21.11+
 */
public class NbtCompatHelper {
    
    public static CompoundTag parseNbtString(String nbtString) {
        try {
            // In 1.21.11, use NbtHelper methods
            Tag element = NbtUtils.snbtToStructure(nbtString);
            if (element instanceof CompoundTag) {
                return (CompoundTag) element;
            }
            return new CompoundTag();
        } catch (Exception e) {
            return new CompoundTag();
        }
    }
    
    public static ItemStack itemStackFromNbt(CompoundTag nbt, HolderLookup.Provider registryManager) {
        if (nbt == null || nbt.isEmpty()) {
            return ItemStack.EMPTY;
        }
        try {
            // Modded stacks may have been stored with count > 99.
            // ItemStack.CODEC rejects those, so we patch count to 1 for parsing
            // and restore the real count afterward.
            int oversizedCount = -1;
            CompoundTag parseNbt = nbt;
            if (nbt.contains("count")) {
                try {
                    int rawCount = ((net.minecraft.nbt.IntTag) nbt.get("count")).intValue();
                    if (rawCount > 99) {
                        oversizedCount = rawCount;
                        parseNbt = nbt.copy();
                        parseNbt.putInt("count", 1);
                    }
                } catch (Exception ignored) {}
            }
            com.mojang.serialization.DataResult<ItemStack> result = ItemStack.CODEC.parse(
                registryManager.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), parseNbt
            );
            ItemStack stack = result.resultOrPartial(error ->
                System.err.println("[MineTracer] Failed to parse ItemStack from NBT: " + error)
            ).orElse(ItemStack.EMPTY);
            if (!stack.isEmpty() && oversizedCount > 0) {
                stack = stack.copyWithCount(oversizedCount);
            }
            return stack;
        } catch (Exception e) {
            System.err.println("[MineTracer] Exception parsing ItemStack from NBT: " + e.getMessage());
            return ItemStack.EMPTY;
        }
    }
    
    public static CompoundTag itemStackToNbt(ItemStack stack, HolderLookup.Provider registryManager) {
        if (stack == null || stack.isEmpty()) {
            return new CompoundTag();
        }
        try {
            int actualCount = stack.getCount();
            // ItemStack.CODEC enforces count in [1;99]. For modded oversized stacks
            // we encode a count=1 copy (capturing all components) then patch the count.
            ItemStack encodeTarget = (actualCount > 99) ? stack.copyWithCount(1) : stack;
            com.mojang.serialization.DataResult<net.minecraft.nbt.Tag> result = ItemStack.CODEC.encodeStart(
                registryManager.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), encodeTarget
            );
            net.minecraft.nbt.Tag encoded = result.resultOrPartial(error ->
                System.err.println("[MineTracer] Failed to encode ItemStack to NBT: " + error)
            ).orElse(null);
            if (!(encoded instanceof CompoundTag out)) {
                return new CompoundTag();
            }
            if (actualCount > 99) {
                out.putInt("count", actualCount);
            }
            return out;
        } catch (Exception e) {
            System.err.println("[MineTracer] Exception encoding ItemStack to NBT: " + e.getMessage());
            return new CompoundTag();
        }
    }
}
