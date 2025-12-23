package com.minetracer.features.minetracer;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.minetracer.features.minetracer.database.MineTracerDatabase;
import com.minetracer.features.minetracer.database.MineTracerConsumer;
import com.minetracer.features.minetracer.database.MineTracerLookup;

/**
 * New optimized storage system using CoreProtect-style database approach
 * Maintains compatibility with existing lookup command system
 */
public class NewOptimizedLogStorage {
    
    private static volatile boolean initialized = false;
    private static final Set<UUID> inspectorPlayers = new HashSet<>();
    
    /**
     * Initialize the new storage system
     */
    public static boolean initialize() {
        if (initialized) {
            return true;
        }
        
        // Initialize database
        if (!MineTracerDatabase.initializeDatabase()) {
            System.err.println("[MineTracer] Failed to initialize database");
            return false;
        }
        
        // Start consumer thread
        MineTracerConsumer.startConsumer();
        
        initialized = true;
        System.out.println("[MineTracer] New optimized storage system initialized");
        return true;
    }
    
    /**
     * Shutdown the storage system
     */
    public static void shutdown() {
        if (initialized) {
            MineTracerConsumer.stopConsumer();
            MineTracerDatabase.shutdown();
            MineTracerLookup.shutdown();
            initialized = false;
            System.out.println("[MineTracer] Storage system shutdown completed");
        }
    }
    
    // =========================
    // LOGGING METHODS
    // =========================
    
    /**
     * Log container action (chest, barrel, etc.)
     */
    public static void logContainerAction(String action, PlayerEntity player, BlockPos pos, ItemStack stack) {
        if (stack.isEmpty() || !initialized) {
            return;
        }
        
        String worldName = getWorldName(((com.minetracer.mixin.EntityAccessor)player).getWorld());
        Object[] data = new Object[]{action, player.getName().getString(), pos, stack, worldName};
        
        MineTracerConsumer.queueEntry(MineTracerConsumer.PROCESS_CONTAINER, data, null);
    }
    
    /**
     * Log block action (place, break)
     */
    public static void logBlockAction(String action, PlayerEntity player, BlockPos pos, String blockId, String nbt) {
        if (!initialized) {
            return;
        }
        
        String worldName = getWorldName(((com.minetracer.mixin.EntityAccessor)player).getWorld());
        Object[] data = new Object[]{action, player.getName().getString(), pos, blockId, nbt, worldName};
        
        MineTracerConsumer.queueEntry(MineTracerConsumer.PROCESS_BLOCK, data, null);
    }
    
    /**
     * Log sign action
     */
    public static void logSignAction(String action, PlayerEntity player, BlockPos pos, String text, String nbt) {
        if (!initialized) {
            return;
        }
        
        String playerName = player != null ? player.getName().getString() : "unknown";
        String worldName = player != null ? getWorldName(((com.minetracer.mixin.EntityAccessor)player).getWorld()) : "unknown";
        Object[] data = new Object[]{action, playerName, pos, text, nbt, worldName};
        
        MineTracerConsumer.queueEntry(MineTracerConsumer.PROCESS_SIGN, data, null);
    }
    
    /**
     * Log kill action
     */
    public static void logKillAction(String killerName, String victimName, BlockPos pos, String world) {
        if (!initialized) {
            return;
        }
        
        Object[] data = new Object[]{killerName, victimName, pos, world};
        
        MineTracerConsumer.queueEntry(MineTracerConsumer.PROCESS_KILL, data, null);
    }
    
    /**
     * Log item pickup/drop action
     */
    public static void logItemPickupDropAction(String action, PlayerEntity player, BlockPos pos, ItemStack stack, String world) {
        if (stack.isEmpty() || player == null || !initialized) {
            return;
        }
        
        Object[] data = new Object[]{action, player.getName().getString(), pos, stack, world};
        
        MineTracerConsumer.queueEntry(MineTracerConsumer.PROCESS_ITEM, data, null);
    }
    
    /**
     * Log inventory action (compatibility method)
     */
    public static void logInventoryAction(String action, PlayerEntity player, ItemStack stack) {
        logContainerAction(action, player, BlockPos.ORIGIN, stack);
    }
    
    // =========================
    // LOOKUP METHODS (Async)
    // =========================
    
    /**
     * Get container logs in range (async)
     */
    public static CompletableFuture<List<MineTracerLookup.ContainerLogEntry>> getLogsInRangeAsync(BlockPos center, int range, String worldName) {
        if (!initialized) {
            return CompletableFuture.completedFuture(List.of());
        }
        
        return MineTracerLookup.getContainerLogsInRangeAsync(center, range, null, worldName);
    }
    
