package com.minetracer.features.minetracer;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

public class KillEventListener {
    public static void register() {
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((world, entity, killedEntity) -> {
            // Quick early returns to avoid unnecessary processing
            if (!(entity instanceof LivingEntity)) return;
            LivingEntity killer = (LivingEntity) entity;
            
            // Only log if the killer is a player - reduces noise from mob-on-mob kills
            if (!(killer instanceof PlayerEntity)) return;
            
            // Cache frequently accessed values to avoid repeated method calls
            String killerName = killer.getName().getString();
            String victimName = killedEntity.getName().getString();
            BlockPos pos = killedEntity.getBlockPos();
            String worldName = world.getRegistryKey().getValue().toString();
            
            // Offload the logging to async processing immediately
            OptimizedLogStorage.logKillAction(killerName, victimName, pos, worldName);
        });
    }
}
