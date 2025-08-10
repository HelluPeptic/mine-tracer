package com.minetracer.features.minetracer;

import net.minecraft.server.command.ServerCommandSource;

public class CommandEventListener {
    public static void register() {
        // Command tracking will be implemented later through mixins
        // For now we have a basic structure ready
        System.out.println("[MineTracer] Command tracking registered (ready for mixin integration)");
    }

    // Method to be called from mixin when a command is executed
    public static void onCommandExecuted(ServerCommandSource source, String command) {
        if (source.getPlayer() != null) {
            String playerName = source.getPlayer().getName().getString();
            OptimizedLogStorage.logCommandAction(playerName, command);
        }
    }
}
