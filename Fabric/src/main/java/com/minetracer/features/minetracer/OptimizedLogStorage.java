package com.minetracer.features.minetracer;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.ArrayDeque;
import java.util.Queue;

// High-performance storage with async operations, spatial indexing, and caching
public class OptimizedLogStorage {
    public static class LogEntry {
        public final String action;
        public final String playerName;
        public final BlockPos pos;
        public final ItemStack stack;
        public final Instant timestamp;

        public LogEntry(String action, String playerName, BlockPos pos, ItemStack stack, Instant timestamp) {
            this.action = action;
            this.playerName = playerName;
            this.pos = pos;
            this.stack = stack.copy();
            this.timestamp = timestamp;
        }
    }

    public static class BlockLogEntry {
        public final String action;
        public final String playerName;
        public final BlockPos pos;
        public final String blockId;
        public final String nbt;
        public final Instant timestamp;

        public BlockLogEntry(String action, String playerName, BlockPos pos, String blockId, String nbt,
                Instant timestamp) {
            this.action = action;
            this.playerName = playerName;
            this.pos = pos;
            this.blockId = blockId;
            this.nbt = nbt;
            this.timestamp = timestamp;
        }
    }

    public static class SignLogEntry {
        public final String action;
        public final String playerName;
        public final BlockPos pos;
        public final String text;
        public final String nbt;
        public final Instant timestamp;

        public SignLogEntry(String action, String playerName, BlockPos pos, String text, String nbt,
                Instant timestamp) {
            this.action = action;
            this.playerName = playerName;
            this.pos = pos;
            this.text = text;
            this.nbt = nbt;
            this.timestamp = timestamp;
        }
    }

    public static class KillLogEntry {
        public final String action = "kill";
        public final String killerName;
        public final String victimName;
        public final BlockPos pos;
        public final String world;
        public final Instant timestamp;

        public KillLogEntry(String killerName, String victimName, BlockPos pos, String world, Instant timestamp) {
            this.killerName = killerName;
            this.victimName = victimName;
            this.pos = pos;
            this.world = world;
            this.timestamp = timestamp;
        }
    }

    // Primary storage lists
    private static final List<LogEntry> logs = new ArrayList<>();
    private static final List<BlockLogEntry> blockLogs = new ArrayList<>();
    private static final List<SignLogEntry> signLogs = new ArrayList<>();
    private static final List<KillLogEntry> killLogs = new ArrayList<>();

    // High-performance spatial indexing using FastUtil
    private static final Object2ObjectOpenHashMap<String, List<LogEntry>> playerContainerLogs = new Object2ObjectOpenHashMap<>();
    private static final Object2ObjectOpenHashMap<String, List<BlockLogEntry>> playerBlockLogs = new Object2ObjectOpenHashMap<>();
    private static final Object2ObjectOpenHashMap<String, List<SignLogEntry>> playerSignLogs = new Object2ObjectOpenHashMap<>();
    private static final Object2ObjectOpenHashMap<String, List<KillLogEntry>> playerKillLogs = new Object2ObjectOpenHashMap<>();

    // Chunk-based spatial indexing for O(1) range queries
    private static final Long2ObjectOpenHashMap<List<LogEntry>> chunkContainerLogs = new Long2ObjectOpenHashMap<>();
    private static final Long2ObjectOpenHashMap<List<BlockLogEntry>> chunkBlockLogs = new Long2ObjectOpenHashMap<>();
    private static final Long2ObjectOpenHashMap<List<SignLogEntry>> chunkSignLogs = new Long2ObjectOpenHashMap<>();
    private static final Long2ObjectOpenHashMap<List<KillLogEntry>> chunkKillLogs = new Long2ObjectOpenHashMap<>();

    private static final Path LOG_FILE = Path.of("config", "minetracer", "logs.json");

    // Advanced async processing
    private static final ReadWriteLock dataLock = new ReentrantReadWriteLock();
    private static final Object saveLock = new Object();
    private static volatile boolean hasUnsavedChanges = false;
    private static volatile boolean isShuttingDown = false;
    private static volatile boolean logsLoaded = false;

    // Thread pools for different operations
    private static ScheduledExecutorService saveScheduler;
    private static ExecutorService queryExecutor;
    private static ExecutorService indexingExecutor;

    // Write batching system
    private static final Queue<Runnable> pendingOperations = new ArrayDeque<>();
    private static final int BATCH_SIZE = 100;

