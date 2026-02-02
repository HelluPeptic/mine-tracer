package com.minetracer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.minetracer.features.minetracer.listeners.NaturalEventListener;

import net.minecraft.block.BlockState;
import net.minecraft.block.FireBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

/**
 * Mixin to track fire spread and burning
 */
@Mixin(FireBlock.class)
public class MixinFireBlock {
    
    /**
     * Track when fire spreads to new locations
     */
    @Inject(method = "trySpreadingFire", at = @At("TAIL"))
    private void onFireSpread(World world, BlockPos pos, int spreadFactor, Random random, int currentAge, CallbackInfo ci) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }
        
        try {
            BlockState currentState = world.getBlockState(pos);
            
            // If fire was placed at this position
            if (currentState.getBlock() instanceof FireBlock) {
                NaturalEventListener.processFireSpread(
                    serverWorld,
                    pos,
                    net.minecraft.block.Blocks.AIR, // Assume air was replaced
                    currentState.getBlock()
                );
            }
        } catch (Exception e) {
            // Silently fail to avoid crashing
        }
    }
    
    /**
     * Track when fire burns down blocks
     */
    @Inject(method = "scheduledTick", at = @At("TAIL"))
    private void onFireTick(BlockState state, net.minecraft.server.world.ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        try {
            BlockState currentState = world.getBlockState(pos);
            
            // If fire went out (state changed to air)
            if (currentState.isAir()) {
                NaturalEventListener.processFireSpread(
                    world,
                    pos,
                    net.minecraft.block.Blocks.FIRE,
                    currentState.getBlock()
                );
            }
        } catch (Exception e) {
            // Silently fail to avoid crashing
        }
    }
}