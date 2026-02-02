package com.minetracer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.minetracer.features.minetracer.listeners.ExplosionEventListener;

import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Mixin to track Creeper explosions
 */
@Mixin(CreeperEntity.class)
public class MixinCreeperEntity {
    
    /**
     * Track when creepers explode and capture blocks before they're destroyed
     */
    @Inject(method = "explode", at = @At("HEAD"))
    private void onCreeperExplode(CallbackInfo ci) {
        try {
            CreeperEntity creeper = (CreeperEntity) (Object) this;
            EntityAccessor entityAccessor = (EntityAccessor) creeper;
            if (entityAccessor.getWorld() instanceof ServerWorld serverWorld) {
                BlockPos creeperPos = creeper.getBlockPos();
                
                // Scan radius for blocks that will be destroyed by the explosion
                int radius = 3; // Creeper explosion radius
                
                for (int x = -radius; x <= radius; x++) {
                    for (int y = -radius; y <= radius; y++) {
                        for (int z = -radius; z <= radius; z++) {
                            BlockPos checkPos = creeperPos.add(x, y, z);
                            double distance = creeperPos.getSquaredDistance(checkPos);
                            
                            // Only check blocks within explosion radius
                            if (distance <= radius * radius) {
                                net.minecraft.block.BlockState state = serverWorld.getBlockState(checkPos);
                                
                                // Log any non-air block that will be destroyed
                                if (!state.isAir() && state.getBlock() != net.minecraft.block.Blocks.BEDROCK) {
                                    ExplosionEventListener.processExplosionBlock(
                                        creeper, 
                                        serverWorld, 
                                        checkPos, 
                                        state
                                    );
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Silently fail to avoid crashing
        }
    }
}