package com.minetracer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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
 * Mixin to track fire spread and burning.
 *
 * trySpreadingFire: HEAD captures the old BlockState before fire modifies it;
 *                   TAIL sees what the world now holds and logs the delta.
 * scheduledTick:    The old state is already available as the `state` parameter
 *                   (the fire BlockState that was ticked). The TAIL reads the
 *                   current world state to detect if fire went out.
 */
@Mixin(FireBlock.class)
public class MixinFireBlock {

    @Unique
    private BlockState minetracer$preSpreadState = null;

    @Inject(method = "trySpreadingFire", at = @At("HEAD"))
    private void onFireSpreadHead(World world, BlockPos pos, int spreadFactor,
            Random random, int currentAge, CallbackInfo ci) {
        if (!(world instanceof ServerWorld)) return;
        try {
            minetracer$preSpreadState = world.getBlockState(pos);
        } catch (Exception e) {
            minetracer$preSpreadState = null;
        }
    }

    @Inject(method = "trySpreadingFire", at = @At("TAIL"))
    private void onFireSpreadTail(World world, BlockPos pos, int spreadFactor,
            Random random, int currentAge, CallbackInfo ci) {
        if (!(world instanceof ServerWorld serverWorld)) return;

        BlockState oldState = minetracer$preSpreadState;
        minetracer$preSpreadState = null;

        if (oldState == null) return;

        try {
            BlockState newState = world.getBlockState(pos);
            // Only log if the block actually changed
            if (newState.getBlock() != oldState.getBlock()) {
                NaturalEventListener.processFireSpread(serverWorld, pos, oldState, newState);
            }
        } catch (Exception e) {
            // Silently fail
        }
    }

    /**
     * Track when fire burns out during a scheduled tick (fire → air transition).
     * The `state` parameter IS the old fire state, so no HEAD capture needed.
     */
    @Inject(method = "scheduledTick", at = @At("TAIL"))
    private void onFireTick(BlockState state, ServerWorld world, BlockPos pos,
            Random random, CallbackInfo ci) {
        try {
            BlockState currentState = world.getBlockState(pos);
            // If fire went out (position is now air)
            if (currentState.isAir()) {
                NaturalEventListener.processFireSpread(world, pos, state, currentState);
            }
        } catch (Exception e) {
            // Silently fail
        }
    }
}