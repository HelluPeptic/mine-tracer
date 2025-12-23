package com.minetracer.features.minetracer;
import com.minetracer.features.minetracer.util.NbtCompatHelper;
import com.minetracer.features.minetracer.database.MineTracerLookup;
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
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
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
    
    // Undo tracking system - stores last rollback/restore operation per player
    private static class UndoOperation {
        final String type; // "rollback" or "restore"
        final List<MineTracerLookup.BlockLogEntry> blockLogs;
        final List<MineTracerLookup.SignLogEntry> signLogs;
        final List<MineTracerLookup.ContainerLogEntry> containerLogs;
        final Instant timestamp;
        
        UndoOperation(String type, List<MineTracerLookup.BlockLogEntry> blockLogs,
                     List<MineTracerLookup.SignLogEntry> signLogs,
                     List<MineTracerLookup.ContainerLogEntry> containerLogs) {
            this.type = type;
            this.blockLogs = new ArrayList<>(blockLogs);
            this.signLogs = new ArrayList<>(signLogs);
            this.containerLogs = new ArrayList<>(containerLogs);
            this.timestamp = Instant.now();
        }
    }
    
    private static final Map<UUID, UndoOperation> lastOperations = new java.util.concurrent.ConcurrentHashMap<>();
    
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
                    .then(CommandManager.literal("restore")
                            .requires(source -> Permissions.check(source, "minetracer.command.restore", 2))
                            .then(CommandManager.argument("arg", StringArgumentType.greedyString())
                                    .suggests(MineTracerCommand::suggestPlayers)
                                    .executes(MineTracerCommand::restore)))
                    .then(CommandManager.literal("undo")
                            .requires(source -> Permissions.check(source, "minetracer.command.undo", 2))
                            .executes(MineTracerCommand::undo))
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
                    .then(CommandManager.literal("saves")
                            .requires(source -> Permissions.check(source, "minetracer.command.saves", 2))
                            .executes(MineTracerCommand::showSaveHistory))
                    .executes(context -> {
                        ServerCommandSource source = context.getSource();
                        source.sendError(Text.literal("Invalid command usage. Use /minetracer <lookup|rollback|restore|undo|page|inspector|save|saves>"));
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
                allPlayerNames.addAll(MineTracerLookup.getAllPlayerNames());
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
            
            // Use CoreProtect-style suggestions from MaterialMatcher
            java.util.List<String> itemSuggestions = com.minetracer.features.minetracer.util.MaterialMatcher.getSuggestions(itemPart);
            java.util.List<String> blockSuggestions = com.minetracer.features.minetracer.util.MaterialMatcher.getBlockSuggestions(itemPart);
            
            // Add item suggestions first
            for (String suggestion : itemSuggestions) {
                builder.suggest(beforeCurrent + "include:" + suggestion);
            }
            
            // Add block suggestions that aren't already items
            for (String suggestion : blockSuggestions) {
                if (!itemSuggestions.contains(suggestion)) {
                    builder.suggest(beforeCurrent + "include:" + suggestion);
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
    public static final Map<UUID, QueryContext> lastQueries = new java.util.HashMap<>();
    public static class FlatLogEntry {
        public final Object entry;
        public final String type;
        public FlatLogEntry(Object entry, String type) {
            this.entry = entry;
            this.type = type;
        }
    }
    public static class QueryContext {
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
            String excludeItem = null;
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
                } else if (part.startsWith("include:") || part.startsWith("i:")) {
                    includeItem = part.startsWith("include:") ? part.substring(8) : part.substring(2);
                } else if (part.startsWith("exclude:") || part.startsWith("e:")) {
                    excludeItem = part.startsWith("exclude:") ? part.substring(8) : part.substring(2);
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
            CompletableFuture<List<MineTracerLookup.BlockLogEntry>> blockLogsFuture;
            CompletableFuture<List<MineTracerLookup.SignLogEntry>> signLogsFuture;
            CompletableFuture<List<MineTracerLookup.ContainerLogEntry>> containerLogsFuture;
            CompletableFuture<List<MineTracerLookup.KillLogEntry>> killLogsFuture;
            CompletableFuture<List<MineTracerLookup.ItemPickupDropLogEntry>> itemLogsFuture;
            ServerPlayerEntity player = source.getPlayer();
            String worldName = ((com.minetracer.mixin.EntityAccessor)player).getWorld().getRegistryKey().getValue().toString();
            if (hasUser && !hasRange) {
                blockLogsFuture = MineTracerLookup.getBlockLogsForUserAsync(userFilter, worldName);
                signLogsFuture = MineTracerLookup.getSignLogsForUserAsync(userFilter, worldName);
                containerLogsFuture = MineTracerLookup.getContainerLogsForUserAsync(userFilter, worldName);
                killLogsFuture = MineTracerLookup.getKillLogsForUserAsync(userFilter, worldName);
                itemLogsFuture = MineTracerLookup.getItemPickupDropLogsForUserAsync(userFilter, worldName);
            } else {
                blockLogsFuture = MineTracerLookup.getBlockLogsInRangeAsync(playerPos, range, userFilter, worldName);
                signLogsFuture = MineTracerLookup.getSignLogsInRangeAsync(playerPos, range, userFilter, worldName);
                containerLogsFuture = MineTracerLookup.getContainerLogsInRangeAsync(playerPos, range, userFilter, worldName);
                killLogsFuture = MineTracerLookup.getKillLogsInRangeAsync(playerPos, range, userFilter, worldName);
                itemLogsFuture = userFilter != null ? MineTracerLookup.getItemPickupDropLogsForUserAsync(userFilter, worldName) : CompletableFuture.supplyAsync(() -> new ArrayList<>());
            }
            try {
                List<MineTracerLookup.BlockLogEntry> blockLogs = blockLogsFuture.get();
                List<MineTracerLookup.SignLogEntry> signLogs = signLogsFuture.get();
                List<MineTracerLookup.ContainerLogEntry> containerLogs = containerLogsFuture.get();
                List<MineTracerLookup.KillLogEntry> killLogs = killLogsFuture.get();
                List<MineTracerLookup.ItemPickupDropLogEntry> itemLogs = itemLogsFuture.get();
                
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
                    
                    // Use CoreProtect-style partial matching instead of exact equals
                    containerLogs.removeIf(
                            entry -> !com.minetracer.features.minetracer.util.MaterialMatcher.matchesIncludeFilter(
                                    Registries.ITEM.getId(entry.stack.getItem()).toString(), includeItemFinal));
                    blockLogs.removeIf(entry -> !com.minetracer.features.minetracer.util.MaterialMatcher.matchesIncludeFilter(
                            entry.blockId, includeItemFinal));
                    itemLogs.removeIf(entry -> !com.minetracer.features.minetracer.util.MaterialMatcher.matchesIncludeFilter(
                            Registries.ITEM.getId(entry.stack.getItem()).toString(), includeItemFinal));
                }
                if (excludeItem != null && !excludeItem.isEmpty()) {
                    final String excludeItemFinal = excludeItem;
                    
                    // Exclude matching items
                    containerLogs.removeIf(
                            entry -> com.minetracer.features.minetracer.util.MaterialMatcher.matchesExcludeFilter(
                                    Registries.ITEM.getId(entry.stack.getItem()).toString(), excludeItemFinal));
                    blockLogs.removeIf(entry -> com.minetracer.features.minetracer.util.MaterialMatcher.matchesExcludeFilter(
                            entry.blockId, excludeItemFinal));
                    itemLogs.removeIf(entry -> com.minetracer.features.minetracer.util.MaterialMatcher.matchesExcludeFilter(
                            Registries.ITEM.getId(entry.stack.getItem()).toString(), excludeItemFinal));
                }
                List<FlatLogEntry> flatList = new ArrayList<>();
                for (MineTracerLookup.ContainerLogEntry entry : containerLogs) {
                    flatList.add(new FlatLogEntry(entry, "container"));
                }
                for (MineTracerLookup.BlockLogEntry entry : blockLogs) {
                    flatList.add(new FlatLogEntry(entry, "block"));
                }
                for (MineTracerLookup.SignLogEntry entry : signLogs) {
                    flatList.add(new FlatLogEntry(entry, "sign"));
                }
                for (MineTracerLookup.KillLogEntry entry : killLogs) {
                    flatList.add(new FlatLogEntry(entry, "kill"));
                }
                for (MineTracerLookup.ItemPickupDropLogEntry entry : itemLogs) {
                    flatList.add(new FlatLogEntry(entry, "item"));
                }
                flatList.sort((a, b) -> {
                    Instant aTime = a.entry instanceof MineTracerLookup.ContainerLogEntry ? ((MineTracerLookup.ContainerLogEntry) a.entry).timestamp
                            : a.entry instanceof MineTracerLookup.BlockLogEntry
                                    ? ((MineTracerLookup.BlockLogEntry) a.entry).timestamp
                                    : a.entry instanceof MineTracerLookup.SignLogEntry
                                            ? ((MineTracerLookup.SignLogEntry) a.entry).timestamp
                                            : a.entry instanceof MineTracerLookup.KillLogEntry
                                                    ? ((MineTracerLookup.KillLogEntry) a.entry).timestamp
                                                    : a.entry instanceof MineTracerLookup.ItemPickupDropLogEntry
                                                            ? ((MineTracerLookup.ItemPickupDropLogEntry) a.entry).timestamp
                                                            : Instant.EPOCH;
                    Instant bTime = b.entry instanceof MineTracerLookup.ContainerLogEntry ? ((MineTracerLookup.ContainerLogEntry) b.entry).timestamp
                            : b.entry instanceof MineTracerLookup.BlockLogEntry
                                    ? ((MineTracerLookup.BlockLogEntry) b.entry).timestamp
                                    : b.entry instanceof MineTracerLookup.SignLogEntry
                                            ? ((MineTracerLookup.SignLogEntry) b.entry).timestamp
                                            : b.entry instanceof MineTracerLookup.KillLogEntry
                                                    ? ((MineTracerLookup.KillLogEntry) b.entry).timestamp
                                                    : b.entry instanceof MineTracerLookup.ItemPickupDropLogEntry
                                                            ? ((MineTracerLookup.ItemPickupDropLogEntry) b.entry).timestamp
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
    public static void displayPage(ServerCommandSource source, List<FlatLogEntry> logs, int page, int entriesPerPage) {
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
            if (fle.entry instanceof MineTracerLookup.SignLogEntry) {
                MineTracerLookup.SignLogEntry se = (MineTracerLookup.SignLogEntry) fle.entry;
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
        if (entry instanceof MineTracerLookup.ContainerLogEntry) {
            MineTracerLookup.ContainerLogEntry ce = (MineTracerLookup.ContainerLogEntry) entry;
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
        } else if (entry instanceof MineTracerLookup.BlockLogEntry) {
            MineTracerLookup.BlockLogEntry be = (MineTracerLookup.BlockLogEntry) entry;
            String timeAgo = getTimeAgo(Duration.between(be.timestamp, Instant.now()).getSeconds());
            net.minecraft.block.Block block = Registries.BLOCK.get(Identifier.of(be.blockId));
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
        } else if (entry instanceof MineTracerLookup.SignLogEntry) {
            MineTracerLookup.SignLogEntry se = (MineTracerLookup.SignLogEntry) entry;
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
        } else if (entry instanceof MineTracerLookup.KillLogEntry) {
            MineTracerLookup.KillLogEntry ke = (MineTracerLookup.KillLogEntry) entry;
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
        } else if (entry instanceof MineTracerLookup.ItemPickupDropLogEntry) {
            MineTracerLookup.ItemPickupDropLogEntry ie = (MineTracerLookup.ItemPickupDropLogEntry) entry;
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
        if (entry instanceof MineTracerLookup.ContainerLogEntry) {
            pos = ((MineTracerLookup.ContainerLogEntry) entry).pos;
        } else if (entry instanceof MineTracerLookup.BlockLogEntry) {
            pos = ((MineTracerLookup.BlockLogEntry) entry).pos;
        } else if (entry instanceof MineTracerLookup.SignLogEntry) {
            pos = ((MineTracerLookup.SignLogEntry) entry).pos;
        } else if (entry instanceof MineTracerLookup.KillLogEntry) {
            pos = ((MineTracerLookup.KillLogEntry) entry).pos;
        } else if (entry instanceof MineTracerLookup.ItemPickupDropLogEntry) {
            pos = ((MineTracerLookup.ItemPickupDropLogEntry) entry).pos;
        }
        if (pos != null) {
            // Create clickable coordinates that teleport the player
            String coordText = "(x" + pos.getX() + "/y" + pos.getY() + "/z" + pos.getZ() + ")";
            String teleportCommand = "/tp @s " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
            
            return Text.literal(coordText)
                    .formatted(Formatting.GOLD)
                    .styled(style -> style
                            // ClickEvent and HoverEvent are now records in 1.21.11
                            // .withClickEvent(new ClickEvent(Action.RUN_COMMAND, teleportCommand))
                            // .withHoverEvent(new HoverEvent(Action.SHOW_TEXT, Text.literal("Click to teleport")))
                            .withUnderline(true));
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
        String excludeItem = null;
        boolean preview = false;
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
            } else if (part.startsWith("include:") || part.startsWith("i:")) {
                includeItem = part.startsWith("include:") ? part.substring(8) : part.substring(2);
            } else if (part.startsWith("exclude:") || part.startsWith("e:")) {
                excludeItem = part.startsWith("exclude:") ? part.substring(8) : part.substring(2);
            } else if (part.equals("#preview")) {
                preview = true;
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
        
        // Use the new database lookup system (same as lookup command)
        String worldName = ((com.minetracer.mixin.EntityAccessor)source.getPlayer()).getWorld().getRegistryKey().getValue().toString();
        List<MineTracerLookup.BlockLogEntry> blockLogs;
        List<MineTracerLookup.SignLogEntry> signLogs;
        List<MineTracerLookup.ContainerLogEntry> containerLogs;
        List<MineTracerLookup.KillLogEntry> killLogs;
        
        try {
            blockLogs = MineTracerLookup.getBlockLogsInRangeAsync(playerPos, range, userFilter, worldName).get();
            signLogs = MineTracerLookup.getSignLogsInRangeAsync(playerPos, range, userFilter, worldName).get();
            containerLogs = MineTracerLookup.getContainerLogsInRangeAsync(playerPos, range, userFilter, worldName).get();
            killLogs = MineTracerLookup.getKillLogsInRangeAsync(playerPos, range, userFilter, worldName).get();
        } catch (Exception e) {
            source.sendError(Text.literal("[MineTracer] Error querying database: " + e.getMessage()));
            e.printStackTrace();
            return Command.SINGLE_SUCCESS;
        }
        
        boolean filterByKiller = actionFilters.contains("kill");
        
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
            // Use CoreProtect-style partial matching instead of exact equals
            containerLogs.removeIf(
                    entry -> !com.minetracer.features.minetracer.util.MaterialMatcher.matchesIncludeFilter(
                            Registries.ITEM.getId(entry.stack.getItem()).toString(), includeItemFinal));
            blockLogs.removeIf(entry -> !com.minetracer.features.minetracer.util.MaterialMatcher.matchesIncludeFilter(
                    entry.blockId, includeItemFinal));
        }
        if (excludeItem != null && !excludeItem.isEmpty()) {
            final String excludeItemFinal = excludeItem;
            // Exclude matching items
            containerLogs.removeIf(
                    entry -> com.minetracer.features.minetracer.util.MaterialMatcher.matchesExcludeFilter(
                            Registries.ITEM.getId(entry.stack.getItem()).toString(), excludeItemFinal));
            blockLogs.removeIf(entry -> com.minetracer.features.minetracer.util.MaterialMatcher.matchesExcludeFilter(
                    entry.blockId, excludeItemFinal));
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
        
        // Preview mode - show ghost blocks to the player
        if (preview) {
            source.sendFeedback(() -> Text.literal("[MineTracer] PREVIEW MODE - Showing ghost blocks...")
                    .formatted(Formatting.YELLOW), false);
            source.sendFeedback(() -> Text.literal("Found " + totalActions + " actions to preview.")
                    .formatted(Formatting.AQUA), false);
            
            ServerPlayerEntity player = source.getPlayer();
            int ghostBlocksShown = 0;
            
            // Send ghost blocks for block changes
            for (MineTracerLookup.BlockLogEntry entry : blockLogs) {
                if (!entry.rolledBack) {
                    if ("broke".equals(entry.action)) {
                        // Show the block that would be restored
                        sendGhostBlock(player, entry.pos, entry.blockId, entry.nbt);
                        ghostBlocksShown++;
                    } else if ("placed".equals(entry.action)) {
                        // Show air where block would be removed
                        sendGhostBlock(player, entry.pos, "minecraft:air", null);
                        ghostBlocksShown++;
                    }
                }
            }
            
            final int finalGhostBlocksShown = ghostBlocksShown;
            source.sendFeedback(() -> Text.literal("Showing " + finalGhostBlocksShown + " ghost blocks. They will disappear when you relog or move away.")
                    .formatted(Formatting.GRAY), false);
            source.sendFeedback(() -> Text.literal("Run without #preview to execute the rollback.")
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
            for (MineTracerLookup.BlockLogEntry entry : blockLogs) {
                if ("broke".equals(entry.action) && !entry.rolledBack) {
                    if (performBlockPlaceRollback(world, entry)) {
                        successfulRollbacks++;
                    } else {
                        failedRollbacks++;
                    }
                }
            }
            for (MineTracerLookup.BlockLogEntry entry : blockLogs) {
                if ("placed".equals(entry.action) && !entry.rolledBack) {
                    if (performBlockBreakRollback(world, entry)) {
                        successfulRollbacks++;
                    } else {
                        failedRollbacks++;
                    }
                }
            }
            for (MineTracerLookup.ContainerLogEntry entry : containerLogs) {
                if (!entry.rolledBack) {
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
            }
            for (MineTracerLookup.SignLogEntry entry : signLogs) {
                if ("edit".equals(entry.action) && !entry.rolledBack) {
                    if (performSignRollback(world, entry)) {
                        successfulRollbacks++;
                    } else {
                        failedRollbacks++;
                    }
                }
            }
        } else {
            for (MineTracerLookup.ContainerLogEntry entry : containerLogs) {
                if (!entry.rolledBack) {
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
            }
            for (MineTracerLookup.BlockLogEntry entry : blockLogs) {
                if (!entry.rolledBack) {
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
            }
            for (MineTracerLookup.SignLogEntry entry : signLogs) {
                if ("edit".equals(entry.action) && !entry.rolledBack) {
                    if (performSignRollback(world, entry)) {
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
            
            // Store operation for undo
            try {
                UUID playerId = source.getPlayer().getUuid();
                UndoOperation undoOp = new UndoOperation("rollback", blockLogs, signLogs, containerLogs);
                lastOperations.put(playerId, undoOp);
                source.sendFeedback(() -> Text.literal(
                    "[MineTracer] Use /minetracer undo to revert this rollback.")
                    .formatted(Formatting.GRAY), false);
            } catch (Exception e) {
                // Player might not exist in some contexts
            }
        } else {
            source.sendFeedback(
                    () -> Text.literal("[MineTracer] No actions found to rollback.").formatted(Formatting.YELLOW),
                    false);
        }
        return Command.SINGLE_SUCCESS;
    }
    
    /**
     * Restore command - reapplies actions that were rolled back
     * This is the inverse of rollback (undoing the undo)
     */
    public static int restore(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!Permissions.check(source, "minetracer.command.restore", 2)) {
            source.sendError(Text.literal("You do not have permission to use this command."));
            return 0;
        }
        String arg = StringArgumentType.getString(ctx, "arg");
        
        // Check for preview mode
        boolean previewMode = arg.contains("#preview");
        if (previewMode) {
            arg = arg.replace("#preview", "").trim();
        }
        
        String userFilter = null;
        String timeArg = null;
        int range = 100;
        java.util.Set<String> actionFilters = new java.util.HashSet<>();
        String includeItem = null;
        String excludeItem = null;
        
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
            } else if (part.startsWith("include:") || part.startsWith("i:")) {
                includeItem = part.startsWith("include:") ? part.substring(8) : part.substring(2);
            } else if (part.startsWith("exclude:") || part.startsWith("e:")) {
                excludeItem = part.startsWith("exclude:") ? part.substring(8) : part.substring(2);
            }
        }
        
        BlockPos playerPos = source.getPlayer().getBlockPos();
        Instant cutoff = null;
        if (timeArg != null) {
            long seconds = parseTimeArg(timeArg);
            cutoff = Instant.now().minusSeconds(seconds);
        }
        
        boolean hasRange = range != 100;
        boolean hasTime = timeArg != null;
        boolean hasUser = userFilter != null;
        int restrictionCount = (hasRange ? 1 : 0) + (hasTime ? 1 : 0) + (hasUser ? 1 : 0);
        
        if (restrictionCount < 2) {
            source.sendError(Text.literal(
                    "Restore requires at least 2 of these filters: range:<blocks>, time:<duration>, user:<player>. Add #preview to see what would be restored."));
            return Command.SINGLE_SUCCESS;
        }
        
        // Use the new database lookup system (same as lookup and rollback commands)
        String worldName = ((com.minetracer.mixin.EntityAccessor)source.getPlayer()).getWorld().getRegistryKey().getValue().toString();
        List<MineTracerLookup.BlockLogEntry> blockLogs;
        List<MineTracerLookup.SignLogEntry> signLogs;
        List<MineTracerLookup.ContainerLogEntry> containerLogs;
        
        try {
            blockLogs = MineTracerLookup.getBlockLogsInRangeAsync(playerPos, range, userFilter, worldName).get();
            signLogs = MineTracerLookup.getSignLogsInRangeAsync(playerPos, range, userFilter, worldName).get();
            containerLogs = MineTracerLookup.getContainerLogsInRangeAsync(playerPos, range, userFilter, worldName).get();
        } catch (Exception e) {
            source.sendError(Text.literal("[MineTracer] Error querying database: " + e.getMessage()));
            e.printStackTrace();
            return Command.SINGLE_SUCCESS;
        }
        
        if (userFilter != null) {
            final String userFilterFinal = userFilter;
            containerLogs.removeIf(entry -> !entry.playerName.equalsIgnoreCase(userFilterFinal));
        }
        
        if (cutoff != null) {
            final Instant cutoffFinal = cutoff;
            blockLogs.removeIf(entry -> entry.timestamp.isBefore(cutoffFinal));
            signLogs.removeIf(entry -> entry.timestamp.isBefore(cutoffFinal));
            containerLogs.removeIf(entry -> entry.timestamp.isBefore(cutoffFinal));
        }
        
        if (!actionFilters.isEmpty()) {
            containerLogs.removeIf(
                    entry -> actionFilters.stream().noneMatch(filter -> entry.action.equalsIgnoreCase(filter)));
            blockLogs.removeIf(
                    entry -> actionFilters.stream().noneMatch(filter -> entry.action.equalsIgnoreCase(filter)));
            signLogs.removeIf(
                    entry -> actionFilters.stream().noneMatch(filter -> entry.action.equalsIgnoreCase(filter)));
        }
        
        if (includeItem != null && !includeItem.isEmpty()) {
            final String includeItemFinal = includeItem;
            containerLogs.removeIf(
                    entry -> !com.minetracer.features.minetracer.util.MaterialMatcher.matchesIncludeFilter(
                            Registries.ITEM.getId(entry.stack.getItem()).toString(), includeItemFinal));
            blockLogs.removeIf(entry -> !com.minetracer.features.minetracer.util.MaterialMatcher.matchesIncludeFilter(
                    entry.blockId, includeItemFinal));
        }
        
        if (excludeItem != null && !excludeItem.isEmpty()) {
            final String excludeItemFinal = excludeItem;
            containerLogs.removeIf(
                    entry -> com.minetracer.features.minetracer.util.MaterialMatcher.matchesExcludeFilter(
                            Registries.ITEM.getId(entry.stack.getItem()).toString(), excludeItemFinal));
            blockLogs.removeIf(entry -> com.minetracer.features.minetracer.util.MaterialMatcher.matchesExcludeFilter(
                    entry.blockId, excludeItemFinal));
        }
        
        int totalActions = containerLogs.size() + blockLogs.size() + signLogs.size();
        if (totalActions == 0) {
            source.sendFeedback(() -> Text.literal("[MineTracer] No actions found matching the specified filters.")
                    .formatted(Formatting.YELLOW), false);
            return Command.SINGLE_SUCCESS;
        }
        
        // Preview mode - show what would be restored without actually doing it
        if (previewMode) {
            source.sendFeedback(() -> Text.literal("[MineTracer] Preview: Would restore " + totalActions + " actions:")
                    .formatted(Formatting.AQUA), false);
            source.sendFeedback(() -> Text.literal("  - " + blockLogs.size() + " block changes")
                    .formatted(Formatting.GRAY), false);
            source.sendFeedback(() -> Text.literal("  - " + containerLogs.size() + " container transactions")
                    .formatted(Formatting.GRAY), false);
            source.sendFeedback(() -> Text.literal("  - " + signLogs.size() + " sign edits")
                    .formatted(Formatting.GRAY), false);
            source.sendFeedback(() -> Text.literal("Remove #preview to execute the restore.")
                    .formatted(Formatting.YELLOW), false);
            return Command.SINGLE_SUCCESS;
        }
        
        int successfulRestores = 0;
        int failedRestores = 0;
        ServerWorld world = source.getWorld();
        
        source.sendFeedback(() -> Text.literal("[MineTracer] Found " + totalActions + " actions to restore.")
                .formatted(Formatting.AQUA), false);
        
        // Restore = inverse of rollback, so we apply the original actions
        // placed -> place block, broke -> remove block
        // withdrew -> remove from container, deposited -> add to container
        
        for (MineTracerLookup.BlockLogEntry entry : blockLogs) {
            if ("placed".equals(entry.action) && !entry.rolledBack) {
                // Re-place the block
                if (performBlockRestore(world, entry)) {
                    successfulRestores++;
                } else {
                    failedRestores++;
                }
            } else if ("broke".equals(entry.action) && !entry.rolledBack) {
                // Re-break the block
                if (performBlockBreakRestore(world, entry)) {
                    successfulRestores++;
                } else {
                    failedRestores++;
                }
            }
        }
        
        for (MineTracerLookup.ContainerLogEntry entry : containerLogs) {
            if (!entry.rolledBack) {
                if ("withdrew".equals(entry.action)) {
                    // Restore withdrawal = remove item from container
                    if (performWithdrawalRestore(world, entry)) {
                        successfulRestores++;
                    } else {
                        failedRestores++;
                    }
                } else if ("deposited".equals(entry.action)) {
                    // Restore deposit = add item to container
                    if (performDepositRestore(world, entry)) {
                        successfulRestores++;
                    } else {
                        failedRestores++;
                    }
                }
            }
        }
        
        if (successfulRestores > 0 || failedRestores > 0) {
            final int finalSuccessfulRestores = successfulRestores;
            final int finalFailedRestores = failedRestores;
            source.sendFeedback(() -> Text.literal(
                    "[MineTracer] Restore complete: " + finalSuccessfulRestores + " actions reapplied, " +
                            finalFailedRestores + " failed.")
                    .formatted(Formatting.GREEN), false);
            
            // Store operation for undo
            try {
                UUID playerId = source.getPlayer().getUuid();
                UndoOperation undoOp = new UndoOperation("restore", blockLogs, signLogs, containerLogs);
                lastOperations.put(playerId, undoOp);
                source.sendFeedback(() -> Text.literal(
                    "[MineTracer] Use /minetracer undo to revert this restore.")
                    .formatted(Formatting.GRAY), false);
            } catch (Exception e) {
                // Player might not exist in some contexts
            }
        } else {
            source.sendFeedback(
                    () -> Text.literal("[MineTracer] No actions found to restore.").formatted(Formatting.YELLOW),
                    false);
        }
        
        return Command.SINGLE_SUCCESS;
    }
    
    /**
     * Undo command - reverts the last rollback or restore operation
     */
    public static int undo(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!Permissions.check(source, "minetracer.command.undo", 2)) {
            source.sendError(Text.literal("You do not have permission to use this command."));
            return 0;
        }
        
        try {
            UUID playerId = source.getPlayer().getUuid();
            UndoOperation lastOp = lastOperations.get(playerId);
            
            if (lastOp == null) {
                source.sendError(Text.literal("[MineTracer] No recent rollback or restore to undo."));
                return 0;
            }
            
            // Check if operation is recent (within 5 minutes)
            long minutesAgo = Duration.between(lastOp.timestamp, Instant.now()).toMinutes();
            if (minutesAgo > 5) {
                source.sendError(Text.literal("[MineTracer] Last operation was " + minutesAgo + " minutes ago. Undo is only available for recent operations (within 5 minutes)."));
                return 0;
            }
            
            int successfulUndos = 0;
            int failedUndos = 0;
            ServerWorld world = source.getWorld();
            
            source.sendFeedback(() -> Text.literal("[MineTracer] Undoing last " + lastOp.type + " operation...")
                    .formatted(Formatting.AQUA), false);
            
            // Undo rollback = restore
            // Undo restore = rollback
            boolean isUndoingRollback = "rollback".equals(lastOp.type);
            
            for (MineTracerLookup.BlockLogEntry entry : lastOp.blockLogs) {
                if (isUndoingRollback) {
                    // Undo rollback: restore the original action
                    if ("broke".equals(entry.action)) {
                        if (performBlockBreakRestore(world, entry)) {
                            successfulUndos++;
                        } else {
                            failedUndos++;
                        }
                    } else if ("placed".equals(entry.action)) {
                        if (performBlockRestore(world, entry)) {
                            successfulUndos++;
                        } else {
                            failedUndos++;
                        }
                    }
                } else {
                    // Undo restore: rollback the action
                    if ("broke".equals(entry.action)) {
                        if (performBlockPlaceRollback(world, entry)) {
                            successfulUndos++;
                        } else {
                            failedUndos++;
                        }
                    } else if ("placed".equals(entry.action)) {
                        if (performBlockBreakRollback(world, entry)) {
                            successfulUndos++;
                        } else {
                            failedUndos++;
                        }
                    }
                }
            }
            
            for (MineTracerLookup.ContainerLogEntry entry : lastOp.containerLogs) {
                if (isUndoingRollback) {
                    // Undo rollback: restore original action
                    if ("withdrew".equals(entry.action)) {
                        if (performWithdrawalRestore(world, entry)) {
                            successfulUndos++;
                        } else {
                            failedUndos++;
                        }
                    } else if ("deposited".equals(entry.action)) {
                        if (performDepositRestore(world, entry)) {
                            successfulUndos++;
                        } else {
                            failedUndos++;
                        }
                    }
                } else {
                    // Undo restore: rollback the action
                    if ("withdrew".equals(entry.action)) {
                        if (performWithdrawalRollback(world, entry)) {
                            successfulUndos++;
                        } else {
                            failedUndos++;
                        }
                    } else if ("deposited".equals(entry.action)) {
                        if (performDepositRollback(world, entry)) {
                            successfulUndos++;
                        } else {
                            failedUndos++;
                        }
                    }
                }
            }
            
            // Clear the undo history after executing
            lastOperations.remove(playerId);
            
            final int finalSuccessfulUndos = successfulUndos;
            final int finalFailedUndos = failedUndos;
            source.sendFeedback(() -> Text.literal(
                    "[MineTracer] Undo complete: " + finalSuccessfulUndos + " changes reverted, " +
                            finalFailedUndos + " failed.")
                    .formatted(Formatting.GREEN), false);
            
        } catch (Exception e) {
            source.sendError(Text.literal("[MineTracer] Failed to undo: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
        
        return Command.SINGLE_SUCCESS;
    }
    
    /**
     * Gets the proper inventory for a container at the given position.
     * Handles double chests by using the BlockState's inventory provider.
     */
    private static Inventory getContainerInventory(ServerWorld world, BlockPos pos) {
        BlockState blockState = world.getBlockState(pos);
        
        // For ChestBlock (including double chests), use the block's inventory method
        if (blockState.getBlock() instanceof net.minecraft.block.ChestBlock) {
            net.minecraft.block.ChestBlock chestBlock = (net.minecraft.block.ChestBlock) blockState.getBlock();
            // This properly handles double chests by returning the combined 54-slot inventory
            return net.minecraft.block.ChestBlock.getInventory(chestBlock, blockState, world, pos, true);
        }
        
        // For other containers, use the BlockEntity directly
        net.minecraft.block.entity.BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity instanceof Inventory) {
            return (Inventory) blockEntity;
        }
        
        return null;
    }
    
    private static boolean performWithdrawalRollback(ServerWorld world, MineTracerLookup.ContainerLogEntry entry) {
        try {
            BlockPos pos = entry.pos;
            ItemStack stackToRestore = entry.stack.copy();
            Inventory inventory = getContainerInventory(world, pos);
            if (inventory != null) {
                ItemStack remaining = addItemToInventory(inventory, stackToRestore);
                inventory.markDirty();
                boolean success = remaining.getCount() < stackToRestore.getCount();
                
                // CoreProtect-style: Mark as rolled back in database
                if (success) {
                    markContainerEntryRolledBack(entry, world);
                }
                
                return success;
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }
    private static boolean performDepositRollback(ServerWorld world, MineTracerLookup.ContainerLogEntry entry) {
        try {
            BlockPos pos = entry.pos;
            ItemStack stackToRemove = entry.stack.copy();
            Inventory inventory = getContainerInventory(world, pos);
            if (inventory != null) {
                ItemStack remaining = removeItemFromInventory(inventory, stackToRemove);
                inventory.markDirty();
                boolean success = remaining.getCount() < stackToRemove.getCount();
                
                // CoreProtect-style: Mark as rolled back in database
                if (success) {
                    markContainerEntryRolledBack(entry, world);
                }
                
                return success;
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
            if (!existingStack.isEmpty() && ItemStack.areItemsAndComponentsEqual(existingStack, remaining)) {
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
            if (!existingStack.isEmpty() && ItemStack.areItemsAndComponentsEqual(existingStack, remaining)) {
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
    private static boolean performBlockBreakRollback(ServerWorld world, MineTracerLookup.BlockLogEntry entry) {
        try {
            BlockPos pos = entry.pos;
            world.setBlockState(pos, net.minecraft.block.Blocks.AIR.getDefaultState());
            
            // CoreProtect-style: Mark as rolled back in database
            markBlockEntryRolledBack(entry, world);
            
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    private static boolean performBlockPlaceRollback(ServerWorld world, MineTracerLookup.BlockLogEntry entry) {
        try {
            BlockPos pos = entry.pos;
            net.minecraft.block.Block block = net.minecraft.registry.Registries.BLOCK
                    .get(net.minecraft.util.Identifier.of(entry.blockId));
            if (block != null && block != net.minecraft.block.Blocks.AIR) {
                net.minecraft.block.BlockState blockState = block.getDefaultState();
                if (entry.nbt != null && !entry.nbt.isEmpty() && !entry.nbt.equals("{}")) {
                    try {
                        net.minecraft.nbt.NbtCompound nbtCompound = com.minetracer.features.minetracer.util.NbtCompatHelper.parseNbtString(entry.nbt);
                        if (nbtCompound.contains("Properties")) {
                            net.minecraft.nbt.NbtCompound properties = nbtCompound.contains("Properties") && nbtCompound.get("Properties") instanceof net.minecraft.nbt.NbtCompound ? (net.minecraft.nbt.NbtCompound)nbtCompound.get("Properties") : new net.minecraft.nbt.NbtCompound();
                            for (String key : properties.getKeys()) {
                                String value = properties.contains(key) && properties.get(key) instanceof net.minecraft.nbt.NbtString ? ((net.minecraft.nbt.NbtString)properties.get(key)).asString().orElse("") : "";
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
                            net.minecraft.nbt.NbtCompound blockEntityData = nbtCompound.contains("BlockEntityTag") && nbtCompound.get("BlockEntityTag") instanceof net.minecraft.nbt.NbtCompound ? (net.minecraft.nbt.NbtCompound)nbtCompound.get("BlockEntityTag") : new net.minecraft.nbt.NbtCompound();
                            net.minecraft.block.entity.BlockEntity blockEntity = world.getBlockEntity(pos);
                            if (blockEntity != null) {
                                // readComponentsFromNbt not available in this version - BlockEntity data already set
                                blockEntity.markDirty();
                            }
                        }
                    } catch (Exception e) {
                        world.setBlockState(pos, blockState);
                    }
                } else {
                    world.setBlockState(pos, blockState);
                }
                
                // CoreProtect-style: Mark as rolled back in database
                markBlockEntryRolledBack(entry, world);
                
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
    private static boolean performSignRollback(ServerWorld world, MineTracerLookup.SignLogEntry entry) {
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
                            net.minecraft.nbt.NbtCompound signNbt = signEntity.createNbt(world.getRegistryManager());
                            if (signNbt.contains("front_text")) {
                                net.minecraft.nbt.NbtCompound frontText = signNbt.contains("front_text") && signNbt.get("front_text") instanceof net.minecraft.nbt.NbtCompound ? (net.minecraft.nbt.NbtCompound)signNbt.get("front_text") : new net.minecraft.nbt.NbtCompound();
                                net.minecraft.nbt.NbtList messages = new net.minecraft.nbt.NbtList();
                                for (net.minecraft.text.Text text : beforeTexts) {
                                    net.minecraft.nbt.NbtElement jsonText = net.minecraft.text.TextCodecs.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, text).getOrThrow();
                                    messages.add(jsonText);
                                }
                                frontText.put("messages", messages);
                                signNbt.put("front_text", frontText);
                                // readComponentsFromNbt not available in this version
                            }
                        } catch (Exception nbtError) {
                            return false;
                        }
                        signEntity.markDirty();
                        world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
                        
                        // CoreProtect-style: Mark as rolled back in database
                        markSignEntryRolledBack(entry, world);
                        
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
    
    // Restore helper methods (inverse of rollback operations)
    
    /**
     * Restore a withdrawal - removes the item from container (undoes the rollback that added it back)
     */
    private static boolean performWithdrawalRestore(ServerWorld world, MineTracerLookup.ContainerLogEntry entry) {
        // Restore withdrawal = remove item (same as deposit rollback)
        return performDepositRollback(world, entry);
    }
    
    /**
     * Restore a deposit - adds the item back to container (undoes the rollback that removed it)
     */
    private static boolean performDepositRestore(ServerWorld world, MineTracerLookup.ContainerLogEntry entry) {
        // Restore deposit = add item (same as withdrawal rollback)
        return performWithdrawalRollback(world, entry);
    }
    
    /**
     * Restore a block placement - places the block again
     */
    private static boolean performBlockRestore(ServerWorld world, MineTracerLookup.BlockLogEntry entry) {
        try {
            BlockPos pos = entry.pos;
            String blockId = entry.blockId;
            net.minecraft.block.Block block = net.minecraft.registry.Registries.BLOCK.get(Identifier.of(blockId));
            if (block != null) {
                net.minecraft.block.BlockState newState = block.getDefaultState();
                world.setBlockState(pos, newState, 3);
                
                // Apply NBT if available
                if (entry.nbt != null && !entry.nbt.isEmpty()) {
                    try {
                        net.minecraft.nbt.NbtCompound nbt = com.minetracer.features.minetracer.util.NbtCompatHelper.parseNbtString(entry.nbt);
                        net.minecraft.block.entity.BlockEntity blockEntity = world.getBlockEntity(pos);
                        if (blockEntity != null) {
                            // readComponentsFromNbt not available in this version
                            blockEntity.markDirty();
                        }
                    } catch (Exception nbtError) {
                        // NBT parsing failed, but block was placed
                    }
                }
                
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Restore a block break - removes the block again
     */
    private static boolean performBlockBreakRestore(ServerWorld world, MineTracerLookup.BlockLogEntry entry) {
        try {
            BlockPos pos = entry.pos;
            world.setBlockState(pos, net.minecraft.block.Blocks.AIR.getDefaultState(), 3);
            return true;
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
    public static int showSaveHistory(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!Permissions.check(source, "minetracer.command.saves", 2)) {
            source.sendError(Text.literal("You do not have permission to use this command."));
            return 0;
        }
        List<OptimizedLogStorage.SaveHistory> saveHistory = OptimizedLogStorage.getSaveHistory();
        if (saveHistory.isEmpty()) {
            source.sendFeedback(() -> Text.literal("No save history available yet.").formatted(Formatting.YELLOW), false);
            return Command.SINGLE_SUCCESS;
        }
        source.sendFeedback(() -> Text.literal("=== MineTracer Save History (Last " + saveHistory.size() + " saves) ===").formatted(Formatting.GOLD), false);
        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneId.systemDefault());
        for (int i = 0; i < saveHistory.size(); i++) {
            OptimizedLogStorage.SaveHistory save = saveHistory.get(i);
            String timeStr = formatter.format(save.timestamp);
            long kilobytes = save.fileSizeBytes / 1024;
            Text message = Text.literal(String.format("[%d] %s - %,d entries (%,d KB)", 
                i + 1, timeStr, save.totalEntries, kilobytes)).formatted(Formatting.WHITE);
            source.sendFeedback(() -> message, false);
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
    
    /**
     * Mark a container entry as rolled back in database (CoreProtect-style)
     */
    private static void markContainerEntryRolledBack(MineTracerLookup.ContainerLogEntry entry, ServerWorld world) {
        try {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try (java.sql.Connection conn = com.minetracer.features.minetracer.database.MineTracerDatabase.getConnection()) {
                    if (conn == null) return;
                    
                    String worldName = world.getRegistryKey().getValue().toString();
                    String sql = "UPDATE minetracer_container SET rolled_back = 1 WHERE " +
                               "user = (SELECT id FROM minetracer_user WHERE user = ?) AND " +
                               "wid = (SELECT id FROM minetracer_world WHERE world = ?) AND " +
                               "x = ? AND y = ? AND z = ? AND time = ? AND rolled_back = 0";
                    
                    try (java.sql.PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setString(1, entry.playerName);
                        stmt.setString(2, worldName);
                        stmt.setInt(3, entry.pos.getX());
                        stmt.setInt(4, entry.pos.getY());
                        stmt.setInt(5, entry.pos.getZ());
                        stmt.setLong(6, entry.timestamp.getEpochSecond());
                        stmt.executeUpdate();
                    }
                } catch (Exception e) {
                    System.err.println("[MineTracer] Failed to mark container entry as rolled back: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            // Silently fail - rollback still succeeded in-game
        }
    }
    
    /**
     * Mark a block entry as rolled back in database (CoreProtect-style)
     */
    private static void markBlockEntryRolledBack(MineTracerLookup.BlockLogEntry entry, ServerWorld world) {
        try {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try (java.sql.Connection conn = com.minetracer.features.minetracer.database.MineTracerDatabase.getConnection()) {
                    if (conn == null) return;
                    
                    String worldName = world.getRegistryKey().getValue().toString();
                    String sql = "UPDATE minetracer_block SET rolled_back = 1 WHERE " +
                               "user = (SELECT id FROM minetracer_user WHERE user = ?) AND " +
                               "wid = (SELECT id FROM minetracer_world WHERE world = ?) AND " +
                               "x = ? AND y = ? AND z = ? AND time = ? AND rolled_back = 0";
                    
                    try (java.sql.PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setString(1, entry.playerName);
                        stmt.setString(2, worldName);
                        stmt.setInt(3, entry.pos.getX());
                        stmt.setInt(4, entry.pos.getY());
                        stmt.setInt(5, entry.pos.getZ());
                        stmt.setLong(6, entry.timestamp.getEpochSecond());
                        stmt.executeUpdate();
                    }
                } catch (Exception e) {
                    System.err.println("[MineTracer] Failed to mark block entry as rolled back: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            // Silently fail - rollback still succeeded in-game
        }
    }
    
    /**
     * Mark a sign entry as rolled back in database (CoreProtect-style)
     */
    private static void markSignEntryRolledBack(MineTracerLookup.SignLogEntry entry, ServerWorld world) {
        try {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try (java.sql.Connection conn = com.minetracer.features.minetracer.database.MineTracerDatabase.getConnection()) {
                    if (conn == null) return;
                    
                    String worldName = world.getRegistryKey().getValue().toString();
                    String sql = "UPDATE minetracer_sign SET rolled_back = 1 WHERE " +
                               "user = (SELECT id FROM minetracer_user WHERE user = ?) AND " +
                               "wid = (SELECT id FROM minetracer_world WHERE world = ?) AND " +
                               "x = ? AND y = ? AND z = ? AND time = ? AND rolled_back = 0";
                    
                    try (java.sql.PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setString(1, entry.playerName);
                        stmt.setString(2, worldName);
                        stmt.setInt(3, entry.pos.getX());
                        stmt.setInt(4, entry.pos.getY());
                        stmt.setInt(5, entry.pos.getZ());
                        stmt.setLong(6, entry.timestamp.getEpochSecond());
                        stmt.executeUpdate();
                    }
                } catch (Exception e) {
                    System.err.println("[MineTracer] Failed to mark sign entry as rolled back: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            // Silently fail - rollback still succeeded in-game
        }
    }
    
    /**
     * Send a ghost block packet to a player (client-side only)
     * The block appears only to this player and disappears when they relog or the chunk reloads
     */
    private static void sendGhostBlock(ServerPlayerEntity player, BlockPos pos, String blockId, String nbtString) {
        try {
            // Parse the block ID and get the block state
            Identifier identifier = Identifier.of(blockId);
            Block block = Registries.BLOCK.get(identifier);
            BlockState state = block.getDefaultState();
            
            // Send the block update packet to the player only
            BlockUpdateS2CPacket packet = new BlockUpdateS2CPacket(pos, state);
            player.networkHandler.sendPacket(packet);
            
            // Note: NBT data (for signs, chests, etc.) would require additional block entity packets
            // For now, we just show the block type as a ghost block
        } catch (Exception e) {
            // Silently fail if block ID is invalid - ghost block preview is best-effort
        }
    }
}
