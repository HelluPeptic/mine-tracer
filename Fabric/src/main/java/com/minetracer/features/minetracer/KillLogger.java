package com.minetracer.features.minetracer;

// KillLogger is now unused; kill logging is handled by a mixin on ServerPlayerEntity#onDeath.
public class KillLogger {
    public static void register() {
        // No-op: kill logging is handled by a mixin.
    }
}
