package com.minetracer.features.minetracer.cache;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.minetracer.features.minetracer.config.MineTracerConfig;
import com.minetracer.features.minetracer.database.MineTracerDatabase;

/**
 * User cache system for improved lookup performance
 * Caches user ID <-> username and UUID mappings
 */
public class UserCache {
    
    // Cache maps (CoreProtect style)
    private static final Map<Integer, String> userIdToName = new ConcurrentHashMap<>();
    private static final Map<String, Integer> userNameToId = new ConcurrentHashMap<>();
    private static final Map<Integer, UUID> userIdToUuid = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> userUuidToId = new ConcurrentHashMap<>();
    
    // LRU tracking
    private static final Map<Integer, Long> lastAccessTime = new ConcurrentHashMap<>();
    private static long accessCounter = 0;
    
    /**
     * Get user ID by username, loading from database if not cached
     */
    public static int getUserId(String username) {
        if (!MineTracerConfig.USER_CACHING) {
            return loadUserIdFromDatabase(username);
        }
        
        Integer cached = userNameToId.get(username.toLowerCase());
        if (cached != null) {
            updateAccessTime(cached);
            return cached;
        }
        
        int userId = loadUserIdFromDatabase(username);
        if (userId > 0) {
            cacheUser(userId, username, null);
        }
        
        return userId;
    }
    
    /**
     * Get user ID by UUID, loading from database if not cached
     */
    public static int getUserId(UUID uuid) {
        if (!MineTracerConfig.USER_CACHING) {
            return loadUserIdFromDatabase(uuid);
        }
        
        Integer cached = userUuidToId.get(uuid);
        if (cached != null) {
            updateAccessTime(cached);
            return cached;
        }
        
        int userId = loadUserIdFromDatabase(uuid);
        if (userId > 0) {
            cacheUser(userId, null, uuid);
        }
        
        return userId;
    }
    
    /**
     * Get username by user ID, loading from database if not cached
     */
    public static String getUsername(int userId) {
        if (!MineTracerConfig.USER_CACHING) {
            return loadUsernameFromDatabase(userId);
        }
        
        String cached = userIdToName.get(userId);
        if (cached != null) {
            updateAccessTime(userId);
            return cached;
        }
        
        String username = loadUsernameFromDatabase(userId);
        if (username != null) {
            cacheUser(userId, username, null);
        }
        
        return username;
    }
    
    /**
     * Get UUID by user ID, loading from database if not cached
     */
    public static UUID getUuid(int userId) {
        if (!MineTracerConfig.USER_CACHING) {
            return loadUuidFromDatabase(userId);
        }
        
        UUID cached = userIdToUuid.get(userId);
        if (cached != null) {
            updateAccessTime(userId);
            return cached;
        }
        
        UUID uuid = loadUuidFromDatabase(userId);
        if (uuid != null) {
            cacheUser(userId, null, uuid);
        }
        
        return uuid;
    }
    
    /**
     * Cache a user entry
     */
    public static void cacheUser(int userId, String username, UUID uuid) {
        if (!MineTracerConfig.USER_CACHING) {
            return;
        }
        
        // Check cache size and evict LRU if needed
        if (userIdToName.size() >= MineTracerConfig.USER_CACHE_SIZE) {
            evictLRU();
        }
        
        if (username != null) {
            userIdToName.put(userId, username);
            userNameToId.put(username.toLowerCase(), userId);
        }
        
        if (uuid != null) {
            userIdToUuid.put(userId, uuid);
            userUuidToId.put(uuid, userId);
        }
        
        updateAccessTime(userId);
    }
    
    /**
     * Clear all cached data
     */
    public static void clearCache() {
        userIdToName.clear();
        userNameToId.clear();
        userIdToUuid.clear();
        userUuidToId.clear();
        lastAccessTime.clear();
        accessCounter = 0;
    }
    
    /**
     * Get cache statistics
     */
    public static String getCacheStats() {
        return String.format("UserCache: %d entries, %d names, %d UUIDs", 
            userIdToName.size(), userNameToId.size(), userUuidToId.size());
    }
    
    // ===== Private helper methods =====
    
    private static void updateAccessTime(int userId) {
        lastAccessTime.put(userId, ++accessCounter);
    }
    
    private static void evictLRU() {
        // Find least recently used entry
        int lruUserId = -1;
        long oldestAccess = Long.MAX_VALUE;
        
        for (Map.Entry<Integer, Long> entry : lastAccessTime.entrySet()) {
            if (entry.getValue() < oldestAccess) {
                oldestAccess = entry.getValue();
                lruUserId = entry.getKey();
            }
        }
        
        if (lruUserId > 0) {
            // Remove from all caches
            String username = userIdToName.remove(lruUserId);
            UUID uuid = userIdToUuid.remove(lruUserId);
            
            if (username != null) {
                userNameToId.remove(username.toLowerCase());
            }
            if (uuid != null) {
                userUuidToId.remove(uuid);
            }
            
            lastAccessTime.remove(lruUserId);
        }
    }
    
    private static int loadUserIdFromDatabase(String username) {
        try (Connection conn = MineTracerDatabase.getConnection()) {
            if (conn == null) return -1;
            
            String query = "SELECT id FROM minetracer_user WHERE name = ? COLLATE NOCASE LIMIT 1";
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, username);
                
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("id");
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[MineTracer] Failed to load user ID for " + username + ": " + e.getMessage());
        }
        
        return -1;
    }
    
    private static int loadUserIdFromDatabase(UUID uuid) {
        try (Connection conn = MineTracerDatabase.getConnection()) {
            if (conn == null) return -1;
            
            String query = "SELECT id FROM minetracer_user WHERE uuid = ? LIMIT 1";
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, uuid.toString());
                
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("id");
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[MineTracer] Failed to load user ID for " + uuid + ": " + e.getMessage());
        }
        
        return -1;
    }
    
    private static String loadUsernameFromDatabase(int userId) {
        try (Connection conn = MineTracerDatabase.getConnection()) {
            if (conn == null) return null;
            
            String query = "SELECT name FROM minetracer_user WHERE id = ? LIMIT 1";
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setInt(1, userId);
                
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("name");
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[MineTracer] Failed to load username for ID " + userId + ": " + e.getMessage());
        }
        
        return null;
    }
    
    private static UUID loadUuidFromDatabase(int userId) {
        try (Connection conn = MineTracerDatabase.getConnection()) {
            if (conn == null) return null;
            
            String query = "SELECT uuid FROM minetracer_user WHERE id = ? LIMIT 1";
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setInt(1, userId);
                
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String uuidStr = rs.getString("uuid");
                        return uuidStr != null ? UUID.fromString(uuidStr) : null;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[MineTracer] Failed to load UUID for ID " + userId + ": " + e.getMessage());
        }
        
        return null;
    }
}
