package com.minetracer.mixin;

import com.minetracer.features.minetracer.CommandEventListener;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.command.ServerCommandSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayerEntity.class)
public class MixinCommandDispatcher {

    @Inject(method = "updatePotionVisibility", at = @At("HEAD"))
    private void minetracer$dummy(CallbackInfo ci) {
        // This is just a dummy mixin to avoid compilation errors
        // Command tracking will be handled differently
    }
}