    /**
     * Get block logs in range (async)
     */
    public static CompletableFuture<List<MineTracerLookup.BlockLogEntry>> getBlockLogsInRangeAsync(BlockPos center, int range, String userFilter, String worldName) {
        if (!initialized) {
            return CompletableFuture.completedFuture(List.of());
        }
        
        return MineTracerLookup.getBlockLogsInRangeAsync(center, range, userFilter, worldName);
    }
    
    /**
     * Get container logs for user (async)
     */
    public static CompletableFuture<List<MineTracerLookup.ContainerLogEntry>> getContainerLogsForUserAsync(String userFilter) {
        if (!initialized) {
            return CompletableFuture.completedFuture(List.of());
        }
        
        return MineTracerLookup.getContainerLogsForUserAsync(userFilter);
    }
    
    // =========================
    // LOOKUP METHODS (Sync - Compatibility)
    // =========================
    
    /**
     * Get container logs in range (synchronous)
     */
    public static List<MineTracerLookup.ContainerLogEntry> getLogsInRange(BlockPos center, int range, String worldName) {
        if (!initialized) {
            return List.of();
        }
        
        return MineTracerLookup.getLogsInRange(center, range, worldName);
    }
    
    /**
     * Get block logs in range (synchronous)
     */
    public static List<MineTracerLookup.BlockLogEntry> getBlockLogsInRange(BlockPos center, int range, String userFilter, String worldName) {
        if (!initialized) {
            return List.of();
        }
        
        return MineTracerLookup.getBlockLogsInRange(center, range, userFilter, worldName);
    }
    
    // =========================
    // INSPECTOR MODE
    // =========================
    
    /**
     * Set inspector mode for player
     */
    public static void setInspectorMode(ServerPlayerEntity player, boolean enabled) {
        synchronized (inspectorPlayers) {
            if (enabled) {
                inspectorPlayers.add(player.getUuid());
            } else {
                inspectorPlayers.remove(player.getUuid());
            }
        }
    }
    
    /**
     * Check if player is in inspector mode
     */
    public static boolean isInspectorMode(ServerPlayerEntity player) {
        synchronized (inspectorPlayers) {
            return inspectorPlayers.contains(player.getUuid());
        }
    }
    
    /**
     * Toggle inspector mode for player
     */
    public static void toggleInspectorMode(ServerPlayerEntity player) {
        setInspectorMode(player, !isInspectorMode(player));
    }
    
    // =========================
    // UTILITY METHODS
    // =========================
    
    /**
     * Get world name from World object
     */
    private static String getWorldName(World world) {
        if (world == null) {
            return "unknown";
        }
        
        // Get dimension name
        String dimensionKey = world.getRegistryKey().getValue().toString();
        return dimensionKey;
    }
    
    /**
     * Register server lifecycle events
     */
    public static void registerServerLifecycle() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            com.minetracer.features.minetracer.util.ServerRegistry.setServer(server);
            if (!initialize()) {
                System.err.println("[MineTracer] CRITICAL: Failed to initialize storage system!");
            }
        });
        
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            System.out.println("[MineTracer] Server stopping - shutting down storage system...");
            shutdown();
        });
    }
    
    /**
     * Check if storage system is initialized
     */
    public static boolean isInitialized() {
        return initialized;
    }
    
    /**
     * Get queue status information
     */
    public static String getQueueStatus() {
        if (!initialized) {
            return "Storage system not initialized";
        }
        
        int queueSize = MineTracerConsumer.getQueueSize();
        boolean consumerRunning = MineTracerConsumer.isRunning();
        boolean paused = MineTracerConsumer.isPaused();
        
        return String.format("Queue: %d entries, Consumer: %s%s", 
                           queueSize, 
                           consumerRunning ? "running" : "stopped",
                           paused ? " (paused)" : "");
    }
    
    /**
     * Force save - not needed with database approach, but kept for compatibility
     */
    public static void forceSave() {
        // Database writes are handled by consumer in real-time
        System.out.println("[MineTracer] Force save requested - data is automatically persisted to database");
    }
    
    /**
     * Get all unique player names from the database
     */
    public static Set<String> getAllPlayerNames() {
        if (!initialized) {
            return new HashSet<>();
        }
        return MineTracerLookup.getAllPlayerNames();
    }
}