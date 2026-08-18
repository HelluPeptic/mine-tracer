package com.minetracer.mixin;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(ServerPlayerGameMode.class)
public interface ServerPlayerInteractionManagerAccessor {
    @Accessor("player")
    ServerPlayer getPlayer();
}