    // High-performance caching
    private static com.github.benmanes.caffeine.cache.Cache<String, List<?>> queryCache;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(java.time.Instant.class, new TypeAdapter<java.time.Instant>() {
                @Override
                public void write(JsonWriter out, java.time.Instant value) throws java.io.IOException {
                    out.value(value.toString());
                }

                @Override
                public java.time.Instant read(JsonReader in) throws java.io.IOException {
                    return java.time.Instant.parse(in.nextString());
                }
            })
            .create();

    static {
        // Initialize caches and thread pools
        queryCache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                .maximumSize(5000)
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .build();

        queryExecutor = ForkJoinPool.commonPool();

        indexingExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MineTracer-Indexer");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
    }

    // Convert BlockPos to chunk coordinate for spatial indexing
    private static long getChunkKey(BlockPos pos) {
        return ((long) (pos.getX() >> 4) << 32) | ((long) (pos.getZ() >> 4) & 0xFFFFFFFFL);
    }

    // Async indexing operations
    private static void indexLogEntryAsync(LogEntry entry) {
        indexingExecutor.submit(() -> {
            dataLock.writeLock().lock();
            try {
                playerContainerLogs.computeIfAbsent(entry.playerName, k -> new ArrayList<>()).add(entry);
                long chunkKey = getChunkKey(entry.pos);
                chunkContainerLogs.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(entry);
            } finally {
                dataLock.writeLock().unlock();
            }
        });
    }

    private static void indexBlockLogEntryAsync(BlockLogEntry entry) {
        indexingExecutor.submit(() -> {
            dataLock.writeLock().lock();
            try {
                playerBlockLogs.computeIfAbsent(entry.playerName, k -> new ArrayList<>()).add(entry);
                long chunkKey = getChunkKey(entry.pos);
                chunkBlockLogs.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(entry);
            } finally {
                dataLock.writeLock().unlock();
            }
        });
    }

    private static void indexSignLogEntryAsync(SignLogEntry entry) {
        indexingExecutor.submit(() -> {
            dataLock.writeLock().lock();
            try {
                playerSignLogs.computeIfAbsent(entry.playerName, k -> new ArrayList<>()).add(entry);
                long chunkKey = getChunkKey(entry.pos);
                chunkSignLogs.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(entry);
            } finally {
                dataLock.writeLock().unlock();
            }
        });
    }

    private static void indexKillLogEntryAsync(KillLogEntry entry) {
        indexingExecutor.submit(() -> {
            dataLock.writeLock().lock();
            try {
                playerKillLogs.computeIfAbsent(entry.killerName, k -> new ArrayList<>()).add(entry);
                long chunkKey = getChunkKey(entry.pos);
                chunkKillLogs.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(entry);
            } finally {
                dataLock.writeLock().unlock();
            }
        });
    }

