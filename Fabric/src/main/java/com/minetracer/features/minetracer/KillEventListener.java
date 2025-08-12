package com.minetracer.features.minetracer;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
public class KillEventListener {
    public static void register() {
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((world, entity, killedEntity) -> {
            if (!(entity instanceof LivingEntity)) return;
            LivingEntity killer = (LivingEntity) entity;
            if (!(killer instanceof PlayerEntity)) return;
            String killerName = killer.getName().getString();
            String victimName = killedEntity.getName().getString();
            BlockPos pos = killedEntity.getBlockPos();
            String worldName = world.getRegistryKey().getValue().toString();
            OptimizedLogStorage.logKillAction(killerName, victimName, pos, worldName);
        });
    }
}
