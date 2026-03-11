package com.minetracer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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
 * Mixin to track water and lava flow.
 *
 * We inject at HEAD to capture the old BlockState *before* the world mutates,
 * then at TAIL to see what the world now contains. This gives us accurate
 * before/after information without relying on Blocks.AIR guesses.
 */
@Mixin(FlowableFluid.class)
public class MixinFlowableFluid {

    /** Cached pre-flow state; thread-local style via unique field on the instance. */
    @Unique
    private BlockState minetracer$preFlowState = null;

    @Inject(method = "flow", at = @At("HEAD"))
    private void onFluidFlowHead(WorldAccess world, BlockPos pos, BlockState state,
            Direction direction, FluidState fluidState, CallbackInfo ci) {
        if (!(world instanceof ServerWorld)) return;
        try {
            minetracer$preFlowState = world.getBlockState(pos);
        } catch (Exception e) {
            minetracer$preFlowState = null;
        }
    }

    @Inject(method = "flow", at = @At("TAIL"))
    private void onFluidFlowTail(WorldAccess world, BlockPos pos, BlockState state,
            Direction direction, FluidState fluidState, CallbackInfo ci) {
        if (!(world instanceof ServerWorld serverWorld)) return;

        BlockState oldState = minetracer$preFlowState;
        minetracer$preFlowState = null;

        if (oldState == null) return;

        try {
            BlockState newState = world.getBlockState(pos);

            // Skip if nothing meaningful changed, or if the new state is just fluid
            if (newState.getBlock() == oldState.getBlock()) return;
            if (newState.getBlock() == Blocks.WATER || newState.getBlock() == Blocks.LAVA) return;
            if (oldState.getBlock() == Blocks.WATER || oldState.getBlock() == Blocks.LAVA
                    || oldState.getBlock() == Blocks.BUBBLE_COLUMN) return;

            FlowableFluid thisFluid = (FlowableFluid) (Object) this;
            boolean isWater = thisFluid.getBucketItem() == net.minecraft.item.Items.WATER_BUCKET;

            NaturalEventListener.processFluidFlow(serverWorld, pos, oldState, newState, isWater);
        } catch (Exception e) {
            // Silently fail to avoid crashing the server
        }
    }
}