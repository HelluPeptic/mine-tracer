package com.minetracer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.entity.mob.CreeperEntity;

/**
 * Creeper explosions are now handled by MixinExplosionImpl which injects into
 * ExplosionImpl.destroyBlocks() - the same method all explosions go through.
 */
@Mixin(CreeperEntity.class)
public class MixinCreeperEntity {
    // Empty - covered by MixinExplosionImpl
}
