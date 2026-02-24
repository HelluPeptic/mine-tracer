package com.minetracer;
import com.minetracer.features.minetracer.MineTracer;
import com.minetracer.features.minetracer.NewOptimizedLogStorage;
import com.minetracer.features.minetracer.config.MineTracerConfig;

import net.fabricmc.api.ModInitializer;

public class MineTracerMod implements ModInitializer {
    @Override
    public void onInitialize() {
        // Load configuration first
        MineTracerConfig.load();
        
        // Initialize user cache system
        if (MineTracerConfig.USER_CACHING) {
            System.out.println("[MineTracer] User caching enabled (max: " + MineTracerConfig.USER_CACHE_SIZE + " entries)");
        }
        
        // Register event listeners
        MineTracer.register();
        
        // Register entity and explosion listeners
        com.minetracer.features.minetracer.listeners.ExplosionEventListener.register();

        
        // Initialize new database-based storage system
        NewOptimizedLogStorage.registerServerLifecycle();
        
        // Initialize CoreProtect-style inspector system
        com.minetracer.features.minetracer.inspector.InspectorEventHandler.init();

    }
}
