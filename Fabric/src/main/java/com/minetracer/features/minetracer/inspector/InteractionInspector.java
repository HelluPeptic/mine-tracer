package com.minetracer.features.minetracer.inspector;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.minetracer.features.minetracer.database.MineTracerLookup;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

/**
 * Interaction inspector for right-click on regular blocks
 * Shows general interaction history at clicked location
 */
public class InteractionInspector extends BaseInspector {
    
    /**
     * Perform interaction lookup for right-clicked block (CoreProtect style)
     */
    public void performInteractionLookup(ServerPlayerEntity player, BlockPos pos) {
        // Run in separate thread like CoreProtect does
        CompletableFuture.runAsync(() -> {
            try {
                checkPreconditions(player);
                startInspection(player);
                
                String worldName = ((com.minetracer.mixin.EntityAccessor)player).getWorld().getRegistryKey().getValue().toString();
                
                // Get recent block logs at this position (shows who placed/broke what)
                CompletableFuture<List<MineTracerLookup.BlockLogEntry>> blockLogsFuture = 
                    MineTracerLookup.getBlockLogsInRangeAsync(pos, 0, null, worldName); // 0 range = exact position
                
                List<MineTracerLookup.BlockLogEntry> blockLogs = blockLogsFuture.get();
                
                if (blockLogs.isEmpty()) {
                    sendMessage(player, "§3MineTracer §f- §7No interaction data found.");
                    return;
                }
                
                // Convert to FlatLogEntry format for paging system integration
                java.util.List<com.minetracer.features.minetracer.MineTracerCommand.FlatLogEntry> flatList = new java.util.ArrayList<>();
                for (MineTracerLookup.BlockLogEntry entry : blockLogs) {
                    flatList.add(new com.minetracer.features.minetracer.MineTracerCommand.FlatLogEntry(entry, "block"));
                }
                
                // Store results in the paging system like regular lookup commands
                String inspectorQuery = "inspector:interaction:" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
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
                sendMessage(player, "§3MineTracer §f- §cError performing interaction lookup.");
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