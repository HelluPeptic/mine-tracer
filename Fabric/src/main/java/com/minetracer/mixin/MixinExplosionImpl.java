package com.minetracer.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.minetracer.features.minetracer.listeners.ExplosionEventListener;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.explosion.Explosion;
import net.minecraft.world.explosion.ExplosionImpl;

/**
 * Mirrors CoreProtect's EntityExplodeListener approach:
 * injects at the HEAD of destroyBlocks() which is called with the exact list
 * of blocks that WILL be destroyed, while those blocks still exist in the world.
 * This is the Fabric 1.21.11 equivalent of Bukkit's EntityExplodeEvent.blockList().
 */
@Mixin(ExplosionImpl.class)
public class MixinExplosionImpl {

    /**
     * Called by the explosion with the exact list of positions it is about to destroy.
     * All blocks still exist at this point — same as CoreProtect reading block states
     * inside EntityExplodeEvent before blocks are removed.
     * Uses the Explosion interface methods (getWorld/getEntity) to avoid @Shadow field remapping issues.
     */
    @Inject(method = "destroyBlocks", at = @At("HEAD"))
    private void onDestroyBlocks(List<BlockPos> positions, CallbackInfo ci) {
        try {
            Explosion explosion = (Explosion) this;
            if (!(explosion.getWorld() instanceof ServerWorld)) {
                return;
            }
            ServerWorld serverWorld = (ServerWorld) explosion.getWorld();
            net.minecraft.entity.Entity entity = explosion.getEntity();

            for (BlockPos pos : positions) {
                BlockState state = serverWorld.getBlockState(pos);
                if (!state.isAir() && state.getBlock() != Blocks.BEDROCK) {
                    ExplosionEventListener.processExplosionBlock(entity, serverWorld, pos, state);
                }
            }
        } catch (Exception e) {
            // Never crash the server
        }
    }
}

