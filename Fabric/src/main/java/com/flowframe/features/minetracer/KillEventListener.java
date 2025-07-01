package com.flowframe.features.minetracer;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class KillEventListener {
    public static void register() {
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((world, entity, killedEntity) -> {
            if (!(entity instanceof LivingEntity killer)) return;
            String killerName = killer.getName().getString();
            String victimName = killedEntity.getName().getString();
            BlockPos pos = killedEntity.getBlockPos();
            LogStorage.logKillAction(killerName, victimName, pos, world.getRegistryKey().getValue().toString());
        });
    }
}
