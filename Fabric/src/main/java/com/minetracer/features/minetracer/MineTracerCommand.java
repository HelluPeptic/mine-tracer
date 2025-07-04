package com.minetracer.features.minetracer;

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

// Handles /minetracer commands (lookup, rollback)
public class MineTracerCommand {
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("minetracer")
                    .then(CommandManager.literal("lookup")
                            .requires(source -> Permissions.check(source, "minetracer.command.lookup", 2))
                            .then(CommandManager.argument("arg", StringArgumentType.greedyString())
                                    .suggests(MineTracerCommand::suggestPlayers)
                                    .executes(MineTracerCommand::lookup)))
                    .then(CommandManager.literal("rollback")
                            .requires(source -> Permissions.check(source, "minetracer.command.rollback", 2))
                            .then(CommandManager.argument("arg", StringArgumentType.greedyString())
                                    .suggests(MineTracerCommand::suggestPlayers)
                                    .executes(MineTracerCommand::rollback)))
                    .then(CommandManager.literal("page")
                            .requires(source -> Permissions.check(source, "minetracer.command.page", 2))
                            .then(CommandManager
                                    .argument("page", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                    .executes(MineTracerCommand::lookupPage)))
                    .then(CommandManager.literal("inspector")
                            .requires(source -> Permissions.check(source, "minetracer.command.inspector", 2))
                            .executes(MineTracerCommand::toggleInspector))
                    .then(CommandManager.literal("save")
                            .requires(source -> Permissions.check(source, "minetracer.command.save", 2))
                            .executes(MineTracerCommand::forceSave)));
        });
    }

    public static CompletableFuture<Suggestions> suggestPlayers(CommandContext<ServerCommandSource> ctx,
            SuggestionsBuilder builder) {
        String input = builder.getInput();
        String remaining = builder.getRemaining();

        // Since we use greedyString, we need to parse the remaining text differently
        String[] remainingParts = remaining.split(" ");
        String currentTyping = remainingParts[remainingParts.length - 1];

        // Check if we just added a space (meaning we want to start a new filter)
        boolean justAddedSpace = remaining.endsWith(" ");

        // Parse what filters are already used
        java.util.Set<String> usedFilters = new java.util.HashSet<>();
        for (String part : remaining.split(" ")) {
            if (part.contains(":")) {
                String filterType = part.substring(0, part.indexOf(":") + 1);
                usedFilters.add(filterType);
            }
        }

        // If we just added a space, suggest new filters by appending to the end
        if (justAddedSpace) {
            String baseText = remaining.trim() + " "; // Ensure there's a space after existing content
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
        // Handle specific filter completions for the current word being typed
        else if (currentTyping.startsWith("user:")) {
            String userPart = currentTyping.substring(5);
            String beforeCurrent = remaining.substring(0, remaining.lastIndexOf(currentTyping));

            for (ServerPlayerEntity player : ctx.getSource().getServer().getPlayerManager().getPlayerList()) {
                String playerName = player.getName().getString();
                if (playerName.toLowerCase().startsWith(userPart.toLowerCase())) {
                    builder.suggest(beforeCurrent + "user:" + playerName);
                }
            }
        } else if (currentTyping.startsWith("action:")) {
            String actionPart = currentTyping.substring(7);
            String[] actions = { "withdrew", "deposited", "broke", "placed", "sign", "kill" };
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
        } else if (currentTyping.startsWith("time:")) {
            String timePart = currentTyping.substring(5);
            String[] timeOptions = { "1h", "30m", "2d", "1w", "12h", "3d" };
            String beforeCurrent = remaining.substring(0, remaining.lastIndexOf(currentTyping));

            for (String time : timeOptions) {
                if (time.startsWith(timePart)) {
                    builder.suggest(beforeCurrent + "time:" + time);
                }
            }
        } else if (currentTyping.startsWith("range:")) {
            String rangePart = currentTyping.substring(6);
            String[] rangeOptions = { "10", "25", "50", "100", "200", "500" };
            String beforeCurrent = remaining.substring(0, remaining.lastIndexOf(currentTyping));

            for (String range : rangeOptions) {
                if (range.startsWith(rangePart)) {
                    builder.suggest(beforeCurrent + "range:" + range);
                }
            }
        } else if (currentTyping.startsWith("include:")) {
            String itemPart = currentTyping.substring(8);
            String beforeCurrent = remaining.substring(0, remaining.lastIndexOf(currentTyping));

            // Limit suggestions to improve performance - suggest first 20 matches
            int maxSuggestions = 20;
            int count = 0;

            // Get all items from the registry
            for (Identifier itemId : Registries.ITEM.getIds()) {
                if (count >= maxSuggestions)
                    break;
                String itemName = itemId.toString();
                if (itemName.toLowerCase().contains(itemPart.toLowerCase()) || itemPart.isEmpty()) {
                    builder.suggest(beforeCurrent + "include:" + itemName);
                    count++;
                }
            }

            // Also include blocks that aren't already items (if we haven't reached limit)
            if (count < maxSuggestions) {
                for (Identifier blockId : Registries.BLOCK.getIds()) {
                    if (count >= maxSuggestions)
                        break;
                    String blockName = blockId.toString();
                    if ((blockName.toLowerCase().contains(itemPart.toLowerCase()) || itemPart.isEmpty()) &&
                            !Registries.ITEM.getIds().contains(blockId)) {
                        builder.suggest(beforeCurrent + "include:" + blockName);
                        count++;
                    }
                }
            }
        }
        // If we're typing the start of a filter (but not after a space)
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
        // If currentTyping is empty but we haven't just added a space, suggest all
        // filters
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

        return builder.buildFuture();
    }

    private static final Map<UUID, QueryContext> lastQueries = new java.util.HashMap<>();

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

    public static int lookup(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!Permissions.check(source, "minetracer.command.lookup", 2)) {
            source.sendError(Text.literal("You do not have permission to use this command."));
            return 0;
        }

        // Automatically save logs before lookup to ensure data is up to date
        try {
            LogStorage.forceSave();
        } catch (Exception e) {
            source.sendError(Text.literal("Error saving log data before lookup: " + e.getMessage()));
            return 0;
        }

        String arg = StringArgumentType.getString(ctx, "arg");

        // Parse filters asynchronously for better performance
        CompletableFuture.supplyAsync(() -> {
            String userFilter = null;
            String timeArg = null;
            int range = 100;
            java.util.Set<String> actionFilters = new java.util.HashSet<>();
            String includeItem = null;

            // Parse filters
            for (String part : arg.split(" ")) {
                if (part.startsWith("user:")) {
                    userFilter = part.substring(5);
                } else if (part.startsWith("time:")) {
                    timeArg = part.substring(5);
                } else if (part.startsWith("range:")) {
                    try {
                        range = Integer.parseInt(part.substring(6));
                    } catch (Exception ignored) {
                    }
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
                            actionFilters.add(act);
                        }
                    }
                } else if (part.startsWith("include:")) {
                    includeItem = part.substring(8);
                }
            }

            BlockPos playerPos = source.getPlayer().getBlockPos();
            Instant cutoff = null;
            if (timeArg != null) {
                long seconds = parseTimeArg(timeArg);
                cutoff = Instant.now().minusSeconds(seconds);
            }

            // Execute all queries in parallel
            CompletableFuture<List<LogStorage.BlockLogEntry>> blockLogsFuture = LogStorage
                    .getBlockLogsInRangeAsync(playerPos, range, userFilter);

            CompletableFuture<List<LogStorage.SignLogEntry>> signLogsFuture = LogStorage
                    .getSignLogsInRangeAsync(playerPos, range, userFilter);

            CompletableFuture<List<LogStorage.LogEntry>> containerLogsFuture = LogStorage.getLogsInRangeAsync(playerPos,
                    range);

            boolean filterByKiller = actionFilters.contains("kill");
            CompletableFuture<List<LogStorage.KillLogEntry>> killLogsFuture = LogStorage
                    .getKillLogsInRangeAsync(playerPos, range, userFilter, filterByKiller);

            try {
                List<LogStorage.BlockLogEntry> blockLogs = blockLogsFuture.get();
                List<LogStorage.SignLogEntry> signLogs = signLogsFuture.get();
                List<LogStorage.LogEntry> containerLogs = containerLogsFuture.get();
                List<LogStorage.KillLogEntry> killLogs = killLogsFuture.get();

                // Apply user filter to container logs
                if (userFilter != null) {
                    final String userFilterFinal = userFilter;
                    containerLogs.removeIf(entry -> !entry.playerName.equalsIgnoreCase(userFilterFinal));
                }

                // Apply time filter
                if (cutoff != null) {
                    final Instant cutoffFinal = cutoff;
                    blockLogs.removeIf(entry -> entry.timestamp.isBefore(cutoffFinal));
                    signLogs.removeIf(entry -> entry.timestamp.isBefore(cutoffFinal));
                    containerLogs.removeIf(entry -> entry.timestamp.isBefore(cutoffFinal));
                    killLogs.removeIf(entry -> entry.timestamp.isBefore(cutoffFinal));
                }

                // Filter by action if specified
                if (!actionFilters.isEmpty()) {
                    containerLogs.removeIf(
                            entry -> actionFilters.stream().noneMatch(filter -> entry.action.equalsIgnoreCase(filter)));
                    blockLogs.removeIf(
                            entry -> actionFilters.stream().noneMatch(filter -> entry.action.equalsIgnoreCase(filter)));
                    signLogs.removeIf(
                            entry -> actionFilters.stream().noneMatch(filter -> entry.action.equalsIgnoreCase(filter)));
                    killLogs.removeIf(
                            entry -> actionFilters.stream().noneMatch(filter -> entry.action.equalsIgnoreCase(filter)));
                }

                // Filter by include item if specified
                if (includeItem != null && !includeItem.isEmpty()) {
                    final String includeItemFinal = includeItem;
                    containerLogs.removeIf(
                            entry -> !Registries.ITEM.getId(entry.stack.getItem()).toString().equals(includeItemFinal));
                    blockLogs.removeIf(entry -> !entry.blockId.equals(includeItemFinal));
                }

                // Combine and sort all logs by timestamp (most recent first)
                List<FlatLogEntry> flatList = new ArrayList<>();
                for (LogStorage.LogEntry entry : containerLogs) {
                    flatList.add(new FlatLogEntry(entry, "container"));
                }
                for (LogStorage.BlockLogEntry entry : blockLogs) {
                    flatList.add(new FlatLogEntry(entry, "block"));
                }
                for (LogStorage.SignLogEntry entry : signLogs) {
                    flatList.add(new FlatLogEntry(entry, "sign"));
                }
                for (LogStorage.KillLogEntry entry : killLogs) {
                    flatList.add(new FlatLogEntry(entry, "kill"));
                }

                flatList.sort((a, b) -> {
                    Instant aTime = a.entry instanceof LogStorage.LogEntry ? ((LogStorage.LogEntry) a.entry).timestamp
                            : a.entry instanceof LogStorage.BlockLogEntry
                                    ? ((LogStorage.BlockLogEntry) a.entry).timestamp
                                    : a.entry instanceof LogStorage.SignLogEntry
                                            ? ((LogStorage.SignLogEntry) a.entry).timestamp
                                            : a.entry instanceof LogStorage.KillLogEntry
                                                    ? ((LogStorage.KillLogEntry) a.entry).timestamp
                                                    : Instant.EPOCH;
                    Instant bTime = b.entry instanceof LogStorage.LogEntry ? ((LogStorage.LogEntry) b.entry).timestamp
                            : b.entry instanceof LogStorage.BlockLogEntry
                                    ? ((LogStorage.BlockLogEntry) b.entry).timestamp
                                    : b.entry instanceof LogStorage.SignLogEntry
                                            ? ((LogStorage.SignLogEntry) b.entry).timestamp
                                            : b.entry instanceof LogStorage.KillLogEntry
                                                    ? ((LogStorage.KillLogEntry) b.entry).timestamp
                                                    : Instant.EPOCH;
                    return bTime.compareTo(aTime);
                });

                return flatList;
            } catch (Exception e) {
                throw new RuntimeException("Error executing lookup", e);
            }
        }).thenAccept(flatList -> {
            // Store query context for pagination
            QueryContext queryContext = new QueryContext(flatList, arg, source.getPlayer().getBlockPos());
            lastQueries.put(source.getPlayer().getUuid(), queryContext);

            // Display first page
            displayPage(source, flatList, 1, queryContext.entriesPerPage);
        }).exceptionally(throwable -> {
            source.sendError(Text.literal("Error performing lookup: " + throwable.getMessage()));
            return null;
        });

        return Command.SINGLE_SUCCESS;
    }

    private static void displayPage(ServerCommandSource source, List<FlatLogEntry> logs, int page, int entriesPerPage) {
        int totalEntries = logs.size();
        int totalPages = (totalEntries + entriesPerPage - 1) / entriesPerPage;
        int start = (page - 1) * entriesPerPage;
        int end = Math.min(start + entriesPerPage, totalEntries);

        if (start >= totalEntries || page < 1) {
            source.sendError(Text.literal("Invalid page number."));
            return;
        }

        source.sendFeedback(() -> Text.literal("----- MineTracer Lookup Results -----").formatted(Formatting.AQUA),
                false);
        for (int i = start; i < end; i++) {
            FlatLogEntry fle = logs.get(i);
            // First send coordinates line
            source.sendFeedback(() -> formatCoordinatesForChat(fle.entry), false);
            // Then send the main action line
            source.sendFeedback(() -> formatLogEntryForChat(fle.entry), false);

            // For sign logs, also display before/after text
            if (fle.entry instanceof LogStorage.SignLogEntry) {
                LogStorage.SignLogEntry se = (LogStorage.SignLogEntry) fle.entry;
                if (se.action.equals("edit") && se.nbt != null && !se.nbt.isEmpty()) {
                    try {
                        com.google.gson.Gson gson = new com.google.gson.Gson();
                        com.google.gson.JsonObject nbtObj = gson.fromJson(se.nbt, com.google.gson.JsonObject.class);
                        String[] beforeLines = gson.fromJson(nbtObj.get("before"), String[].class);
                        String[] afterLines = gson.fromJson(nbtObj.get("after"), String[].class);

                        // Display [before] lines
                        source.sendFeedback(() -> Text.literal("[before]").formatted(Formatting.RED), false);
                        for (String line : beforeLines) {
                            if (line != null && !line.trim().isEmpty()) {
                                source.sendFeedback(() -> Text.literal("  " + line).formatted(Formatting.WHITE), false);
                            }
                        }

                        // Display [after] lines
                        source.sendFeedback(() -> Text.literal("[after]").formatted(Formatting.GREEN), false);
                        for (String line : afterLines) {
                            if (line != null && !line.trim().isEmpty()) {
                                source.sendFeedback(() -> Text.literal("  " + line).formatted(Formatting.WHITE), false);
                            }
                        }
                    } catch (Exception e) {
                        // If parsing fails, just show basic info
                        source.sendFeedback(
                                () -> Text.literal("  (Sign text parsing failed)").formatted(Formatting.GRAY), false);
                    }
                }
            }
        }

        source.sendFeedback(
                () -> Text
                        .literal("Page " + page + "/" + totalPages + " (" + totalEntries
                                + " entries) - Use /minetracer page <number> for other pages")
                        .formatted(Formatting.GRAY),
                false);
    }

    public static Text formatLogEntryForChat(Object entry) {
        if (entry instanceof LogStorage.LogEntry) {
            LogStorage.LogEntry ce = (LogStorage.LogEntry) entry;
            String timeAgo = getTimeAgo(Duration.between(ce.timestamp, Instant.now()).getSeconds());
            String itemId = Registries.ITEM.getId(ce.stack.getItem()).toString();
            String itemName = ce.stack.getItem().getName().getString();

            // Format: "8.00s ago — HelloPeptic withdrew 1x #minecraft:oak_log (Oak Log)"
            return Text.literal(timeAgo + " ago").formatted(Formatting.WHITE)
                    .append(Text.literal(" — ").formatted(Formatting.WHITE))
                    .append(Text.literal(ce.playerName).formatted(Formatting.AQUA))
                    .append(Text.literal(" " + ce.action + " ").formatted(Formatting.GREEN))
                    .append(Text.literal(ce.stack.getCount() + "x ").formatted(Formatting.WHITE))
                    .append(Text.literal("#" + itemId).formatted(Formatting.YELLOW))
                    .append(Text.literal(" (" + itemName + ")").formatted(Formatting.GRAY));

        } else if (entry instanceof LogStorage.BlockLogEntry) {
            LogStorage.BlockLogEntry be = (LogStorage.BlockLogEntry) entry;
            String timeAgo = getTimeAgo(Duration.between(be.timestamp, Instant.now()).getSeconds());

            // Get block display name
            net.minecraft.block.Block block = Registries.BLOCK.get(new Identifier(be.blockId));
            String blockName = block.getName().getString();

            // Format: "8.00s ago — HelloPeptic placed block #minecraft:glass (Glass)"
            return Text.literal(timeAgo + " ago").formatted(Formatting.WHITE)
                    .append(Text.literal(" — ").formatted(Formatting.WHITE))
                    .append(Text.literal(be.playerName).formatted(Formatting.AQUA))
                    .append(Text.literal(" " + be.action + " block ").formatted(Formatting.GREEN))
                    .append(Text.literal("#" + be.blockId).formatted(Formatting.YELLOW))
                    .append(Text.literal(" (" + blockName + ")").formatted(Formatting.GRAY));

        } else if (entry instanceof LogStorage.SignLogEntry) {
            LogStorage.SignLogEntry se = (LogStorage.SignLogEntry) entry;
            String timeAgo = getTimeAgo(Duration.between(se.timestamp, Instant.now()).getSeconds());

            // Format: "8.00s ago — HelloPeptic edited sign"
            return Text.literal(timeAgo + " ago").formatted(Formatting.WHITE)
                    .append(Text.literal(" — ").formatted(Formatting.WHITE))
                    .append(Text.literal(se.playerName).formatted(Formatting.AQUA))
                    .append(Text.literal(" edited sign").formatted(Formatting.YELLOW));

        } else if (entry instanceof LogStorage.KillLogEntry) {
            LogStorage.KillLogEntry ke = (LogStorage.KillLogEntry) entry;
            String timeAgo = getTimeAgo(Duration.between(ke.timestamp, Instant.now()).getSeconds());

            return Text.literal(timeAgo + " ago").formatted(Formatting.WHITE)
                    .append(Text.literal(" — ").formatted(Formatting.WHITE))
                    .append(Text.literal(ke.killerName).formatted(Formatting.AQUA))
                    .append(Text.literal(" killed ").formatted(Formatting.GREEN))
                    .append(Text.literal(ke.victimName).formatted(Formatting.RED));
        }

        return Text.literal("Unknown log entry").formatted(Formatting.GRAY);
    }

    public static Text formatCoordinatesForChat(Object entry) {
        BlockPos pos = null;

        if (entry instanceof LogStorage.LogEntry) {
            pos = ((LogStorage.LogEntry) entry).pos;
        } else if (entry instanceof LogStorage.BlockLogEntry) {
            pos = ((LogStorage.BlockLogEntry) entry).pos;
        } else if (entry instanceof LogStorage.SignLogEntry) {
            pos = ((LogStorage.SignLogEntry) entry).pos;
        } else if (entry instanceof LogStorage.KillLogEntry) {
            pos = ((LogStorage.KillLogEntry) entry).pos;
        }

        if (pos != null) {
            return Text.literal("(x" + pos.getX() + "/y" + pos.getY() + "/z" + pos.getZ() + ")")
                    .formatted(Formatting.GOLD);
        }

        return Text.literal("").formatted(Formatting.GRAY);
    }

    public static int rollback(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!Permissions.check(source, "minetracer.command.rollback", 2)) {
            source.sendError(Text.literal("You do not have permission to use this command."));
            return 0;
        }

        // Automatically save logs before rollback to ensure data is up to date
        try {
            LogStorage.forceSave();
        } catch (Exception e) {
            source.sendError(Text.literal("Error saving log data before rollback: " + e.getMessage()));
            return 0;
        }

        String arg = StringArgumentType.getString(ctx, "arg");
        String userFilter = null;
        String timeArg = null;
        int range = 100;
        java.util.Set<String> actionFilters = new java.util.HashSet<>();
        String includeItem = null;
        // Parse filters
        for (String part : arg.split(" ")) {
            if (part.startsWith("user:")) {
                userFilter = part.substring(5);
            } else if (part.startsWith("time:")) {
                timeArg = part.substring(5);
            } else if (part.startsWith("range:")) {
                try {
                    range = Integer.parseInt(part.substring(6));
                } catch (Exception ignored) {
                }
            } else if (part.startsWith("action:")) {
                String actions = part.substring(7).toLowerCase();
                // Split by comma to support multiple actions
                for (String act : actions.split(",")) {
                    act = act.trim();
                    // Convert "place" to "placed" for backwards compatibility
                    if (act.equals("place")) {
                        act = "placed";
                    }
                    // Convert "sign" to "edit" since sign edits are stored as "edit" actions
                    if (act.equals("sign")) {
                        act = "edit";
                    }
                    if (!act.isEmpty()) {
                        actionFilters.add(act);
                    }
                }
            } else if (part.startsWith("include:")) {
                includeItem = part.substring(8);
            }
        }
        BlockPos playerPos = source.getPlayer().getBlockPos();
        Instant cutoff = null;
        if (timeArg != null) {
            long seconds = parseTimeArg(timeArg);
            cutoff = Instant.now().minusSeconds(seconds);
        }

        // Validate rollback restrictions - require at least 2 of: range, time, user
        boolean hasRange = range != 100; // 100 is the default, so anything else means range was specified
        boolean hasTime = timeArg != null;
        boolean hasUser = userFilter != null;

        int restrictionCount = (hasRange ? 1 : 0) + (hasTime ? 1 : 0) + (hasUser ? 1 : 0);
        if (restrictionCount < 2) {
            source.sendError(Text.literal(
                    "Rollback requires at least 2 of these filters: range:<blocks>, time:<duration>, user:<player>. Examples: 'range:50 user:PlayerName' or 'time:1h user:PlayerName' or 'range:20 time:30m'"));
            return Command.SINGLE_SUCCESS;
        }
        // Gather all logs
        List<LogStorage.BlockLogEntry> blockLogs = LogStorage.getBlockLogsInRange(playerPos, range, userFilter);
        List<LogStorage.SignLogEntry> signLogs = LogStorage.getSignLogsInRange(playerPos, range, userFilter);
        List<LogStorage.LogEntry> containerLogs = LogStorage.getLogsInRange(playerPos, range);
        // For kill logs, filter by killer if action:kill, otherwise by victim
        boolean filterByKiller = actionFilters.contains("kill");
        List<LogStorage.KillLogEntry> killLogs = LogStorage.getKillLogsInRange(playerPos, range, userFilter,
                filterByKiller);

        // Apply user filter to container logs (since getLogsInRange doesn't accept
        // userFilter)
        if (userFilter != null) {
            final String userFilterFinal = userFilter;
            containerLogs.removeIf(entry -> !entry.playerName.equalsIgnoreCase(userFilterFinal));
        }

        // Apply time filter - rollback should only affect logs AFTER the cutoff time
        // e.g., "time:1h" means rollback actions from the last 1 hour (keep entries
        // after cutoff)
        if (cutoff != null) {
            final Instant cutoffFinal = cutoff;
            blockLogs.removeIf(entry -> entry.timestamp.isBefore(cutoffFinal));
            signLogs.removeIf(entry -> entry.timestamp.isBefore(cutoffFinal));
            containerLogs.removeIf(entry -> entry.timestamp.isBefore(cutoffFinal));
            killLogs.removeIf(entry -> entry.timestamp.isBefore(cutoffFinal));
        }
        // Filter by action if specified - check if entry action matches any of the
        // specified actions
        if (!actionFilters.isEmpty()) {
            containerLogs.removeIf(
                    entry -> actionFilters.stream().noneMatch(filter -> entry.action.equalsIgnoreCase(filter)));
            blockLogs.removeIf(
                    entry -> actionFilters.stream().noneMatch(filter -> entry.action.equalsIgnoreCase(filter)));
            signLogs.removeIf(
                    entry -> actionFilters.stream().noneMatch(filter -> entry.action.equalsIgnoreCase(filter)));
            killLogs.removeIf(
                    entry -> actionFilters.stream().noneMatch(filter -> entry.action.equalsIgnoreCase(filter)));
        }
        // Filter by include item if specified
        if (includeItem != null && !includeItem.isEmpty()) {
            final String includeItemFinal = includeItem;
            containerLogs.removeIf(
                    entry -> !Registries.ITEM.getId(entry.stack.getItem()).toString().equals(includeItemFinal));
            // For block logs, filter by block ID instead of item ID
            blockLogs.removeIf(entry -> !entry.blockId.equals(includeItemFinal));
        }

        // ACTUAL ROLLBACK IMPLEMENTATION
        int successfulRollbacks = 0;
        int failedRollbacks = 0;
        ServerWorld world = source.getWorld();

        int totalActions = containerLogs.size() + blockLogs.size() + signLogs.size();

        if (totalActions == 0) {
            source.sendFeedback(() -> Text.literal("[MineTracer] No actions found matching the specified filters.")
                    .formatted(Formatting.YELLOW), false);
            return Command.SINGLE_SUCCESS;
        }

        source.sendFeedback(() -> Text.literal("[MineTracer] Found " + totalActions + " actions to rollback.")
                .formatted(Formatting.AQUA), false);

        // Process container rollbacks
        for (LogStorage.LogEntry entry : containerLogs) {
            if ("withdrew".equals(entry.action)) {
                if (performWithdrawalRollback(world, entry)) {
                    successfulRollbacks++;
                } else {
                    failedRollbacks++;
                }
            } else if ("deposited".equals(entry.action)) {
                if (performDepositRollback(world, entry)) {
                    successfulRollbacks++;
                } else {
                    failedRollbacks++;
                }
            }
        }

        // Process block rollbacks (placed blocks -> break them, broken blocks ->
        // restore them)
        for (LogStorage.BlockLogEntry entry : blockLogs) {
            if ("placed".equals(entry.action)) {
                if (performBlockBreakRollback(world, entry)) {
                    successfulRollbacks++;
                } else {
                    failedRollbacks++;
                }
            } else if ("broke".equals(entry.action)) {
                if (performBlockPlaceRollback(world, entry)) {
                    successfulRollbacks++;
                } else {
                    failedRollbacks++;
                }
            }
        }

        // Process sign rollbacks (edit -> restore original text)
        for (LogStorage.SignLogEntry entry : signLogs) {
            if ("edit".equals(entry.action)) {
                if (performSignRollback(world, entry)) {
                    successfulRollbacks++;
                } else {
                    failedRollbacks++;
                }
            }
        }

        // Send feedback about rollback results
        if (successfulRollbacks > 0 || failedRollbacks > 0) {
            final int finalSuccessfulRollbacks = successfulRollbacks;
            final int finalFailedRollbacks = failedRollbacks;
            source.sendFeedback(() -> Text.literal(
                    "[MineTracer] Rollback complete: " + finalSuccessfulRollbacks + " actions restored, " +
                            finalFailedRollbacks + " failed.")
                    .formatted(Formatting.GREEN), false);
        } else {
            source.sendFeedback(
                    () -> Text.literal("[MineTracer] No actions found to rollback.").formatted(Formatting.YELLOW),
                    false);
        }

        return Command.SINGLE_SUCCESS;
    }

    private static boolean performWithdrawalRollback(ServerWorld world, LogStorage.LogEntry entry) {
        try {
            BlockPos pos = entry.pos;
            ItemStack stackToRestore = entry.stack.copy();

            // Check if there's a container at this position
            net.minecraft.block.entity.BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity instanceof Inventory) {
                Inventory inventory = (Inventory) blockEntity;
                // Try to add the item back to the container
                ItemStack remaining = addItemToInventory(inventory, stackToRestore);

                // Mark the inventory as changed so the game updates it
                inventory.markDirty();

                // Return true if we successfully added at least some of the stack
                return remaining.getCount() < stackToRestore.getCount();
            }
            return false;
        } catch (RuntimeException e) {
            System.err.println("[MineTracer] Error during withdrawal rollback: " + e.getMessage());
            return false;
        }
    }

    private static boolean performDepositRollback(ServerWorld world, LogStorage.LogEntry entry) {
        try {
            BlockPos pos = entry.pos;
            ItemStack stackToRemove = entry.stack.copy();

            // Check if there's a container at this position
            net.minecraft.block.entity.BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity instanceof Inventory) {
                Inventory inventory = (Inventory) blockEntity;
                // Try to remove the item from the container
                ItemStack remaining = removeItemFromInventory(inventory, stackToRemove);

                // Mark the inventory as changed so the game updates it
                inventory.markDirty();

                // Return true if we successfully removed at least some of the stack
                return remaining.getCount() < stackToRemove.getCount();
            }
            return false;
        } catch (RuntimeException e) {
            System.err.println("[MineTracer] Error during deposit rollback: " + e.getMessage());
            return false;
        }
    }

    private static ItemStack removeItemFromInventory(Inventory inventory, ItemStack stackToRemove) {
        ItemStack remaining = stackToRemove.copy();

        // Go through the inventory and remove matching items
        for (int i = 0; i < inventory.size() && !remaining.isEmpty(); i++) {
            ItemStack existingStack = inventory.getStack(i);
            if (!existingStack.isEmpty() && ItemStack.canCombine(existingStack, remaining)) {
                int canRemove = Math.min(existingStack.getCount(), remaining.getCount());
                if (canRemove > 0) {
                    existingStack.decrement(canRemove);
                    remaining.decrement(canRemove);

                    // If the stack is now empty, set it to empty
                    if (existingStack.isEmpty()) {
                        inventory.setStack(i, ItemStack.EMPTY);
                    } else {
                        inventory.setStack(i, existingStack);
                    }
                }
            }
        }

        return remaining;
    }

    private static ItemStack addItemToInventory(Inventory inventory, ItemStack stack) {
        ItemStack remaining = stack.copy();

        // First pass: try to stack with existing items
        for (int i = 0; i < inventory.size() && !remaining.isEmpty(); i++) {
            ItemStack existingStack = inventory.getStack(i);
            if (!existingStack.isEmpty() && ItemStack.canCombine(existingStack, remaining)) {
                int maxStackSize = existingStack.getMaxCount();
                int canAdd = maxStackSize - existingStack.getCount();
                if (canAdd > 0) {
                    int toAdd = Math.min(canAdd, remaining.getCount());
                    existingStack.increment(toAdd);
                    remaining.decrement(toAdd);
                    inventory.setStack(i, existingStack);
                }
            }
        }

        // Second pass: try to place in empty slots
        for (int i = 0; i < inventory.size() && !remaining.isEmpty(); i++) {
            ItemStack existingStack = inventory.getStack(i);
            if (existingStack.isEmpty()) {
                int maxStackSize = remaining.getMaxCount();
                int toPlace = Math.min(maxStackSize, remaining.getCount());
                ItemStack toSet = remaining.copy();
                toSet.setCount(toPlace);
                inventory.setStack(i, toSet);
                remaining.decrement(toPlace);
            }
        }

        return remaining;
    }

    private static boolean performBlockBreakRollback(ServerWorld world, LogStorage.BlockLogEntry entry) {
        try {
            BlockPos pos = entry.pos;
            // Break the placed block by setting it to air
            world.setBlockState(pos, net.minecraft.block.Blocks.AIR.getDefaultState());
            return true;
        } catch (Exception e) {
            System.err.println("[MineTracer] Error during block break rollback: " + e.getMessage());
            return false;
        }
    }

    private static boolean performBlockPlaceRollback(ServerWorld world, LogStorage.BlockLogEntry entry) {
        try {
            BlockPos pos = entry.pos;
            // Restore the broken block
            net.minecraft.block.Block block = net.minecraft.registry.Registries.BLOCK
                    .get(new net.minecraft.util.Identifier(entry.blockId));
            if (block != null && block != net.minecraft.block.Blocks.AIR) {
                net.minecraft.block.BlockState blockState = block.getDefaultState();

                // Try to restore NBT data and block state properties if available
                if (entry.nbt != null && !entry.nbt.isEmpty() && !entry.nbt.equals("{}")) {
                    try {
                        net.minecraft.nbt.NbtCompound nbtCompound = net.minecraft.nbt.StringNbtReader.parse(entry.nbt);

                        // Check if NBT contains block state properties
                        if (nbtCompound.contains("Properties")) {
                            net.minecraft.nbt.NbtCompound properties = nbtCompound.getCompound("Properties");

                            // Apply each property to the block state
                            for (String key : properties.getKeys()) {
                                String value = properties.getString(key);
                                try {
                                    net.minecraft.state.property.Property<?> property = null;
                                    for (net.minecraft.state.property.Property<?> prop : blockState.getProperties()) {
                                        if (prop.getName().equals(key)) {
                                            property = prop;
                                            break;
                                        }
                                    }
                                    if (property != null) {
                                        blockState = setBlockStateProperty(blockState, property, value);
                                    }
                                } catch (Exception e) {
                                    System.err.println("[MineTracer] Failed to apply property " + key + "=" + value
                                            + ": " + e.getMessage());
                                }
                            }
                        }

                        // Set the block state first
                        world.setBlockState(pos, blockState);

                        // Then try to restore block entity data if present
                        if (nbtCompound.contains("BlockEntityTag")) {
                            net.minecraft.nbt.NbtCompound blockEntityData = nbtCompound.getCompound("BlockEntityTag");
                            net.minecraft.block.entity.BlockEntity blockEntity = world.getBlockEntity(pos);
                            if (blockEntity != null) {
                                blockEntity.readNbt(blockEntityData);
                                blockEntity.markDirty();
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("[MineTracer] Failed to parse NBT for block restoration: " + e.getMessage());
                        // Fall back to placing the block without NBT data
                        world.setBlockState(pos, blockState);
                    }
                } else {
                    // No NBT data, just place the block
                    world.setBlockState(pos, blockState);
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            System.err.println("[MineTracer] Error during block place rollback: " + e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> net.minecraft.block.BlockState setBlockStateProperty(
            net.minecraft.block.BlockState state, net.minecraft.state.property.Property<T> property, String value) {
        java.util.Optional<T> parsedValue = property.parse(value);
        if (parsedValue.isPresent()) {
            return state.with(property, parsedValue.get());
        }
        return state;
    }

    private static boolean performSignRollback(ServerWorld world, LogStorage.SignLogEntry entry) {
        try {
            BlockPos pos = entry.pos;

            // Check if there's a sign at this position
            net.minecraft.block.entity.BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity instanceof net.minecraft.block.entity.SignBlockEntity) {
                net.minecraft.block.entity.SignBlockEntity signEntity = (net.minecraft.block.entity.SignBlockEntity) blockEntity;

                // Parse the NBT to get the before text
                if (entry.nbt != null && !entry.nbt.isEmpty()) {
                    try {
                        com.google.gson.Gson gson = new com.google.gson.Gson();
                        com.google.gson.JsonObject nbtObj = gson.fromJson(entry.nbt, com.google.gson.JsonObject.class);
                        String[] beforeLines = gson.fromJson(nbtObj.get("before"), String[].class);

                        // Create Text objects from the before lines
                        net.minecraft.text.Text[] beforeTexts = new net.minecraft.text.Text[4];
                        for (int i = 0; i < 4; i++) {
                            if (i < beforeLines.length && beforeLines[i] != null) {
                                beforeTexts[i] = net.minecraft.text.Text.literal(beforeLines[i]);
                            } else {
                                beforeTexts[i] = net.minecraft.text.Text.literal("");
                            }
                        }

                        // Try to restore the sign text by directly modifying the NBT
                        try {
                            net.minecraft.nbt.NbtCompound signNbt = signEntity.createNbt();

                            // Modify the front text in the NBT
                            if (signNbt.contains("front_text")) {
                                net.minecraft.nbt.NbtCompound frontText = signNbt.getCompound("front_text");
                                net.minecraft.nbt.NbtList messages = new net.minecraft.nbt.NbtList();
                                for (net.minecraft.text.Text text : beforeTexts) {
                                    // Use the text's JSON representation
                                    String jsonText = net.minecraft.text.Text.Serializer.toJson(text);
                                    messages.add(net.minecraft.nbt.NbtString.of(jsonText));
                                }
                                frontText.put("messages", messages);
                                signNbt.put("front_text", frontText);
                                signEntity.readNbt(signNbt);
                            }
                        } catch (Exception nbtError) {
                            System.err.println("[MineTracer] Could not restore sign via NBT: " + nbtError.getMessage());
                            return false;
                        }

                        // Mark the sign as dirty to save changes
                        signEntity.markDirty();

                        // Update the block to trigger client updates
                        world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3);

                        return true;
                    } catch (Exception e) {
                        System.err.println("[MineTracer] Failed to parse sign NBT for rollback: " + e.getMessage());
                        return false;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            System.err.println("[MineTracer] Error during sign rollback: " + e.getMessage());
            return false;
        }
    }

    public static int lookupPage(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!Permissions.check(source, "minetracer.command.page", 2)) {
            source.sendError(Text.literal("You do not have permission to use this command."));
            return 0;
        }

        UUID playerId = source.getPlayer().getUuid();
        QueryContext queryContext = lastQueries.get(playerId);
        if (queryContext == null) {
            source.sendError(Text.literal("No previous lookup found. Please run a lookup command first."));
            return 0;
        }

        int page = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "page");
        displayPage(source, queryContext.results, page, queryContext.entriesPerPage);

        return Command.SINGLE_SUCCESS;
    }

    public static int toggleInspector(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!Permissions.check(source, "minetracer.command.inspector", 2)) {
            source.sendError(Text.literal("You do not have permission to use this command."));
            return 0;
        }

        ServerPlayerEntity player = source.getPlayer();
        boolean isInspector = LogStorage.isInspectorMode(player);

        if (isInspector) {
            LogStorage.setInspectorMode(player, false);
            source.sendFeedback(() -> Text.literal("Inspector mode disabled.").formatted(Formatting.YELLOW), false);
        } else {
            LogStorage.setInspectorMode(player, true);
            source.sendFeedback(
                    () -> Text.literal("Inspector mode enabled. Right-click or break blocks to see their history.")
                            .formatted(Formatting.GREEN),
                    false);
        }

        return Command.SINGLE_SUCCESS;
    }

    public static int forceSave(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!Permissions.check(source, "minetracer.command.save", 2)) {
            source.sendError(Text.literal("You do not have permission to use this command."));
            return 0;
        }

        source.sendFeedback(() -> Text.literal("Forcing save of all log data...").formatted(Formatting.YELLOW), false);

        // Force immediate save
        try {
            LogStorage.forceSave();
            source.sendFeedback(
                    () -> Text.literal("Successfully saved all log data to disk.").formatted(Formatting.GREEN), false);
        } catch (Exception e) {
            source.sendError(Text.literal("Error saving log data: " + e.getMessage()));
            return 0;
        }

        return Command.SINGLE_SUCCESS;
    }

    private static long parseTimeArg(String timeArg) {
        try {
            if (timeArg.endsWith("s")) {
                return Long.parseLong(timeArg.substring(0, timeArg.length() - 1));
            } else if (timeArg.endsWith("m")) {
                return Long.parseLong(timeArg.substring(0, timeArg.length() - 1)) * 60;
            } else if (timeArg.endsWith("h")) {
                return Long.parseLong(timeArg.substring(0, timeArg.length() - 1)) * 3600;
            } else if (timeArg.endsWith("d")) {
                return Long.parseLong(timeArg.substring(0, timeArg.length() - 1)) * 86400;
            } else {
                return Long.parseLong(timeArg);
            }
        } catch (NumberFormatException e) {
            return 3600; // Default to 1 hour
        }
    }

    private static String getTimeAgo(long seconds) {
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
