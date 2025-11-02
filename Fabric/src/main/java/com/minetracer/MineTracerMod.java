package com.minetracer;
import net.fabricmc.api.ModInitializer;
import com.minetracer.features.minetracer.MineTracer;
import com.minetracer.features.minetracer.NewOptimizedLogStorage;
import com.minetracer.features.minetracer.database.MigrationUtility;

public class MineTracerMod implements ModInitializer {
    @Override
    public void onInitialize() {
        // Register event listeners
        MineTracer.register();
        
        // Initialize new database-based storage system
        NewOptimizedLogStorage.registerServerLifecycle();
        
        // Attempt to migrate existing JSON data
        try {
            MigrationUtility.migrateFromJSON();
        } catch (Exception e) {
            System.err.println("[MineTracer] Migration failed, but continuing with new system: " + e.getMessage());
        }
        
        System.out.println("[MineTracer] Initialized with CoreProtect-style optimized database system");
    }
}
