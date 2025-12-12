package com.minetracer.features.minetracer.rollback;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.minetracer.features.minetracer.config.MineTracerConfig;
import com.minetracer.features.minetracer.database.MineTracerDatabase;
import com.minetracer.features.minetracer.database.MineTracerLookup;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * Rollback system for MineTracer
 * Handles undoing changes made by players
 */
public class RollbackManager {
    
    /**
     * Rollback context for tracking rollback operations
     */
    public static class RollbackContext {
        public final UUID playerId;
        public final String query;
        public final Instant startTime;
        public int blocksChanged = 0;
        public int containersChanged = 0;
        public int totalChanges = 0;
        
        public RollbackContext(UUID playerId, String query) {
            this.playerId = playerId;
            this.query = query;
            this.startTime = Instant.now();
        }
    }
    
    // Store last rollback for undo functionality
    private static final java.util.Map<UUID, RollbackContext> lastRollbacks = new java.util.concurrent.ConcurrentHashMap<>();
    
    /**
     * Perform a rollback based on query parameters
     */
    public static RollbackContext rollback(ServerPlayerEntity player, String username, 
                                          BlockPos center, int radius, long timeSeconds, 
                                          List<String> actions, String includeFilter) {
        
        RollbackContext context = new RollbackContext(player.getUuid(), 
            "user:" + username + " time:" + timeSeconds + "s radius:" + radius);
        
        try {
            ServerWorld world = player.getServerWorld();
            String worldName = world.getRegistryKey().getValue().toString();
            
            // Get all relevant logs
            List<MineTracerLookup.BlockLogEntry> blockLogs = getBlockLogsForRollback(
                username, worldName, center, radius, timeSeconds, actions, includeFilter);
            
            List<MineTracerLookup.ContainerLogEntry> containerLogs = getContainerLogsForRollback(
                username, worldName, center, radius, timeSeconds, actions, includeFilter);
            
            // Apply rollback changes
            for (MineTracerLookup.BlockLogEntry entry : blockLogs) {
                if (rollbackBlockEntry(world, entry)) {
                    context.blocksChanged++;
                    context.totalChanges++;
                }
            }
            
            for (MineTracerLookup.ContainerLogEntry entry : containerLogs) {
                if (rollbackContainerEntry(world, entry)) {
                    context.containersChanged++;
                    context.totalChanges++;
                }
            }
            
            // Mark entries as rolled back in database
            markAsRolledBack(blockLogs, containerLogs);
            
            // Store for undo
            lastRollbacks.put(player.getUuid(), context);
            
            // Send success message
            player.sendMessage(Text.literal(String.format(
                "§3MineTracer §f- §aRollback complete! §7%d blocks, %d containers",
                context.blocksChanged, context.containersChanged
            )));
            
        } catch (Exception e) {
            player.sendMessage(Text.literal("§3MineTracer §f- §cRollback failed: " + e.getMessage()));
            e.printStackTrace();
        }
        
        return context;
    }
    
    /**
     * Preview a rollback without actually applying it
     */
    public static void previewRollback(ServerPlayerEntity player, String username, 
                                      BlockPos center, int radius, long timeSeconds,
                                      List<String> actions, String includeFilter) {
        
        if (!MineTracerConfig.ENABLE_PREVIEW) {
            player.sendMessage(Text.literal("§3MineTracer §f- §cPreview mode is disabled"));
            return;
        }
        
        try {
            String worldName = player.getServerWorld().getRegistryKey().getValue().toString();
            
            List<MineTracerLookup.BlockLogEntry> blockLogs = getBlockLogsForRollback(
                username, worldName, center, radius, timeSeconds, actions, includeFilter);
            
            List<MineTracerLookup.ContainerLogEntry> containerLogs = getContainerLogsForRollback(
                username, worldName, center, radius, timeSeconds, actions, includeFilter);
            
            int totalChanges = blockLogs.size() + containerLogs.size();
            
            player.sendMessage(Text.literal(String.format(
                "§3MineTracer §f- §ePreview: §7Would rollback %d blocks and %d containers (%d total changes)",
                blockLogs.size(), containerLogs.size(), totalChanges
            )));
            
            player.sendMessage(Text.literal(
                "§3MineTracer §f- §7Run without §6#preview §7to apply changes"
            ));
            
        } catch (Exception e) {
            player.sendMessage(Text.literal("§3MineTracer §f- §cPreview failed: " + e.getMessage()));
            e.printStackTrace();
        }
    }
    
    // ===== Private helper methods =====
    
