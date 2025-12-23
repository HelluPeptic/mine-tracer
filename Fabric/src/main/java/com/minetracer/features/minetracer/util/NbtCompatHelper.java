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
            java.util.Optional<ItemStack> result = ItemStack.CODEC.parse(
                net.minecraft.nbt.NbtOps.INSTANCE, nbt
            ).result();
            return result.orElse(ItemStack.EMPTY);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }
    
    public static NbtCompound itemStackToNbt(ItemStack stack, RegistryWrapper.WrapperLookup registryManager) {
        if (stack == null || stack.isEmpty()) {
            return new NbtCompound();
        }
        try {
            return (NbtCompound) ItemStack.CODEC.encodeStart(
                net.minecraft.nbt.NbtOps.INSTANCE, stack
            ).getOrThrow();
        } catch (Exception e) {
            return new NbtCompound();
        }
    }
}
