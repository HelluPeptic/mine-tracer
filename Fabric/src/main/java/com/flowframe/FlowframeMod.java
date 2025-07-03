package com.flowframe;

/*
MineTracer Mod Features (Optimized):

- High-performance block break/place logging with NBT support and async operations
- Optimized container interaction logging (chest, furnace, etc.) with spatial indexing
- Async sign edit logging with concurrent processing
- Parallel kill event logging with background indexing
- Advanced query system with filters and caching (user, time, range, action, item)
- Optimized rollback functionality with async operations
- High-performance inspector mode for real-time block inspection
- Permission-based commands with async lookup, rollback, and inspection
- Spatial indexing for O(1) range queries
- Caffeine caching for frequently accessed data
- FastUtil collections for memory efficiency
- Parallel processing for large data operations
*/

import net.fabricmc.api.ModInitializer;
import com.flowframe.features.minetracer.MineTracerCommand;
import com.flowframe.features.minetracer.ChestEventListener;
import com.flowframe.features.minetracer.MineTracer;

public class FlowframeMod implements ModInitializer {
    @Override
    public void onInitialize() {
        System.out.println("[MINETRACER] Initializing optimized MineTracer mod");
        MineTracerCommand.register();
        ChestEventListener.register();
        MineTracer.register();
        // Register optimized log storage lifecycle hooks with async operations
        com.flowframe.features.minetracer.LogStorage.registerServerLifecycle();
        System.out.println("[MINETRACER] Optimized MineTracer mod initialized with async operations and spatial indexing");
    }
}
