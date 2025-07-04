package com.minetracer.mixin;

import com.minetracer.features.minetracer.LogStorage;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registries;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import com.minetracer.mixin.ServerPlayerInteractionManagerAccessor;
import com.google.gson.Gson;
import java.util.concurrent.CompletableFuture;

@Mixin(ServerPlayerInteractionManager.class)
public class MixinServerPlayerInteractionManager {
    private static final Gson GSON = new Gson();

    @org.spongepowered.asm.mixin.Unique
    private BlockState minetracer$prevPlacedState = null;

    @org.spongepowered.asm.mixin.Unique
    private BlockState minetracer$prevBrokenState = null;

    @org.spongepowered.asm.mixin.Unique
    private BlockEntity minetracer$prevBrokenBlockEntity = null;

    @Inject(method = "interactBlock", at = @At("HEAD"))
    private void minetracer$cacheBlockPlaceState(ServerPlayerEntity player, net.minecraft.world.World world,
            net.minecraft.item.ItemStack stack, net.minecraft.util.Hand hand,
            net.minecraft.util.hit.BlockHitResult hitResult,
            CallbackInfoReturnable<net.minecraft.util.ActionResult> cir) {
        // Store the block state before interaction
        BlockPos placedPos = hitResult.getBlockPos().offset(hitResult.getSide());
        this.minetracer$prevPlacedState = world.getBlockState(placedPos);
    }

    @Inject(method = "interactBlock", at = @At("RETURN"))
    private void minetracer$logBlockPlace(ServerPlayerEntity player, net.minecraft.world.World world,
            net.minecraft.item.ItemStack stack, net.minecraft.util.Hand hand,
            net.minecraft.util.hit.BlockHitResult hitResult,
            CallbackInfoReturnable<net.minecraft.util.ActionResult> cir) {
        // Inspector mode check
        if (LogStorage.isInspectorMode(player)) {
            minetracer$inspectorModeInteract(player, world, stack, hand, hitResult, cir);
            return;
        }

        BlockPos placedPos = hitResult.getBlockPos().offset(hitResult.getSide());
        BlockState placedState = world.getBlockState(placedPos);
        BlockState prevState = this.minetracer$prevPlacedState;
        this.minetracer$prevPlacedState = null;

        // Only log if the block actually changed
        if (placedState.isAir() || (prevState != null && placedState.getBlock() == prevState.getBlock()))
            return;

        // Use async logging to avoid blocking the main thread
        CompletableFuture.runAsync(() -> {
            try {
                Identifier blockId = Registries.BLOCK.getId(placedState.getBlock());
                net.minecraft.block.entity.BlockEntity blockEntity = world.getBlockEntity(placedPos);

                // Create NBT compound that includes both block state properties and block
                // entity data
                net.minecraft.nbt.NbtCompound fullNbt = new net.minecraft.nbt.NbtCompound();

                // Store block state properties
                if (!placedState.getProperties().isEmpty()) {
                    net.minecraft.nbt.NbtCompound propertiesNbt = new net.minecraft.nbt.NbtCompound();
                    for (net.minecraft.state.property.Property<?> property : placedState.getProperties()) {
                        String propertyName = property.getName();
                        String propertyValue = placedState.get(property).toString();
                        propertiesNbt.putString(propertyName, propertyValue);
                    }
                    fullNbt.put("Properties", propertiesNbt);
                }

                // Store block entity data if present
                if (blockEntity != null) {
                    fullNbt.put("BlockEntityTag", blockEntity.createNbt());
                }

                String nbt = fullNbt.isEmpty() ? null : fullNbt.toString();

                // Check if this is a sign - if so, only log as sign action, not block action
                if (blockEntity instanceof net.minecraft.block.entity.SignBlockEntity) {
                    net.minecraft.block.entity.SignBlockEntity sign = (net.minecraft.block.entity.SignBlockEntity) blockEntity;
                    // Extract all visible lines as plain text
                    String[] lines = new String[4];
                    for (int i = 0; i < 4; i++) {
                        try {
                            lines[i] = sign.getFrontText().getMessage(i, false).getString();
                        } catch (Exception e) {
                            lines[i] = "";
                        }
                    }
                    String beforeText = GSON.toJson(lines);
                    LogStorage.logSignAction("placed", player, placedPos, beforeText, sign.createNbt().toString());
                } else {
                    // Log as block action for non-sign blocks
                    LogStorage.logBlockAction("placed", player, placedPos, blockId.toString(), nbt);
                }
            } catch (Exception e) {
                System.err.println("[MineTracer] Error logging block place: " + e.getMessage());
            }
        }, LogStorage.getAsyncExecutor());
    }

