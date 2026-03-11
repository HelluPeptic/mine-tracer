package com.minetracer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.minetracer.features.minetracer.listeners.EntityBlockChangeListener;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.RavagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Mixin to track Ravager block breaking
 */
@Mixin(RavagerEntity.class)
public class MixinRavagerEntity {
    
    /**
     * Track when ravagers break blocks
     */
    @Inject(method = "updateMovementInFluid", at = @At("TAIL"))
    private void onRavagerMovement(CallbackInfo ci) {
        try {
            RavagerEntity ravager = (RavagerEntity) (Object) this;
            EntityAccessor entityAccessor = (EntityAccessor) ravager;
            
            if (entityAccessor.getWorld() instanceof ServerWorld world) {
                BlockPos pos = ravager.getBlockPos();
                BlockState currentState = world.getBlockState(pos);
                
                // Check if ravager is in contact with breakable blocks
                // This is a simplified approach - in reality, ravagers break specific blocks
                if (!currentState.isAir() && ravager.isAlive() && ravager.getVelocity().lengthSquared() > 0.01) {
                    EntityBlockChangeListener.processEntityBlockChange(
                        (Entity) ravager,
                        world,
                        pos,
                        currentState,
                        net.minecraft.block.Blocks.AIR.getDefaultState()
                    );
                }
            }
        } catch (Exception e) {
            // Silently fail to avoid crashing
        }
    }
}