package com.minetracer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.minetracer.features.minetracer.listeners.EntityBlockChangeListener;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Mixin to track Enderman block interactions
 */
@Mixin(EndermanEntity.class)
public class MixinEndermanEntity {

    /**
     * Intercept when Enderman picks up a block
     */
    @Inject(method = "pickUpBlock", at = @At("HEAD"))
    private void onPickUpBlock(CallbackInfo ci) {
        EndermanEntity enderman = (EndermanEntity) (Object) this;
        
        if (((EntityAccessor) enderman).getWorld() instanceof ServerWorld) {
            ServerWorld world = (ServerWorld) ((EntityAccessor) enderman).getWorld();
            BlockPos pos = enderman.getBlockPos().down(); // Enderman typically picks up block below them
            BlockState currentState = world.getBlockState(pos);
            
            if (!currentState.isAir()) {
                // Log the block being picked up (broken)
                EntityBlockChangeListener.processEntityBlockChange(
                    enderman, world, pos, currentState, Blocks.AIR.getDefaultState());
            }
        }
    }
    
    /**
     * Intercept when Enderman places a block
     */
    @Inject(method = "placeBlock", at = @At("HEAD"))
    private void onPlaceBlock(CallbackInfo ci) {
        EndermanEntity enderman = (EndermanEntity) (Object) this;
        
        if (((EntityAccessor) enderman).getWorld() instanceof ServerWorld) {
            ServerWorld world = (ServerWorld) ((EntityAccessor) enderman).getWorld();
            BlockPos pos = enderman.getBlockPos().down(); // Enderman typically places block below them
            BlockState oldState = world.getBlockState(pos);
            BlockState carriedBlock = enderman.getCarriedBlock();
            
            if (carriedBlock != null && !carriedBlock.isAir()) {
                // Log the block being placed
                EntityBlockChangeListener.processEntityBlockChange(
                    enderman, world, pos, oldState, carriedBlock);
            }
        }
    }
}