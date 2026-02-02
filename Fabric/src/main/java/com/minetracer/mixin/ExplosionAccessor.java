package com.minetracer.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.explosion.Explosion;

/**
 * Accessor to get private fields from Explosion class
 */
@Mixin(Explosion.class)
public interface ExplosionAccessor {
    
    /**
     * Access the private affectedBlocks field from Explosion
     */
    @Accessor("affectedBlocks")
    List<BlockPos> getAffectedBlocks();
}