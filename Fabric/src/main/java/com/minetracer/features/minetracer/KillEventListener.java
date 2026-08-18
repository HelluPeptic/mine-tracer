package com.minetracer.features.minetracer;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
public class KillEventListener {
    public static void register() {
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((ServerLevel world, Entity entity, LivingEntity killedEntity, DamageSource damageSource) -> {
            if (!(entity instanceof Player)) {
                return;
            }
            String killerName = entity.getName().getString();
            String victimName = killedEntity.getName().getString();
            BlockPos pos = killedEntity.blockPosition();
            String worldName = world.dimension().identifier().toString();
            NewOptimizedLogStorage.logKillAction(killerName, victimName, pos, worldName);
        });
    }
}
