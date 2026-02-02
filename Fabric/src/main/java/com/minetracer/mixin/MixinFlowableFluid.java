package com.minetracer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.minetracer.features.minetracer.listeners.NaturalEventListener;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FlowableFluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.WorldAccess;

/**
 * Mixin to track water and lava flow
 */
@Mixin(FlowableFluid.class)
public class MixinFlowableFluid {
    
    /**
     * Track fluid flow when it changes blocks
     */
    @Inject(method = "flow", at = @At("TAIL"))
    private void onFluidFlow(WorldAccess world, BlockPos pos, BlockState state, Direction direction, FluidState fluidState, CallbackInfo ci) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }
        
        try {
            FlowableFluid thisFluid = (FlowableFluid) (Object) this;
            BlockState currentState = world.getBlockState(pos);
            
            // Check if this is water or lava and if a block change occurred
            boolean isWater = thisFluid.getBucketItem() == net.minecraft.item.Items.WATER_BUCKET;
            
            // If the flow resulted in a solid block (like cobblestone generation)
            if (currentState.getBlock() != Blocks.AIR && 
                currentState.getBlock() != Blocks.CAVE_AIR &&
                currentState.getBlock() != Blocks.WATER &&
                currentState.getBlock() != Blocks.LAVA) {
                
                NaturalEventListener.processFluidFlow(
                    serverWorld, 
                    pos, 
                    Blocks.AIR, // Assume air was replaced
                    currentState.getBlock(), 
                    isWater
                );
            }
        } catch (Exception e) {
            // Silently fail to avoid crashing
        }
    }
}