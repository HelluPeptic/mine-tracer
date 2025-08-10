package com.minetracer;

import net.fabricmc.api.ModInitializer;
import com.minetracer.features.minetracer.MineTracerCommand;
import com.minetracer.features.minetracer.ChestEventListener;
import com.minetracer.features.minetracer.KillEventListener;
import com.minetracer.features.minetracer.CommandEventListener;
import com.minetracer.features.minetracer.ChatEventListener;
import com.minetracer.features.minetracer.MineTracer;

public class MineTracerMod implements ModInitializer {
    @Override
    public void onInitialize() {
        System.out.println("[MineTracer] Starting MineTracer mod initialization...");
        MineTracerCommand.register();
        ChestEventListener.register();
        KillEventListener.register();
        CommandEventListener.register();
        ChatEventListener.register();
        MineTracer.register();
        // Register optimized log storage lifecycle hooks with async operations
        com.minetracer.features.minetracer.OptimizedLogStorage.registerServerLifecycle();
        System.out.println("[MineTracer] MineTracer mod initialization complete!");
    }
}
