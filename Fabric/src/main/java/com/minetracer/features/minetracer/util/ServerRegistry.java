package com.minetracer.features.minetracer.util;

import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;

/**
 * Helper class to provide access to server registry manager for NBT operations
 */
public class ServerRegistry {
    private static MinecraftServer server;
    
    public static void setServer(MinecraftServer serverInstance) {
        server = serverInstance;
    }
    
    public static MinecraftServer getServer() {
        return server;
    }
    
    public static RegistryWrapper.WrapperLookup getRegistryManager() {
        if (server == null) {
            throw new IllegalStateException("Server not initialized yet");
        }
        return server.getRegistryManager();
    }
}
