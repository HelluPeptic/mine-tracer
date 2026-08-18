package com.minetracer.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.minetracer.features.minetracer.listeners.ExplosionEventListener;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.explosion.Explosion;

/**
 * Mirrors CoreProtect's EntityExplodeListener approach: injects at the HEAD of
 * affectWorld(), which runs after collectBlocksAndDamageEntities() has already
 * populated the affected-blocks list but before any block is actually destroyed,
 * so all blocks still exist in the world at this point.
 */
@Mixin(Explosion.class)
public class MixinExplosionImpl {

    @Shadow
    private World world;

    @Inject(method = "affectWorld", at = @At("HEAD"))
    private void onAffectWorld(boolean particles, CallbackInfo ci) {
        try {
            if (!(this.world instanceof ServerWorld)) {
                return;
            }
            ServerWorld serverWorld = (ServerWorld) this.world;
            Explosion explosion = (Explosion) (Object) this;
            Entity entity = explosion.getEntity();
            List<BlockPos> affectedBlocks = explosion.getAffectedBlocks();

            for (BlockPos pos : affectedBlocks) {
                BlockState state = serverWorld.getBlockState(pos);
                if (!state.isAir() && state.getBlock() != Blocks.BEDROCK) {
                    ExplosionEventListener.processExplosionBlock(entity, serverWorld, pos, state);
                }
            }
        } catch (Exception e) {
            // Never crash the server, but surface the failure so it isn't silently lost
            System.err.println("[MineTracer] Failed to log explosion block destruction: " + e.getMessage());
        }
    }
}
