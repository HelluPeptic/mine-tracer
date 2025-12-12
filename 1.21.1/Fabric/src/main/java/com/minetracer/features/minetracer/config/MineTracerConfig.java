package com.minetracer.features.minetracer.config;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration manager for MineTracer
 * Handles loading and accessing configuration values
 */
public class MineTracerConfig {
    
    private static final Path CONFIG_PATH = Path.of("config", "minetracer", "config.yml");
    private static final Map<String, Object> config = new HashMap<>();
    
    // Database settings
    public static int MAX_POOL_SIZE = 10;
    public static boolean ENABLE_WAL = true;
    public static int CACHE_SIZE = 10000;
    public static String SYNCHRONOUS = "NORMAL";
    public static String TEMP_STORE = "MEMORY";
    
    // Logging settings
    public static boolean LOG_CONTAINER_TRANSACTIONS = true;
    public static boolean LOG_BLOCK_CHANGES = true;
    public static boolean LOG_ITEM_PICKUPS = true;
    public static boolean LOG_ITEM_DROPS = true;
    public static boolean LOG_ENTITY_KILLS = true;
    public static boolean LOG_SIGN_TEXT = true;
    public static boolean LOG_CHAT_MESSAGES = false;
    public static boolean LOG_PLAYER_COMMANDS = false;
    
    // Inspector settings
    public static int INSPECTOR_COOLDOWN_MS = 100;
    public static int DEFAULT_PAGE_SIZE = 7;
    public static int MAX_SEARCH_RADIUS = 2;
    public static boolean CLICKABLE_COORDINATES = true;
    
    // Performance settings
    public static int ASYNC_QUEUE_SIZE = 5000;
    public static int BATCH_INSERT_SIZE = 500;
    public static int BATCH_INSERT_INTERVAL = 100;
    public static boolean VERBOSE = false;
    
    // Rollback settings
    public static boolean ROLLBACK_ITEMS = true;
    public static boolean ROLLBACK_ENTITIES = false;
    public static int MAX_RADIUS = 100;
    public static int DEFAULT_RADIUS = 10;
    public static boolean ENABLE_PREVIEW = true;
    
    // Feature flags
    public static boolean USER_CACHING = true;
    public static int USER_CACHE_SIZE = 1000;
    public static boolean HOPPER_TRANSACTIONS = true;
    public static boolean CHECK_UPDATES = true;
    
