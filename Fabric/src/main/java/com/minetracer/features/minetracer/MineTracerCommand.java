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
                            .executes(MineTracerCommand::save))
                    .executes(context -> {
                        ServerCommandSource source = context.getSource();
                        source.sendError(Text.literal("Invalid command usage. Use /minetracer <lookup|rollback|page|inspector|save>"));
                        return 0;
                    }));
        });
    }
    public static CompletableFuture<Suggestions> suggestPlayers(CommandContext<ServerCommandSource> ctx,
            SuggestionsBuilder builder) {
        String input = builder.getInput();
        String remaining = builder.getRemaining();
        String[] remainingParts = remaining.split(" ");
        String currentTyping = remainingParts[remainingParts.length - 1];
        boolean justAddedSpace = remaining.endsWith(" ");
        java.util.Set<String> usedFilters = new java.util.HashSet<>();
        for (String part : remaining.split(" ")) {
            if (part.contains(":")) {
                String filterType = part.substring(0, part.indexOf(":") + 1);
                usedFilters.add(filterType);
            }
        }
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
        else if (currentTyping.startsWith("user:")) {
            String userPart = currentTyping.substring(5);
            String beforeCurrent = remaining.substring(0, remaining.lastIndexOf(currentTyping));
            java.util.Set<String> allPlayerNames = new java.util.HashSet<>();
            for (ServerPlayerEntity player : ctx.getSource().getServer().getPlayerManager().getPlayerList()) {
                allPlayerNames.add(player.getName().getString());
            }
            try {
                allPlayerNames.addAll(OptimizedLogStorage.getAllPlayerNames());
            } catch (Exception e) {
            }
            for (String playerName : allPlayerNames) {
                if (playerName.toLowerCase().startsWith(userPart.toLowerCase())) {
                    builder.suggest(beforeCurrent + "user:" + playerName);
                }
            }
        } else if (currentTyping.startsWith("action:")) {
            String actionPart = currentTyping.substring(7);
            String[] actions = { "withdrew", "deposited", "broke", "placed", "pickup", "drop", "sign", "kill" };
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
            int maxSuggestions = 20;
            int count = 0;
            for (Identifier itemId : Registries.ITEM.getIds()) {
                if (count >= maxSuggestions)
                    break;
                String itemName = itemId.toString();
                if (itemName.toLowerCase().contains(itemPart.toLowerCase()) || itemPart.isEmpty()) {
                    builder.suggest(beforeCurrent + "include:" + itemName);
                    count++;
                }
            }
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
        String arg = StringArgumentType.getString(ctx, "arg");
        CompletableFuture.supplyAsync(() -> {
            String userFilter = null;
            String timeArg = null;
            int range = 100;
            java.util.Set<String> actionFilters = new java.util.HashSet<>();
            String includeItem = null;
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
            boolean hasRange = range != 100; // 100 is the default, so anything else means range was specified
            boolean hasTime = timeArg != null;
            boolean hasUser = userFilter != null;
            int restrictionCount = (hasRange ? 1 : 0) + (hasTime ? 1 : 0) + (hasUser ? 1 : 0);
            if (restrictionCount < 2) {
                source.sendError(Text.literal(
                        "Lookup requires at least 2 of these filters: range:<blocks>, time:<duration>, user:<player>. Examples: 'range:50 user:PlayerName' or 'time:1h user:PlayerName' or 'range:20 time:30m'"));
                return null;
            }
            BlockPos searchCenter = playerPos;
            int searchRange = range;
            if (hasUser && !hasRange) {
                searchRange = 50000; // Very large range for global search
            }
            boolean filterByKiller = actionFilters.contains("kill");
            CompletableFuture<List<OptimizedLogStorage.BlockLogEntry>> blockLogsFuture;
            CompletableFuture<List<OptimizedLogStorage.SignLogEntry>> signLogsFuture;
            CompletableFuture<List<OptimizedLogStorage.LogEntry>> containerLogsFuture;
            CompletableFuture<List<OptimizedLogStorage.KillLogEntry>> killLogsFuture;
            CompletableFuture<List<OptimizedLogStorage.ItemPickupDropLogEntry>> itemLogsFuture;
            if (hasUser && !hasRange) {
                blockLogsFuture = OptimizedLogStorage.getBlockLogsForUserAsync(userFilter);
                signLogsFuture = OptimizedLogStorage.getSignLogsForUserAsync(userFilter);
                containerLogsFuture = OptimizedLogStorage.getContainerLogsForUserAsync(userFilter);
                killLogsFuture = OptimizedLogStorage.getKillLogsForUserAsync(userFilter, filterByKiller);
                itemLogsFuture = OptimizedLogStorage.getItemPickupDropLogsForUserAsync(userFilter);
            } else {
                blockLogsFuture = OptimizedLogStorage.getBlockLogsInRangeAsync(playerPos, range, userFilter);
                signLogsFuture = OptimizedLogStorage.getSignLogsInRangeAsync(playerPos, range, userFilter);
                containerLogsFuture = OptimizedLogStorage.getLogsInRangeAsync(playerPos, range);
                killLogsFuture = OptimizedLogStorage.getKillLogsInRangeAsync(playerPos, range, userFilter, filterByKiller);
                itemLogsFuture = OptimizedLogStorage.getItemPickupDropLogsInRangeAsync(playerPos, range, userFilter);
            }
            try {
                List<OptimizedLogStorage.BlockLogEntry> blockLogs = blockLogsFuture.get();
                List<OptimizedLogStorage.SignLogEntry> signLogs = signLogsFuture.get();
                List<OptimizedLogStorage.LogEntry> containerLogs = containerLogsFuture.get();
                List<OptimizedLogStorage.KillLogEntry> killLogs = killLogsFuture.get();
                List<OptimizedLogStorage.ItemPickupDropLogEntry> itemLogs = itemLogsFuture.get();
                if (userFilter != null && !(hasUser && !hasRange)) {
                    final String userFilterFinal = userFilter;
                    containerLogs.removeIf(entry -> !entry.playerName.equalsIgnoreCase(userFilterFinal));
                }
                if (cutoff != null) {
                    final Instant cutoffFinal = cutoff;
                    blockLogs.removeIf(entry -> entry.timestamp.isBefore(cutoffFinal));
                    signLogs.removeIf(entry -> entry.timestamp.isBefore(cutoffFinal));
                    containerLogs.removeIf(entry -> entry.timestamp.isBefore(cutoffFinal));
                    killLogs.removeIf(entry -> entry.timestamp.isBefore(cutoffFinal));
                    itemLogs.removeIf(entry -> entry.timestamp.isBefore(cutoffFinal));
                }
                if (!actionFilters.isEmpty()) {
                    containerLogs.removeIf(
                            entry -> actionFilters.stream().noneMatch(filter -> entry.action.equalsIgnoreCase(filter)));
                    blockLogs.removeIf(
                            entry -> actionFilters.stream().noneMatch(filter -> entry.action.equalsIgnoreCase(filter)));
                    signLogs.removeIf(
                            entry -> actionFilters.stream().noneMatch(filter -> entry.action.equalsIgnoreCase(filter)));
                    killLogs.removeIf(
                            entry -> actionFilters.stream().noneMatch(filter -> entry.action.equalsIgnoreCase(filter)));
                    itemLogs.removeIf(
                            entry -> actionFilters.stream().noneMatch(filter -> entry.action.equalsIgnoreCase(filter)));
                }
                if (includeItem != null && !includeItem.isEmpty()) {
                    final String includeItemFinal = includeItem;
                    containerLogs.removeIf(
                            entry -> !Registries.ITEM.getId(entry.stack.getItem()).toString().equals(includeItemFinal));
                    blockLogs.removeIf(entry -> !entry.blockId.equals(includeItemFinal));
                }
                List<FlatLogEntry> flatList = new ArrayList<>();
                for (OptimizedLogStorage.LogEntry entry : containerLogs) {
                    flatList.add(new FlatLogEntry(entry, "container"));
                }
                for (OptimizedLogStorage.BlockLogEntry entry : blockLogs) {
                    flatList.add(new FlatLogEntry(entry, "block"));
                }
                for (OptimizedLogStorage.SignLogEntry entry : signLogs) {
                    flatList.add(new FlatLogEntry(entry, "sign"));
                }
                for (OptimizedLogStorage.KillLogEntry entry : killLogs) {
                    flatList.add(new FlatLogEntry(entry, "kill"));
                }
                for (OptimizedLogStorage.ItemPickupDropLogEntry entry : itemLogs) {
                    flatList.add(new FlatLogEntry(entry, "item"));
                }
                flatList.sort((a, b) -> {
                    Instant aTime = a.entry instanceof OptimizedLogStorage.LogEntry ? ((OptimizedLogStorage.LogEntry) a.entry).timestamp
                            : a.entry instanceof OptimizedLogStorage.BlockLogEntry
                                    ? ((OptimizedLogStorage.BlockLogEntry) a.entry).timestamp
                                    : a.entry instanceof OptimizedLogStorage.SignLogEntry
                                            ? ((OptimizedLogStorage.SignLogEntry) a.entry).timestamp
                                            : a.entry instanceof OptimizedLogStorage.KillLogEntry
                                                    ? ((OptimizedLogStorage.KillLogEntry) a.entry).timestamp
                                                    : a.entry instanceof OptimizedLogStorage.ItemPickupDropLogEntry
                                                            ? ((OptimizedLogStorage.ItemPickupDropLogEntry) a.entry).timestamp
                                                            : Instant.EPOCH;
                    Instant bTime = b.entry instanceof OptimizedLogStorage.LogEntry ? ((OptimizedLogStorage.LogEntry) b.entry).timestamp
                            : b.entry instanceof OptimizedLogStorage.BlockLogEntry
                                    ? ((OptimizedLogStorage.BlockLogEntry) b.entry).timestamp
                                    : b.entry instanceof OptimizedLogStorage.SignLogEntry
                                            ? ((OptimizedLogStorage.SignLogEntry) b.entry).timestamp
                                            : b.entry instanceof OptimizedLogStorage.KillLogEntry
                                                    ? ((OptimizedLogStorage.KillLogEntry) b.entry).timestamp
                                                    : b.entry instanceof OptimizedLogStorage.ItemPickupDropLogEntry
                                                            ? ((OptimizedLogStorage.ItemPickupDropLogEntry) b.entry).timestamp
                                                            : Instant.EPOCH;
                    return bTime.compareTo(aTime);
                });
                return flatList;
            } catch (Exception e) {
                throw new RuntimeException("Error executing lookup", e);
            }
        }).thenAccept(flatList -> {
            QueryContext queryContext = new QueryContext(flatList, arg, source.getPlayer().getBlockPos());
            lastQueries.put(source.getPlayer().getUuid(), queryContext);
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
            source.sendFeedback(() -> formatCoordinatesForChat(fle.entry), false);
            source.sendFeedback(() -> formatLogEntryForChat(fle.entry), false);
            if (fle.entry instanceof OptimizedLogStorage.SignLogEntry) {
                OptimizedLogStorage.SignLogEntry se = (OptimizedLogStorage.SignLogEntry) fle.entry;
                if (se.action.equals("edit") && se.nbt != null && !se.nbt.isEmpty()) {
                    try {
                        com.google.gson.Gson gson = new com.google.gson.Gson();
                        com.google.gson.JsonObject nbtObj = gson.fromJson(se.nbt, com.google.gson.JsonObject.class);
                        String[] beforeLines = gson.fromJson(nbtObj.get("before"), String[].class);
                        String[] afterLines = gson.fromJson(nbtObj.get("after"), String[].class);
                        source.sendFeedback(() -> Text.literal("[before]").formatted(Formatting.RED), false);
                        for (String line : beforeLines) {
                            if (line != null && !line.trim().isEmpty()) {
                                source.sendFeedback(() -> Text.literal("  " + line).formatted(Formatting.WHITE), false);
                            }
                        }
                        source.sendFeedback(() -> Text.literal("[after]").formatted(Formatting.GREEN), false);
                        for (String line : afterLines) {
                            if (line != null && !line.trim().isEmpty()) {
                                source.sendFeedback(() -> Text.literal("  " + line).formatted(Formatting.WHITE), false);
                            }
                        }
                    } catch (Exception e) {
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
        if (entry instanceof OptimizedLogStorage.LogEntry) {
            OptimizedLogStorage.LogEntry ce = (OptimizedLogStorage.LogEntry) entry;
            String timeAgo = getTimeAgo(Duration.between(ce.timestamp, Instant.now()).getSeconds());
            String itemId = Registries.ITEM.getId(ce.stack.getItem()).toString();
            String itemName = ce.stack.getItem().getName().getString();
            boolean isRolledBack = ce.rolledBack;
            Text base = Text.literal(timeAgo + " ago").formatted(Formatting.WHITE)
                    .append(Text.literal(" — ").formatted(Formatting.WHITE))
                    .append(Text.literal(ce.playerName).formatted(Formatting.AQUA))
                    .append(Text.literal(" " + ce.action + " ").formatted(Formatting.GREEN))
                    .append(Text.literal(ce.stack.getCount() + "x ").formatted(Formatting.WHITE))
                    .append(Text.literal("#" + itemId).formatted(Formatting.YELLOW))
                    .append(Text.literal(" (" + itemName + ")").formatted(Formatting.GRAY));
            if (isRolledBack) {
                base = base.copy().setStyle(base.getStyle().withStrikethrough(true).withColor(Formatting.DARK_GRAY));
            }
            return base;
        } else if (entry instanceof OptimizedLogStorage.BlockLogEntry) {
            OptimizedLogStorage.BlockLogEntry be = (OptimizedLogStorage.BlockLogEntry) entry;
            String timeAgo = getTimeAgo(Duration.between(be.timestamp, Instant.now()).getSeconds());
            net.minecraft.block.Block block = Registries.BLOCK.get(new Identifier(be.blockId));
            String blockName = block.getName().getString();
            boolean isRolledBack = be.rolledBack;
            Text base = Text.literal(timeAgo + " ago").formatted(Formatting.WHITE)
                    .append(Text.literal(" — ").formatted(Formatting.WHITE))
                    .append(Text.literal(be.playerName).formatted(Formatting.AQUA))
                    .append(Text.literal(" " + be.action + " block ").formatted(Formatting.GREEN))
                    .append(Text.literal("#" + be.blockId).formatted(Formatting.YELLOW))
                    .append(Text.literal(" (" + blockName + ")").formatted(Formatting.GRAY));
            if (isRolledBack) {
                base = base.copy().setStyle(base.getStyle().withStrikethrough(true).withColor(Formatting.DARK_GRAY));
            }
            return base;
        } else if (entry instanceof OptimizedLogStorage.SignLogEntry) {
            OptimizedLogStorage.SignLogEntry se = (OptimizedLogStorage.SignLogEntry) entry;
            String timeAgo = getTimeAgo(Duration.between(se.timestamp, Instant.now()).getSeconds());
            boolean isRolledBack = se.rolledBack;
            Text base = Text.literal(timeAgo + " ago").formatted(Formatting.WHITE)
                    .append(Text.literal(" — ").formatted(Formatting.WHITE))
                    .append(Text.literal(se.playerName).formatted(Formatting.AQUA))
                    .append(Text.literal(" edited sign").formatted(Formatting.YELLOW));
            if (isRolledBack) {
                base = base.copy().setStyle(base.getStyle().withStrikethrough(true).withColor(Formatting.DARK_GRAY));
            }
            return base;
        } else if (entry instanceof OptimizedLogStorage.KillLogEntry) {
            OptimizedLogStorage.KillLogEntry ke = (OptimizedLogStorage.KillLogEntry) entry;
            String timeAgo = getTimeAgo(Duration.between(ke.timestamp, Instant.now()).getSeconds());
            boolean isRolledBack = ke.rolledBack;
            Text base = Text.literal(timeAgo + " ago").formatted(Formatting.WHITE)
                    .append(Text.literal(" — ").formatted(Formatting.WHITE))
                    .append(Text.literal(ke.killerName).formatted(Formatting.AQUA))
                    .append(Text.literal(" killed ").formatted(Formatting.GREEN))
                    .append(Text.literal(ke.victimName).formatted(Formatting.RED));
            if (isRolledBack) {
                base = base.copy().setStyle(base.getStyle().withStrikethrough(true).withColor(Formatting.DARK_GRAY));
            }
            return base;
        } else if (entry instanceof OptimizedLogStorage.ItemPickupDropLogEntry) {
            OptimizedLogStorage.ItemPickupDropLogEntry ie = (OptimizedLogStorage.ItemPickupDropLogEntry) entry;
            String timeAgo = getTimeAgo(Duration.between(ie.timestamp, Instant.now()).getSeconds());
            String itemId = Registries.ITEM.getId(ie.stack.getItem()).toString();
            String itemName = ie.stack.getItem().getName().getString();
            Text base = Text.literal(timeAgo + " ago").formatted(Formatting.WHITE)
                    .append(Text.literal(" — ").formatted(Formatting.WHITE))
                    .append(Text.literal(ie.playerName).formatted(Formatting.AQUA))
                    .append(Text.literal(" " + ie.action + " ").formatted(Formatting.GREEN))
                    .append(Text.literal(ie.stack.getCount() + "x ").formatted(Formatting.WHITE))
                    .append(Text.literal("#" + itemId).formatted(Formatting.YELLOW))
                    .append(Text.literal(" (" + itemName + ")").formatted(Formatting.GRAY));
            return base;
        }
        return Text.literal("Unknown log entry").formatted(Formatting.GRAY);
    }
    public static Text formatCoordinatesForChat(Object entry) {
        BlockPos pos = null;
        if (entry instanceof OptimizedLogStorage.LogEntry) {
            pos = ((OptimizedLogStorage.LogEntry) entry).pos;
        } else if (entry instanceof OptimizedLogStorage.BlockLogEntry) {
            pos = ((OptimizedLogStorage.BlockLogEntry) entry).pos;
        } else if (entry instanceof OptimizedLogStorage.SignLogEntry) {
            pos = ((OptimizedLogStorage.SignLogEntry) entry).pos;
        } else if (entry instanceof OptimizedLogStorage.KillLogEntry) {
            pos = ((OptimizedLogStorage.KillLogEntry) entry).pos;
        } else if (entry instanceof OptimizedLogStorage.ItemPickupDropLogEntry) {
            pos = ((OptimizedLogStorage.ItemPickupDropLogEntry) entry).pos;
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
        String arg = StringArgumentType.getString(ctx, "arg");
        String userFilter = null;
        String timeArg = null;
        int range = 100;
        java.util.Set<String> actionFilters = new java.util.HashSet<>();
        String includeItem = null;
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
        boolean hasRange = range != 100; // 100 is the default, so anything else means range was specified
        boolean hasTime = timeArg != null;
        boolean hasUser = userFilter != null;
        int restrictionCount = (hasRange ? 1 : 0) + (hasTime ? 1 : 0) + (hasUser ? 1 : 0);
        if (restrictionCount < 2) {
            source.sendError(Text.literal(
                    "Rollback requires at least 2 of these filters: range:<blocks>, time:<duration>, user:<player>. Examples: 'range:50 user:PlayerName' or 'time:1h user:PlayerName' or 'range:20 time:30m'"));
            return Command.SINGLE_SUCCESS;
        }
        List<OptimizedLogStorage.BlockLogEntry> blockLogs = OptimizedLogStorage.getBlockLogsInRange(playerPos, range, userFilter);
        List<OptimizedLogStorage.SignLogEntry> signLogs = OptimizedLogStorage.getSignLogsInRange(playerPos, range, userFilter);
        List<OptimizedLogStorage.LogEntry> containerLogs = OptimizedLogStorage.getLogsInRange(playerPos, range);
        boolean filterByKiller = actionFilters.contains("kill");
        List<OptimizedLogStorage.KillLogEntry> killLogs = OptimizedLogStorage.getKillLogsInRange(playerPos, range, userFilter,
                filterByKiller);
        if (userFilter != null) {
            final String userFilterFinal = userFilter;
            containerLogs.removeIf(entry -> !entry.playerName.equalsIgnoreCase(userFilterFinal));
        }
        if (cutoff != null) {
            final Instant cutoffFinal = cutoff;
            blockLogs.removeIf(entry -> entry.timestamp.isBefore(cutoffFinal));
            signLogs.removeIf(entry -> entry.timestamp.isBefore(cutoffFinal));
            containerLogs.removeIf(entry -> entry.timestamp.isBefore(cutoffFinal));
            killLogs.removeIf(entry -> entry.timestamp.isBefore(cutoffFinal));
        }
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
        if (includeItem != null && !includeItem.isEmpty()) {
            final String includeItemFinal = includeItem;
            containerLogs.removeIf(
                    entry -> !Registries.ITEM.getId(entry.stack.getItem()).toString().equals(includeItemFinal));
            blockLogs.removeIf(entry -> !entry.blockId.equals(includeItemFinal));
        }
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
        if (actionFilters.isEmpty()) {
            blockLogs.sort((a, b) -> b.timestamp.compareTo(a.timestamp));
            signLogs.sort((a, b) -> b.timestamp.compareTo(a.timestamp));
            containerLogs.sort((a, b) -> b.timestamp.compareTo(a.timestamp));
            source.sendFeedback(() -> Text.literal("[MineTracer] Processing rollback in reverse chronological order (newest actions first).")
                    .formatted(Formatting.GRAY), false);
        }
        if (actionFilters.isEmpty()) {
            for (OptimizedLogStorage.BlockLogEntry entry : blockLogs) {
                if ("broke".equals(entry.action) && !entry.rolledBack) {
                    if (performBlockPlaceRollback(world, entry)) {
                        entry.rolledBack = true;
                        successfulRollbacks++;
                    } else {
                        failedRollbacks++;
                    }
                }
            }
            for (OptimizedLogStorage.BlockLogEntry entry : blockLogs) {
                if ("placed".equals(entry.action) && !entry.rolledBack) {
                    if (performBlockBreakRollback(world, entry)) {
                        entry.rolledBack = true;
                        successfulRollbacks++;
                    } else {
                        failedRollbacks++;
                    }
                }
            }
            for (OptimizedLogStorage.LogEntry entry : containerLogs) {
                if (!entry.rolledBack) {
                    if ("withdrew".equals(entry.action)) {
                        if (performWithdrawalRollback(world, entry)) {
                            entry.rolledBack = true;
                            successfulRollbacks++;
                        } else {
                            failedRollbacks++;
                        }
                    } else if ("deposited".equals(entry.action)) {
                        if (performDepositRollback(world, entry)) {
                            entry.rolledBack = true;
                            successfulRollbacks++;
                        } else {
                            failedRollbacks++;
                        }
                    }
                }
            }
            for (OptimizedLogStorage.SignLogEntry entry : signLogs) {
                if ("edit".equals(entry.action) && !entry.rolledBack) {
                    if (performSignRollback(world, entry)) {
                        entry.rolledBack = true;
                        successfulRollbacks++;
                    } else {
                        failedRollbacks++;
                    }
                }
            }
        } else {
            for (OptimizedLogStorage.LogEntry entry : containerLogs) {
                if (!entry.rolledBack) {
                    if ("withdrew".equals(entry.action)) {
                        if (performWithdrawalRollback(world, entry)) {
                            entry.rolledBack = true;
                            successfulRollbacks++;
                        } else {
                            failedRollbacks++;
                        }
                    } else if ("deposited".equals(entry.action)) {
                        if (performDepositRollback(world, entry)) {
                            entry.rolledBack = true;
                            successfulRollbacks++;
                        } else {
                            failedRollbacks++;
                        }
                    }
                }
            }
            for (OptimizedLogStorage.BlockLogEntry entry : blockLogs) {
                if (!entry.rolledBack) {
                    if ("placed".equals(entry.action)) {
                        if (performBlockBreakRollback(world, entry)) {
                            entry.rolledBack = true;
                            successfulRollbacks++;
                        } else {
                            failedRollbacks++;
                        }
                    } else if ("broke".equals(entry.action)) {
                        if (performBlockPlaceRollback(world, entry)) {
                            entry.rolledBack = true;
                            successfulRollbacks++;
                        } else {
                            failedRollbacks++;
                        }
                    }
                }
            }
            for (OptimizedLogStorage.SignLogEntry entry : signLogs) {
                if ("edit".equals(entry.action) && !entry.rolledBack) {
                    if (performSignRollback(world, entry)) {
                        entry.rolledBack = true;
                        successfulRollbacks++;
                    } else {
                        failedRollbacks++;
                    }
                }
            }
        }
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
    private static boolean performWithdrawalRollback(ServerWorld world, OptimizedLogStorage.LogEntry entry) {
        try {
            BlockPos pos = entry.pos;
            ItemStack stackToRestore = entry.stack.copy();
            net.minecraft.block.entity.BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity instanceof Inventory) {
                Inventory inventory = (Inventory) blockEntity;
                ItemStack remaining = addItemToInventory(inventory, stackToRestore);
                inventory.markDirty();
                return remaining.getCount() < stackToRestore.getCount();
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }
    private static boolean performDepositRollback(ServerWorld world, OptimizedLogStorage.LogEntry entry) {
        try {
            BlockPos pos = entry.pos;
            ItemStack stackToRemove = entry.stack.copy();
            net.minecraft.block.entity.BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity instanceof Inventory) {
                Inventory inventory = (Inventory) blockEntity;
                ItemStack remaining = removeItemFromInventory(inventory, stackToRemove);
                inventory.markDirty();
                return remaining.getCount() < stackToRemove.getCount();
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }
    private static ItemStack removeItemFromInventory(Inventory inventory, ItemStack stackToRemove) {
        ItemStack remaining = stackToRemove.copy();
        for (int i = 0; i < inventory.size() && !remaining.isEmpty(); i++) {
            ItemStack existingStack = inventory.getStack(i);
            if (!existingStack.isEmpty() && ItemStack.canCombine(existingStack, remaining)) {
                int canRemove = Math.min(existingStack.getCount(), remaining.getCount());
                if (canRemove > 0) {
                    existingStack.decrement(canRemove);
                    remaining.decrement(canRemove);
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
    private static boolean performBlockBreakRollback(ServerWorld world, OptimizedLogStorage.BlockLogEntry entry) {
        try {
            BlockPos pos = entry.pos;
            world.setBlockState(pos, net.minecraft.block.Blocks.AIR.getDefaultState());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    private static boolean performBlockPlaceRollback(ServerWorld world, OptimizedLogStorage.BlockLogEntry entry) {
        try {
            BlockPos pos = entry.pos;
            net.minecraft.block.Block block = net.minecraft.registry.Registries.BLOCK
                    .get(new net.minecraft.util.Identifier(entry.blockId));
            if (block != null && block != net.minecraft.block.Blocks.AIR) {
                net.minecraft.block.BlockState blockState = block.getDefaultState();
                if (entry.nbt != null && !entry.nbt.isEmpty() && !entry.nbt.equals("{}")) {
                    try {
                        net.minecraft.nbt.NbtCompound nbtCompound = net.minecraft.nbt.StringNbtReader.parse(entry.nbt);
                        if (nbtCompound.contains("Properties")) {
                            net.minecraft.nbt.NbtCompound properties = nbtCompound.getCompound("Properties");
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
                                }
                            }
                        }
                        world.setBlockState(pos, blockState);
                        if (nbtCompound.contains("BlockEntityTag")) {
                            net.minecraft.nbt.NbtCompound blockEntityData = nbtCompound.getCompound("BlockEntityTag");
                            net.minecraft.block.entity.BlockEntity blockEntity = world.getBlockEntity(pos);
                            if (blockEntity != null) {
                                blockEntity.readNbt(blockEntityData);
                                blockEntity.markDirty();
                            }
                        }
                    } catch (Exception e) {
                        world.setBlockState(pos, blockState);
                    }
                } else {
                    world.setBlockState(pos, blockState);
                }
                return true;
            }
            return false;
        } catch (Exception e) {
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
    private static boolean performSignRollback(ServerWorld world, OptimizedLogStorage.SignLogEntry entry) {
        try {
            BlockPos pos = entry.pos;
            net.minecraft.block.entity.BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity instanceof net.minecraft.block.entity.SignBlockEntity) {
                net.minecraft.block.entity.SignBlockEntity signEntity = (net.minecraft.block.entity.SignBlockEntity) blockEntity;
                if (entry.nbt != null && !entry.nbt.isEmpty()) {
                    try {
                        com.google.gson.Gson gson = new com.google.gson.Gson();
                        com.google.gson.JsonObject nbtObj = gson.fromJson(entry.nbt, com.google.gson.JsonObject.class);
                        String[] beforeLines = gson.fromJson(nbtObj.get("before"), String[].class);
                        net.minecraft.text.Text[] beforeTexts = new net.minecraft.text.Text[4];
                        for (int i = 0; i < 4; i++) {
                            if (i < beforeLines.length && beforeLines[i] != null) {
                                beforeTexts[i] = net.minecraft.text.Text.literal(beforeLines[i]);
                            } else {
                                beforeTexts[i] = net.minecraft.text.Text.literal("");
                            }
                        }
                        try {
                            net.minecraft.nbt.NbtCompound signNbt = signEntity.createNbt();
                            if (signNbt.contains("front_text")) {
                                net.minecraft.nbt.NbtCompound frontText = signNbt.getCompound("front_text");
                                net.minecraft.nbt.NbtList messages = new net.minecraft.nbt.NbtList();
                                for (net.minecraft.text.Text text : beforeTexts) {
                                    String jsonText = net.minecraft.text.Text.Serializer.toJson(text);
                                    messages.add(net.minecraft.nbt.NbtString.of(jsonText));
                                }
                                frontText.put("messages", messages);
                                signNbt.put("front_text", frontText);
                                signEntity.readNbt(signNbt);
                            }
                        } catch (Exception nbtError) {
                            return false;
                        }
                        signEntity.markDirty();
                        world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
                        return true;
                    } catch (Exception e) {
                        return false;
                    }
                }
            }
            return false;
        } catch (Exception e) {
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
        boolean isInspector = OptimizedLogStorage.isInspectorMode(player);
        if (isInspector) {
            OptimizedLogStorage.setInspectorMode(player, false);
            source.sendFeedback(() -> Text.literal("Inspector mode disabled.").formatted(Formatting.YELLOW), false);
        } else {
            OptimizedLogStorage.setInspectorMode(player, true);
            source.sendFeedback(
                    () -> Text.literal("Inspector mode enabled. Right-click or break blocks to see their history.")
                            .formatted(Formatting.GREEN),
                    false);
        }
        return Command.SINGLE_SUCCESS;
    }
    public static int save(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!Permissions.check(source, "minetracer.command.save", 2)) {
            source.sendError(Text.literal("You do not have permission to use this command."));
            return 0;
        }
        source.sendFeedback(() -> Text.literal("Forcing save of all log data...").formatted(Formatting.YELLOW), false);
        try {
            OptimizedLogStorage.forceSave();
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
            double minutes = seconds / 60.0;
            return String.format("%.1fm", minutes);
        } else if (seconds < 86400) {
            double hours = seconds / 3600.0;
            return String.format("%.1fh", hours);
        } else {
            double days = seconds / 86400.0;
            return String.format("%.1fd", days);
        }
    }
}