    // Async log loading with spatial indexing
    private static CompletableFuture<Void> loadAllLogsAsync() {
        return CompletableFuture.runAsync(() -> {
            dataLock.writeLock().lock();
            try {
                logsLoaded = false; // Reset flag before loading
                
                // Clear all data structures
                logs.clear();
                blockLogs.clear();
                signLogs.clear();
                killLogs.clear();

                playerContainerLogs.clear();
                playerBlockLogs.clear();
                playerSignLogs.clear();
                playerKillLogs.clear();
                chunkContainerLogs.clear();
                chunkBlockLogs.clear();
                chunkSignLogs.clear();
                chunkKillLogs.clear();

                Files.createDirectories(LOG_FILE.getParent());
                if (Files.exists(LOG_FILE)) {
                    String json = Files.readString(LOG_FILE, StandardCharsets.UTF_8);
                    Type type = new TypeToken<Map<String, Object>>() {
                    }.getType();
                    Map<String, Object> allLogs = GSON.fromJson(json, type);

                    if (allLogs != null) {
                        // Load container logs with immediate indexing
                        List<Map<String, Object>> containerList = (List<Map<String, Object>>) allLogs
                                .getOrDefault("container", new ArrayList<>());
                        for (Map<String, Object> obj : containerList) {
                            String[] posParts = ((String) obj.get("pos")).split(",");
                            BlockPos pos = new BlockPos(Integer.parseInt(posParts[0]), Integer.parseInt(posParts[1]),
                                    Integer.parseInt(posParts[2]));
                            try {
                                net.minecraft.nbt.NbtCompound nbt = net.minecraft.nbt.StringNbtReader
                                        .parse((String) obj.get("itemNbt"));
                                ItemStack stack = ItemStack.fromNbt(nbt);
                                LogEntry entry = new LogEntry((String) obj.get("action"),
                                        (String) obj.get("playerName"), pos, stack,
                                        java.time.Instant.parse((String) obj.get("timestamp")));
                                logs.add(entry);

                                // Index immediately during load
                                playerContainerLogs.computeIfAbsent(entry.playerName, k -> new ArrayList<>())
                                        .add(entry);
                                long chunkKey = getChunkKey(entry.pos);
                                chunkContainerLogs.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(entry);
                            } catch (Exception nbtEx) {
                                System.err.println(
                                        "[MineTracer] Failed to parse NBT for container log: " + nbtEx.getMessage());
                            }
                        }

                        // Load block logs
                        List<Map<String, Object>> blockList = (List<Map<String, Object>>) allLogs.getOrDefault("block",
                                new ArrayList<>());
                        for (Map<String, Object> obj : blockList) {
                            String[] posParts = ((String) obj.get("pos")).split(",");
                            BlockPos pos = new BlockPos(Integer.parseInt(posParts[0]), Integer.parseInt(posParts[1]),
                                    Integer.parseInt(posParts[2]));
                            BlockLogEntry entry = new BlockLogEntry((String) obj.get("action"),
                                    (String) obj.get("playerName"), pos, (String) obj.get("blockId"),
                                    (String) obj.get("nbt"), java.time.Instant.parse((String) obj.get("timestamp")));
                            blockLogs.add(entry);

                            playerBlockLogs.computeIfAbsent(entry.playerName, k -> new ArrayList<>()).add(entry);
                            long chunkKey = getChunkKey(entry.pos);
                            chunkBlockLogs.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(entry);
                        }

                        // Load sign logs
                        List<Map<String, Object>> signList = (List<Map<String, Object>>) allLogs.getOrDefault("sign",
                                new ArrayList<>());
                        for (Map<String, Object> obj : signList) {
                            String[] posParts = ((String) obj.get("pos")).split(",");
                            BlockPos pos = new BlockPos(Integer.parseInt(posParts[0]), Integer.parseInt(posParts[1]),
                                    Integer.parseInt(posParts[2]));
                            SignLogEntry entry = new SignLogEntry((String) obj.get("action"),
                                    (String) obj.get("playerName"), pos, (String) obj.get("text"),
                                    (String) obj.get("nbt"), java.time.Instant.parse((String) obj.get("timestamp")));
                            signLogs.add(entry);

                            playerSignLogs.computeIfAbsent(entry.playerName, k -> new ArrayList<>()).add(entry);
                            long chunkKey = getChunkKey(entry.pos);
                            chunkSignLogs.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(entry);
                        }

                        // Load kill logs
                        List<Map<String, Object>> killList = (List<Map<String, Object>>) allLogs.getOrDefault("kill",
                                new ArrayList<>());
                        for (Map<String, Object> obj : killList) {
                            String[] posParts = ((String) obj.get("pos")).split(",");
                            BlockPos pos = new BlockPos(Integer.parseInt(posParts[0]), Integer.parseInt(posParts[1]),
                                    Integer.parseInt(posParts[2]));
                            KillLogEntry entry = new KillLogEntry((String) obj.get("killerName"),
                                    (String) obj.get("victimName"), pos, (String) obj.get("world"),
                                    java.time.Instant.parse((String) obj.get("timestamp")));
                            killLogs.add(entry);

                            playerKillLogs.computeIfAbsent(entry.killerName, k -> new ArrayList<>()).add(entry);
                            long chunkKey = getChunkKey(entry.pos);
                            chunkKillLogs.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(entry);
                        }
                    }
                }
                hasUnsavedChanges = false;
                logsLoaded = true;
            } catch (Exception e) {
                System.err.println("[MineTracer] Failed to load logs: " + e.getMessage());
                e.printStackTrace();
            } finally {
                dataLock.writeLock().unlock();
            }
        }, queryExecutor);
    }

    // Public API methods
    public static void logContainerAction(String action, PlayerEntity player, BlockPos pos, ItemStack stack) {
        LogEntry entry = new LogEntry(action, player.getName().getString(), pos, stack, Instant.now());
        dataLock.writeLock().lock();
        try {
            logs.add(entry);
            hasUnsavedChanges = true;
        } finally {
            dataLock.writeLock().unlock();
        }
        indexLogEntryAsync(entry);
        invalidateQueryCache();
    }

