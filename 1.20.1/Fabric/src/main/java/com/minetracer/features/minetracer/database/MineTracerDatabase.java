package com.minetracer.features.minetracer.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * MineTracer Database Management System
 * Based on CoreProtect's optimized database design
 */
public class MineTracerDatabase {
    
    private static final String DATABASE_VERSION = "1.0.0";
    private static final Path DATABASE_PATH = Path.of("config", "minetracer", "database.db");
    private static final ReadWriteLock CONNECTION_LOCK = new ReentrantReadWriteLock();
    
    // Database table constants
    public static final int CONTAINER = 0;
    public static final int BLOCK = 1;
    public static final int SIGN = 2;
    public static final int KILL = 3;
    public static final int ITEM_PICKUP_DROP = 4;
    public static final int USER = 5;
    public static final int WORLD = 6;
    
    private static volatile boolean databaseInitialized = false;
    private static volatile boolean shutdownInProgress = false;
    
    /**
     * Get a database connection with proper locking
     */
    public static Connection getConnection() {
        return getConnection(false, 1000);
    }
    
    public static Connection getConnection(boolean force, int waitTime) {
        Connection connection = null;
        
        try {
            if (!force && shutdownInProgress) {
                return connection;
            }
            
            // Ensure SQLite driver is loaded
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                System.err.println("[MineTracer] SQLite JDBC driver not found: " + e.getMessage());
                return null;
            }
            
            // Ensure database directory exists
            Files.createDirectories(DATABASE_PATH.getParent());
            
            // Create SQLite connection
            String database = "jdbc:sqlite:" + DATABASE_PATH.toAbsolutePath();
            connection = DriverManager.getConnection(database);
            
            // Enable WAL mode for better concurrency
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("PRAGMA journal_mode=WAL;");
                statement.executeUpdate("PRAGMA synchronous=NORMAL;");
                statement.executeUpdate("PRAGMA cache_size=10000;");
                statement.executeUpdate("PRAGMA temp_store=MEMORY;");
            }
            
        } catch (Exception e) {
            System.err.println("[MineTracer] Database connection error: " + e.getMessage());
            e.printStackTrace();
        }
        
        return connection;
    }
    
    /**
     * Initialize database tables and indexes
     */
    public static boolean initializeDatabase() {
        if (databaseInitialized) {
            return true;
        }
        
        CONNECTION_LOCK.writeLock().lock();
        try {
            if (databaseInitialized) {
                return true;
            }
            
            try (Connection connection = getConnection(true, 0)) {
                if (connection == null) {
                    return false;
                }
                
                Statement statement = connection.createStatement();
                
                // Create all tables
                createContainerTable(statement);
                createBlockTable(statement);
                createSignTable(statement);
                createKillTable(statement);
                createItemPickupDropTable(statement);
                createUserTable(statement);
                createWorldTable(statement);
                createVersionTable(statement);
                
                // Create indexes for optimal query performance
                createIndexes(statement);
                
                // Initialize version tracking
                initializeVersion(statement);
                
                statement.close();
                databaseInitialized = true;
                
                System.out.println("[MineTracer] Database initialized successfully with optimized schema");
                return true;
                
            } catch (Exception e) {
                System.err.println("[MineTracer] Failed to initialize database: " + e.getMessage());
                e.printStackTrace();
                return false;
            }
            
        } finally {
            CONNECTION_LOCK.writeLock().unlock();
        }
    }
    
    private static void createContainerTable(Statement statement) throws SQLException {
        statement.executeUpdate(
            "CREATE TABLE IF NOT EXISTS minetracer_container (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "time INTEGER NOT NULL, " +
            "user INTEGER NOT NULL, " +
            "wid INTEGER NOT NULL, " +
            "x INTEGER NOT NULL, " +
            "y INTEGER NOT NULL, " +
            "z INTEGER NOT NULL, " +
            "type INTEGER NOT NULL, " +
            "data BLOB, " +
            "amount INTEGER NOT NULL, " +
            "metadata BLOB, " +
            "action INTEGER NOT NULL, " +
            "rolled_back INTEGER DEFAULT 0" +
            ");"
        );
    }
    
    private static void createBlockTable(Statement statement) throws SQLException {
        statement.executeUpdate(
            "CREATE TABLE IF NOT EXISTS minetracer_block (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "time INTEGER NOT NULL, " +
            "user INTEGER NOT NULL, " +
            "wid INTEGER NOT NULL, " +
            "x INTEGER NOT NULL, " +
            "y INTEGER NOT NULL, " +
            "z INTEGER NOT NULL, " +
            "type TEXT NOT NULL, " +
            "data TEXT, " +
            "nbt TEXT, " +
            "action TEXT NOT NULL, " +
            "rolled_back INTEGER DEFAULT 0" +
            ");"
        );
    }
    
    private static void createSignTable(Statement statement) throws SQLException {
        statement.executeUpdate(
            "CREATE TABLE IF NOT EXISTS minetracer_sign (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "time INTEGER NOT NULL, " +
            "user INTEGER NOT NULL, " +
            "wid INTEGER NOT NULL, " +
            "x INTEGER NOT NULL, " +
            "y INTEGER NOT NULL, " +
            "z INTEGER NOT NULL, " +
            "action TEXT NOT NULL, " +
            "text TEXT, " +
            "nbt TEXT, " +
            "rolled_back INTEGER DEFAULT 0" +
            ");"
        );
    }
    
    private static void createKillTable(Statement statement) throws SQLException {
        statement.executeUpdate(
            "CREATE TABLE IF NOT EXISTS minetracer_kill (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "time INTEGER NOT NULL, " +
            "killer_user INTEGER NOT NULL, " +
            "victim_name TEXT NOT NULL, " +
            "wid INTEGER NOT NULL, " +
            "x INTEGER NOT NULL, " +
            "y INTEGER NOT NULL, " +
            "z INTEGER NOT NULL, " +
            "rolled_back INTEGER DEFAULT 0" +
            ");"
        );
    }
    
    private static void createItemPickupDropTable(Statement statement) throws SQLException {
        statement.executeUpdate(
            "CREATE TABLE IF NOT EXISTS minetracer_item (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "time INTEGER NOT NULL, " +
            "user INTEGER NOT NULL, " +
            "wid INTEGER NOT NULL, " +
            "x INTEGER NOT NULL, " +
            "y INTEGER NOT NULL, " +
            "z INTEGER NOT NULL, " +
            "type INTEGER NOT NULL, " +
            "data BLOB, " +
            "amount INTEGER NOT NULL, " +
            "action INTEGER NOT NULL, " +
            "rolled_back INTEGER DEFAULT 0" +
            ");"
        );
    }
    
    private static void createUserTable(Statement statement) throws SQLException {
        statement.executeUpdate(
            "CREATE TABLE IF NOT EXISTS minetracer_user (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "time INTEGER NOT NULL, " +
            "user TEXT NOT NULL UNIQUE, " +
            "uuid TEXT NOT NULL" +
            ");"
        );
    }
    
    private static void createWorldTable(Statement statement) throws SQLException {
        statement.executeUpdate(
            "CREATE TABLE IF NOT EXISTS minetracer_world (" +
            "id INTEGER PRIMARY KEY, " +
            "world TEXT NOT NULL UNIQUE" +
            ");"
        );
    }
    
    private static void createVersionTable(Statement statement) throws SQLException {
        statement.executeUpdate(
            "CREATE TABLE IF NOT EXISTS minetracer_version (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "time INTEGER NOT NULL, " +
            "version TEXT NOT NULL" +
            ");"
        );
    }
    
    private static void createIndexes(Statement statement) throws SQLException {
        // Container table indexes (with composite indexes for better query performance)
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_container_location ON minetracer_container(wid,x,z,time);");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_container_lookup ON minetracer_container(wid,x,y,z,time);");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_container_user ON minetracer_container(user,time);");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_container_type ON minetracer_container(type,time);");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_container_time_user ON minetracer_container(time,user);");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_container_rolled_back ON minetracer_container(rolled_back,time);");
        
        // Block table indexes (with composite indexes for better query performance)
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_block_location ON minetracer_block(wid,x,z,time);");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_block_lookup ON minetracer_block(wid,x,y,z,time);");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_block_user ON minetracer_block(user,time);");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_block_type ON minetracer_block(type,time);");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_block_time_user ON minetracer_block(time,user);");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_block_rolled_back ON minetracer_block(rolled_back,time);");
        
        // Sign table indexes
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sign_location ON minetracer_sign(wid,x,z,time);");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sign_user ON minetracer_sign(user,time);");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sign_time ON minetracer_sign(time);");
        
        // Kill table indexes
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_kill_location ON minetracer_kill(wid,x,z,time);");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_kill_killer ON minetracer_kill(killer_user,time);");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_kill_victim ON minetracer_kill(victim_name,time);");
        
        // Item table indexes
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_item_location ON minetracer_item(wid,x,z,time);");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_item_user ON minetracer_item(user,time);");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_item_type ON minetracer_item(type,time);");
        
        // User table indexes
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_user_name ON minetracer_user(user);");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_user_uuid ON minetracer_user(uuid);");
        
        // World table indexes
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_world_id ON minetracer_world(id);");
    }
    
    private static void initializeVersion(Statement statement) throws SQLException {
        // Check if version exists
        ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM minetracer_version WHERE version = '" + DATABASE_VERSION + "'");
        if (rs.next() && rs.getInt(1) == 0) {
            // Insert version
            statement.executeUpdate("INSERT INTO minetracer_version (time, version) VALUES (" + 
                (System.currentTimeMillis() / 1000) + ", '" + DATABASE_VERSION + "')");
        }
        rs.close();
    }
    
    /**
     * Get prepared statement for specific table operation
     */
    public static PreparedStatement prepareStatement(Connection connection, int tableType, boolean returnKeys) {
        PreparedStatement preparedStatement = null;
        
        try {
            String query = getInsertQuery(tableType);
            if (query != null) {
                if (returnKeys) {
                    preparedStatement = connection.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
                } else {
                    preparedStatement = connection.prepareStatement(query);
                }
            }
        } catch (Exception e) {
            System.err.println("[MineTracer] Failed to prepare statement: " + e.getMessage());
            e.printStackTrace();
        }
        
        return preparedStatement;
    }
    
    private static String getInsertQuery(int tableType) {
        switch (tableType) {
            case CONTAINER:
                return "INSERT INTO minetracer_container (time, user, wid, x, y, z, type, data, amount, metadata, action, rolled_back) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            case BLOCK:
                return "INSERT INTO minetracer_block (time, user, wid, x, y, z, type, data, nbt, action, rolled_back) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            case SIGN:
                return "INSERT INTO minetracer_sign (time, user, wid, x, y, z, action, text, nbt, rolled_back) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            case KILL:
                return "INSERT INTO minetracer_kill (time, killer_user, victim_name, wid, x, y, z, rolled_back) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            case ITEM_PICKUP_DROP:
                return "INSERT INTO minetracer_item (time, user, wid, x, y, z, type, data, amount, action, rolled_back) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            case USER:
                return "INSERT OR IGNORE INTO minetracer_user (time, user, uuid) VALUES (?, ?, ?)";
            case WORLD:
                return "INSERT OR IGNORE INTO minetracer_world (id, world) VALUES (?, ?)";
            default:
                return null;
        }
    }
    
    /**
     * Close database connections safely
     */
    public static void shutdown() {
        shutdownInProgress = true;
        CONNECTION_LOCK.writeLock().lock();
        try {
            System.out.println("[MineTracer] Database shutdown completed");
        } finally {
            CONNECTION_LOCK.writeLock().unlock();
        }
    }
    
    /**
     * Check if database is initialized
     */
    public static boolean isInitialized() {
        return databaseInitialized;
    }
}