    @Inject(method = "tryBreakBlock", at = @At("HEAD"))
    private void minetracer$cacheBlockBreakState(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        ServerPlayerEntity player = ((ServerPlayerInteractionManagerAccessor) this).getPlayer();
        net.minecraft.world.World world = player.getWorld();

        // Cache the block state and block entity before it's broken
        this.minetracer$prevBrokenState = world.getBlockState(pos);
        this.minetracer$prevBrokenBlockEntity = world.getBlockEntity(pos);
    }

    @Inject(method = "tryBreakBlock", at = @At("RETURN"))
    private void minetracer$logBlockBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        ServerPlayerEntity player = ((ServerPlayerInteractionManagerAccessor) this).getPlayer();

        // Only log if the block was actually broken (method returned true)
        if (!cir.getReturnValue()) {
            this.minetracer$prevBrokenState = null;
            this.minetracer$prevBrokenBlockEntity = null;
            return;
        }

        BlockState state = this.minetracer$prevBrokenState;
        BlockEntity blockEntity = this.minetracer$prevBrokenBlockEntity;

        // Clear the cached values
        this.minetracer$prevBrokenState = null;
        this.minetracer$prevBrokenBlockEntity = null;

        // Inspector mode check
        if (LogStorage.isInspectorMode(player)) {
            minetracer$inspectorModeBreak(pos, cir);
            return;
        }

        if (state == null || state.isAir()) {
            return;
        }

        System.out
                .println("[MineTracer] Logging block break: " + state.getBlock().getName().getString() + " at " + pos);

