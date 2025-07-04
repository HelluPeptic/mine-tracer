package com.minetracer.features.minetracer;

// Entry point for the MineTracer module
public class MineTracer {
    // ...existing code...

    public static void register() {
        KillEventListener.register();
        // ...register other listeners if needed...
    }
}
