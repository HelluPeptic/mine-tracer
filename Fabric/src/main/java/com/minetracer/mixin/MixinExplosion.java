package com.minetracer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.entity.TntEntity;

/**
 * Superseded by MixinExplosionImpl which mirrors CoreProtect's approach:
 * hooking into ExplosionImpl.destroyBlocks() for all explosion types.
 */
@Mixin(TntEntity.class)
public class MixinExplosion {
    // Empty - TNT explosions are now handled by MixinExplosionImpl
}