    public static void logBlockAction(String action, PlayerEntity player, BlockPos pos, String blockId, String nbt) {
        BlockLogEntry entry = new BlockLogEntry(action, player.getName().getString(), pos, blockId, nbt, Instant.now());
        dataLock.writeLock().lock();
        try {
            blockLogs.add(entry);
            hasUnsavedChanges = true;
        } finally {
            dataLock.writeLock().unlock();
        }
        indexBlockLogEntryAsync(entry);
        invalidateQueryCache();
    }

    public static void logSignAction(String action, PlayerEntity player, BlockPos pos, String text, String nbt) {
        String playerName = player != null ? player.getName().getString() : "unknown";
        SignLogEntry entry = new SignLogEntry(action, playerName, pos, text, nbt, Instant.now());
        dataLock.writeLock().lock();
        try {
            signLogs.add(entry);
            hasUnsavedChanges = true;
        } finally {
            dataLock.writeLock().unlock();
        }
        indexSignLogEntryAsync(entry);
        invalidateQueryCache();
    }

    public static void logKillAction(String killerName, String victimName, BlockPos pos, String world) {
        KillLogEntry entry = new KillLogEntry(killerName, victimName, pos, world, Instant.now());
        dataLock.writeLock().lock();
        try {
            killLogs.add(entry);
            hasUnsavedChanges = true;
        } finally {
            dataLock.writeLock().unlock();
        }
        indexKillLogEntryAsync(entry);
        invalidateQueryCache();
    }

    public static void logInventoryAction(String action, PlayerEntity player, ItemStack stack) {
        logContainerAction(action, player, BlockPos.ORIGIN, stack);
    }

    // High-performance range queries with explicit range validation
    public static CompletableFuture<List<BlockLogEntry>> getBlockLogsInRangeAsync(BlockPos center, int range,
            String userFilter) {
        return CompletableFuture.supplyAsync(() -> {
            ensureLogsLoaded(); // Ensure logs are loaded before querying
            
            String cacheKey = "block_" + center + "_" + range + "_" + userFilter;
            List<BlockLogEntry> cached = (List<BlockLogEntry>) queryCache.getIfPresent(cacheKey);
            if (cached != null) {
                return cached;
            }

            dataLock.readLock().lock();
            try {
                List<BlockLogEntry> result = new ArrayList<>();
                int r2 = range * range;

                // Use spatial indexing to check only nearby chunks
                int chunkRange = (range >> 4) + 1;
                int centerChunkX = center.getX() >> 4;
                int centerChunkZ = center.getZ() >> 4;

                for (int cx = centerChunkX - chunkRange; cx <= centerChunkX + chunkRange; cx++) {
                    for (int cz = centerChunkZ - chunkRange; cz <= centerChunkZ + chunkRange; cz++) {
                        long chunkKey = ((long) cx << 32) | ((long) cz & 0xFFFFFFFFL);
                        List<BlockLogEntry> chunkEntries = chunkBlockLogs.get(chunkKey);
                        if (chunkEntries != null) {
                            for (BlockLogEntry entry : chunkEntries) {
                                if ((userFilter == null || entry.playerName.equalsIgnoreCase(userFilter)) &&
                                        entry.pos.getSquaredDistance(center.getX(), center.getY(),
                                                center.getZ()) <= r2) {
                                    result.add(entry);
                                }
                            }
                        }
                    }
                }

                queryCache.put(cacheKey, result);
                return result;
            } finally {
                dataLock.readLock().unlock();
            }
        }, queryExecutor);
    }

    public static CompletableFuture<List<SignLogEntry>> getSignLogsInRangeAsync(BlockPos center, int range,
            String userFilter) {
        return CompletableFuture.supplyAsync(() -> {
            ensureLogsLoaded(); // Ensure logs are loaded before querying
            
            String cacheKey = "sign_" + center + "_" + range + "_" + userFilter;
            List<SignLogEntry> cached = (List<SignLogEntry>) queryCache.getIfPresent(cacheKey);
            if (cached != null) {
                return cached;
            }

            dataLock.readLock().lock();
            try {
                List<SignLogEntry> result = new ArrayList<>();
                int r2 = range * range;

                int chunkRange = (range >> 4) + 1;
                int centerChunkX = center.getX() >> 4;
                int centerChunkZ = center.getZ() >> 4;

                for (int cx = centerChunkX - chunkRange; cx <= centerChunkX + chunkRange; cx++) {
                    for (int cz = centerChunkZ - chunkRange; cz <= centerChunkZ + chunkRange; cz++) {
                        long chunkKey = ((long) cx << 32) | ((long) cz & 0xFFFFFFFFL);
                        List<SignLogEntry> chunkEntries = chunkSignLogs.get(chunkKey);
                        if (chunkEntries != null) {
                            for (SignLogEntry entry : chunkEntries) {
                                if ((userFilter == null || entry.playerName.equalsIgnoreCase(userFilter)) &&
                                        entry.pos.getSquaredDistance(center.getX(), center.getY(),
                                                center.getZ()) <= r2) {
                                    result.add(entry);
                                }
                            }
                        }
                    }
                }

                queryCache.put(cacheKey, result);
                return result;
            } finally {
                dataLock.readLock().unlock();
            }
        }, queryExecutor);
    }

