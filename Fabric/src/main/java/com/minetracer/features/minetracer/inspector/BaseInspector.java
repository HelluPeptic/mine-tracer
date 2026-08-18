package com.minetracer.features.minetracer.inspector;

import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import com.minetracer.features.minetracer.config.MineTracerConfig;

/**
 * Base inspector class following CoreProtect's pattern
 * Handles common functionality for all inspector types
 */
public abstract class BaseInspector {
    
    // Track inspector throttling per player like CoreProtect does
    protected static final ConcurrentHashMap<String, Object[]> lookupThrottle = new ConcurrentHashMap<>();
    
    /**
     * Check preconditions before performing inspection
     */
    protected void checkPreconditions(ServerPlayer player) throws InspectionException {
        // Check if database is busy (similar to CoreProtect's throttling)
        String playerName = player.getName().getString();
        Object[] throttleInfo = lookupThrottle.get(playerName);
        
        if (throttleInfo != null) {
            boolean isActive = (boolean) throttleInfo[0];
            long lastTime = (long) throttleInfo[1];
            
            // Use config value for cooldown
            if (isActive || (System.currentTimeMillis() - lastTime) < MineTracerConfig.INSPECTOR_COOLDOWN_MS) {
                throw new InspectionException("§3MineTracer §f- §cDatabase is busy. Please wait.");
            }
        }
    }
    
    /**
     * Start an inspection (set throttling)
     */
    protected void startInspection(ServerPlayer player) {
        String playerName = player.getName().getString();
        lookupThrottle.put(playerName, new Object[] { true, System.currentTimeMillis() });
    }
    
    /**
     * Finish an inspection (clear throttling)
     */
    protected void finishInspection(ServerPlayer player) {
        String playerName = player.getName().getString();
        lookupThrottle.put(playerName, new Object[] { false, System.currentTimeMillis() });
    }
    
    /**
     * Send a formatted message to the player (CoreProtect style)
     */
    protected void sendMessage(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message));
    }
    
    /**
     * Exception class for inspection errors
     */
    public static class InspectionException extends Exception {
        public InspectionException(String message) {
            super(message);
        }
    }
}