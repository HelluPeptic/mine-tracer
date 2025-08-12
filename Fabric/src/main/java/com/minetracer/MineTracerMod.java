package com.minetracer;
import net.fabricmc.api.ModInitializer;
import com.minetracer.features.minetracer.MineTracer;
public class MineTracerMod implements ModInitializer {
    @Override
    public void onInitialize() {
        MineTracer.register();
        com.minetracer.features.minetracer.OptimizedLogStorage.registerServerLifecycle();
    }
}