    private static List<MineTracerLookup.BlockLogEntry> getBlockLogsForRollback(
            String username, String worldName, BlockPos center, int radius, 
            long timeSeconds, List<String> actions, String includeFilter) throws Exception {
        
        List<MineTracerLookup.BlockLogEntry> result = new ArrayList<>();
        
        try (Connection conn = MineTracerDatabase.getConnection()) {
            if (conn == null) return result;
            
            // Build query
            StringBuilder query = new StringBuilder(
                "SELECT * FROM minetracer_block WHERE rolled_back = 0 AND time >= ?");
            
            // Add spatial filter
            if (center != null && radius > 0) {
                query.append(" AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?");
            }
            
            // Add action filter
            if (actions != null && !actions.isEmpty()) {
                query.append(" AND action IN (");
                for (int i = 0; i < actions.size(); i++) {
                    query.append(i > 0 ? ",?" : "?");
                }
                query.append(")");
            }
            
            query.append(" ORDER BY time DESC");
            
            try (PreparedStatement stmt = conn.prepareStatement(query.toString())) {
                int paramIndex = 1;
                
                // Time parameter
                long cutoffTime = Instant.now().getEpochSecond() - timeSeconds;
                stmt.setLong(paramIndex++, cutoffTime);
                
                // Spatial parameters
                if (center != null && radius > 0) {
                    stmt.setInt(paramIndex++, center.getX() - radius);
                    stmt.setInt(paramIndex++, center.getX() + radius);
                    stmt.setInt(paramIndex++, center.getY() - radius);
                    stmt.setInt(paramIndex++, center.getY() + radius);
                    stmt.setInt(paramIndex++, center.getZ() - radius);
                    stmt.setInt(paramIndex++, center.getZ() + radius);
                }
                
                // Action parameters
                if (actions != null && !actions.isEmpty()) {
                    for (String action : actions) {
                        stmt.setString(paramIndex++, action);
                    }
                }
                
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        // Parse entry (simplified)
                        String action = rs.getString("action");
                        String playerName = username; // Would load from user table
                        BlockPos pos = new BlockPos(
                            rs.getInt("x"),
                            rs.getInt("y"),
                            rs.getInt("z")
                        );
                        String blockId = rs.getString("block_id");
                        String nbt = rs.getString("nbt");
                        Instant timestamp = Instant.ofEpochSecond(rs.getLong("time"));
                        
                        result.add(new MineTracerLookup.BlockLogEntry(
                            action, playerName, pos, blockId, nbt, timestamp, false
                        ));
                    }
                }
            }
        }
        
        return result;
    }
    
    private static List<MineTracerLookup.ContainerLogEntry> getContainerLogsForRollback(
            String username, String worldName, BlockPos center, int radius,
            long timeSeconds, List<String> actions, String includeFilter) throws Exception {
        
        // Similar to getBlockLogsForRollback but for containers
        return new ArrayList<>(); // Simplified for now
    }
    
    private static boolean rollbackBlockEntry(ServerWorld world, MineTracerLookup.BlockLogEntry entry) {
        try {
            BlockPos pos = entry.pos;
            
            // Inverse the action
            if ("placed".equals(entry.action)) {
                // Remove the block that was placed
                world.setBlockState(pos, net.minecraft.block.Blocks.AIR.getDefaultState());
                return true;
            } else if ("broke".equals(entry.action)) {
                // Restore the block that was broken
                Block block = Registries.BLOCK.get(new Identifier(entry.blockId));
                if (block != null) {
                    BlockState state = block.getDefaultState();
                    
                    // Apply NBT if available
                    if (entry.nbt != null && !entry.nbt.isEmpty()) {
                        // Would parse and apply NBT data
                    }
                    
                    world.setBlockState(pos, state);
                    return true;
                }
            }
        } catch (Exception e) {
            System.err.println("[MineTracer] Failed to rollback block at " + entry.pos + ": " + e.getMessage());
        }
        
        return false;
    }
    
    private static boolean rollbackContainerEntry(ServerWorld world, MineTracerLookup.ContainerLogEntry entry) {
        if (!MineTracerConfig.ROLLBACK_ITEMS) {
            return false;
        }
        
        try {
            // Inverse container actions
            // "withdrew" → add item back
            // "deposited" → remove item
            
            // This would require accessing the container inventory
            // Implementation depends on container type
            
            return false; // Simplified for now
        } catch (Exception e) {
            System.err.println("[MineTracer] Failed to rollback container at " + entry.pos + ": " + e.getMessage());
        }
        
        return false;
    }
    
    private static void markAsRolledBack(List<MineTracerLookup.BlockLogEntry> blockLogs,
                                        List<MineTracerLookup.ContainerLogEntry> containerLogs) {
        try (Connection conn = MineTracerDatabase.getConnection()) {
            if (conn == null) return;
            
            // Mark block entries
            if (!blockLogs.isEmpty()) {
                StringBuilder query = new StringBuilder("UPDATE minetracer_block SET rolled_back = 1 WHERE id IN (");
                for (int i = 0; i < blockLogs.size(); i++) {
                    query.append(i > 0 ? ",?" : "?");
                }
                query.append(")");
                
                // Would execute update with IDs
            }
            
            // Mark container entries
            if (!containerLogs.isEmpty()) {
                StringBuilder query = new StringBuilder("UPDATE minetracer_container SET rolled_back = 1 WHERE id IN (");
                for (int i = 0; i < containerLogs.size(); i++) {
                    query.append(i > 0 ? ",?" : "?");
                }
                query.append(")");
                
                // Would execute update with IDs
            }
            
        } catch (Exception e) {
            System.err.println("[MineTracer] Failed to mark entries as rolled back: " + e.getMessage());
        }
    }
}
