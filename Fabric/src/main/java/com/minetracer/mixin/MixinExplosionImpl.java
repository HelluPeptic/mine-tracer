package com.minetracer.mixin;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.minetracer.features.minetracer.listeners.ExplosionEventListener;

/**
 * Mirrors CoreProtect's EntityExplodeListener approach:
 * injects at the HEAD of destroyBlocks() which is called with the exact list
 * of blocks that WILL be destroyed, while those blocks still exist in the world.
 * This is the Fabric 1.21.11 equivalent of Bukkit's EntityExplodeEvent.blockList().
 */
@Mixin(ServerExplosion.class)
public class MixinExplosionImpl {

    /**
     * Called by the explosion with the exact list of positions it is about to destroy.
     * All blocks still exist at this point — same as CoreProtect reading block states
     * inside EntityExplodeEvent before blocks are removed.
     * Uses the Explosion interface methods (getWorld/getEntity) to avoid @Shadow field remapping issues.
     */
    @Inject(method = "interactWithBlocks", at = @At("HEAD"))
    private void onDestroyBlocks(List<BlockPos> positions, CallbackInfo ci) {
        try {
            Explosion explosion = (Explosion) this;
            if (!(explosion.level() instanceof ServerLevel)) {
                return;
            }
            ServerLevel serverWorld = (ServerLevel) explosion.level();
            net.minecraft.world.entity.Entity entity = explosion.getDirectSourceEntity();

            for (BlockPos pos : positions) {
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