    /**
     * Load configuration from file
     */
    public static void load() {
        try {
            // Create config directory if it doesn't exist
            Files.createDirectories(CONFIG_PATH.getParent());
            
            // Copy default config if it doesn't exist
            if (!Files.exists(CONFIG_PATH)) {
                copyDefaultConfig();
                System.out.println("[MineTracer] Created default configuration file");
            }
            
            // Parse config file
            parseConfig();
            
            System.out.println("[MineTracer] Configuration loaded successfully");
            
        } catch (Exception e) {
            System.err.println("[MineTracer] Failed to load configuration: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void copyDefaultConfig() throws IOException {
        // Write default configuration
        try (BufferedWriter writer = Files.newBufferedWriter(CONFIG_PATH)) {
            writer.write(getDefaultConfig());
        }
    }
    
    private static void parseConfig() throws IOException {
        if (!Files.exists(CONFIG_PATH)) {
            return;
        }
        
        try (BufferedReader reader = Files.newBufferedReader(CONFIG_PATH)) {
            String line;
            String currentSection = "";
            
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                
                // Skip comments and empty lines
                if (line.startsWith("#") || line.isEmpty()) {
                    continue;
                }
                
                // Parse section headers
                if (line.endsWith(":") && !line.contains(" ")) {
                    currentSection = line.substring(0, line.length() - 1);
                    continue;
                }
                
                // Parse key-value pairs
                if (line.contains(":")) {
                    String[] parts = line.split(":", 2);
                    if (parts.length == 2) {
                        String key = parts[0].trim();
                        String value = parts[1].trim();
                        
                        // Store in config map
                        String fullKey = currentSection.isEmpty() ? key : currentSection + "." + key;
                        config.put(fullKey, parseValue(value));
                    }
                }
            }
        }
        
        // Apply configuration values
        applyConfig();
    }
    
    private static Object parseValue(String value) {
        value = value.trim();
        
        // Remove comments
        int commentIndex = value.indexOf('#');
        if (commentIndex > 0) {
            value = value.substring(0, commentIndex).trim();
        }
        
        // Parse boolean
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        
        // Parse number
        try {
            if (value.contains(".")) {
                return Double.parseDouble(value);
            } else {
                return Integer.parseInt(value);
            }
        } catch (NumberFormatException e) {
            // Not a number
        }
        
        // Return as string
        return value;
    }
    
    private static void applyConfig() {
        // Database
        MAX_POOL_SIZE = getInt("database.max-pool-size", MAX_POOL_SIZE);
        ENABLE_WAL = getBoolean("database.enable-wal", ENABLE_WAL);
        CACHE_SIZE = getInt("database.cache-size", CACHE_SIZE);
        SYNCHRONOUS = getString("database.synchronous", SYNCHRONOUS);
        TEMP_STORE = getString("database.temp-store", TEMP_STORE);
        
        // Logging
        LOG_CONTAINER_TRANSACTIONS = getBoolean("logging.container-transactions", LOG_CONTAINER_TRANSACTIONS);
        LOG_BLOCK_CHANGES = getBoolean("logging.block-changes", LOG_BLOCK_CHANGES);
        LOG_ITEM_PICKUPS = getBoolean("logging.item-pickups", LOG_ITEM_PICKUPS);
        LOG_ITEM_DROPS = getBoolean("logging.item-drops", LOG_ITEM_DROPS);
        LOG_ENTITY_KILLS = getBoolean("logging.entity-kills", LOG_ENTITY_KILLS);
        LOG_SIGN_TEXT = getBoolean("logging.sign-text", LOG_SIGN_TEXT);
        LOG_CHAT_MESSAGES = getBoolean("logging.chat-messages", LOG_CHAT_MESSAGES);
        LOG_PLAYER_COMMANDS = getBoolean("logging.player-commands", LOG_PLAYER_COMMANDS);
        
        // Inspector
        INSPECTOR_COOLDOWN_MS = getInt("inspector.cooldown-ms", INSPECTOR_COOLDOWN_MS);
        DEFAULT_PAGE_SIZE = getInt("inspector.default-page-size", DEFAULT_PAGE_SIZE);
        MAX_SEARCH_RADIUS = getInt("inspector.max-search-radius", MAX_SEARCH_RADIUS);
        CLICKABLE_COORDINATES = getBoolean("inspector.clickable-coordinates", CLICKABLE_COORDINATES);
        
        // Performance
        ASYNC_QUEUE_SIZE = getInt("performance.async-queue-size", ASYNC_QUEUE_SIZE);
        BATCH_INSERT_SIZE = getInt("performance.batch-insert-size", BATCH_INSERT_SIZE);
        BATCH_INSERT_INTERVAL = getInt("performance.batch-insert-interval", BATCH_INSERT_INTERVAL);
        VERBOSE = getBoolean("performance.verbose", VERBOSE);
        
        // Rollback
        ROLLBACK_ITEMS = getBoolean("rollback.rollback-items", ROLLBACK_ITEMS);
        ROLLBACK_ENTITIES = getBoolean("rollback.rollback-entities", ROLLBACK_ENTITIES);
        MAX_RADIUS = getInt("rollback.max-radius", MAX_RADIUS);
        DEFAULT_RADIUS = getInt("rollback.default-radius", DEFAULT_RADIUS);
        ENABLE_PREVIEW = getBoolean("rollback.enable-preview", ENABLE_PREVIEW);
        
        // Features
        USER_CACHING = getBoolean("features.user-caching", USER_CACHING);
        USER_CACHE_SIZE = getInt("features.user-cache-size", USER_CACHE_SIZE);
        HOPPER_TRANSACTIONS = getBoolean("features.hopper-transactions", HOPPER_TRANSACTIONS);
        CHECK_UPDATES = getBoolean("features.check-updates", CHECK_UPDATES);
    }
    
    private static boolean getBoolean(String key, boolean defaultValue) {
        Object value = config.get(key);
        return value instanceof Boolean ? (Boolean) value : defaultValue;
    }
    
    private static int getInt(String key, int defaultValue) {
        Object value = config.get(key);
        return value instanceof Integer ? (Integer) value : defaultValue;
    }
    
    private static String getString(String key, String defaultValue) {
        Object value = config.get(key);
        return value != null ? value.toString() : defaultValue;
    }
    
    private static String getDefaultConfig() {
        return """
# MineTracer Configuration File
# https://github.com/HelluPeptic/mine-tracer

# Database Configuration
database:
  max-pool-size: 10
  enable-wal: true
  cache-size: 10000
  synchronous: NORMAL
  temp-store: MEMORY

# Logging Configuration
logging:
  container-transactions: true
  block-changes: true
  item-pickups: true
  item-drops: true
  entity-kills: true
  sign-text: true
  chat-messages: false
  player-commands: false

# Inspector Configuration
inspector:
  cooldown-ms: 100
  default-page-size: 7
  max-search-radius: 2
  clickable-coordinates: true

# Performance Configuration
performance:
  async-queue-size: 5000
  batch-insert-size: 500
  batch-insert-interval: 100
  verbose: false

# Rollback Configuration
rollback:
  rollback-items: true
  rollback-entities: false
  max-radius: 100
  default-radius: 10
  enable-preview: true

# Feature Flags
features:
  user-caching: true
  user-cache-size: 1000
  hopper-transactions: true
  check-updates: true
""";
    }
}
