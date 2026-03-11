package com.minetracer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.minetracer.features.minetracer.listeners.NaturalEventListener;

import net.minecraft.block.BlockState;
import net.minecraft.block.IceBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;

/**
 * Mixin to track ice melting
 */
@Mixin(IceBlock.class)
public class MixinIceBlock {
    
    /**
     * Track when ice melts
     */
    @Inject(method = "randomTick", at = @At("HEAD"))
    private void onIceMelt(BlockState state, ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        try {
            // Before the ice melts, log that it's about to be broken by melting
            NaturalEventListener.processIceMelt(world, pos, state);
        } catch (Exception e) {
            // Silently fail to avoid crashing
        }
    }
}