    public static CompletableFuture<List<KillLogEntry>> getKillLogsInRangeAsync(BlockPos center, int range,
            String userFilter, boolean filterByKiller) {
        return CompletableFuture.supplyAsync(() -> {
            ensureLogsLoaded(); // Ensure logs are loaded before querying
            
            String cacheKey = "kill_" + center + "_" + range + "_" + userFilter + "_" + filterByKiller;
            List<KillLogEntry> cached = (List<KillLogEntry>) queryCache.getIfPresent(cacheKey);
            if (cached != null) {
                return cached;
            }

            dataLock.readLock().lock();
            try {
                List<KillLogEntry> result = new ArrayList<>();
                int r2 = range * range;

                int chunkRange = (range >> 4) + 1;
                int centerChunkX = center.getX() >> 4;
                int centerChunkZ = center.getZ() >> 4;

                for (int cx = centerChunkX - chunkRange; cx <= centerChunkX + chunkRange; cx++) {
                    for (int cz = centerChunkZ - chunkRange; cz <= centerChunkZ + chunkRange; cz++) {
                        long chunkKey = ((long) cx << 32) | ((long) cz & 0xFFFFFFFFL);
                        List<KillLogEntry> chunkEntries = chunkKillLogs.get(chunkKey);
                        if (chunkEntries != null) {
                            for (KillLogEntry entry : chunkEntries) {
                                boolean userMatch = true;
                                if (userFilter != null) {
                                    if (filterByKiller) {
                                        userMatch = entry.killerName.equalsIgnoreCase(userFilter);
                                    } else {
                                        userMatch = entry.victimName.equalsIgnoreCase(userFilter);
                                    }
                                }
                                if (userMatch && entry.pos.getSquaredDistance(center.getX(), center.getY(),
                                        center.getZ()) <= r2) {
                                    result.add(entry);
                                }
                            }
                        }
                    }
                }

                queryCache.put(cacheKey, result);
                return result;
            } finally {
                dataLock.readLock().unlock();
            }
        }, queryExecutor);
    }

    public static CompletableFuture<List<LogEntry>> getLogsInRangeAsync(BlockPos center, int range) {
        return CompletableFuture.supplyAsync(() -> {
            ensureLogsLoaded(); // Ensure logs are loaded before querying
            
            String cacheKey = "container_" + center + "_" + range;
            List<LogEntry> cached = (List<LogEntry>) queryCache.getIfPresent(cacheKey);
            if (cached != null) {
                return cached;
            }

            dataLock.readLock().lock();
            try {
                List<LogEntry> result = new ArrayList<>();
                int r2 = range * range;

                int chunkRange = (range >> 4) + 1;
                int centerChunkX = center.getX() >> 4;
                int centerChunkZ = center.getZ() >> 4;

                for (int cx = centerChunkX - chunkRange; cx <= centerChunkX + chunkRange; cx++) {
                    for (int cz = centerChunkZ - chunkRange; cz <= centerChunkZ + chunkRange; cz++) {
                        long chunkKey = ((long) cx << 32) | ((long) cz & 0xFFFFFFFFL);
                        List<LogEntry> chunkEntries = chunkContainerLogs.get(chunkKey);
                        if (chunkEntries != null) {
                            for (LogEntry entry : chunkEntries) {
                                if (entry.pos.getSquaredDistance(center.getX(), center.getY(), center.getZ()) <= r2) {
                                    result.add(entry);
                                }
                            }
                        }
                    }
                }

                queryCache.put(cacheKey, result);
                return result;
            } finally {
                dataLock.readLock().unlock();
            }
        }, queryExecutor);
    }

