package com.minetracer.features.minetracer.util;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.registry.RegistryWrapper;

/**
 * Compatibility layer for NBT operations in Minecraft 1.21.11+
 */
public class NbtCompatHelper {
    
    public static NbtCompound parseNbtString(String nbtString) {
        try {
            // In 1.21.11, use NbtHelper methods
            NbtElement element = NbtHelper.fromNbtProviderString(nbtString);
            if (element instanceof NbtCompound) {
                return (NbtCompound) element;
            }
            return new NbtCompound();
        } catch (Exception e) {
            return new NbtCompound();
        }
    }
    
    public static ItemStack itemStackFromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryManager) {
        if (nbt == null || nbt.isEmpty()) {
            return ItemStack.EMPTY;
        }
        try {
            // Use the Minecraft CODEC with proper error handling to avoid corrupted items
            com.mojang.serialization.DataResult<ItemStack> result = ItemStack.CODEC.parse(
                registryManager.getOps(net.minecraft.nbt.NbtOps.INSTANCE), nbt
            );
            return result.resultOrPartial(error -> {
                System.err.println("[MineTracer] Failed to parse ItemStack from NBT: " + error);
            }).orElse(ItemStack.EMPTY);
        } catch (Exception e) {
            System.err.println("[MineTracer] Exception parsing ItemStack from NBT: " + e.getMessage());
            return ItemStack.EMPTY;
        }
    }
    
    public static NbtCompound itemStackToNbt(ItemStack stack, RegistryWrapper.WrapperLookup registryManager) {
        if (stack == null || stack.isEmpty()) {
            return new NbtCompound();
        }
        try {
            // Use the Minecraft CODEC with proper error handling
            com.mojang.serialization.DataResult<net.minecraft.nbt.NbtElement> result = ItemStack.CODEC.encodeStart(
                registryManager.getOps(net.minecraft.nbt.NbtOps.INSTANCE), stack
            );
            return (NbtCompound) result.resultOrPartial(error -> {
                System.err.println("[MineTracer] Failed to encode ItemStack to NBT: " + error);
            }).orElse(new NbtCompound());
        } catch (Exception e) {
            System.err.println("[MineTracer] Exception encoding ItemStack to NBT: " + e.getMessage());
            return new NbtCompound();
        }
    }
}
