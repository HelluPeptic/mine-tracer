package com.minetracer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.minetracer.features.minetracer.listeners.NaturalEventListener;

import net.minecraft.block.BlockState;
import net.minecraft.block.GrassBlock;
import net.minecraft.block.MyceliumBlock;
import net.minecraft.block.SpreadableBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;

/**
 * Mixin to track grass and mycelium spreading
 */
@Mixin(SpreadableBlock.class)
public class MixinSpreadableBlock {
    
    /**
     * Track when grass/mycelium spreads to dirt blocks
     */
    @Inject(method = "randomTick", at = @At("TAIL"))
    private void onGrassSpread(BlockState state, ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        try {
            // Check nearby blocks for any new grass/mycelium that might have been spread
            for (int i = 0; i < 4; ++i) {
                BlockPos targetPos = pos.add(random.nextInt(3) - 1, random.nextInt(5) - 3, random.nextInt(3) - 1);
                BlockState targetState = world.getBlockState(targetPos);
                
                // If we found grass or mycelium that might have been newly spread
                if ((targetState.getBlock() instanceof GrassBlock || 
                     targetState.getBlock() instanceof MyceliumBlock) &&
                    targetState.getBlock() != state.getBlock()) {
                    
                    NaturalEventListener.processGrassSpread(
                        world,
                        targetPos,
                        net.minecraft.block.Blocks.DIRT, // Assume dirt was converted
                        targetState.getBlock()
                    );
                }
            }
        } catch (Exception e) {
            // Silently fail to avoid crashing
        }
    }
}