package com.minetracer.features.minetracer.inspector;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.minetracer.features.minetracer.database.MineTracerLookup;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.Registries;

/**
 * Container inspector for right-click on container blocks
 * Shows container transaction history (what items were added/removed)
 */
public class ContainerInspector extends BaseInspector {
    
    /**
     * Perform container lookup for right-clicked container (CoreProtect style)
     */
    public void performContainerLookup(ServerPlayerEntity player, BlockPos pos) {
        // Run in separate thread like CoreProtect does
        CompletableFuture.runAsync(() -> {
            try {
                checkPreconditions(player);
                startInspection(player);
                
                String worldName = player.getServerWorld().getRegistryKey().getValue().toString();
                
                // Get recent container logs at this position (limit to recent activity)
                CompletableFuture<List<MineTracerLookup.ContainerLogEntry>> containerLogsFuture = 
                    MineTracerLookup.getContainerLogsInRangeAsync(pos, 0, null, worldName); // 0 range = exact position
                
                List<MineTracerLookup.ContainerLogEntry> containerLogs = containerLogsFuture.get();
                
                if (containerLogs.isEmpty()) {
                    // Try a 2-block radius search (handles double chests properly)
                    System.out.println("[MineTracer] No exact match, trying 2-block radius for double chests...");
                    CompletableFuture<List<MineTracerLookup.ContainerLogEntry>> nearbyLogsFuture = 
                        MineTracerLookup.getContainerLogsInRangeAsync(pos, 2, null, worldName);
                    List<MineTracerLookup.ContainerLogEntry> nearbyLogs = nearbyLogsFuture.get();
                    
                    if (!nearbyLogs.isEmpty()) {
                        System.out.println("[MineTracer] Found " + nearbyLogs.size() + " container logs within 2 blocks");
                        containerLogs = nearbyLogs; // Use the nearby logs
                    } else {
                        // Let's also check if there are any container logs at all in the database
                        try {
                            List<MineTracerLookup.ContainerLogEntry> allLogs = MineTracerLookup.getContainerLogsInRangeAsync(pos, 100, null, worldName).get();
                            System.out.println("[MineTracer] Found " + allLogs.size() + " container logs in 100-block range");
                            sendMessage(player, "§3MineTracer §f- §7No container data found at exact position. Found " + allLogs.size() + " entries in 100-block range.");
                        } catch (Exception e2) {
                            System.out.println("[MineTracer] Error checking broader range: " + e2.getMessage());
                            sendMessage(player, "§3MineTracer §f- §7No container data found.");
                        }
                        return;
                    }
                }
                
                if (containerLogs.isEmpty()) {
                    return; // Still no logs after nearby search
                }
                
                // Convert to FlatLogEntry format for paging system integration
                java.util.List<com.minetracer.features.minetracer.MineTracerCommand.FlatLogEntry> flatList = new java.util.ArrayList<>();
                for (MineTracerLookup.ContainerLogEntry entry : containerLogs) {
                    flatList.add(new com.minetracer.features.minetracer.MineTracerCommand.FlatLogEntry(entry, "container"));
                }
                
                // Store results in the paging system like regular lookup commands
                String inspectorQuery = "inspector:container:" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
                com.minetracer.features.minetracer.MineTracerCommand.QueryContext queryContext = 
                    new com.minetracer.features.minetracer.MineTracerCommand.QueryContext(flatList, inspectorQuery, pos);
                com.minetracer.features.minetracer.MineTracerCommand.lastQueries.put(player.getUuid(), queryContext);
                
                // Display first page using existing display system
                com.minetracer.features.minetracer.MineTracerCommand.displayPage(
                    player.getCommandSource(), flatList, 1, queryContext.entriesPerPage);
                
                if (flatList.size() > queryContext.entriesPerPage) {
                    sendMessage(player, "§3MineTracer §f- §7Use §6/minetracer page <number> §7to view more results.");
                }
                
            } catch (InspectionException e) {
                sendMessage(player, e.getMessage());
            } catch (Exception e) {
                sendMessage(player, "§3MineTracer §f- §cError performing container lookup.");
                e.printStackTrace();
            } finally {
                finishInspection(player);
            }
        });
    }
    
    /**
     * Format time difference into human-readable string
     */
    private String getTimeAgo(java.time.Instant timestamp) {
        long seconds = java.time.Duration.between(timestamp, java.time.Instant.now()).getSeconds();
        
        if (seconds < 60) {
            return seconds + "s";
        } else if (seconds < 3600) {
            return (seconds / 60) + "m";
        } else if (seconds < 86400) {
            return (seconds / 3600) + "h";
        } else {
            return (seconds / 86400) + "d";
        }
    }
}