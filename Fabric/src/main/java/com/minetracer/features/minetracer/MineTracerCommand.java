package com.minetracer.features.minetracer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.minetracer.features.minetracer.database.MineTracerConsumer;
import com.minetracer.features.minetracer.database.MineTracerLookup;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
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
            dispatcher.register(Commands.literal("minetracer")
                    .then(Commands.literal("lookup")
                            .requires(source -> Permissions.check(source, "minetracer.command.lookup", 2))
                            .then(Commands.argument("arg", StringArgumentType.greedyString())
                                    .suggests(MineTracerCommand::suggestPlayers)
                                    .executes(MineTracerCommand::lookup)))
                    .then(Commands.literal("rollback")
                            .requires(source -> Permissions.check(source, "minetracer.command.rollback", 2))
                            .then(Commands.argument("arg", StringArgumentType.greedyString())
                                    .suggests(MineTracerCommand::suggestPlayers)
                                    .executes(MineTracerCommand::rollback)))
                    .then(Commands.literal("restore")
                            .requires(source -> Permissions.check(source, "minetracer.command.restore", 2))
                            .then(Commands.argument("arg", StringArgumentType.greedyString())
                                    .suggests(MineTracerCommand::suggestPlayers)
                                    .executes(MineTracerCommand::restore)))
                    .then(Commands.literal("undo")
                            .requires(source -> Permissions.check(source, "minetracer.command.undo", 2))
                            .executes(MineTracerCommand::undo))
                    .then(Commands.literal("page")
                            .requires(source -> Permissions.check(source, "minetracer.command.page", 2))
                            .then(Commands
                                    .argument("page", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                    .executes(MineTracerCommand::lookupPage)))
                    .then(Commands.literal("inspector")
                            .requires(source -> Permissions.check(source, "minetracer.command.inspector", 2))
                            .executes(MineTracerCommand::toggleInspector))
                    .then(Commands.literal("save")
                            .requires(source -> Permissions.check(source, "minetracer.command.save", 2))
                            .executes(MineTracerCommand::save))
                    .then(Commands.literal("saves")
                            .requires(source -> Permissions.check(source, "minetracer.command.saves", 2))
                            .executes(MineTracerCommand::showSaveHistory))
                    .executes(context -> {
                        CommandSourceStack source = context.getSource();
                        source.sendFailure(Component.literal("Invalid command usage. Use /minetracer <lookup|rollback|restore|undo|page|inspector|save|saves>"));
                        return 0;
                    }));

            // /mt shorthand aliases
            dispatcher.register(Commands.literal("mt")
                    .then(Commands.literal("i")
                            .requires(source -> Permissions.check(source, "minetracer.command.inspector", 2))
                            .executes(MineTracerCommand::toggleInspector))
                    .then(Commands.literal("l")
                            .requires(source -> Permissions.check(source, "minetracer.command.lookup", 2))
                            .then(Commands.argument("arg", StringArgumentType.greedyString())
                                    .suggests(MineTracerCommand::suggestPlayers)
                                    .executes(MineTracerCommand::lookup)))
                    .then(Commands.literal("rb")
                            .requires(source -> Permissions.check(source, "minetracer.command.rollback", 2))
                            .then(Commands.argument("arg", StringArgumentType.greedyString())
                                    .suggests(MineTracerCommand::suggestPlayers)
                                    .executes(MineTracerCommand::rollback)))
                    .then(Commands.literal("rs")
                            .requires(source -> Permissions.check(source, "minetracer.command.restore", 2))
                            .then(Commands.argument("arg", StringArgumentType.greedyString())
                                    .suggests(MineTracerCommand::suggestPlayers)
                                    .executes(MineTracerCommand::restore)))
                    .then(Commands.literal("undo")
                            .requires(source -> Permissions.check(source, "minetracer.command.undo", 2))
                            .executes(MineTracerCommand::undo))
                    .then(Commands.literal("page")
                            .requires(source -> Permissions.check(source, "minetracer.command.page", 2))
                            .then(Commands
                                    .argument("page", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                    .executes(MineTracerCommand::lookupPage)))
                    .executes(context -> {
                        CommandSourceStack source = context.getSource();
                        source.sendFailure(Component.literal("Invalid command usage. Use /mt i (inspector), /mt l <filters> (lookup), /mt rb <filters> (rollback), /mt rs <filters> (restore)"));
                        return 0;
                    }));
        });
    }
    public static CompletableFuture<Suggestions> suggestPlayers(CommandContext<CommandSourceStack> ctx,
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
            for (ServerPlayer player : ctx.getSource().getServer().getPlayerList().getPlayers()) {
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
    public static int lookup(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!Permissions.check(source, "minetracer.command.lookup", 2)) {
            source.sendFailure(Component.literal("You do not have permission to use this command."));
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
            BlockPos playerPos = source.getPlayer().blockPosition();
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
                source.sendFailure(Component.literal(
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
            ServerPlayer player = source.getPlayer();
            String worldName = ((com.minetracer.mixin.EntityAccessor)player).getWorld().dimension().identifier().toString();
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
                    // Note: User filtering is already handled by database queries above
                    // Removing redundant filtering to ensure TNT (#tnt) and other non-player entities are included
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
                                    BuiltInRegistries.ITEM.getKey(entry.stack.getItem()).toString(), includeItemFinal));
                    blockLogs.removeIf(entry -> !com.minetracer.features.minetracer.util.MaterialMatcher.matchesIncludeFilter(
                            entry.blockId, includeItemFinal));
                    itemLogs.removeIf(entry -> !com.minetracer.features.minetracer.util.MaterialMatcher.matchesIncludeFilter(
                            BuiltInRegistries.ITEM.getKey(entry.stack.getItem()).toString(), includeItemFinal));
                }
                if (excludeItem != null && !excludeItem.isEmpty()) {
                    final String excludeItemFinal = excludeItem;
                    
                    // Exclude matching items
                    containerLogs.removeIf(
                            entry -> com.minetracer.features.minetracer.util.MaterialMatcher.matchesExcludeFilter(
                                    BuiltInRegistries.ITEM.getKey(entry.stack.getItem()).toString(), excludeItemFinal));
                    blockLogs.removeIf(entry -> com.minetracer.features.minetracer.util.MaterialMatcher.matchesExcludeFilter(
                            entry.blockId, excludeItemFinal));
                    itemLogs.removeIf(entry -> com.minetracer.features.minetracer.util.MaterialMatcher.matchesExcludeFilter(
                            BuiltInRegistries.ITEM.getKey(entry.stack.getItem()).toString(), excludeItemFinal));
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
            QueryContext queryContext = new QueryContext(flatList, arg, source.getPlayer().blockPosition());
            lastQueries.put(source.getPlayer().getUUID(), queryContext);
            displayPage(source, flatList, 1, queryContext.entriesPerPage);
        }).exceptionally(throwable -> {
            source.sendFailure(Component.literal("Error performing lookup: " + throwable.getMessage()));
            return null;
        });
        return Command.SINGLE_SUCCESS;
    }
    public static void displayPage(CommandSourceStack source, List<FlatLogEntry> logs, int page, int entriesPerPage) {
        int totalEntries = logs.size();
        int totalPages = (totalEntries + entriesPerPage - 1) / entriesPerPage;
        int start = (page - 1) * entriesPerPage;
        int end = Math.min(start + entriesPerPage, totalEntries);
        if (start >= totalEntries || page < 1) {
            source.sendFailure(Component.literal("Invalid page number."));
            return;
        }
        source.sendSuccess(() -> Component.literal("----- MineTracer Lookup Results -----").withStyle(ChatFormatting.AQUA),
                false);
        for (int i = start; i < end; i++) {
            FlatLogEntry fle = logs.get(i);
            source.sendSuccess(() -> formatCoordinatesForChat(fle.entry), false);
            source.sendSuccess(() -> formatLogEntryForChat(fle.entry), false);
            if (fle.entry instanceof MineTracerLookup.SignLogEntry) {
                MineTracerLookup.SignLogEntry se = (MineTracerLookup.SignLogEntry) fle.entry;
                if (se.action.equals("edit") && se.nbt != null && !se.nbt.isEmpty()) {
                    try {
                        com.google.gson.Gson gson = new com.google.gson.Gson();
                        com.google.gson.JsonObject nbtObj = gson.fromJson(se.nbt, com.google.gson.JsonObject.class);
                        String[] beforeLines = gson.fromJson(nbtObj.get("before"), String[].class);
                        String[] afterLines = gson.fromJson(nbtObj.get("after"), String[].class);
                        source.sendSuccess(() -> Component.literal("[before]").withStyle(ChatFormatting.RED), false);
                        for (String line : beforeLines) {
                            if (line != null && !line.trim().isEmpty()) {
                                source.sendSuccess(() -> Component.literal("  " + line).withStyle(ChatFormatting.WHITE), false);
                            }
                        }
                        source.sendSuccess(() -> Component.literal("[after]").withStyle(ChatFormatting.GREEN), false);
                        for (String line : afterLines) {
                            if (line != null && !line.trim().isEmpty()) {
                                source.sendSuccess(() -> Component.literal("  " + line).withStyle(ChatFormatting.WHITE), false);
                            }
                        }
                    } catch (Exception e) {
                        source.sendSuccess(
                                () -> Component.literal("  (Sign text parsing failed)").withStyle(ChatFormatting.GRAY), false);
                    }
                }
            }
        }
        source.sendSuccess(
                () -> Component
                        .literal("Page " + page + "/" + totalPages + " (" + totalEntries
                                + " entries) - Use /minetracer page <number> for other pages")
                        .withStyle(ChatFormatting.GRAY),
                false);
    }
    public static Component formatLogEntryForChat(Object entry) {
        if (entry instanceof MineTracerLookup.ContainerLogEntry) {
            MineTracerLookup.ContainerLogEntry ce = (MineTracerLookup.ContainerLogEntry) entry;
            String timeAgo = getTimeAgo(Duration.between(ce.timestamp, Instant.now()).getSeconds());
            String itemId = BuiltInRegistries.ITEM.getKey(ce.stack.getItem()).toString();
            String itemName = ce.stack.getItem().getName(ce.stack).getString();
            boolean isRolledBack = ce.rolledBack;
            Component base = Component.literal(timeAgo + " ago").withStyle(ChatFormatting.WHITE)
                    .append(Component.literal(" — ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(ce.playerName).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" " + ce.action + " ").withStyle(ChatFormatting.GREEN))
                    .append(Component.literal(ce.stack.getCount() + "x ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal("#" + itemId).withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal(" (" + itemName + ")").withStyle(ChatFormatting.GRAY));
            if (isRolledBack) {
                base = base.copy().setStyle(base.getStyle().withStrikethrough(true).withColor(ChatFormatting.DARK_GRAY));
            }
            return base;
        } else if (entry instanceof MineTracerLookup.BlockLogEntry) {
            MineTracerLookup.BlockLogEntry be = (MineTracerLookup.BlockLogEntry) entry;
            String timeAgo = getTimeAgo(Duration.between(be.timestamp, Instant.now()).getSeconds());
            net.minecraft.world.level.block.Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(be.blockId));
            String blockName = block.getName().getString();
            boolean isRolledBack = be.rolledBack;
            Component base = Component.literal(timeAgo + " ago").withStyle(ChatFormatting.WHITE)
                    .append(Component.literal(" — ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(be.playerName).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" " + be.action + " block ").withStyle(ChatFormatting.GREEN))
                    .append(Component.literal("#" + be.blockId).withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal(" (" + blockName + ")").withStyle(ChatFormatting.GRAY));
            if (isRolledBack) {
                base = base.copy().setStyle(base.getStyle().withStrikethrough(true).withColor(ChatFormatting.DARK_GRAY));
            }
            return base;
        } else if (entry instanceof MineTracerLookup.SignLogEntry) {
            MineTracerLookup.SignLogEntry se = (MineTracerLookup.SignLogEntry) entry;
            String timeAgo = getTimeAgo(Duration.between(se.timestamp, Instant.now()).getSeconds());
            boolean isRolledBack = se.rolledBack;
            Component base = Component.literal(timeAgo + " ago").withStyle(ChatFormatting.WHITE)
                    .append(Component.literal(" — ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(se.playerName).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" edited sign").withStyle(ChatFormatting.YELLOW));
            if (isRolledBack) {
                base = base.copy().setStyle(base.getStyle().withStrikethrough(true).withColor(ChatFormatting.DARK_GRAY));
            }
            return base;
        } else if (entry instanceof MineTracerLookup.KillLogEntry) {
            MineTracerLookup.KillLogEntry ke = (MineTracerLookup.KillLogEntry) entry;
            String timeAgo = getTimeAgo(Duration.between(ke.timestamp, Instant.now()).getSeconds());
            boolean isRolledBack = ke.rolledBack;
            Component base = Component.literal(timeAgo + " ago").withStyle(ChatFormatting.WHITE)
                    .append(Component.literal(" — ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(ke.killerName).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" killed ").withStyle(ChatFormatting.GREEN))
                    .append(Component.literal(ke.victimName).withStyle(ChatFormatting.RED));
            if (isRolledBack) {
                base = base.copy().setStyle(base.getStyle().withStrikethrough(true).withColor(ChatFormatting.DARK_GRAY));
            }
            return base;
        } else if (entry instanceof MineTracerLookup.ItemPickupDropLogEntry) {
            MineTracerLookup.ItemPickupDropLogEntry ie = (MineTracerLookup.ItemPickupDropLogEntry) entry;
            String timeAgo = getTimeAgo(Duration.between(ie.timestamp, Instant.now()).getSeconds());
            String itemId = BuiltInRegistries.ITEM.getKey(ie.stack.getItem()).toString();
            String itemName = ie.stack.getItem().getName(ie.stack).getString();
            Component base = Component.literal(timeAgo + " ago").withStyle(ChatFormatting.WHITE)
                    .append(Component.literal(" — ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(ie.playerName).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" " + ie.action + " ").withStyle(ChatFormatting.GREEN))
                    .append(Component.literal(ie.stack.getCount() + "x ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal("#" + itemId).withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal(" (" + itemName + ")").withStyle(ChatFormatting.GRAY));
            return base;
        }
        return Component.literal("Unknown log entry").withStyle(ChatFormatting.GRAY);
    }
    public static Component formatCoordinatesForChat(Object entry) {
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
            
            return Component.literal(coordText)
                    .withStyle(ChatFormatting.GOLD)
                    .withStyle(style -> style
                            // ClickEvent and HoverEvent are now records in 1.21.11
                            // .withClickEvent(new ClickEvent(Action.RUN_COMMAND, teleportCommand))
                            // .withHoverEvent(new HoverEvent(Action.SHOW_TEXT, Text.literal("Click to teleport")))
                            .withUnderlined(true));
        }
        return Component.literal("").withStyle(ChatFormatting.GRAY);
    }
    public static int rollback(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!Permissions.check(source, "minetracer.command.rollback", 2)) {
            source.sendFailure(Component.literal("You do not have permission to use this command."));
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
        boolean force = false;
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
            } else if (part.equals("#force")) {
                force = true;
            }
        }
        BlockPos playerPos = source.getPlayer().blockPosition();
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
            source.sendFailure(Component.literal(
                    "Rollback requires at least 2 of these filters: range:<blocks>, time:<duration>, user:<player>. Examples: 'range:50 user:PlayerName' or 'time:1h user:PlayerName' or 'range:20 time:30m'. Add #force to re-rollback previously processed entries."));
            return Command.SINGLE_SUCCESS;
        }
        
        // Use the new database lookup system (same as lookup command)
        String worldName = ((com.minetracer.mixin.EntityAccessor)source.getPlayer()).getWorld().dimension().identifier().toString();
        List<MineTracerLookup.BlockLogEntry> blockLogs;
        List<MineTracerLookup.SignLogEntry> signLogs;
        List<MineTracerLookup.ContainerLogEntry> containerLogs;
        List<MineTracerLookup.KillLogEntry> killLogs;
        
        // When user is specified without an explicit range, search the full world
        // (mirrors CoreProtect's r:#world behavior for user-based queries like u:#tnt)
        int searchRange = (!hasRange && hasUser) ? 50000 : range;

        // Wait for the consumer queue to drain so all explosion/block entries are in the DB
        // before we query. With a 5-second timeout to avoid blocking indefinitely.
        int queueSizeBefore = MineTracerConsumer.getQueueSize();
        if (queueSizeBefore > 0) {
            final int finalQueueSize = queueSizeBefore;
            source.sendSuccess(() -> Component.literal("[MineTracer] Flushing " + finalQueueSize + " pending log entries before rollback...")
                    .withStyle(ChatFormatting.GRAY), false);
            MineTracerConsumer.waitForQueue(5000);
        }

        try {
            blockLogs = MineTracerLookup.getBlockLogsInRangeAsync(playerPos, searchRange, userFilter, worldName, Integer.MAX_VALUE).get();
            signLogs = MineTracerLookup.getSignLogsInRangeAsync(playerPos, searchRange, userFilter, worldName).get();
            containerLogs = MineTracerLookup.getContainerLogsInRangeAsync(playerPos, searchRange, userFilter, worldName).get();
            killLogs = MineTracerLookup.getKillLogsInRangeAsync(playerPos, searchRange, userFilter, worldName).get();
        } catch (Exception e) {
            source.sendFailure(Component.literal("[MineTracer] Error querying database: " + e.getMessage()));
            e.printStackTrace();
            return Command.SINGLE_SUCCESS;
        }
        
        boolean filterByKiller = actionFilters.contains("kill");
        
        // Note: User filtering is already handled by the database queries above,
        // so we don't need additional filtering here. This ensures TNT (#tnt) and
        // other non-player entities are properly included in rollbacks.
        
        // Debug: Show counts before time filtering
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
                            BuiltInRegistries.ITEM.getKey(entry.stack.getItem()).toString(), includeItemFinal));
            blockLogs.removeIf(entry -> !com.minetracer.features.minetracer.util.MaterialMatcher.matchesIncludeFilter(
                    entry.blockId, includeItemFinal));
        }
        if (excludeItem != null && !excludeItem.isEmpty()) {
            final String excludeItemFinal = excludeItem;
            // Exclude matching items
            containerLogs.removeIf(
                    entry -> com.minetracer.features.minetracer.util.MaterialMatcher.matchesExcludeFilter(
                            BuiltInRegistries.ITEM.getKey(entry.stack.getItem()).toString(), excludeItemFinal));
            blockLogs.removeIf(entry -> com.minetracer.features.minetracer.util.MaterialMatcher.matchesExcludeFilter(
                    entry.blockId, excludeItemFinal));
        }
        int successfulRollbacks = 0;
        int failedRollbacks = 0;
        ServerLevel world = source.getLevel();
        
        int totalActions = containerLogs.size() + blockLogs.size() + signLogs.size();
        if (totalActions == 0) {
            source.sendSuccess(() -> Component.literal("[MineTracer] No actions found matching the specified filters.")
                    .withStyle(ChatFormatting.YELLOW), false);
            return Command.SINGLE_SUCCESS;
        }
        
        // Preview mode - show ghost blocks to the player
        if (preview) {
            source.sendSuccess(() -> Component.literal("[MineTracer] PREVIEW MODE - Showing ghost blocks...")
                    .withStyle(ChatFormatting.YELLOW), false);
            source.sendSuccess(() -> Component.literal("Found " + totalActions + " actions to preview.")
                    .withStyle(ChatFormatting.AQUA), false);
            
            ServerPlayer player = source.getPlayer();
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
            source.sendSuccess(() -> Component.literal("Showing " + finalGhostBlocksShown + " ghost blocks. They will disappear when you relog or move away.")
                    .withStyle(ChatFormatting.GRAY), false);
            source.sendSuccess(() -> Component.literal("Run without #preview to execute the rollback.")
                    .withStyle(ChatFormatting.YELLOW), false);
            return Command.SINGLE_SUCCESS;
        }
        
        source.sendSuccess(() -> Component.literal("[MineTracer] Found " + totalActions + " actions to rollback.")
                .withStyle(ChatFormatting.AQUA), false);
        if (actionFilters.isEmpty()) {
            blockLogs.sort((a, b) -> b.timestamp.compareTo(a.timestamp));
            signLogs.sort((a, b) -> b.timestamp.compareTo(a.timestamp));
            containerLogs.sort((a, b) -> b.timestamp.compareTo(a.timestamp));
            source.sendSuccess(() -> Component.literal("[MineTracer] Processing rollback in reverse chronological order (newest actions first).")
                    .withStyle(ChatFormatting.GRAY), false);
        }
        if (actionFilters.isEmpty()) {
            int brokeEntriesProcessed = 0;
            int brokeEntriesSkipped = 0;
            int alreadyRolledBack = 0;
            int wrongAction = 0;
            
            for (MineTracerLookup.BlockLogEntry entry : blockLogs) {
                if ("broke".equals(entry.action)) {
                    if (!entry.rolledBack || force) {
                        brokeEntriesProcessed++;
                        if (performBlockPlaceRollback(world, entry)) {
                            successfulRollbacks++;
                        } else {
                            failedRollbacks++;
                        }
                    } else {
                        alreadyRolledBack++;
                    }
                } else {
                    wrongAction++;
                }
            }
            
            for (MineTracerLookup.BlockLogEntry entry : blockLogs) {
                if ("placed".equals(entry.action)) {
                    if (!entry.rolledBack || force) {
                        if (performBlockBreakRollback(world, entry)) {
                            successfulRollbacks++;
                        } else {
                            failedRollbacks++;
                        }
                    }
                }
            }
            // Only roll back container entries that occurred at or after the earliest block event
            // being rolled back (e.g., the explosion). Pre-explosion deposits/withdrawals must not
            // be undone when rolling back a block-destruction event.
            java.time.Instant earliestBlockEvent = blockLogs.stream()
                    .map(e -> e.timestamp)
                    .min(java.time.Instant::compareTo)
                    .orElse(java.time.Instant.EPOCH);
            for (MineTracerLookup.ContainerLogEntry entry : containerLogs) {
                if (entry.timestamp.isBefore(earliestBlockEvent)) {
                    continue; // skip pre-explosion container history
                }
                if (!entry.rolledBack || force) {
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
                if ("edit".equals(entry.action)) {
                    if (!entry.rolledBack || force) {
                        if (performSignRollback(world, entry)) {
                            successfulRollbacks++;
                        } else {
                            failedRollbacks++;
                        }
                    }
                }
            }
        } else {
            for (MineTracerLookup.ContainerLogEntry entry : containerLogs) {
                if (!entry.rolledBack || force) {
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
                if (!entry.rolledBack || force) {
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
                if ("edit".equals(entry.action)) {
                    if (!entry.rolledBack || force) {
                        if (performSignRollback(world, entry)) {
                            successfulRollbacks++;
                        } else {
                            failedRollbacks++;
                        }
                    }
                }
            }
        }
        // Final pass: geometrically re-link every adjacent identical-facing chest pair
        // that was just restored. This runs after ALL blocks are placed so both halves
        // are guaranteed to be in the world, regardless of placement order.
        relinkRestoredDoubleChests(world, blockLogs);
        if (successfulRollbacks > 0 || failedRollbacks > 0) {
            final int finalSuccessfulRollbacks = successfulRollbacks;
            final int finalFailedRollbacks = failedRollbacks;
            source.sendSuccess(() -> Component.literal(
                    "[MineTracer] Rollback complete: " + finalSuccessfulRollbacks + " actions restored, " +
                            finalFailedRollbacks + " failed.")
                    .withStyle(ChatFormatting.GREEN), false);
            
            // Store operation for undo
            try {
                UUID playerId = source.getPlayer().getUUID();
                UndoOperation undoOp = new UndoOperation("rollback", blockLogs, signLogs, containerLogs);
                lastOperations.put(playerId, undoOp);
                source.sendSuccess(() -> Component.literal(
                    "[MineTracer] Use /minetracer undo to revert this rollback.")
                    .withStyle(ChatFormatting.GRAY), false);
            } catch (Exception e) {
                // Player might not exist in some contexts
            }
        } else {
            source.sendSuccess(
                    () -> Component.literal("[MineTracer] No actions found to rollback.").withStyle(ChatFormatting.YELLOW),
                    false);
        }
        return Command.SINGLE_SUCCESS;
    }
    
    /**
     * Restore command - reapplies actions that were rolled back
     * This is the inverse of rollback (undoing the undo)
     */
    public static int restore(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!Permissions.check(source, "minetracer.command.restore", 2)) {
            source.sendFailure(Component.literal("You do not have permission to use this command."));
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
        
        BlockPos playerPos = source.getPlayer().blockPosition();
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
            source.sendFailure(Component.literal(
                    "Restore requires at least 2 of these filters: range:<blocks>, time:<duration>, user:<player>. Add #preview to see what would be restored."));
            return Command.SINGLE_SUCCESS;
        }
        
        // Use the new database lookup system (same as lookup and rollback commands)
        String worldName = ((com.minetracer.mixin.EntityAccessor)source.getPlayer()).getWorld().dimension().identifier().toString();
        List<MineTracerLookup.BlockLogEntry> blockLogs;
        List<MineTracerLookup.SignLogEntry> signLogs;
        List<MineTracerLookup.ContainerLogEntry> containerLogs;
        
        try {
            blockLogs = MineTracerLookup.getBlockLogsInRangeAsync(playerPos, range, userFilter, worldName).get();
            signLogs = MineTracerLookup.getSignLogsInRangeAsync(playerPos, range, userFilter, worldName).get();
            containerLogs = MineTracerLookup.getContainerLogsInRangeAsync(playerPos, range, userFilter, worldName).get();
        } catch (Exception e) {
            source.sendFailure(Component.literal("[MineTracer] Error querying database: " + e.getMessage()));
            e.printStackTrace();
            return Command.SINGLE_SUCCESS;
        }
        
        // Note: User filtering is already handled by database queries above,
        // so we don't need additional filtering here. This ensures TNT (#tnt) and
        // other non-player entities are properly included in restores.
        
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
                            BuiltInRegistries.ITEM.getKey(entry.stack.getItem()).toString(), includeItemFinal));
            blockLogs.removeIf(entry -> !com.minetracer.features.minetracer.util.MaterialMatcher.matchesIncludeFilter(
                    entry.blockId, includeItemFinal));
        }
        
        if (excludeItem != null && !excludeItem.isEmpty()) {
            final String excludeItemFinal = excludeItem;
            containerLogs.removeIf(
                    entry -> com.minetracer.features.minetracer.util.MaterialMatcher.matchesExcludeFilter(
                            BuiltInRegistries.ITEM.getKey(entry.stack.getItem()).toString(), excludeItemFinal));
            blockLogs.removeIf(entry -> com.minetracer.features.minetracer.util.MaterialMatcher.matchesExcludeFilter(
                    entry.blockId, excludeItemFinal));
        }
        
        int totalActions = containerLogs.size() + blockLogs.size() + signLogs.size();
        if (totalActions == 0) {
            source.sendSuccess(() -> Component.literal("[MineTracer] No actions found matching the specified filters.")
                    .withStyle(ChatFormatting.YELLOW), false);
            return Command.SINGLE_SUCCESS;
        }
        
        // Preview mode - show what would be restored without actually doing it
        if (previewMode) {
            source.sendSuccess(() -> Component.literal("[MineTracer] Preview: Would restore " + totalActions + " actions:")
                    .withStyle(ChatFormatting.AQUA), false);
            source.sendSuccess(() -> Component.literal("  - " + blockLogs.size() + " block changes")
                    .withStyle(ChatFormatting.GRAY), false);
            source.sendSuccess(() -> Component.literal("  - " + containerLogs.size() + " container transactions")
                    .withStyle(ChatFormatting.GRAY), false);
            source.sendSuccess(() -> Component.literal("  - " + signLogs.size() + " sign edits")
                    .withStyle(ChatFormatting.GRAY), false);
            source.sendSuccess(() -> Component.literal("Remove #preview to execute the restore.")
                    .withStyle(ChatFormatting.YELLOW), false);
            return Command.SINGLE_SUCCESS;
        }
        
        int successfulRestores = 0;
        int failedRestores = 0;
        ServerLevel world = source.getLevel();
        
        source.sendSuccess(() -> Component.literal("[MineTracer] Found " + totalActions + " actions to restore.")
                .withStyle(ChatFormatting.AQUA), false);
        
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

        // Final pass: geometrically re-link any adjacent identical-facing chest pairs
        relinkRestoredDoubleChests(world, blockLogs);

        if (successfulRestores > 0 || failedRestores > 0) {
            final int finalSuccessfulRestores = successfulRestores;
            final int finalFailedRestores = failedRestores;
            source.sendSuccess(() -> Component.literal(
                    "[MineTracer] Restore complete: " + finalSuccessfulRestores + " actions reapplied, " +
                            finalFailedRestores + " failed.")
                    .withStyle(ChatFormatting.GREEN), false);
            
            // Store operation for undo
            try {
                UUID playerId = source.getPlayer().getUUID();
                UndoOperation undoOp = new UndoOperation("restore", blockLogs, signLogs, containerLogs);
                lastOperations.put(playerId, undoOp);
                source.sendSuccess(() -> Component.literal(
                    "[MineTracer] Use /minetracer undo to revert this restore.")
                    .withStyle(ChatFormatting.GRAY), false);
            } catch (Exception e) {
                // Player might not exist in some contexts
            }
        } else {
            source.sendSuccess(
                    () -> Component.literal("[MineTracer] No actions found to restore.").withStyle(ChatFormatting.YELLOW),
                    false);
        }
        
        return Command.SINGLE_SUCCESS;
    }
    
    /**
     * Undo command - reverts the last rollback or restore operation
     */
    public static int undo(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!Permissions.check(source, "minetracer.command.undo", 2)) {
            source.sendFailure(Component.literal("You do not have permission to use this command."));
            return 0;
        }
        
        try {
            UUID playerId = source.getPlayer().getUUID();
            UndoOperation lastOp = lastOperations.get(playerId);
            
            if (lastOp == null) {
                source.sendFailure(Component.literal("[MineTracer] No recent rollback or restore to undo."));
                return 0;
            }
            
            // Check if operation is recent (within 5 minutes)
            long minutesAgo = Duration.between(lastOp.timestamp, Instant.now()).toMinutes();
            if (minutesAgo > 5) {
                source.sendFailure(Component.literal("[MineTracer] Last operation was " + minutesAgo + " minutes ago. Undo is only available for recent operations (within 5 minutes)."));
                return 0;
            }
            
            int successfulUndos = 0;
            int failedUndos = 0;
            ServerLevel world = source.getLevel();
            
            source.sendSuccess(() -> Component.literal("[MineTracer] Undoing last " + lastOp.type + " operation...")
                    .withStyle(ChatFormatting.AQUA), false);
            
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
            source.sendSuccess(() -> Component.literal(
                    "[MineTracer] Undo complete: " + finalSuccessfulUndos + " changes reverted, " +
                            finalFailedUndos + " failed.")
                    .withStyle(ChatFormatting.GREEN), false);
            
        } catch (Exception e) {
            source.sendFailure(Component.literal("[MineTracer] Failed to undo: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
        
        return Command.SINGLE_SUCCESS;
    }
    
    /**
     * Gets the proper inventory for a container at the given position.
     * Handles double chests by using the BlockState's inventory provider.
     */
    private static Container getContainerInventory(ServerLevel world, BlockPos pos) {
        BlockState blockState = world.getBlockState(pos);
        
        // For ChestBlock (including double chests), use the block's inventory method
        if (blockState.getBlock() instanceof net.minecraft.world.level.block.ChestBlock) {
            net.minecraft.world.level.block.ChestBlock chestBlock = (net.minecraft.world.level.block.ChestBlock) blockState.getBlock();
            // This properly handles double chests by returning the combined 54-slot inventory
            return net.minecraft.world.level.block.ChestBlock.getContainer(chestBlock, blockState, world, pos, true);
        }
        
        // For other containers, use the BlockEntity directly
        net.minecraft.world.level.block.entity.BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity instanceof Container) {
            return (Container) blockEntity;
        }
        
        return null;
    }
    
    private static boolean performWithdrawalRollback(ServerLevel world, MineTracerLookup.ContainerLogEntry entry) {
        try {
            BlockPos pos = entry.pos;
            ItemStack stackToRestore = entry.stack.copy();
            Container inventory = getContainerInventory(world, pos);
            if (inventory != null) {
                ItemStack remaining = addItemToInventory(inventory, stackToRestore);
                inventory.setChanged();
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
    private static boolean performDepositRollback(ServerLevel world, MineTracerLookup.ContainerLogEntry entry) {
        try {
            BlockPos pos = entry.pos;
            ItemStack stackToRemove = entry.stack.copy();
            Container inventory = getContainerInventory(world, pos);
            if (inventory != null) {
                ItemStack remaining = removeItemFromInventory(inventory, stackToRemove);
                inventory.setChanged();
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
    private static ItemStack removeItemFromInventory(Container inventory, ItemStack stackToRemove) {
        ItemStack remaining = stackToRemove.copy();
        for (int i = 0; i < inventory.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack existingStack = inventory.getItem(i);
            if (!existingStack.isEmpty() && ItemStack.isSameItemSameComponents(existingStack, remaining)) {
                int canRemove = Math.min(existingStack.getCount(), remaining.getCount());
                if (canRemove > 0) {
                    existingStack.shrink(canRemove);
                    remaining.shrink(canRemove);
                    if (existingStack.isEmpty()) {
                        inventory.setItem(i, ItemStack.EMPTY);
                    } else {
                        inventory.setItem(i, existingStack);
                    }
                }
            }
        }
        return remaining;
    }
    private static ItemStack addItemToInventory(Container inventory, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int i = 0; i < inventory.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack existingStack = inventory.getItem(i);
            if (!existingStack.isEmpty() && ItemStack.isSameItemSameComponents(existingStack, remaining)) {
                int maxStackSize = existingStack.getMaxStackSize();
                int canAdd = maxStackSize - existingStack.getCount();
                if (canAdd > 0) {
                    int toAdd = Math.min(canAdd, remaining.getCount());
                    existingStack.grow(toAdd);
                    remaining.shrink(toAdd);
                    inventory.setItem(i, existingStack);
                }
            }
        }
        for (int i = 0; i < inventory.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack existingStack = inventory.getItem(i);
            if (existingStack.isEmpty()) {
                int maxStackSize = remaining.getMaxStackSize();
                int toPlace = Math.min(maxStackSize, remaining.getCount());
                ItemStack toSet = remaining.copy();
                toSet.setCount(toPlace);
                inventory.setItem(i, toSet);
                remaining.shrink(toPlace);
            }
        }
        return remaining;
    }
    private static boolean performBlockBreakRollback(ServerLevel world, MineTracerLookup.BlockLogEntry entry) {
        try {
            BlockPos pos = entry.pos;
            world.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            
            // CoreProtect-style: Mark as rolled back in database
            markBlockEntryRolledBack(entry, world);
            
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    private static boolean performBlockPlaceRollback(ServerLevel world, MineTracerLookup.BlockLogEntry entry) {
        try {
            BlockPos pos = entry.pos;
            net.minecraft.world.level.block.Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getValue(net.minecraft.resources.Identifier.parse(entry.blockId));
            if (block == null || block == net.minecraft.world.level.block.Blocks.AIR) {
                return false;
            }

            net.minecraft.world.level.block.state.BlockState blockState = block.defaultBlockState();

            // Apply stored block state properties.
            // Handles three formats in priority order:
            //   1. "[snowy=false,facing=north]" - new CoreProtect-style bracket format
            //   2. "{Properties:{snowy:\"false\"}}" - previous SNBT format
            //   3. "Block{minecraft:grass_block}[snowy=false]" - legacy toString format
            String nbt = entry.nbt;
            if (nbt != null && !nbt.isEmpty()) {
                String propsSection = null;
                net.minecraft.nbt.CompoundTag blockEntityNbt = null;

                if (nbt.startsWith("[")) {
                    // Format 1: CoreProtect-style bracket properties
                    int end = nbt.indexOf(']');
                    if (end > 1) {
                        propsSection = nbt.substring(1, end);
                    }
                } else if (nbt.startsWith("{")) {
                    // Format 2: SNBT
                    net.minecraft.nbt.CompoundTag compound = com.minetracer.features.minetracer.util.NbtCompatHelper.parseNbtString(nbt);
                    if (compound.contains("Properties") && compound.get("Properties") instanceof net.minecraft.nbt.CompoundTag props) {
                        StringBuilder sb = new StringBuilder();
                        for (String key : props.keySet()) {
                            if (sb.length() > 0) sb.append(',');
                            sb.append(key).append('=').append(props.getString(key).orElse(""));
                        }
                        propsSection = sb.toString();
                    }
                    if (compound.contains("BlockEntityTag") && compound.get("BlockEntityTag") instanceof net.minecraft.nbt.CompoundTag bet) {
                        blockEntityNbt = bet;
                    }
                } else if (nbt.startsWith("Block{")) {
                    // Format 3: legacy toString
                    int bracketStart = nbt.indexOf('[');
                    int bracketEnd = nbt.lastIndexOf(']');
                    if (bracketStart != -1 && bracketEnd > bracketStart) {
                        propsSection = nbt.substring(bracketStart + 1, bracketEnd);
                    }
                }

                if (propsSection != null && !propsSection.isEmpty()) {
                    for (String kv : propsSection.split(",")) {
                        String[] pair = kv.trim().split("=", 2);
                        if (pair.length == 2) {
                            String key = pair[0].trim();
                            String value = pair[1].trim();
                            for (net.minecraft.world.level.block.state.properties.Property<?> prop : blockState.getProperties()) {
                                if (prop.getName().equals(key)) {
                                    blockState = setBlockStateProperty(blockState, prop, value);
                                    break;
                                }
                            }
                        }
                    }
                }

                // Use FORCE_STATE so that getStateForNeighborUpdate() does NOT reset
                // a chest's type (e.g. LEFT→SINGLE) because the matching half is not
                // in the world yet.  NOTIFY_ALL is still set so clients see the change.
                int placeFlags = (block instanceof net.minecraft.world.level.block.ChestBlock)
                        ? (net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE | net.minecraft.world.level.block.Block.UPDATE_ALL)
                        : net.minecraft.world.level.block.Block.UPDATE_ALL;

                world.setBlock(pos, blockState, placeFlags);

                // If this is one half of a double chest, ensure the neighbour is also correctly set
                // so Minecraft connects the two halves. This must happen before we restore inventory.
                if (block instanceof net.minecraft.world.level.block.ChestBlock &&
                        blockState.hasProperty(net.minecraft.world.level.block.ChestBlock.TYPE)) {
                    net.minecraft.world.level.block.state.properties.ChestType chestType =
                            blockState.getValue(net.minecraft.world.level.block.ChestBlock.TYPE);
                    if (chestType != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
                        linkDoubleChestNeighbour(world, pos, blockState, block);
                    }
                }

                if (blockEntityNbt != null && !blockEntityNbt.isEmpty()) {
                    blockEntityNbt.putInt("x", pos.getX());
                    blockEntityNbt.putInt("y", pos.getY());
                    blockEntityNbt.putInt("z", pos.getZ());
                    // Re-place to ensure a fresh block entity exists (handles cases where
                    // the world already had a different block entity at this pos)
                    world.removeBlockEntity(pos);
                    world.setBlock(pos, blockState, placeFlags);
                    net.minecraft.world.level.block.entity.BlockEntity newBE = world.getBlockEntity(pos);
                    if (newBE instanceof net.minecraft.world.Container inv) {
                        // Clear first so stale items don't remain
                        for (int i = 0; i < inv.getContainerSize(); i++) {
                            inv.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
                        }
                        java.util.Optional<net.minecraft.nbt.ListTag> itemsOpt = blockEntityNbt.getList("Items");
                        if (itemsOpt.isPresent()) {
                            for (int i = 0; i < itemsOpt.get().size(); i++) {
                                if (itemsOpt.get().get(i) instanceof net.minecraft.nbt.CompoundTag itemNbt) {
                                    java.util.Optional<Byte> slotOpt = itemNbt.getByte("Slot");
                                    if (slotOpt.isPresent()) {
                                        int slot = slotOpt.get() & 255;
                                        if (slot < inv.getContainerSize()) {
                                            // Strip "Slot" before passing to ItemStack.CODEC — the CODEC only
                                            // knows about id/count/components, not the container-specific Slot key
                                            net.minecraft.nbt.CompoundTag itemOnlyNbt = itemNbt.copy();
                                            itemOnlyNbt.remove("Slot");
                                            net.minecraft.world.item.ItemStack stack =
                                                com.minetracer.features.minetracer.util.NbtCompatHelper.itemStackFromNbt(itemOnlyNbt, world.registryAccess());
                                            if (!stack.isEmpty()) {
                                                inv.setItem(slot, stack);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        inv.setChanged();
                    }
                    if (newBE != null) newBE.setChanged();
                    world.sendBlockUpdated(pos, blockState, blockState, net.minecraft.world.level.block.Block.UPDATE_ALL);
                }
            } else {
                int placeFlags = (block instanceof net.minecraft.world.level.block.ChestBlock)
                        ? (net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE | net.minecraft.world.level.block.Block.UPDATE_ALL)
                        : net.minecraft.world.level.block.Block.UPDATE_ALL;
                world.setBlock(pos, blockState, placeFlags);
                // Link double chests in the no-NBT path too
                if (block instanceof net.minecraft.world.level.block.ChestBlock &&
                        blockState.hasProperty(net.minecraft.world.level.block.ChestBlock.TYPE)) {
                    net.minecraft.world.level.block.state.properties.ChestType chestType =
                            blockState.getValue(net.minecraft.world.level.block.ChestBlock.TYPE);
                    if (chestType != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
                        linkDoubleChestNeighbour(world, pos, blockState, block);
                    }
                }
            }

            markBlockEntryRolledBack(entry, world);
            return true;
        } catch (Exception e) {
            System.err.println("[MineTracer] Exception in performBlockPlaceRollback: " + e.getMessage());
            return false;
        }
    }

    /**
     * Special handling for chest restoration to properly handle double chests
     */
    /**
     * After ALL blocks have been placed/restored, scan blockLogs for chest blocks
     * and geometrically determine the correct LEFT/RIGHT type for every adjacent
     * pair of matching same-facing chests.  This runs as a post-rollback second pass,
     * guaranteeing correctness regardless of placement order or NBT accuracy.
     */
    private static void relinkRestoredDoubleChests(ServerLevel world,
            java.util.List<MineTracerLookup.BlockLogEntry> blockLogs) {
        java.util.Set<net.minecraft.core.BlockPos> processed = new java.util.HashSet<>();
        for (MineTracerLookup.BlockLogEntry entry : blockLogs) {
            net.minecraft.core.BlockPos pos = entry.pos;
            if (processed.contains(pos)) continue;
            net.minecraft.world.level.block.state.BlockState state = world.getBlockState(pos);
            net.minecraft.world.level.block.Block blk = state.getBlock();
            if (!(blk instanceof net.minecraft.world.level.block.ChestBlock)) continue;
            if (!state.hasProperty(net.minecraft.world.level.block.ChestBlock.TYPE)) continue;
            if (!state.hasProperty(net.minecraft.world.level.block.ChestBlock.FACING)) continue;
            net.minecraft.core.Direction facing = state.getValue(net.minecraft.world.level.block.ChestBlock.FACING);
            // Only check the two directions perpendicular to facing
            net.minecraft.core.Direction[] perp = {
                facing.getClockWise(), facing.getCounterClockWise()
            };
            boolean linked = false;
            for (net.minecraft.core.Direction dir : perp) {
                net.minecraft.core.BlockPos neighbourPos = pos.relative(dir);
                net.minecraft.world.level.block.state.BlockState neighbourState = world.getBlockState(neighbourPos);
                if (neighbourState.getBlock() == blk
                        && neighbourState.hasProperty(net.minecraft.world.level.block.ChestBlock.FACING)
                        && neighbourState.getValue(net.minecraft.world.level.block.ChestBlock.FACING) == facing) {
                    // dir == rotateYClockwise → neighbour is to the right → this block is LEFT
                    net.minecraft.world.level.block.state.properties.ChestType myType =
                            (dir == perp[0]) ? net.minecraft.world.level.block.state.properties.ChestType.LEFT
                                             : net.minecraft.world.level.block.state.properties.ChestType.RIGHT;
                    net.minecraft.world.level.block.state.properties.ChestType neighbourType =
                            (dir == perp[0]) ? net.minecraft.world.level.block.state.properties.ChestType.RIGHT
                                             : net.minecraft.world.level.block.state.properties.ChestType.LEFT;
                    int flags = net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE | net.minecraft.world.level.block.Block.UPDATE_ALL;
                    world.setBlock(pos, state.setValue(net.minecraft.world.level.block.ChestBlock.TYPE, myType), flags);
                    world.setBlock(neighbourPos,
                            neighbourState.setValue(net.minecraft.world.level.block.ChestBlock.TYPE, neighbourType), flags);
                    processed.add(pos);
                    processed.add(neighbourPos);
                    linked = true;
                    break;
                }
            }
            if (!linked) {
                // No matching neighbour — ensure this chest is SINGLE
                if (state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE)
                        != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
                    world.setBlock(pos,
                            state.setValue(net.minecraft.world.level.block.ChestBlock.TYPE,
                                    net.minecraft.world.level.block.state.properties.ChestType.SINGLE),
                            net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE | net.minecraft.world.level.block.Block.UPDATE_ALL);
                }
                processed.add(pos);
            }
        }
    }

    /**
     * After placing a double-chest half, ensure the neighbouring chest half has the
     * complementary ChestType so Minecraft treats them as a connected double chest.
     * This is necessary because world.setBlockState does NOT trigger the placement
     * logic that normally auto-connects adjacent chests.
     */
    private static void linkDoubleChestNeighbour(ServerLevel world, BlockPos pos,
            net.minecraft.world.level.block.state.BlockState blockState, net.minecraft.world.level.block.Block block) {
        net.minecraft.world.level.block.state.properties.ChestType myType = blockState.getValue(net.minecraft.world.level.block.ChestBlock.TYPE);
        net.minecraft.core.Direction facing = blockState.getValue(net.minecraft.world.level.block.ChestBlock.FACING);

        // Compute direction toward the neighbour from this half's perspective
        net.minecraft.core.Direction toNeighbour;
        net.minecraft.world.level.block.state.properties.ChestType neighbourNeedsType;
        if (myType == net.minecraft.world.level.block.state.properties.ChestType.LEFT) {
            toNeighbour = facing.getClockWise();
            neighbourNeedsType = net.minecraft.world.level.block.state.properties.ChestType.RIGHT;
        } else {
            toNeighbour = facing.getCounterClockWise();
            neighbourNeedsType = net.minecraft.world.level.block.state.properties.ChestType.LEFT;
        }

        BlockPos neighbourPos = pos.relative(toNeighbour);
        net.minecraft.world.level.block.state.BlockState neighbourState = world.getBlockState(neighbourPos);

        if (neighbourState.getBlock() == block
                && neighbourState.hasProperty(net.minecraft.world.level.block.ChestBlock.TYPE)
                && neighbourState.hasProperty(net.minecraft.world.level.block.ChestBlock.FACING)) {
            net.minecraft.world.level.block.state.BlockState corrected = neighbourState
                    .setValue(net.minecraft.world.level.block.ChestBlock.TYPE, neighbourNeedsType)
                    .setValue(net.minecraft.world.level.block.ChestBlock.FACING, facing);
            if (!corrected.equals(neighbourState)) {
                // FORCE_STATE again: prevents getStateForNeighborUpdate from resetting
                // this neighbour's type based on what is (or isn't) next to it yet.
                world.setBlock(neighbourPos, corrected,
                        net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE | net.minecraft.world.level.block.Block.UPDATE_ALL);
            }
        }
    }

    private static void restoreChestBlock(ServerLevel world, BlockPos pos, net.minecraft.world.level.block.state.BlockState blockState, net.minecraft.nbt.CompoundTag nbtCompound) {
        try {
            // Check if this is a double chest by looking at the chest type
            if (blockState.hasProperty(net.minecraft.world.level.block.ChestBlock.TYPE)) {
                net.minecraft.world.level.block.state.properties.ChestType chestType = blockState.getValue(net.minecraft.world.level.block.ChestBlock.TYPE);
                net.minecraft.core.Direction facing = blockState.getValue(net.minecraft.world.level.block.ChestBlock.FACING);
                
                if (chestType != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
                    // This is part of a double chest - ensure both halves are restored
                    BlockPos otherHalf = getOtherChestHalf(pos, facing, chestType);
                    
                    if (otherHalf != null) {
                        // Check if the other half exists and is properly configured
                        net.minecraft.world.level.block.state.BlockState otherState = world.getBlockState(otherHalf);
                        
                        if (otherState.getBlock() instanceof net.minecraft.world.level.block.ChestBlock) {
                            net.minecraft.world.level.block.state.properties.ChestType otherChestType = otherState.getValue(net.minecraft.world.level.block.ChestBlock.TYPE);
                            net.minecraft.core.Direction otherFacing = otherState.getValue(net.minecraft.world.level.block.ChestBlock.FACING);
                            
                            // Ensure both halves have correct properties
                            if (otherFacing != facing || 
                                (chestType == net.minecraft.world.level.block.state.properties.ChestType.LEFT && otherChestType != net.minecraft.world.level.block.state.properties.ChestType.RIGHT) ||
                                (chestType == net.minecraft.world.level.block.state.properties.ChestType.RIGHT && otherChestType != net.minecraft.world.level.block.state.properties.ChestType.LEFT)) {
                                
                                // Fix the other half
                                net.minecraft.world.level.block.state.properties.ChestType correctOtherType = 
                                    chestType == net.minecraft.world.level.block.state.properties.ChestType.LEFT ? 
                                    net.minecraft.world.level.block.state.properties.ChestType.RIGHT : net.minecraft.world.level.block.state.properties.ChestType.LEFT;
                                
                                net.minecraft.world.level.block.state.BlockState correctedOtherState = net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState()
                                    .setValue(net.minecraft.world.level.block.ChestBlock.FACING, facing)
                                    .setValue(net.minecraft.world.level.block.ChestBlock.TYPE, correctOtherType);
                                
                                world.setBlockAndUpdate(otherHalf, correctedOtherState);
                            }
                        } else {
                            // Other half is missing - place it
                            net.minecraft.world.level.block.state.properties.ChestType correctOtherType = 
                                chestType == net.minecraft.world.level.block.state.properties.ChestType.LEFT ? 
                                net.minecraft.world.level.block.state.properties.ChestType.RIGHT : net.minecraft.world.level.block.state.properties.ChestType.LEFT;
                            
                            net.minecraft.world.level.block.state.BlockState otherChestState = net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState()
                                .setValue(net.minecraft.world.level.block.ChestBlock.FACING, facing)
                                .setValue(net.minecraft.world.level.block.ChestBlock.TYPE, correctOtherType);
                            
                            world.setBlockAndUpdate(otherHalf, otherChestState);
                        }
                        
                        // Force block updates to ensure proper double chest formation
                        world.sendBlockUpdated(pos, blockState, blockState, 3);
                        world.sendBlockUpdated(otherHalf, world.getBlockState(otherHalf), world.getBlockState(otherHalf), 3);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[MineTracer] Error in chest restoration: " + e.getMessage());
        }
    }
    
    /**
     * Calculate the position of the other half of a double chest
     */
    private static BlockPos getOtherChestHalf(BlockPos chestPos, net.minecraft.core.Direction facing, net.minecraft.world.level.block.state.properties.ChestType chestType) {
        net.minecraft.core.Direction otherHalfDirection;
        
        // Determine where the other half should be based on facing and type
        if (chestType == net.minecraft.world.level.block.state.properties.ChestType.LEFT) {
            // Left chest, other half is to the right relative to facing
            otherHalfDirection = facing.getClockWise();
        } else if (chestType == net.minecraft.world.level.block.state.properties.ChestType.RIGHT) {
            // Right chest, other half is to the left relative to facing
            otherHalfDirection = facing.getCounterClockWise();
        } else {
            // Single chest, no other half
            return null;
        }
        
        return chestPos.relative(otherHalfDirection);
    }
    
    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> net.minecraft.world.level.block.state.BlockState setBlockStateProperty(
            net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.level.block.state.properties.Property<T> property, String value) {
        java.util.Optional<T> parsedValue = property.getValue(value);
        if (parsedValue.isPresent()) {
            return state.setValue(property, parsedValue.get());
        }
        return state;
    }
    private static boolean performSignRollback(ServerLevel world, MineTracerLookup.SignLogEntry entry) {
        try {
            BlockPos pos = entry.pos;
            net.minecraft.world.level.block.entity.BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity instanceof net.minecraft.world.level.block.entity.SignBlockEntity) {
                net.minecraft.world.level.block.entity.SignBlockEntity signEntity = (net.minecraft.world.level.block.entity.SignBlockEntity) blockEntity;
                if (entry.nbt != null && !entry.nbt.isEmpty()) {
                    try {
                        com.google.gson.Gson gson = new com.google.gson.Gson();
                        com.google.gson.JsonObject nbtObj = gson.fromJson(entry.nbt, com.google.gson.JsonObject.class);
                        String[] beforeLines = gson.fromJson(nbtObj.get("before"), String[].class);
                        net.minecraft.network.chat.Component[] beforeTexts = new net.minecraft.network.chat.Component[4];
                        for (int i = 0; i < 4; i++) {
                            if (i < beforeLines.length && beforeLines[i] != null) {
                                beforeTexts[i] = net.minecraft.network.chat.Component.literal(beforeLines[i]);
                            } else {
                                beforeTexts[i] = net.minecraft.network.chat.Component.literal("");
                            }
                        }
                        try {
                            net.minecraft.nbt.CompoundTag signNbt = signEntity.saveWithoutMetadata(world.registryAccess());
                            if (signNbt.contains("front_text")) {
                                net.minecraft.nbt.CompoundTag frontText = signNbt.contains("front_text") && signNbt.get("front_text") instanceof net.minecraft.nbt.CompoundTag ? (net.minecraft.nbt.CompoundTag)signNbt.get("front_text") : new net.minecraft.nbt.CompoundTag();
                                net.minecraft.nbt.ListTag messages = new net.minecraft.nbt.ListTag();
                                for (net.minecraft.network.chat.Component text : beforeTexts) {
                                    net.minecraft.nbt.Tag jsonText = net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, text).getOrThrow();
                                    messages.add(jsonText);
                                }
                                frontText.put("messages", messages);
                                signNbt.put("front_text", frontText);
                                // readComponentsFromNbt not available in this version
                            }
                        } catch (Exception nbtError) {
                            return false;
                        }
                        signEntity.setChanged();
                        world.sendBlockUpdated(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
                        
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
    private static boolean performWithdrawalRestore(ServerLevel world, MineTracerLookup.ContainerLogEntry entry) {
        // Restore withdrawal = remove item (same as deposit rollback)
        return performDepositRollback(world, entry);
    }
    
    /**
     * Restore a deposit - adds the item back to container (undoes the rollback that removed it)
     */
    private static boolean performDepositRestore(ServerLevel world, MineTracerLookup.ContainerLogEntry entry) {
        // Restore deposit = add item (same as withdrawal rollback)
        return performWithdrawalRollback(world, entry);
    }
    
    /**
     * Restore a block placement - places the block again
     */
    private static boolean performBlockRestore(ServerLevel world, MineTracerLookup.BlockLogEntry entry) {
        try {
            BlockPos pos = entry.pos;
            String blockId = entry.blockId;
            net.minecraft.world.level.block.Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(Identifier.parse(blockId));
            if (block != null) {
                net.minecraft.world.level.block.state.BlockState newState = block.defaultBlockState();
                world.setBlock(pos, newState, 3);
                
                // Apply NBT if available
                if (entry.nbt != null && !entry.nbt.isEmpty()) {
                    try {
                        net.minecraft.nbt.CompoundTag nbt = com.minetracer.features.minetracer.util.NbtCompatHelper.parseNbtString(entry.nbt);
                        net.minecraft.world.level.block.entity.BlockEntity blockEntity = world.getBlockEntity(pos);
                        if (blockEntity != null) {
                            // readComponentsFromNbt not available in this version
                            blockEntity.setChanged();
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
    private static boolean performBlockBreakRestore(ServerLevel world, MineTracerLookup.BlockLogEntry entry) {
        try {
            BlockPos pos = entry.pos;
            world.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    public static int lookupPage(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!Permissions.check(source, "minetracer.command.page", 2)) {
            source.sendFailure(Component.literal("You do not have permission to use this command."));
            return 0;
        }
        UUID playerId = source.getPlayer().getUUID();
        QueryContext queryContext = lastQueries.get(playerId);
        if (queryContext == null) {
            source.sendFailure(Component.literal("No previous lookup found. Please run a lookup command first."));
            return 0;
        }
        int page = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "page");
        displayPage(source, queryContext.results, page, queryContext.entriesPerPage);
        return Command.SINGLE_SUCCESS;
    }
    public static int toggleInspector(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!Permissions.check(source, "minetracer.command.inspector", 2)) {
            source.sendFailure(Component.literal("You do not have permission to use this command."));
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        boolean isInspector = OptimizedLogStorage.isInspectorMode(player);
        if (isInspector) {
            OptimizedLogStorage.setInspectorMode(player, false);
            source.sendSuccess(() -> Component.literal("Inspector mode disabled.").withStyle(ChatFormatting.YELLOW), false);
        } else {
            OptimizedLogStorage.setInspectorMode(player, true);
            source.sendSuccess(
                    () -> Component.literal("Inspector mode enabled. Right-click or break blocks to see their history.")
                            .withStyle(ChatFormatting.GREEN),
                    false);
        }
        return Command.SINGLE_SUCCESS;
    }
    public static int save(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!Permissions.check(source, "minetracer.command.save", 2)) {
            source.sendFailure(Component.literal("You do not have permission to use this command."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Forcing save of all log data...").withStyle(ChatFormatting.YELLOW), false);
        try {
            OptimizedLogStorage.forceSave();
            source.sendSuccess(
                    () -> Component.literal("Successfully saved all log data to disk.").withStyle(ChatFormatting.GREEN), false);
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error saving log data: " + e.getMessage()));
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }
    public static int showSaveHistory(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!Permissions.check(source, "minetracer.command.saves", 2)) {
            source.sendFailure(Component.literal("You do not have permission to use this command."));
            return 0;
        }
        List<OptimizedLogStorage.SaveHistory> saveHistory = OptimizedLogStorage.getSaveHistory();
        if (saveHistory.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No save history available yet.").withStyle(ChatFormatting.YELLOW), false);
            return Command.SINGLE_SUCCESS;
        }
        source.sendSuccess(() -> Component.literal("=== MineTracer Save History (Last " + saveHistory.size() + " saves) ===").withStyle(ChatFormatting.GOLD), false);
        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneId.systemDefault());
        for (int i = 0; i < saveHistory.size(); i++) {
            OptimizedLogStorage.SaveHistory save = saveHistory.get(i);
            String timeStr = formatter.format(save.timestamp);
            long kilobytes = save.fileSizeBytes / 1024;
            Component message = Component.literal(String.format("[%d] %s - %,d entries (%,d KB)", 
                i + 1, timeStr, save.totalEntries, kilobytes)).withStyle(ChatFormatting.WHITE);
            source.sendSuccess(() -> message, false);
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
    private static void markContainerEntryRolledBack(MineTracerLookup.ContainerLogEntry entry, ServerLevel world) {
        try {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try (java.sql.Connection conn = com.minetracer.features.minetracer.database.MineTracerDatabase.getConnection()) {
                    if (conn == null) return;
                    
                    String worldName = world.dimension().identifier().toString();
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
    private static void markBlockEntryRolledBack(MineTracerLookup.BlockLogEntry entry, ServerLevel world) {
        try {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try (java.sql.Connection conn = com.minetracer.features.minetracer.database.MineTracerDatabase.getConnection()) {
                    if (conn == null) return;
                    
                    String worldName = world.dimension().identifier().toString();
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
    private static void markSignEntryRolledBack(MineTracerLookup.SignLogEntry entry, ServerLevel world) {
        try {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try (java.sql.Connection conn = com.minetracer.features.minetracer.database.MineTracerDatabase.getConnection()) {
                    if (conn == null) return;
                    
                    String worldName = world.dimension().identifier().toString();
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
    private static void sendGhostBlock(ServerPlayer player, BlockPos pos, String blockId, String nbtString) {
        try {
            // Parse the block ID and get the block state
            Identifier identifier = Identifier.parse(blockId);
            Block block = BuiltInRegistries.BLOCK.getValue(identifier);
            BlockState state = block.defaultBlockState();
            
            // Send the block update packet to the player only
            ClientboundBlockUpdatePacket packet = new ClientboundBlockUpdatePacket(pos, state);
            player.connection.send(packet);
            
            // Note: NBT data (for signs, chests, etc.) would require additional block entity packets
            // For now, we just show the block type as a ghost block
        } catch (Exception e) {
            // Silently fail if block ID is invalid - ghost block preview is best-effort
        }
    }
}
