package com.minetracer;
import net.fabricmc.api.ModInitializer;
import com.minetracer.features.minetracer.MineTracer;
import com.minetracer.features.minetracer.NewOptimizedLogStorage;
import com.minetracer.features.minetracer.database.MigrationUtility;
import com.minetracer.features.minetracer.config.MineTracerConfig;
import com.minetracer.features.minetracer.cache.UserCache;
import com.minetracer.features.minetracer.KillEventListener;

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
        KillEventListener.register();
        
        // Initialize new database-based storage system
        NewOptimizedLogStorage.registerServerLifecycle();
        
        // Initialize CoreProtect-style inspector system
        com.minetracer.features.minetracer.inspector.InspectorEventHandler.init();
        
        // Attempt to migrate existing JSON data
        try {
            MigrationUtility.migrateFromJSON();
        } catch (Exception e) {
            System.err.println("[MineTracer] Migration failed, but continuing with new system: " + e.getMessage());
        }
        
        System.out.println("[MineTracer] Initialized with CoreProtect-style optimized database system");
    }
}