    // Global search methods for user-specific lookups (no range restriction)
    public static CompletableFuture<List<BlockLogEntry>> getBlockLogsForUserAsync(String userFilter) {
        return CompletableFuture.supplyAsync(() -> {
            if (userFilter == null || userFilter.isEmpty()) {
                return new ArrayList<>();
            }
            
            String cacheKey = "block_user_" + userFilter;
            List<BlockLogEntry> cached = (List<BlockLogEntry>) queryCache.getIfPresent(cacheKey);
            if (cached != null) {
                return cached;
            }

            dataLock.readLock().lock();
            try {
                List<BlockLogEntry> result = new ArrayList<>();
                
                // Case-insensitive lookup - search through all player entries
                for (Map.Entry<String, List<BlockLogEntry>> playerEntry : playerBlockLogs.entrySet()) {
                    if (playerEntry.getKey().equalsIgnoreCase(userFilter)) {
                        result.addAll(playerEntry.getValue());
                        break; // Found the player, no need to continue
                    }
                }

                queryCache.put(cacheKey, result);
                return result;
            } finally {
                dataLock.readLock().unlock();
            }
        }, queryExecutor);
    }

    public static CompletableFuture<List<SignLogEntry>> getSignLogsForUserAsync(String userFilter) {
        return CompletableFuture.supplyAsync(() -> {
            ensureLogsLoaded(); // Ensure logs are loaded before querying
            
            if (userFilter == null || userFilter.isEmpty()) {
                return new ArrayList<>();
            }
            
            String cacheKey = "sign_user_" + userFilter;
            List<SignLogEntry> cached = (List<SignLogEntry>) queryCache.getIfPresent(cacheKey);
            if (cached != null) {
                return cached;
            }

            dataLock.readLock().lock();
            try {
                List<SignLogEntry> result = new ArrayList<>();
                
                // Case-insensitive lookup - search through all player entries
                for (Map.Entry<String, List<SignLogEntry>> playerEntry : playerSignLogs.entrySet()) {
                    if (playerEntry.getKey().equalsIgnoreCase(userFilter)) {
                        result.addAll(playerEntry.getValue());
                        break; // Found the player, no need to continue
                    }
                }

                queryCache.put(cacheKey, result);
                return result;
            } finally {
                dataLock.readLock().unlock();
            }
        }, queryExecutor);
    }

    public static CompletableFuture<List<LogEntry>> getContainerLogsForUserAsync(String userFilter) {
        return CompletableFuture.supplyAsync(() -> {
            if (userFilter == null || userFilter.isEmpty()) {
                return new ArrayList<>();
            }
            
            String cacheKey = "container_user_" + userFilter;
            List<LogEntry> cached = (List<LogEntry>) queryCache.getIfPresent(cacheKey);
            if (cached != null) {
                return cached;
            }

            dataLock.readLock().lock();
            try {
                List<LogEntry> result = new ArrayList<>();
                
                // Case-insensitive lookup - search through all player entries
                for (Map.Entry<String, List<LogEntry>> playerEntry : playerContainerLogs.entrySet()) {
                    if (playerEntry.getKey().equalsIgnoreCase(userFilter)) {
                        result.addAll(playerEntry.getValue());
                        break; // Found the player, no need to continue
                    }
                }

                queryCache.put(cacheKey, result);
                return result;
            } finally {
                dataLock.readLock().unlock();
            }
        }, queryExecutor);
    }

    public static CompletableFuture<List<KillLogEntry>> getKillLogsForUserAsync(String userFilter, boolean filterByKiller) {
        return CompletableFuture.supplyAsync(() -> {
            ensureLogsLoaded(); // Ensure logs are loaded before querying
            
            if (userFilter == null || userFilter.isEmpty()) {
                return new ArrayList<>();
            }
            
            String cacheKey = "kill_user_" + userFilter + "_" + filterByKiller;
            List<KillLogEntry> cached = (List<KillLogEntry>) queryCache.getIfPresent(cacheKey);
            if (cached != null) {
                return cached;
            }

            dataLock.readLock().lock();
            try {
                List<KillLogEntry> result = new ArrayList<>();
                if (filterByKiller) {
                    // Case-insensitive lookup for killer
                    for (Map.Entry<String, List<KillLogEntry>> playerEntry : playerKillLogs.entrySet()) {
                        if (playerEntry.getKey().equalsIgnoreCase(userFilter)) {
                            result.addAll(playerEntry.getValue());
                            break; // Found the player, no need to continue
                        }
                    }
                } else {
                    // Search through all kill logs for victim name (case-insensitive)
                    for (KillLogEntry entry : killLogs) {
                        if (entry.victimName.equalsIgnoreCase(userFilter)) {
                            result.add(entry);
                        }
                    }
                }

                queryCache.put(cacheKey, result);
                return result;
            } finally {
                dataLock.readLock().unlock();
            }
        }, queryExecutor);
    }

