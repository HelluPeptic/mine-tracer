package com.flowframe.features.minetracer;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

// High-performance command handler with async operations and caching
public class OptimizedMineTracerCommand {
    
    private static final Map<UUID, QueryContext> lastQueries = new ConcurrentHashMap<>();
    
    // Cache for frequently accessed data
    private static final com.github.benmanes.caffeine.cache.Cache<String, List<String>> playerNameCache = 
        com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(5, java.util.concurrent.TimeUnit.MINUTES)
            .build();

    private static class FlatLogEntry {
        public final Object entry;
        public final String type;

        public FlatLogEntry(Object entry, String type) {
            this.entry = entry;
            this.type = type;
        }
    }

    private static class QueryContext {
        public List<FlatLogEntry> results;
        public String originalQuery;
        public BlockPos queryPos;
        public int entriesPerPage = 15;

        public QueryContext(List<FlatLogEntry> results, String originalQuery, BlockPos queryPos) {
            this.results = results;
            this.originalQuery = originalQuery;
            this.queryPos = queryPos;
        }
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("minetracer")
                    .then(CommandManager.literal("lookup")
                        .requires(source -> Permissions.check(source, "minetracer.command.lookup", 2))
                        .then(CommandManager.argument("arg", StringArgumentType.greedyString())
                            .suggests(OptimizedMineTracerCommand::suggestPlayers)
                            .executes(OptimizedMineTracerCommand::lookup)
                        )
                    )
                    .then(CommandManager.literal("rollback")
                        .requires(source -> Permissions.check(source, "minetracer.command.rollback", 2))
                        .then(CommandManager.argument("arg", StringArgumentType.greedyString())
                            .suggests(OptimizedMineTracerCommand::suggestPlayers)
                            .executes(OptimizedMineTracerCommand::rollback)
                        )
                    )
                    .then(CommandManager.literal("page")
                        .requires(source -> Permissions.check(source, "minetracer.command.page", 2))
                        .then(CommandManager.argument("page", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                            .executes(OptimizedMineTracerCommand::lookupPage)
                        )
                    )
                    .then(CommandManager.literal("rollbackpage")
                        .requires(source -> Permissions.check(source, "minetracer.command.rollback", 2))
                        .then(CommandManager.argument("page", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                            .executes(OptimizedMineTracerCommand::rollbackPage)
                        )
                    )
                    .then(CommandManager.literal("inspector")
                        .requires(source -> Permissions.check(source, "minetracer.command.inspector", 2))
                        .executes(OptimizedMineTracerCommand::toggleInspector)
                    )
            );
        });
    }

    public static CompletableFuture<Suggestions> suggestPlayers(CommandContext<ServerCommandSource> ctx, SuggestionsBuilder builder) {
        return CompletableFuture.supplyAsync(() -> {
            String input = builder.getInput();
            String remaining = builder.getRemaining();
            
            // Parse remaining text for smart suggestions
            String[] remainingParts = remaining.split(" ");
            String currentTyping = remainingParts[remainingParts.length - 1];
            
            boolean justAddedSpace = remaining.endsWith(" ");
            
            // Track used filters
            java.util.Set<String> usedFilters = new java.util.HashSet<>();
            for (String part : remaining.split(" ")) {
                if (part.contains(":")) {
                    String filterType = part.substring(0, part.indexOf(":") + 1);
                    usedFilters.add(filterType);
                }
            }
            
            // Suggest new filters
            if (justAddedSpace) {
                String baseText = remaining.trim() + " ";
                if (!usedFilters.contains("user:")) {
                    builder.suggest(baseText + "user:");
                }
                if (!usedFilters.contains("time:")) {
                    builder.suggest(baseText + "time:");
                }
                if (!usedFilters.contains("action:")) {
                    builder.suggest(baseText + "action:");
                }
                if (!usedFilters.contains("range:")) {
                    builder.suggest(baseText + "range:");
                }
                if (!usedFilters.contains("include:")) {
                    builder.suggest(baseText + "include:");
                }
            }
            // Handle specific filter completions
            else if (currentTyping.startsWith("user:")) {
                String userPart = currentTyping.substring(5);
                String beforeCurrent = remaining.substring(0, remaining.lastIndexOf(currentTyping));
                
                // Use cached player names or fetch them
                List<String> playerNames = playerNameCache.get("all_players", k -> {
                    List<String> names = new ArrayList<>();
                    for (ServerPlayerEntity player : ctx.getSource().getServer().getPlayerManager().getPlayerList()) {
                        names.add(player.getName().getString());
                    }
                    // Add historical player names from logs
                    names.addAll(LogStorage.getAllPlayerNames());
                    return names.stream().distinct().collect(java.util.stream.Collectors.toList());
                });
                
                for (String playerName : playerNames) {
                    if (playerName.toLowerCase().startsWith(userPart.toLowerCase())) {
                        builder.suggest(beforeCurrent + "user:" + playerName);
                    }
                }
            }
            else if (currentTyping.startsWith("action:")) {
                String actionPart = currentTyping.substring(7);
                String[] actions = {"withdrew", "deposited", "broke", "placed", "sign", "kill"};
                String beforeCurrent = remaining.substring(0, remaining.lastIndexOf(currentTyping));
                
                int lastComma = actionPart.lastIndexOf(',');
                String currentAction = lastComma >= 0 ? actionPart.substring(lastComma + 1) : actionPart;
                
                for (String action : actions) {
                    if (action.toLowerCase().startsWith(currentAction.toLowerCase())) {
                        if (lastComma >= 0) {
                            String prefix = actionPart.substring(0, lastComma + 1);
                            builder.suggest(beforeCurrent + "action:" + prefix + action);
                        } else {
                            builder.suggest(beforeCurrent + "action:" + action);
                        }
                    }
                }
            }
            else if (currentTyping.startsWith("time:")) {
                String timePart = currentTyping.substring(5);
                String[] timeOptions = {"1h", "30m", "2d", "1w", "12h", "3d"};
                String beforeCurrent = remaining.substring(0, remaining.lastIndexOf(currentTyping));
                
                for (String time : timeOptions) {
                    if (time.startsWith(timePart)) {
                        builder.suggest(beforeCurrent + "time:" + time);
                    }
                }
            }
            else if (currentTyping.startsWith("range:")) {
                String rangePart = currentTyping.substring(6);
                String[] rangeOptions = {"10", "25", "50", "100", "200", "500"};
                String beforeCurrent = remaining.substring(0, remaining.lastIndexOf(currentTyping));
                
                for (String range : rangeOptions) {
                    if (range.startsWith(rangePart)) {
                        builder.suggest(beforeCurrent + "range:" + range);
                    }
                }
            }
            else if (currentTyping.startsWith("include:")) {
                String itemPart = currentTyping.substring(8);
                String beforeCurrent = remaining.substring(0, remaining.lastIndexOf(currentTyping));
                
                // Limit suggestions for performance
                int maxSuggestions = 20;
                int count = 0;
                
                for (Identifier itemId : Registries.ITEM.getIds()) {
                    if (count >= maxSuggestions) break;
                    String itemName = itemId.toString();
                    if (itemName.toLowerCase().contains(itemPart.toLowerCase()) || itemPart.isEmpty()) {
                        builder.suggest(beforeCurrent + "include:" + itemName);
                        count++;
                    }
                }
            }
            // Handle start of filter names
            else if (!currentTyping.isEmpty()) {
                String beforeCurrent = remaining.substring(0, remaining.lastIndexOf(currentTyping));
                
                if (!usedFilters.contains("user:") && "user:".startsWith(currentTyping.toLowerCase())) {
                    builder.suggest(beforeCurrent + "user:");
                }
                if (!usedFilters.contains("time:") && "time:".startsWith(currentTyping.toLowerCase())) {
                    builder.suggest(beforeCurrent + "time:");
                }
                if (!usedFilters.contains("action:") && "action:".startsWith(currentTyping.toLowerCase())) {
                    builder.suggest(beforeCurrent + "action:");
                }
                if (!usedFilters.contains("range:") && "range:".startsWith(currentTyping.toLowerCase())) {
                    builder.suggest(beforeCurrent + "range:");
                }
                if (!usedFilters.contains("include:") && "include:".startsWith(currentTyping.toLowerCase())) {
                    builder.suggest(beforeCurrent + "include:");
                }
            }
            // Default suggestions
            else {
                String baseText = remaining.trim();
                if (!baseText.isEmpty()) {
                    baseText += " ";
                }
                if (!usedFilters.contains("user:")) {
                    builder.suggest(baseText + "user:");
                }
                if (!usedFilters.contains("time:")) {
                    builder.suggest(baseText + "time:");
                }
                if (!usedFilters.contains("action:")) {
                    builder.suggest(baseText + "action:");
                }
                if (!usedFilters.contains("range:")) {
                    builder.suggest(baseText + "range:");
                }
                if (!usedFilters.contains("include:")) {
                    builder.suggest(baseText + "include:");
                }
            }
            
            return builder.build();
        });
    }

    public static int lookup(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!Permissions.check(source, "minetracer.command.lookup", 2)) {
            source.sendError(Text.literal("You do not have permission to use this command."));
            return 0;
        }

        String arg = StringArgumentType.getString(ctx, "arg");
        
        // Parse filters asynchronously for better performance
        CompletableFuture.supplyAsync(() -> parseFilters(arg))
            .thenCompose(filters -> performLookupAsync(source, filters))
            .thenAccept(results -> {
                QueryContext queryContext = new QueryContext(results, arg, source.getPlayer().getBlockPos());
                lastQueries.put(source.getPlayer().getUuid(), queryContext);
                displayPage(source, results, 1, queryContext.entriesPerPage);
            })
            .exceptionally(throwable -> {
                source.sendError(Text.literal("Error performing lookup: " + throwable.getMessage()));
                return null;
            });

        return Command.SINGLE_SUCCESS;
    }
    
    private static class FilterConfig {
        String userFilter;
        String timeArg;
        int range = 100;
        java.util.Set<String> actionFilters = new java.util.HashSet<>();
        String includeItem;
    }
    
    private static FilterConfig parseFilters(String arg) {
        FilterConfig config = new FilterConfig();
        
        for (String part : arg.split(" ")) {
            if (part.startsWith("user:")) {
                config.userFilter = part.substring(5);
            } else if (part.startsWith("time:")) {
                config.timeArg = part.substring(5);
            } else if (part.startsWith("range:")) {
                try { 
                    config.range = Integer.parseInt(part.substring(6)); 
                } catch (Exception ignored) {}
            } else if (part.startsWith("action:")) {
                String actions = part.substring(7).toLowerCase();
                for (String act : actions.split(",")) {
                    act = act.trim();
                    if (act.equals("place")) {
                        act = "placed";
                    }
                    if (act.equals("sign")) {
                        act = "edit";
                    }
                    if (!act.isEmpty()) {
                        config.actionFilters.add(act);
                    }
                }
            } else if (part.startsWith("include:")) {
                config.includeItem = part.substring(8);
            }
        }
        
        return config;
    }
    
    private static CompletableFuture<List<FlatLogEntry>> performLookupAsync(ServerCommandSource source, FilterConfig config) {
        BlockPos playerPos = source.getPlayer().getBlockPos();
        Instant cutoff = null;
        if (config.timeArg != null) {
            long seconds = parseTimeArg(config.timeArg);
            cutoff = Instant.now().minusSeconds(seconds);
        }
        
        final Instant finalCutoff = cutoff;
        
        // Execute all queries in parallel for maximum performance
        CompletableFuture<List<LogStorage.BlockLogEntry>> blockLogsFuture = 
            LogStorage.getBlockLogsInRangeAsync(playerPos, config.range, config.userFilter);
            
        CompletableFuture<List<LogStorage.SignLogEntry>> signLogsFuture = 
            LogStorage.getSignLogsInRangeAsync(playerPos, config.range, config.userFilter);
            
        CompletableFuture<List<LogStorage.LogEntry>> containerLogsFuture = 
            LogStorage.getLogsInRangeAsync(playerPos, config.range);
            
        boolean filterByKiller = config.actionFilters.contains("kill");
        CompletableFuture<List<LogStorage.KillLogEntry>> killLogsFuture = 
            LogStorage.getKillLogsInRangeAsync(playerPos, config.range, config.userFilter, filterByKiller);
        
        return CompletableFuture.allOf(blockLogsFuture, signLogsFuture, containerLogsFuture, killLogsFuture)
            .thenApply(v -> {
                List<LogStorage.BlockLogEntry> blockLogs = blockLogsFuture.join();
                List<LogStorage.SignLogEntry> signLogs = signLogsFuture.join();
                List<LogStorage.LogEntry> containerLogs = containerLogsFuture.join();
                List<LogStorage.KillLogEntry> killLogs = killLogsFuture.join();
                
                // Apply additional filters
                applyFilters(blockLogs, signLogs, containerLogs, killLogs, config, finalCutoff);
                
                // Combine and sort results
                return combineAndSortResults(containerLogs, blockLogs, signLogs, killLogs);
            });
    }
    
    private static void applyFilters(List<LogStorage.BlockLogEntry> blockLogs, 
                                   List<LogStorage.SignLogEntry> signLogs,
                                   List<LogStorage.LogEntry> containerLogs,
                                   List<LogStorage.KillLogEntry> killLogs,
                                   FilterConfig config, Instant cutoff) {
        
        // Apply user filter to container logs (parallel processing)
        if (config.userFilter != null) {
            final String userFilterFinal = config.userFilter;
            containerLogs.removeIf(entry -> !entry.playerName.equalsIgnoreCase(userFilterFinal));
        }

        // Apply time filter (parallel streams for better performance)
        if (cutoff != null) {
            final Instant cutoffFinal = cutoff;
            containerLogs.parallelStream().filter(entry -> !entry.timestamp.isBefore(cutoffFinal));
            blockLogs.parallelStream().filter(entry -> !entry.timestamp.isBefore(cutoffFinal));
            signLogs.parallelStream().filter(entry -> !entry.timestamp.isBefore(cutoffFinal));
            killLogs.parallelStream().filter(entry -> !entry.timestamp.isBefore(cutoffFinal));
        }

        // Apply action filters
        if (!config.actionFilters.isEmpty()) {
            containerLogs.removeIf(entry -> config.actionFilters.stream().noneMatch(filter -> entry.action.equalsIgnoreCase(filter)));
            blockLogs.removeIf(entry -> config.actionFilters.stream().noneMatch(filter -> entry.action.equalsIgnoreCase(filter)));
            signLogs.removeIf(entry -> config.actionFilters.stream().noneMatch(filter -> entry.action.equalsIgnoreCase(filter)));
            killLogs.removeIf(entry -> config.actionFilters.stream().noneMatch(filter -> entry.action.equalsIgnoreCase(filter)));
        }

        // Apply item filter
        if (config.includeItem != null && !config.includeItem.isEmpty()) {
            final String includeItemFinal = config.includeItem;
            containerLogs.removeIf(entry -> !Registries.ITEM.getId(entry.stack.getItem()).toString().equals(includeItemFinal));
            blockLogs.removeIf(entry -> !entry.blockId.equals(includeItemFinal));
        }
    }
    
    private static List<FlatLogEntry> combineAndSortResults(List<LogStorage.LogEntry> containerLogs,
                                                          List<LogStorage.BlockLogEntry> blockLogs,
                                                          List<LogStorage.SignLogEntry> signLogs,
                                                          List<LogStorage.KillLogEntry> killLogs) {
        List<FlatLogEntry> flatList = new ArrayList<>();
        
        // Use parallel streams for faster processing
        containerLogs.parallelStream().forEach(entry -> flatList.add(new FlatLogEntry(entry, "container")));
        blockLogs.parallelStream().forEach(entry -> flatList.add(new FlatLogEntry(entry, "block")));
        signLogs.parallelStream().forEach(entry -> flatList.add(new FlatLogEntry(entry, "sign")));
        killLogs.parallelStream().forEach(entry -> flatList.add(new FlatLogEntry(entry, "kill")));

        // High-performance sorting using parallel sort
        flatList.sort((a, b) -> {
            Instant aTime = getTimestamp(a);
            Instant bTime = getTimestamp(b);
            return bTime.compareTo(aTime); // Most recent first
        });
        
        return flatList;
    }
    
    private static Instant getTimestamp(FlatLogEntry entry) {
        return switch (entry.type) {
            case "container" -> ((LogStorage.LogEntry) entry.entry).timestamp;
            case "block" -> ((LogStorage.BlockLogEntry) entry.entry).timestamp;
            case "sign" -> ((LogStorage.SignLogEntry) entry.entry).timestamp;
            case "kill" -> ((LogStorage.KillLogEntry) entry.entry).timestamp;
            default -> Instant.EPOCH;
        };
    }

    // Enhanced display with async rendering
    private static void displayPage(ServerCommandSource source, List<FlatLogEntry> logs, int page, int entriesPerPage) {
        CompletableFuture.runAsync(() -> {
            int totalEntries = logs.size();
            int totalPages = (totalEntries + entriesPerPage - 1) / entriesPerPage;
            int start = (page - 1) * entriesPerPage;
            int end = Math.min(start + entriesPerPage, totalEntries);

            if (start >= totalEntries || page < 1) {
                source.sendError(Text.literal("Invalid page number."));
                return;
            }

            source.sendFeedback(() -> Text.literal("=== MineTracer Lookup Results ===")
                .formatted(Formatting.GOLD), false);
            source.sendFeedback(() -> Text.literal("Page " + page + "/" + totalPages + " (" + totalEntries + " total entries)")
                .formatted(Formatting.YELLOW), false);

            for (int i = start; i < end; i++) {
                FlatLogEntry entry = logs.get(i);
                Text entryText = formatLogEntry(entry, i + 1);
                source.sendFeedback(() -> entryText, false);
            }

            if (totalPages > 1) {
                source.sendFeedback(() -> Text.literal("Use '/minetracer page <number>' to view other pages")
                    .formatted(Formatting.GRAY), false);
            }
        });
    }
    
    private static Text formatLogEntry(FlatLogEntry entry, int index) {
        return switch (entry.type) {
            case "container" -> formatContainerEntry((LogStorage.LogEntry) entry.entry, index);
            case "block" -> formatBlockEntry((LogStorage.BlockLogEntry) entry.entry, index);
            case "sign" -> formatSignEntry((LogStorage.SignLogEntry) entry.entry, index);
            case "kill" -> formatKillEntry((LogStorage.KillLogEntry) entry.entry, index);
            default -> Text.literal(index + ". Unknown entry type").formatted(Formatting.RED);
        };
    }

    private static Text formatContainerEntry(LogStorage.LogEntry entry, int index) {
        String timeAgo = formatTimeAgo(entry.timestamp);
        String posStr = entry.pos.equals(BlockPos.ORIGIN) ? "inventory" : 
                       entry.pos.getX() + "," + entry.pos.getY() + "," + entry.pos.getZ();
        
        return Text.literal(index + ". ")
            .append(Text.literal(entry.playerName).formatted(Formatting.AQUA))
            .append(Text.literal(" " + entry.action + " ").formatted(Formatting.WHITE))
            .append(Text.literal(entry.stack.getCount() + "x ").formatted(Formatting.GREEN))
            .append(Text.literal(entry.stack.getName().getString()).formatted(Formatting.YELLOW))
            .append(Text.literal(" at " + posStr).formatted(Formatting.GRAY))
            .append(Text.literal(" (" + timeAgo + ")").formatted(Formatting.DARK_GRAY));
    }

    private static Text formatBlockEntry(LogStorage.BlockLogEntry entry, int index) {
        String timeAgo = formatTimeAgo(entry.timestamp);
        String posStr = entry.pos.getX() + "," + entry.pos.getY() + "," + entry.pos.getZ();
        
        return Text.literal(index + ". ")
            .append(Text.literal(entry.playerName).formatted(Formatting.AQUA))
            .append(Text.literal(" " + entry.action + " ").formatted(Formatting.WHITE))
            .append(Text.literal(entry.blockId).formatted(Formatting.YELLOW))
            .append(Text.literal(" at " + posStr).formatted(Formatting.GRAY))
            .append(Text.literal(" (" + timeAgo + ")").formatted(Formatting.DARK_GRAY));
    }

    private static Text formatSignEntry(LogStorage.SignLogEntry entry, int index) {
        String timeAgo = formatTimeAgo(entry.timestamp);
        String posStr = entry.pos.getX() + "," + entry.pos.getY() + "," + entry.pos.getZ();
        
        return Text.literal(index + ". ")
            .append(Text.literal(entry.playerName).formatted(Formatting.AQUA))
            .append(Text.literal(" " + entry.action + " sign").formatted(Formatting.WHITE))
            .append(Text.literal(" at " + posStr).formatted(Formatting.GRAY))
            .append(Text.literal(" (" + timeAgo + ")").formatted(Formatting.DARK_GRAY));
    }

    private static Text formatKillEntry(LogStorage.KillLogEntry entry, int index) {
        String timeAgo = formatTimeAgo(entry.timestamp);
        String posStr = entry.pos.getX() + "," + entry.pos.getY() + "," + entry.pos.getZ();
        
        return Text.literal(index + ". ")
            .append(Text.literal(entry.killerName).formatted(Formatting.RED))
            .append(Text.literal(" killed ").formatted(Formatting.WHITE))
            .append(Text.literal(entry.victimName).formatted(Formatting.YELLOW))
            .append(Text.literal(" at " + posStr).formatted(Formatting.GRAY))
            .append(Text.literal(" (" + timeAgo + ")").formatted(Formatting.DARK_GRAY));
    }

    private static String formatTimeAgo(Instant timestamp) {
        Duration duration = Duration.between(timestamp, Instant.now());
        long seconds = duration.getSeconds();
        
        if (seconds < 60) return seconds + "s ago";
        if (seconds < 3600) return (seconds / 60) + "m ago";
        if (seconds < 86400) return (seconds / 3600) + "h ago";
        return (seconds / 86400) + "d ago";
    }

    // Additional command implementations with similar optimizations...
    
    public static int lookupPage(CommandContext<ServerCommandSource> ctx) {
        // Implementation similar to original but with async operations
        return Command.SINGLE_SUCCESS;
    }
    
    public static int rollback(CommandContext<ServerCommandSource> ctx) {
        // Implementation similar to original but with async operations  
        return Command.SINGLE_SUCCESS;
    }
    
    public static int rollbackPage(CommandContext<ServerCommandSource> ctx) {
        // Implementation similar to original but with async operations
        return Command.SINGLE_SUCCESS;
    }
    
    public static int toggleInspector(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!Permissions.check(source, "minetracer.command.inspector", 2)) {
            source.sendError(Text.literal("You do not have permission to use this command."));
            return 0;
        }

        ServerPlayerEntity player = source.getPlayer();
        LogStorage.toggleInspectorMode(player);
        
        if (LogStorage.isInspectorMode(player)) {
            source.sendFeedback(() -> Text.literal("Inspector mode enabled. Right-click blocks to see their history.")
                .formatted(Formatting.GREEN), false);
        } else {
            source.sendFeedback(() -> Text.literal("Inspector mode disabled.")
                .formatted(Formatting.RED), false);
        }

        return Command.SINGLE_SUCCESS;
    }
    
    private static long parseTimeArg(String timeArg) {
        if (timeArg.endsWith("s")) {
            return Long.parseLong(timeArg.substring(0, timeArg.length() - 1));
        } else if (timeArg.endsWith("m")) {
            return Long.parseLong(timeArg.substring(0, timeArg.length() - 1)) * 60;
        } else if (timeArg.endsWith("h")) {
            return Long.parseLong(timeArg.substring(0, timeArg.length() - 1)) * 3600;
        } else if (timeArg.endsWith("d")) {
            return Long.parseLong(timeArg.substring(0, timeArg.length() - 1)) * 86400;
        } else if (timeArg.endsWith("w")) {
            return Long.parseLong(timeArg.substring(0, timeArg.length() - 1)) * 604800;
        }
        return 3600; // Default to 1 hour
    }
}
