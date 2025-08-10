package com.minetracer;

import net.fabricmc.api.ModInitializer;
import com.minetracer.features.minetracer.MineTracerCommand;
import com.minetracer.features.minetracer.ChestEventListener;
import com.minetracer.features.minetracer.KillEventListener;
import com.minetracer.features.minetracer.MineTracer;

public class MineTracerMod implements ModInitializer {
    @Override
    public void onInitialize() {
        MineTracerCommand.register();
        ChestEventListener.register();
        KillEventListener.register();
        MineTracer.register();
        // Register optimized log storage lifecycle hooks with async operations
        com.minetracer.features.minetracer.OptimizedLogStorage.registerServerLifecycle();
    }
}
