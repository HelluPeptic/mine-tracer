package com.minetracer.features.minetracer.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.time.Instant;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.Registries;

/**
 * MineTracer Database Lookup System
 * High-performance queries with proper indexing
 */
public class MineTracerLookup {
    
    private static final ExecutorService queryExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "MineTracer-Lookup");
        t.setDaemon(true);
        return t;
    });
    
    /**
     * Container log entry from database
     */
    public static class ContainerLogEntry {
        public final String action;
        public final String playerName;
        public final BlockPos pos;
        public final ItemStack stack;
        public final Instant timestamp;
        public final boolean rolledBack;
        
        public ContainerLogEntry(String action, String playerName, BlockPos pos, ItemStack stack, 
                               Instant timestamp, boolean rolledBack) {
            this.action = action;
            this.playerName = playerName;
            this.pos = pos;
            this.stack = stack;
            this.timestamp = timestamp;
            this.rolledBack = rolledBack;
        }
    }
    
    /**
     * Block log entry from database
     */
    public static class BlockLogEntry {
        public final String action;
        public final String playerName;
        public final BlockPos pos;
        public final String blockId;
        public final String nbt;
        public final Instant timestamp;
        public final boolean rolledBack;
        
        public BlockLogEntry(String action, String playerName, BlockPos pos, String blockId, 
                           String nbt, Instant timestamp, boolean rolledBack) {
            this.action = action;
            this.playerName = playerName;
            this.pos = pos;
            this.blockId = blockId;
            this.nbt = nbt;
            this.timestamp = timestamp;
            this.rolledBack = rolledBack;
        }
    }
    
    /**
     * Sign log entry from database
     */
    public static class SignLogEntry {
        public final String action;
        public final String playerName;
        public final BlockPos pos;
        public final String text;
        public final String nbt;
        public final Instant timestamp;
        public final boolean rolledBack;
        
        public SignLogEntry(String action, String playerName, BlockPos pos, String text, 
                          String nbt, Instant timestamp, boolean rolledBack) {
            this.action = action;
            this.playerName = playerName;
            this.pos = pos;
            this.text = text;
            this.nbt = nbt;
            this.timestamp = timestamp;
            this.rolledBack = rolledBack;
        }
    }
    
    /**
     * Kill log entry from database
     */
    public static class KillLogEntry {
        public final String action = "kill";
        public final String killerName;
        public final String playerName; // Alias for killerName for compatibility
        public final String victimName;
        public final BlockPos pos;
        public final String world;
        public final Instant timestamp;
        public final boolean rolledBack;
        
        public KillLogEntry(String killerName, String victimName, BlockPos pos, String world, 
                          Instant timestamp, boolean rolledBack) {
            this.killerName = killerName;
            this.playerName = killerName; // For compatibility with command filtering
            this.victimName = victimName;
            this.pos = pos;
            this.world = world;
            this.timestamp = timestamp;
            this.rolledBack = rolledBack;
        }
    }
    
    /**
     * Item pickup/drop log entry from database
     */
    public static class ItemPickupDropLogEntry {
        public final String action;
        public final String playerName;
        public final BlockPos pos;
        public final ItemStack stack;
        public final String world;
        public final Instant timestamp;
        public final boolean rolledBack;
        
        public ItemPickupDropLogEntry(String action, String playerName, BlockPos pos, ItemStack stack, 
                                    String world, Instant timestamp, boolean rolledBack) {
            this.action = action;
            this.playerName = playerName;
            this.pos = pos;
            this.stack = stack;
            this.world = world;
            this.timestamp = timestamp;
            this.rolledBack = rolledBack;
        }
    }
    
    /**
     * Get container logs in range (async)
     */
    public static CompletableFuture<List<ContainerLogEntry>> getContainerLogsInRangeAsync(
            BlockPos center, int range, String userFilter, String worldName) {
        return CompletableFuture.supplyAsync(() -> {
            List<ContainerLogEntry> results = new ArrayList<>();
            
            try (Connection connection = MineTracerDatabase.getConnection()) {
                if (connection == null) return results;
                
                StringBuilder query = new StringBuilder(
                    "SELECT c.time, u.user, c.x, c.y, c.z, c.type, c.data, c.amount, c.action, c.rolled_back " +
                    "FROM minetracer_container c " +
                    "JOIN minetracer_user u ON c.user = u.id " +
                    "JOIN minetracer_world w ON c.wid = w.id " +
                    "WHERE w.world = ? "
                );
                
                List<Object> params = new ArrayList<>();
                params.add(worldName);
                
                if (userFilter != null && !userFilter.isEmpty()) {
                    query.append("AND u.user = ? ");
                    params.add(userFilter);
                }
                
                // Range filter (including Y coordinate for exact matching)
                if (range == 0) {
                    // Exact position match
                    query.append("AND c.x = ? AND c.y = ? AND c.z = ? ");
                    params.add(center.getX());
                    params.add(center.getY());
                    params.add(center.getZ());
                } else {
                    // Range match
                    query.append("AND c.x BETWEEN ? AND ? AND c.y BETWEEN ? AND ? AND c.z BETWEEN ? AND ? ");
                    params.add(center.getX() - range);
                    params.add(center.getX() + range);
                    params.add(center.getY() - range);
                    params.add(center.getY() + range);
                    params.add(center.getZ() - range);
                    params.add(center.getZ() + range);
                }
                
                query.append("ORDER BY c.time DESC LIMIT 1000");
                
                // Debug: Log the query and parameters
                System.out.println("[MineTracer] Container query: " + query.toString());
                System.out.println("[MineTracer] Parameters: " + params.toString());
                
                // Debug: Show some nearby container entries to understand coordinate storage
                if (range == 0) {
                    try (PreparedStatement debugStmt = connection.prepareStatement(
                        "SELECT c.x, c.y, c.z, c.action, u.user FROM minetracer_container c " +
                        "JOIN minetracer_user u ON c.user = u.id " +
                        "JOIN minetracer_world w ON c.wid = w.id " +
                        "WHERE w.world = ? AND c.x BETWEEN ? AND ? AND c.z BETWEEN ? AND ? " +
                        "ORDER BY c.time DESC LIMIT 5")) {
                        debugStmt.setString(1, worldName);
                        debugStmt.setInt(2, center.getX() - 2);
                        debugStmt.setInt(3, center.getX() + 2);
                        debugStmt.setInt(4, center.getZ() - 2);
                        debugStmt.setInt(5, center.getZ() + 2);
                        ResultSet debugRs = debugStmt.executeQuery();
                        System.out.println("[MineTracer] Nearby container entries within 2 blocks:");
                        while (debugRs.next()) {
                            System.out.println("[MineTracer]   " + debugRs.getInt("x") + "," + debugRs.getInt("y") + "," + debugRs.getInt("z") + " - " + debugRs.getString("user") + " action:" + debugRs.getString("action"));
                        }
                    } catch (Exception debugE) {
                        System.out.println("[MineTracer] Debug query failed: " + debugE.getMessage());
                    }
                }
                
                try (PreparedStatement stmt = connection.prepareStatement(query.toString())) {
                    for (int i = 0; i < params.size(); i++) {
                        stmt.setObject(i + 1, params.get(i));
                    }
                    
                    ResultSet rs = stmt.executeQuery();
                    while (rs.next()) {
                        ContainerLogEntry entry = parseContainerEntry(rs, center, range);
                        if (entry != null) {
                            results.add(entry);
                        }
                    }
                }
                
            } catch (Exception e) {
                System.err.println("[MineTracer] Error in container lookup: " + e.getMessage());
                e.printStackTrace();
            }
            
            return results;
        }, queryExecutor);
    }
    
    /**
     * Get block logs in range (async)
     */
    public static CompletableFuture<List<BlockLogEntry>> getBlockLogsInRangeAsync(
            BlockPos center, int range, String userFilter, String worldName) {
        return CompletableFuture.supplyAsync(() -> {
            List<BlockLogEntry> results = new ArrayList<>();
            
            try (Connection connection = MineTracerDatabase.getConnection()) {
                if (connection == null) return results;
                
                StringBuilder query = new StringBuilder(
                    "SELECT b.time, u.user, b.x, b.y, b.z, b.type, b.nbt, b.action, b.rolled_back " +
                    "FROM minetracer_block b " +
                    "JOIN minetracer_user u ON b.user = u.id " +
                    "JOIN minetracer_world w ON b.wid = w.id " +
                    "WHERE w.world = ? "
                );
                
                List<Object> params = new ArrayList<>();
                params.add(worldName);
                
                if (userFilter != null && !userFilter.isEmpty()) {
                    query.append("AND u.user = ? ");
                    params.add(userFilter);
                }
                
                // Range filter (including Y coordinate for exact matching)
                if (range == 0) {
                    // Exact position match
                    query.append("AND b.x = ? AND b.y = ? AND b.z = ? ");
                    params.add(center.getX());
                    params.add(center.getY());
                    params.add(center.getZ());
                } else {
                    // Range match
                    query.append("AND b.x BETWEEN ? AND ? AND b.y BETWEEN ? AND ? AND b.z BETWEEN ? AND ? ");
                    params.add(center.getX() - range);
                    params.add(center.getX() + range);
                    params.add(center.getY() - range);
                    params.add(center.getY() + range);
                    params.add(center.getZ() - range);
                    params.add(center.getZ() + range);
                }
                
                query.append("ORDER BY b.time DESC LIMIT 1000");
                
                try (PreparedStatement stmt = connection.prepareStatement(query.toString())) {
                    for (int i = 0; i < params.size(); i++) {
                        stmt.setObject(i + 1, params.get(i));
                    }
                    
                    ResultSet rs = stmt.executeQuery();
                    while (rs.next()) {
                        BlockLogEntry entry = parseBlockEntry(rs, center, range);
                        if (entry != null) {
                            results.add(entry);
                        }
                    }
                }
                
            } catch (Exception e) {
                System.err.println("[MineTracer] Error in block lookup: " + e.getMessage());
                e.printStackTrace();
            }
            
            return results;
        }, queryExecutor);
    }
    
    /**
     * Get logs for specific user (async)
     */
    public static CompletableFuture<List<ContainerLogEntry>> getContainerLogsForUserAsync(String userName) {
        return CompletableFuture.supplyAsync(() -> {
            List<ContainerLogEntry> results = new ArrayList<>();
            
            try (Connection connection = MineTracerDatabase.getConnection()) {
                if (connection == null) return results;
                
                String query = "SELECT c.time, u.user, c.x, c.y, c.z, c.type, c.data, c.amount, c.action, c.rolled_back " +
                              "FROM minetracer_container c " +
                              "JOIN minetracer_user u ON c.user = u.id " +
                              "WHERE u.user = ? " +
                              "ORDER BY c.time DESC LIMIT 1000";
                
                try (PreparedStatement stmt = connection.prepareStatement(query)) {
                    stmt.setString(1, userName);
                    
                    ResultSet rs = stmt.executeQuery();
                    while (rs.next()) {
                        ContainerLogEntry entry = parseContainerEntry(rs, null, Integer.MAX_VALUE);
                        if (entry != null) {
                            results.add(entry);
                        }
                    }
                }
                
            } catch (Exception e) {
                System.err.println("[MineTracer] Error in user container lookup: " + e.getMessage());
                e.printStackTrace();
            }
            
            return results;
        }, queryExecutor);
    }
    
    /**
     * Parse container entry from ResultSet
     */
    private static ContainerLogEntry parseContainerEntry(ResultSet rs, BlockPos center, int range) throws SQLException {
        long time = rs.getLong("time");
        String user = rs.getString("user");
        int x = rs.getInt("x");
        int y = rs.getInt("y");
        int z = rs.getInt("z");
        int type = rs.getInt("type");
        byte[] data = rs.getBytes("data");
        int amount = rs.getInt("amount");
        int action = rs.getInt("action");
        boolean rolledBack = rs.getInt("rolled_back") > 0;
        
        BlockPos pos = new BlockPos(x, y, z);
        
        // Range check if needed
        if (center != null && pos.getSquaredDistance(center) > range * range) {
            return null;
        }
        
        ItemStack stack = deserializeItemStack(data, type, amount);
        String actionString = action == 1 ? "deposited" : "withdrew";
        
        return new ContainerLogEntry(actionString, user, pos, stack, 
                                   Instant.ofEpochSecond(time), rolledBack);
    }
    
    /**
     * Parse block entry from ResultSet
     */
    private static BlockLogEntry parseBlockEntry(ResultSet rs, BlockPos center, int range) throws SQLException {
        long time = rs.getLong("time");
        String user = rs.getString("user");
        int x = rs.getInt("x");
        int y = rs.getInt("y");
        int z = rs.getInt("z");
        String type = rs.getString("type");
        String nbt = rs.getString("nbt");
        String action = rs.getString("action");
        boolean rolledBack = rs.getInt("rolled_back") > 0;
        
        BlockPos pos = new BlockPos(x, y, z);
        
        // Range check if needed
        if (center != null && pos.getSquaredDistance(center) > range * range) {
            return null;
        }
        
        return new BlockLogEntry(action, user, pos, type, nbt, 
                               Instant.ofEpochSecond(time), rolledBack);
    }
    
    /**
     * Deserialize ItemStack from database
     */
    private static ItemStack deserializeItemStack(byte[] data, int typeId, int amount) {
        try {
            if (data != null && data.length > 0) {
                // Try to parse NBT data
                String nbtString = new String(data, "UTF-8");
                NbtCompound nbt = StringNbtReader.parse(nbtString);
                return ItemStack.fromNbt(nbt);
            } else {
                // Fallback: create from type ID and amount
                return new ItemStack(Registries.ITEM.get(typeId), amount);
            }
        } catch (Exception e) {
            // Fallback: create empty stack
            return ItemStack.EMPTY;
        }
    }
    
    /**
     * Shutdown lookup executor
     */
    public static void shutdown() {
        queryExecutor.shutdown();
    }
    
    // Synchronous wrapper methods for backward compatibility
    public static List<ContainerLogEntry> getLogsInRange(BlockPos center, int range, String worldName) {
        try {
            return getContainerLogsInRangeAsync(center, range, null, worldName).get();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
    
    public static List<BlockLogEntry> getBlockLogsInRange(BlockPos center, int range, String userFilter, String worldName) {
        try {
            return getBlockLogsInRangeAsync(center, range, userFilter, worldName).get();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
    
    /**
     * Get all unique player names from the database
     */
    public static Set<String> getAllPlayerNames() {
        Set<String> playerNames = new HashSet<>();
        
        try (Connection conn = MineTracerDatabase.getConnection()) {
            String sql = "SELECT DISTINCT user FROM minetracer_user";
            
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                
                while (rs.next()) {
                    playerNames.add(rs.getString("user"));
                }
            }
        } catch (SQLException e) {
            System.err.println("[MineTracer] Error getting all player names: " + e.getMessage());
            e.printStackTrace();
        }
        
        return playerNames;
    }

    /**
     * Get block logs for specific user with world filter (async)
     */
    public static CompletableFuture<List<BlockLogEntry>> getBlockLogsForUserAsync(String userName, String worldName) {
        return CompletableFuture.supplyAsync(() -> {
            List<BlockLogEntry> results = new ArrayList<>();
            
            try (Connection connection = MineTracerDatabase.getConnection()) {
                if (connection == null) return results;
                
                String query = "SELECT b.time, u.user, b.x, b.y, b.z, b.type, b.data, b.action, b.rolled_back " +
                              "FROM minetracer_block b " +
                              "JOIN minetracer_user u ON b.user = u.id " +
                              "JOIN minetracer_world w ON b.wid = w.id " +
                              "WHERE u.user = ? AND w.world = ? " +
                              "ORDER BY b.time DESC LIMIT 1000";
                
                PreparedStatement statement = connection.prepareStatement(query);
                statement.setString(1, userName);
                statement.setString(2, worldName);
                
                ResultSet rs = statement.executeQuery();
                while (rs.next()) {
                    BlockLogEntry entry = parseBlockEntry(rs, null, Integer.MAX_VALUE);
                    if (entry != null) {
                        results.add(entry);
                    }
                }
                
            } catch (SQLException e) {
                System.err.println("[MineTracer] Error getting block logs for user: " + e.getMessage());
                e.printStackTrace();
            }
            
            return results;
        });
    }

    /**
     * Get container logs for specific user with world filter (async)
     */
    public static CompletableFuture<List<ContainerLogEntry>> getContainerLogsForUserAsync(String userName, String worldName) {
        return CompletableFuture.supplyAsync(() -> {
            List<ContainerLogEntry> results = new ArrayList<>();
            
            try (Connection connection = MineTracerDatabase.getConnection()) {
                if (connection == null) return results;
                
                String query = "SELECT c.time, u.user, c.x, c.y, c.z, c.type, c.data, c.amount, c.action, c.rolled_back " +
                              "FROM minetracer_container c " +
                              "JOIN minetracer_user u ON c.user = u.id " +
                              "JOIN minetracer_world w ON c.wid = w.id " +
                              "WHERE u.user = ? AND w.world = ? " +
                              "ORDER BY c.time DESC LIMIT 1000";
                
                PreparedStatement statement = connection.prepareStatement(query);
                statement.setString(1, userName);
                statement.setString(2, worldName);
                
                ResultSet rs = statement.executeQuery();
                while (rs.next()) {
                    ContainerLogEntry entry = parseContainerEntry(rs, null, Integer.MAX_VALUE);
                    if (entry != null) {
                        results.add(entry);
                    }
                }
                
            } catch (SQLException e) {
                System.err.println("[MineTracer] Error getting container logs for user: " + e.getMessage());
                e.printStackTrace();
            }
            
            return results;
        });
    }

    /**
     * Get kill logs for specific user with world filter (async)
     */
    public static CompletableFuture<List<KillLogEntry>> getKillLogsForUserAsync(String userName, String worldName) {
        return CompletableFuture.supplyAsync(() -> {
            List<KillLogEntry> results = new ArrayList<>();
            
            try (Connection connection = MineTracerDatabase.getConnection()) {
                if (connection == null) return results;
                
                String query = "SELECT k.time, u.user, k.x, k.y, k.z, k.weapon, k.target, k.rolled_back " +
                              "FROM minetracer_kill k " +
                              "JOIN minetracer_user u ON k.user = u.id " +
                              "JOIN minetracer_world w ON k.wid = w.id " +
                              "WHERE u.user = ? AND w.world = ? " +
                              "ORDER BY k.time DESC LIMIT 1000";
                
                PreparedStatement statement = connection.prepareStatement(query);
                statement.setString(1, userName);
                statement.setString(2, worldName);
                
                ResultSet rs = statement.executeQuery();
                while (rs.next()) {
                    long time = rs.getLong("time");
                    String user = rs.getString("user");
                    int x = rs.getInt("x");
                    int y = rs.getInt("y");
                    int z = rs.getInt("z");
                    String weapon = rs.getString("weapon");
                    String target = rs.getString("target");
                    boolean rolledBack = rs.getInt("rolled_back") > 0;
                    
                    BlockPos pos = new BlockPos(x, y, z);
                    results.add(new KillLogEntry(user, target, pos, worldName, 
                                               Instant.ofEpochSecond(time), rolledBack));
                }
                
            } catch (SQLException e) {
                System.err.println("[MineTracer] Error getting kill logs for user: " + e.getMessage());
                e.printStackTrace();
            }
            
            return results;
        });
    }

    /**
     * Get item pickup/drop logs for specific user with world filter (async)
     */
    public static CompletableFuture<List<ItemPickupDropLogEntry>> getItemPickupDropLogsForUserAsync(String userName, String worldName) {
        return CompletableFuture.supplyAsync(() -> {
            List<ItemPickupDropLogEntry> results = new ArrayList<>();
            
            try (Connection connection = MineTracerDatabase.getConnection()) {
                if (connection == null) return results;
                
                String query = "SELECT i.time, u.user, i.x, i.y, i.z, i.type, i.data, i.amount, i.action, i.rolled_back " +
                              "FROM minetracer_item i " +
                              "JOIN minetracer_user u ON i.user = u.id " +
                              "JOIN minetracer_world w ON i.wid = w.id " +
                              "WHERE u.user = ? AND w.world = ? " +
                              "ORDER BY i.time DESC LIMIT 1000";
                
                PreparedStatement statement = connection.prepareStatement(query);
                statement.setString(1, userName);
                statement.setString(2, worldName);
                
                ResultSet rs = statement.executeQuery();
                while (rs.next()) {
                    long time = rs.getLong("time");
                    String user = rs.getString("user");
                    int x = rs.getInt("x");
                    int y = rs.getInt("y");
                    int z = rs.getInt("z");
                    int type = rs.getInt("type");
                    byte[] data = rs.getBytes("data");
                    int amount = rs.getInt("amount");
                    int action = rs.getInt("action");
                    boolean rolledBack = rs.getInt("rolled_back") > 0;
                    
                    BlockPos pos = new BlockPos(x, y, z);
                    ItemStack stack = deserializeItemStack(data, type, amount);
                    String actionString = action == 1 ? "pickup" : "drop";
                    
                    results.add(new ItemPickupDropLogEntry(actionString, user, pos, stack, worldName,
                                                         Instant.ofEpochSecond(time), rolledBack));
                }
                
            } catch (SQLException e) {
                System.err.println("[MineTracer] Error getting item logs for user: " + e.getMessage());
                e.printStackTrace();
            }
            
            return results;
        });
    }
}