package com.minetracer.features.minetracer;

public class ChatEventListener {
    public static void register() {
        System.out.println("[MineTracer] Chat event listener registered (using test commands for now)");
        
        // For now, chat logging will be tested via /minetracer testchat command
        // Real chat event integration can be added later when we figure out the correct event API
    }
}