        // Use async logging to avoid blocking the main thread
        CompletableFuture.runAsync(() -> {
            try {
                Identifier blockId = Registries.BLOCK.getId(state.getBlock());

                // Create NBT compound that includes both block state properties and block
                // entity data
                net.minecraft.nbt.NbtCompound fullNbt = new net.minecraft.nbt.NbtCompound();

                // Store block state properties
                if (!state.getProperties().isEmpty()) {
                    net.minecraft.nbt.NbtCompound propertiesNbt = new net.minecraft.nbt.NbtCompound();
                    for (net.minecraft.state.property.Property<?> property : state.getProperties()) {
                        String propertyName = property.getName();
                        String propertyValue = state.get(property).toString();
                        propertiesNbt.putString(propertyName, propertyValue);
                    }
                    fullNbt.put("Properties", propertiesNbt);
                }

                // Store block entity data if present
                if (blockEntity != null) {
                    fullNbt.put("BlockEntityTag", blockEntity.createNbt());
                }

                String nbt = fullNbt.isEmpty() ? null : fullNbt.toString();

                // Check if this is a sign - if so, only log as sign action, not block action
                if (blockEntity instanceof SignBlockEntity) {
                    SignBlockEntity sign = (SignBlockEntity) blockEntity;
                    // Extract all visible lines as plain text
                    String[] lines = new String[4];
                    for (int i = 0; i < 4; i++) {
                        try {
                            lines[i] = sign.getFrontText().getMessage(i, false).getString();
                        } catch (Exception e) {
                            lines[i] = "";
                        }
                    }
                    String beforeText = GSON.toJson(lines);
                    LogStorage.logSignAction("broke", player, pos, beforeText, sign.createNbt().toString());
                } else {
                    // Log as block action for non-sign blocks
                    LogStorage.logBlockAction("broke", player, pos, blockId.toString(), nbt);
                }
            } catch (Exception e) {
                System.err.println("[MineTracer] Error logging block break: " + e.getMessage());
            }
        }, LogStorage.getAsyncExecutor());
    }

    @org.spongepowered.asm.mixin.Unique
    private void minetracer$inspectorModeInteract(ServerPlayerEntity player, net.minecraft.world.World world,
            net.minecraft.item.ItemStack stack, net.minecraft.util.Hand hand,
            net.minecraft.util.hit.BlockHitResult hitResult,
            CallbackInfoReturnable<net.minecraft.util.ActionResult> cir) {
        BlockPos pos = hitResult.getBlockPos();

        // Use async lookup to avoid blocking
        CompletableFuture.supplyAsync(() -> {
            java.util.List<LogStorage.BlockLogEntry> blockLogs = LogStorage.getBlockLogsInRange(pos, 0, null);
            java.util.List<LogStorage.SignLogEntry> signLogs = LogStorage.getSignLogsInRange(pos, 0, null);
            java.util.List<LogStorage.LogEntry> containerLogs = LogStorage.getLogsInRange(pos, 0);
            java.util.List<LogStorage.KillLogEntry> killLogs = LogStorage.getKillLogsInRange(pos, 0, null);

            boolean found = false;
            StringBuilder message = new StringBuilder(
                    "§6[Inspector] Block at " + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ":");

            for (LogStorage.BlockLogEntry entry : blockLogs) {
                message.append("\n§7").append(entry.action).append(" by ").append(entry.playerName).append(" - ")
                        .append(entry.blockId);
                found = true;
            }
            for (LogStorage.SignLogEntry entry : signLogs) {
                message.append("\n§7Sign ").append(entry.action).append(" by ").append(entry.playerName);
                found = true;
            }
            for (LogStorage.LogEntry entry : containerLogs) {
                message.append("\n§7Container ").append(entry.action).append(" by ").append(entry.playerName);
                found = true;
            }
            for (LogStorage.KillLogEntry entry : killLogs) {
                if (entry.pos.equals(pos)) {
                    message.append("\n§7Kill: ").append(entry.killerName).append(" -> ").append(entry.victimName);
                    found = true;
                }
            }

            if (!found) {
                message.append("\n§8No logged activity found.");
            }

            return message.toString();
        }, LogStorage.getAsyncExecutor()).thenAccept(message -> {
            // Send message back on main thread
            player.sendMessage(net.minecraft.text.Text.literal(message), false);
        });
    }

    @org.spongepowered.asm.mixin.Unique
    private void minetracer$inspectorModeBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        ServerPlayerEntity player = ((ServerPlayerInteractionManagerAccessor) this).getPlayer();

        // Use async lookup to avoid blocking
        CompletableFuture.supplyAsync(() -> {
            java.util.List<LogStorage.BlockLogEntry> blockLogs = LogStorage.getBlockLogsInRange(pos, 0, null);
            java.util.List<LogStorage.SignLogEntry> signLogs = LogStorage.getSignLogsInRange(pos, 0, null);
            java.util.List<LogStorage.LogEntry> containerLogs = LogStorage.getLogsInRange(pos, 0);
            java.util.List<LogStorage.KillLogEntry> killLogs = LogStorage.getKillLogsInRange(pos, 0, null);

            boolean found = false;
            StringBuilder message = new StringBuilder(
                    "§6[Inspector] Block at " + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ":");

            for (LogStorage.BlockLogEntry entry : blockLogs) {
                message.append("\n§7").append(entry.action).append(" by ").append(entry.playerName).append(" - ")
                        .append(entry.blockId);
                found = true;
            }
            for (LogStorage.SignLogEntry entry : signLogs) {
                message.append("\n§7Sign ").append(entry.action).append(" by ").append(entry.playerName);
                found = true;
            }
            for (LogStorage.LogEntry entry : containerLogs) {
                message.append("\n§7Container ").append(entry.action).append(" by ").append(entry.playerName);
                found = true;
            }
            for (LogStorage.KillLogEntry entry : killLogs) {
                if (entry.pos.equals(pos)) {
                    message.append("\n§7Kill: ").append(entry.killerName).append(" -> ").append(entry.victimName);
                    found = true;
                }
            }

            if (!found) {
                message.append("\n§8No logged activity found.");
            }

            return message.toString();
        }, LogStorage.getAsyncExecutor()).thenAccept(message -> {
            // Send message back on main thread
            player.sendMessage(net.minecraft.text.Text.literal(message), false);
        });
    }
}
