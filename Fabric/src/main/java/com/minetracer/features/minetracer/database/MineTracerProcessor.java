package com.minetracer.features.minetracer.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.Registries;

/**
 * MineTracer Database Processor
 * Handles batch processing of queued log entries
 */
public class MineTracerProcessor {
    
    // User and world ID caches
    private static final Map<String, Integer> userIdCache = new ConcurrentHashMap<>();
    private static final Map<String, Integer> worldIdCache = new ConcurrentHashMap<>();
    
    /**
     * Process a batch of queue entries
     */
    public void processBatch(List<MineTracerConsumer.QueueEntry> batch, Map<Integer, Object> associatedData) {
        if (batch.isEmpty()) {
            return;
        }
        
        try (Connection connection = MineTracerDatabase.getConnection()) {
            if (connection == null) {
                System.err.println("[MineTracer] Failed to get database connection for batch processing");
                return;
            }
            
            // Begin transaction for batch
            connection.setAutoCommit(false);
            
            try {
                processBatchEntries(connection, batch, associatedData);
                connection.commit();
                
                if (batch.size() > 50) {
                    System.out.println("[MineTracer] Processed batch of " + batch.size() + " entries");
                }
                
            } catch (Exception e) {
                connection.rollback();
                System.err.println("[MineTracer] Batch processing failed, rolled back: " + e.getMessage());
                e.printStackTrace();
            }
            
        } catch (Exception e) {
            System.err.println("[MineTracer] Database error during batch processing: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void processBatchEntries(Connection connection, List<MineTracerConsumer.QueueEntry> batch, Map<Integer, Object> associatedData) throws SQLException {
        // Group entries by type for efficient batch processing
        for (MineTracerConsumer.QueueEntry entry : batch) {
            Object data = associatedData.get(entry.id);
            
            switch (entry.processType) {
                case MineTracerConsumer.PROCESS_CONTAINER:
                    processContainerEntry(connection, entry, data);
                    break;
                case MineTracerConsumer.PROCESS_BLOCK:
                    processBlockEntry(connection, entry, data);
                    break;
                case MineTracerConsumer.PROCESS_SIGN:
                    processSignEntry(connection, entry, data);
                    break;
                case MineTracerConsumer.PROCESS_KILL:
                    processKillEntry(connection, entry, data);
                    break;
                case MineTracerConsumer.PROCESS_ITEM:
                    processItemEntry(connection, entry, data);
                    break;
                case MineTracerConsumer.PROCESS_USER:
                    processUserEntry(connection, entry, data);
                    break;
                case MineTracerConsumer.PROCESS_WORLD:
                    processWorldEntry(connection, entry, data);
                    break;
            }
        }
    }
    
    private void processContainerEntry(Connection connection, MineTracerConsumer.QueueEntry entry, Object data) throws SQLException {
        Object[] entryData = entry.data;
        
        String action = (String) entryData[0];
        String playerName = (String) entryData[1];
        BlockPos pos = (BlockPos) entryData[2];
        ItemStack stack = (ItemStack) entryData[3];
        String worldName = (String) entryData[4];
        
        int userId = getUserId(connection, playerName);
        int worldId = getWorldId(connection, worldName);
        int materialId = getMaterialId(stack);
        
        try (PreparedStatement stmt = MineTracerDatabase.prepareStatement(connection, MineTracerDatabase.CONTAINER, false)) {
            stmt.setLong(1, entry.timestamp / 1000); // Convert to seconds
            stmt.setInt(2, userId);
            stmt.setInt(3, worldId);
            stmt.setInt(4, pos.getX());
            stmt.setInt(5, pos.getY());
            stmt.setInt(6, pos.getZ());
            stmt.setInt(7, materialId);
            stmt.setBytes(8, serializeItemStack(stack));
            stmt.setInt(9, stack.getCount());
            stmt.setBytes(10, null); // metadata - not used currently
            stmt.setInt(11, action.equals("deposited") ? 1 : 0); // action: 0=withdrew, 1=deposited
            stmt.setInt(12, 0); // rolled_back
            
            stmt.executeUpdate();
        }
    }
    
    private void processBlockEntry(Connection connection, MineTracerConsumer.QueueEntry entry, Object data) throws SQLException {
        Object[] entryData = entry.data;
        
        String action = (String) entryData[0];
        String playerName = (String) entryData[1];
        BlockPos pos = (BlockPos) entryData[2];
        String blockId = (String) entryData[3];
        String nbt = (String) entryData[4];
        String worldName = (String) entryData[5];
        
        int userId = getUserId(connection, playerName);
        int worldId = getWorldId(connection, worldName);
        
        try (PreparedStatement stmt = MineTracerDatabase.prepareStatement(connection, MineTracerDatabase.BLOCK, false)) {
            stmt.setLong(1, entry.timestamp / 1000);
            stmt.setInt(2, userId);
            stmt.setInt(3, worldId);
            stmt.setInt(4, pos.getX());
            stmt.setInt(5, pos.getY());
            stmt.setInt(6, pos.getZ());
            stmt.setString(7, blockId);
            stmt.setString(8, null); // block data - not used currently
            stmt.setString(9, nbt);
            stmt.setString(10, action);
            stmt.setInt(11, 0); // rolled_back
            
            stmt.executeUpdate();
        }
    }
    
    private void processSignEntry(Connection connection, MineTracerConsumer.QueueEntry entry, Object data) throws SQLException {
        Object[] entryData = entry.data;
        
        String action = (String) entryData[0];
        String playerName = (String) entryData[1];
        BlockPos pos = (BlockPos) entryData[2];
        String text = (String) entryData[3];
        String nbt = (String) entryData[4];
        String worldName = (String) entryData[5];
        
        int userId = getUserId(connection, playerName);
        int worldId = getWorldId(connection, worldName);
        
        try (PreparedStatement stmt = MineTracerDatabase.prepareStatement(connection, MineTracerDatabase.SIGN, false)) {
            stmt.setLong(1, entry.timestamp / 1000);
            stmt.setInt(2, userId);
            stmt.setInt(3, worldId);
            stmt.setInt(4, pos.getX());
            stmt.setInt(5, pos.getY());
            stmt.setInt(6, pos.getZ());
            stmt.setString(7, action);
            stmt.setString(8, text);
            stmt.setString(9, nbt);
            stmt.setInt(10, 0); // rolled_back
            
            stmt.executeUpdate();
        }
    }
    
    private void processKillEntry(Connection connection, MineTracerConsumer.QueueEntry entry, Object data) throws SQLException {
        Object[] entryData = entry.data;
        
        String killerName = (String) entryData[0];
        String victimName = (String) entryData[1];
        BlockPos pos = (BlockPos) entryData[2];
        String worldName = (String) entryData[3];
        
        int killerUserId = getUserId(connection, killerName);
        int worldId = getWorldId(connection, worldName);
        
        try (PreparedStatement stmt = MineTracerDatabase.prepareStatement(connection, MineTracerDatabase.KILL, false)) {
            stmt.setLong(1, entry.timestamp / 1000);
            stmt.setInt(2, killerUserId);
            stmt.setString(3, victimName);
            stmt.setInt(4, worldId);
            stmt.setInt(5, pos.getX());
            stmt.setInt(6, pos.getY());
            stmt.setInt(7, pos.getZ());
            stmt.setInt(8, 0); // rolled_back
            
            stmt.executeUpdate();
        }
    }
    
    private void processItemEntry(Connection connection, MineTracerConsumer.QueueEntry entry, Object data) throws SQLException {
        Object[] entryData = entry.data;
        
        String action = (String) entryData[0];
        String playerName = (String) entryData[1];
        BlockPos pos = (BlockPos) entryData[2];
        ItemStack stack = (ItemStack) entryData[3];
        String worldName = (String) entryData[4];
        
        int userId = getUserId(connection, playerName);
        int worldId = getWorldId(connection, worldName);
        int materialId = getMaterialId(stack);
        
        try (PreparedStatement stmt = MineTracerDatabase.prepareStatement(connection, MineTracerDatabase.ITEM_PICKUP_DROP, false)) {
            stmt.setLong(1, entry.timestamp / 1000);
            stmt.setInt(2, userId);
            stmt.setInt(3, worldId);
            stmt.setInt(4, pos.getX());
            stmt.setInt(5, pos.getY());
            stmt.setInt(6, pos.getZ());
            stmt.setInt(7, materialId);
            stmt.setBytes(8, serializeItemStack(stack));
            stmt.setInt(9, stack.getCount());
            stmt.setInt(10, action.equals("pickup") ? 0 : 1); // action: 0=pickup, 1=drop
            stmt.setInt(11, 0); // rolled_back
            
            stmt.executeUpdate();
        }
    }
    
    private void processUserEntry(Connection connection, MineTracerConsumer.QueueEntry entry, Object data) throws SQLException {
        Object[] entryData = entry.data;
        String userName = (String) entryData[0];
        String uuid = (String) entryData[1];
        
        try (PreparedStatement stmt = MineTracerDatabase.prepareStatement(connection, MineTracerDatabase.USER, false)) {
            stmt.setLong(1, entry.timestamp / 1000);
            stmt.setString(2, userName);
            stmt.setString(3, uuid);
            
            stmt.executeUpdate();
        }
    }
    
    private void processWorldEntry(Connection connection, MineTracerConsumer.QueueEntry entry, Object data) throws SQLException {
        Object[] entryData = entry.data;
        int worldId = (Integer) entryData[0];
        String worldName = (String) entryData[1];
        
        try (PreparedStatement stmt = MineTracerDatabase.prepareStatement(connection, MineTracerDatabase.WORLD, false)) {
            stmt.setInt(1, worldId);
            stmt.setString(2, worldName);
            
            stmt.executeUpdate();
        }
    }
    
    /**
     * Get or create user ID
     */
    private int getUserId(Connection connection, String userName) throws SQLException {
        // Check cache first
        Integer cachedId = userIdCache.get(userName);
        if (cachedId != null) {
            return cachedId;
        }
        
        // Check database
        try (PreparedStatement stmt = connection.prepareStatement("SELECT id FROM minetracer_user WHERE user = ?")) {
            stmt.setString(1, userName);
            ResultSet rs = stmt.executeQuery();
            
            if (rs.next()) {
                int id = rs.getInt(1);
                userIdCache.put(userName, id);
                return id;
            }
        }
        
        // Create new user entry
        try (PreparedStatement stmt = connection.prepareStatement("INSERT INTO minetracer_user (time, user, uuid) VALUES (?, ?, ?)")) {
            stmt.setLong(1, System.currentTimeMillis() / 1000);
            stmt.setString(2, userName);
            stmt.setString(3, "unknown"); // UUID will be updated when available
            stmt.executeUpdate();
            
            // Get the last inserted row ID using SQLite's function
            try (PreparedStatement lastIdStmt = connection.prepareStatement("SELECT last_insert_rowid()")) {
                ResultSet rs = lastIdStmt.executeQuery();
                if (rs.next()) {
                    int id = rs.getInt(1);
                    userIdCache.put(userName, id);
                    return id;
                }
            }
        }
        
        return -1; // Should not happen
    }
    
    /**
     * Get or create world ID
     */
    private int getWorldId(Connection connection, String worldName) throws SQLException {
        // Check cache first
        Integer cachedId = worldIdCache.get(worldName);
        if (cachedId != null) {
            return cachedId;
        }
        
        // Check database
        try (PreparedStatement stmt = connection.prepareStatement("SELECT id FROM minetracer_world WHERE world = ?")) {
            stmt.setString(1, worldName);
            ResultSet rs = stmt.executeQuery();
            
            if (rs.next()) {
                int id = rs.getInt(1);
                worldIdCache.put(worldName, id);
                return id;
            }
        }
        
        // Create new world entry with auto-generated ID
        try (PreparedStatement stmt = connection.prepareStatement("INSERT INTO minetracer_world (world) VALUES (?)")) {
            stmt.setString(1, worldName);
            stmt.executeUpdate();
            
            // Get the last inserted row ID using SQLite's function
            try (PreparedStatement lastIdStmt = connection.prepareStatement("SELECT last_insert_rowid()")) {
                ResultSet rs = lastIdStmt.executeQuery();
                if (rs.next()) {
                    int id = rs.getInt(1);
                    worldIdCache.put(worldName, id);
                    return id;
                }
            }
        }
        
        return -1; // Should not happen
    }
    
    /**
     * Get material ID from ItemStack
     */
    private int getMaterialId(ItemStack stack) {
        return Registries.ITEM.getRawId(stack.getItem());
    }
    
    /**
     * Serialize ItemStack to bytes
     */
    private byte[] serializeItemStack(ItemStack stack) {
        try {
            NbtCompound nbt = (NbtCompound) stack.encodeAllowEmpty(com.minetracer.features.minetracer.util.ServerRegistry.getRegistryManager());
            return nbt.toString().getBytes("UTF-8");
        } catch (Exception e) {
            System.err.println("[MineTracer] Failed to serialize ItemStack: " + e.getMessage());
            return new byte[0];
        }
    }
}