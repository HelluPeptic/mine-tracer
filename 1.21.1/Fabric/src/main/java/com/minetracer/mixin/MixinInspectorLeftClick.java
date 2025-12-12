package com.minetracer.mixin;

import com.minetracer.features.minetracer.OptimizedLogStorage;
import com.minetracer.features.minetracer.inspector.InspectorEventHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to handle inspector left-click (attack block) events
 */
@Mixin(ServerPlayerInteractionManager.class)
public class MixinInspectorLeftClick {
    
    @Shadow @Final public ServerPlayerEntity player;
    
    /**
     * Intercept left-click on blocks for inspector mode
     */
    @Inject(method = "tryBreakBlock", at = @At("HEAD"), cancellable = true)
    private void onLeftClickBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        // Check if player is in inspector mode
        if (OptimizedLogStorage.isInspectorMode(player)) {
            // Use new inspector system for left-click
            InspectorEventHandler.onBlockLeftClick(player, pos);
            
            // Cancel the block break event
            cir.setReturnValue(false);
        }
    }
}