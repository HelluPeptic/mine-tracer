package com.minetracer.features.minetracer;
public class MineTracer {
    public static void register() {
        KillEventListener.register();
        OptimizedChestEventListener.register(); // For container breaking
        ContainerInteractionListener.register(); // For container interactions (deposits/withdrawals)
        ItemPickupDropEventListener.register(); // For item pickup/drop tracking
        MineTracerCommand.register();
    }
}
