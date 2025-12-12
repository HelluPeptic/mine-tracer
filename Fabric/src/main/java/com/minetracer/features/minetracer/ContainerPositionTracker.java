package com.minetracer.features.minetracer;

import net.minecraft.util.math.BlockPos;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the last opened container position for each player.
 * Used to determine container positions for SimpleInventory-based containers
 * that don't expose BlockEntity interface.
 */
public class ContainerPositionTracker {
    private static final Map<UUID, BlockPos> lastOpenedContainerPos = new ConcurrentHashMap<>();
    
    public static void setLastOpenedContainer(UUID playerId, BlockPos pos) {
        lastOpenedContainerPos.put(playerId, pos);
    }
    
    public static BlockPos getLastOpenedContainer(UUID playerId) {
        return lastOpenedContainerPos.get(playerId);
    }
    
    public static void clearPlayerData(UUID playerId) {
        lastOpenedContainerPos.remove(playerId);
    }
}
