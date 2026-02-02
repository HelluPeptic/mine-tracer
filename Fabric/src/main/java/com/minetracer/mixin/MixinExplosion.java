package com.minetracer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.minetracer.features.minetracer.listeners.ExplosionEventListener;

import net.minecraft.block.BlockState;
import net.minecraft.entity.TntEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Mixin to track TNT explosions and capture blocks before they're destroyed
 */
@Mixin(TntEntity.class)
public class MixinExplosion {
    
    /**
     * Track TNT explosions and capture blocks BEFORE they're destroyed
     */
    @Inject(method = "explode", at = @At("HEAD"))
    private void onTntExplodePre(CallbackInfo ci) {
        try {
            TntEntity tnt = (TntEntity) (Object) this;
            EntityAccessor entityAccessor = (EntityAccessor) tnt;
            World world = entityAccessor.getWorld();
            
            if (!(world instanceof ServerWorld serverWorld)) {
                return;
            }
            
            BlockPos tntPos = tnt.getBlockPos();
            
            // Scan a radius around the TNT explosion to capture blocks before they're destroyed
            int radius = 4; // TNT explosion radius is roughly 4 blocks
            
            for (int x = -radius; x <= radius; x++) {
                for (int y = -radius; y <= radius; y++) {
                    for (int z = -radius; z <= radius; z++) {
                        BlockPos checkPos = tntPos.add(x, y, z);
                        double distance = tntPos.getSquaredDistance(checkPos);
                        
                        // Only check blocks within explosion radius
                        if (distance <= radius * radius) {
                            BlockState state = serverWorld.getBlockState(checkPos);
                            
                            // Log any non-air block that will be destroyed
                            if (!state.isAir() && state.getBlock() != net.minecraft.block.Blocks.BEDROCK) {
                                ExplosionEventListener.processExplosionBlock(
                                    tnt, 
                                    serverWorld, 
                                    checkPos, 
                                    state
                                );
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