    // Backwards compatibility synchronous methods
    public static List<BlockLogEntry> getBlockLogsInRange(BlockPos center, int range, String userFilter) {
        try {
            return getBlockLogsInRangeAsync(center, range, userFilter).get();
        } catch (Exception e) {
            System.err.println("[MineTracer] Error in getBlockLogsInRange: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public static List<SignLogEntry> getSignLogsInRange(BlockPos center, int range, String userFilter) {
        try {
            return getSignLogsInRangeAsync(center, range, userFilter).get();
        } catch (Exception e) {
            System.err.println("[MineTracer] Error in getSignLogsInRange: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public static List<KillLogEntry> getKillLogsInRange(BlockPos center, int range, String userFilter,
            boolean filterByKiller) {
        try {
            return getKillLogsInRangeAsync(center, range, userFilter, filterByKiller).get();
        } catch (Exception e) {
            System.err.println("[MineTracer] Error in getKillLogsInRange: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public static List<KillLogEntry> getKillLogsInRange(BlockPos center, int range, String userFilter) {
        return getKillLogsInRange(center, range, userFilter, true);
    }

    public static List<LogEntry> getLogsInRange(BlockPos center, int range) {
        try {
            return getLogsInRangeAsync(center, range).get();
        } catch (Exception e) {
            System.err.println("[MineTracer] Error in getLogsInRange: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public static List<String> getAllPlayerNames() {
        ensureLogsLoaded(); // Ensure logs are loaded before querying
        
        dataLock.readLock().lock();
        try {
            java.util.Set<String> names = new java.util.HashSet<>();
            names.addAll(playerContainerLogs.keySet());
            names.addAll(playerBlockLogs.keySet());
            names.addAll(playerSignLogs.keySet());
            names.addAll(playerKillLogs.keySet());
            
            return new java.util.ArrayList<>(names);
        } finally {
            dataLock.readLock().unlock();
        }
    }

    private static void invalidateQueryCache() {
        // Only invalidate cache when new data is added, not on every query
        queryCache.invalidateAll();
    }

    // Inspector mode state tracking
    private static final java.util.Set<java.util.UUID> inspectorPlayers = new java.util.HashSet<>();

    public static void setInspectorMode(ServerPlayerEntity player, boolean enabled) {
        synchronized (inspectorPlayers) {
            if (enabled) {
                inspectorPlayers.add(player.getUuid());
            } else {
                inspectorPlayers.remove(player.getUuid());
            }
        }
    }

    public static boolean isInspectorMode(ServerPlayerEntity player) {
        synchronized (inspectorPlayers) {
            return inspectorPlayers.contains(player.getUuid());
        }
    }

    public static void toggleInspectorMode(ServerPlayerEntity player) {
        setInspectorMode(player, !isInspectorMode(player));
    }

    // Get the async executor for external use
    public static ExecutorService getAsyncExecutor() {
        return queryExecutor != null ? queryExecutor : ForkJoinPool.commonPool();
    }

    // Async save operations
    private static CompletableFuture<Void> saveAllLogsAsync() {
        return CompletableFuture.runAsync(() -> {
            synchronized (saveLock) {
                if (!hasUnsavedChanges && !isShuttingDown) {
                    return;
                }

                try {
                    Files.createDirectories(LOG_FILE.getParent());
                    Map<String, Object> allLogs = new HashMap<>();

                    dataLock.readLock().lock();
                    try {
                        List<Object> containerList = new ArrayList<>();
                        for (LogEntry entry : logs)
                            containerList.add(new LogEntryJson(entry));
                        allLogs.put("container", containerList);

                        List<Object> blockList = new ArrayList<>();
                        for (BlockLogEntry entry : blockLogs)
                            blockList.add(new BlockLogEntryJson(entry));
                        allLogs.put("block", blockList);

                        List<Object> signList = new ArrayList<>();
                        for (SignLogEntry entry : signLogs)
                            signList.add(new SignLogEntryJson(entry));
                        allLogs.put("sign", signList);

                        List<Object> killList = new ArrayList<>();
                        for (KillLogEntry entry : killLogs)
                            killList.add(new KillLogEntryJson(entry));
                        allLogs.put("kill", killList);
                    } finally {
                        dataLock.readLock().unlock();
                    }

                    String json = GSON.toJson(allLogs);
                    Files.writeString(LOG_FILE, json, StandardCharsets.UTF_8);
                    hasUnsavedChanges = false;
                } catch (Exception e) {
                    System.err.println("[MineTracer] Failed to save logs: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }, queryExecutor);
    }

    // JSON serialization helper classes
    private static class LogEntryJson {
        String action;
        String playerName;
        String pos;
        String itemNbt;
        String timestamp;

        LogEntryJson(LogEntry entry) {
            this.action = entry.action;
            this.playerName = entry.playerName;
            this.pos = entry.pos.getX() + "," + entry.pos.getY() + "," + entry.pos.getZ();
            this.itemNbt = entry.stack.writeNbt(new NbtCompound()).toString();
            this.timestamp = entry.timestamp.toString();
        }
    }

    private static class BlockLogEntryJson {
        String action;
        String playerName;
        String pos;
        String blockId;
        String nbt;
        String timestamp;

        BlockLogEntryJson(BlockLogEntry entry) {
            this.action = entry.action;
            this.playerName = entry.playerName;
            this.pos = entry.pos.getX() + "," + entry.pos.getY() + "," + entry.pos.getZ();
            this.blockId = entry.blockId;
            this.nbt = entry.nbt;
            this.timestamp = entry.timestamp.toString();
        }
    }

    private static class SignLogEntryJson {
        String action;
        String playerName;
        String pos;
        String text;
        String nbt;
        String timestamp;

        SignLogEntryJson(SignLogEntry entry) {
            this.action = entry.action;
            this.playerName = entry.playerName;
            this.pos = entry.pos.getX() + "," + entry.pos.getY() + "," + entry.pos.getZ();
            this.text = entry.text;
            this.nbt = entry.nbt;
            this.timestamp = entry.timestamp.toString();
        }
    }

    private static class KillLogEntryJson {
        String action = "kill";
        String killerName;
        String victimName;
        String pos;
        String world;
        String timestamp;

        KillLogEntryJson(KillLogEntry entry) {
            this.killerName = entry.killerName;
            this.victimName = entry.victimName;
            this.pos = entry.pos.getX() + "," + entry.pos.getY() + "," + entry.pos.getZ();
            this.world = entry.world;
            this.timestamp = entry.timestamp.toString();
        }
    }

    // Public method to force immediate save (for critical situations)
    public static void forceSave() {
        try {
            saveAllLogsAsync().get();
        } catch (Exception e) {
            System.err.println("[MineTracer] Error during force save: " + e.getMessage());
        }
    }

    // Register server lifecycle hooks for loading/saving logs
    public static void registerServerLifecycle() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            try {
                // Ensure logs are loaded synchronously during server startup
                loadAllLogsAsync().get(); // Wait for loading to complete
                System.out.println("[MineTracer] Logs loaded successfully during server startup");
                startPeriodicSaving();
                com.minetracer.features.minetracer.KillLogger.register();
            } catch (Exception e) {
                System.err.println("[MineTracer] Error during server startup: " + e.getMessage());
                e.printStackTrace();
            }
        });

        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            isShuttingDown = true;
            stopPeriodicSaving();
            try {
                saveAllLogsAsync().get(); // Wait for final save to complete
            } catch (Exception e) {
                System.err.println("[MineTracer] Error during server shutdown: " + e.getMessage());
            }

            // Shutdown thread pools
            if (indexingExecutor != null && !indexingExecutor.isShutdown()) {
                indexingExecutor.shutdown();
            }
        });
    }

    // Start the periodic saving task
    private static void startPeriodicSaving() {
        if (saveScheduler == null || saveScheduler.isShutdown()) {
            saveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "MineTracer-LogSaver");
                t.setDaemon(true);
                return t;
            });

            // Save every 2 minutes if there are unsaved changes
            saveScheduler.scheduleWithFixedDelay(() -> {
                if (hasUnsavedChanges && !isShuttingDown) {
                    saveAllLogsAsync();
                }
            }, 120, 120, TimeUnit.SECONDS);
        }
    }

    // Stop the periodic saving task
    private static void stopPeriodicSaving() {
        if (saveScheduler != null && !saveScheduler.isShutdown()) {
            saveScheduler.shutdown();
            try {
                if (!saveScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    saveScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                saveScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    // Public method to ensure logs are loaded (for command execution)
    public static void ensureLogsLoaded() {
        if (!logsLoaded) {
            synchronized (saveLock) {
                if (!logsLoaded) {
                    try {
                        loadAllLogsAsync().get(); // Wait for loading to complete
                    } catch (Exception e) {
                        System.err.println("[MineTracer] Error ensuring logs are loaded: " + e.getMessage());
                    }
                }
            }
        }
    }
}
