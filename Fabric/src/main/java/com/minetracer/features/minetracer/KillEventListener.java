package com.minetracer.features.minetracer;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
public class KillEventListener {
    public static void register() {
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((ServerWorld world, Entity entity, LivingEntity killedEntity, DamageSource damageSource) -> {
            if (!(entity instanceof PlayerEntity)) {
                return;
            }
            String killerName = entity.getName().getString();
            String victimName = killedEntity.getName().getString();
            BlockPos pos = killedEntity.getBlockPos();
            String worldName = world.getRegistryKey().getValue().toString();
            NewOptimizedLogStorage.logKillAction(killerName, victimName, pos, worldName);
        });
    }
}
