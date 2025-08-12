package com.minetracer.mixin;
import com.minetracer.features.minetracer.OptimizedLogStorage;
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
        BlockPos placedPos = hitResult.getBlockPos().offset(hitResult.getSide());
        this.minetracer$prevPlacedState = world.getBlockState(placedPos);
    }
    @Inject(method = "interactBlock", at = @At("RETURN"))
    private void minetracer$logBlockPlace(ServerPlayerEntity player, net.minecraft.world.World world,
            net.minecraft.item.ItemStack stack, net.minecraft.util.Hand hand,
            net.minecraft.util.hit.BlockHitResult hitResult,
            CallbackInfoReturnable<net.minecraft.util.ActionResult> cir) {
        if (OptimizedLogStorage.isInspectorMode(player)) {
            minetracer$inspectorModeInteract(player, world, stack, hand, hitResult, cir);
            return;
        }
        BlockPos placedPos = hitResult.getBlockPos().offset(hitResult.getSide());
        BlockState placedState = world.getBlockState(placedPos);
        BlockState prevState = this.minetracer$prevPlacedState;
        this.minetracer$prevPlacedState = null;
        if (placedState.isAir() || (prevState != null && placedState.getBlock() == prevState.getBlock()))
            return;
        CompletableFuture.runAsync(() -> {
            try {
                Identifier blockId = Registries.BLOCK.getId(placedState.getBlock());
                net.minecraft.block.entity.BlockEntity blockEntity = world.getBlockEntity(placedPos);
                String nbt = null;
                if (!placedState.getProperties().isEmpty() || blockEntity != null) {
                    net.minecraft.nbt.NbtCompound fullNbt = new net.minecraft.nbt.NbtCompound();
                    if (!placedState.getProperties().isEmpty()) {
                        net.minecraft.nbt.NbtCompound propertiesNbt = new net.minecraft.nbt.NbtCompound();
                        for (net.minecraft.state.property.Property<?> property : placedState.getProperties()) {
                            String propertyName = property.getName();
                            String propertyValue = placedState.get(property).toString();
                            propertiesNbt.putString(propertyName, propertyValue);
                        }
                        fullNbt.put("Properties", propertiesNbt);
                    }
                    if (blockEntity != null) {
                        fullNbt.put("BlockEntityTag", blockEntity.createNbt());
                    }
                    nbt = fullNbt.toString();
                }
                if (blockEntity instanceof net.minecraft.block.entity.SignBlockEntity) {
                    net.minecraft.block.entity.SignBlockEntity sign = (net.minecraft.block.entity.SignBlockEntity) blockEntity;
                    String[] lines = new String[4];
                    for (int i = 0; i < 4; i++) {
                        try {
                            lines[i] = sign.getFrontText().getMessage(i, false).getString();
                        } catch (Exception e) {
                            lines[i] = "";
                        }
                    }
                    String beforeText = GSON.toJson(lines);
                    OptimizedLogStorage.logSignAction("placed", player, placedPos, beforeText, sign.createNbt().toString());
                } else {
                    OptimizedLogStorage.logBlockAction("placed", player, placedPos, blockId.toString(), nbt);
                }
            } catch (Exception e) {
            }
        }, OptimizedLogStorage.getAsyncExecutor());
    }
    @Inject(method = "tryBreakBlock", at = @At("HEAD"))
    private void minetracer$cacheBlockBreakState(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        ServerPlayerEntity player = ((ServerPlayerInteractionManagerAccessor) this).getPlayer();
        net.minecraft.world.World world = player.getWorld();
        this.minetracer$prevBrokenState = world.getBlockState(pos);
        this.minetracer$prevBrokenBlockEntity = world.getBlockEntity(pos);
    }
    @Inject(method = "tryBreakBlock", at = @At("RETURN"))
    private void minetracer$logBlockBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        ServerPlayerEntity player = ((ServerPlayerInteractionManagerAccessor) this).getPlayer();
        if (!cir.getReturnValue()) {
            this.minetracer$prevBrokenState = null;
            this.minetracer$prevBrokenBlockEntity = null;
            return;
        }
        BlockState state = this.minetracer$prevBrokenState;
        BlockEntity blockEntity = this.minetracer$prevBrokenBlockEntity;
        this.minetracer$prevBrokenState = null;
        this.minetracer$prevBrokenBlockEntity = null;
        if (OptimizedLogStorage.isInspectorMode(player)) {
            minetracer$inspectorModeBreak(pos, cir);
            return;
        }
        if (state == null || state.isAir()) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                Identifier blockId = Registries.BLOCK.getId(state.getBlock());
                String nbt = null;
                if (!state.getProperties().isEmpty() || blockEntity != null) {
                    net.minecraft.nbt.NbtCompound fullNbt = new net.minecraft.nbt.NbtCompound();
                    if (!state.getProperties().isEmpty()) {
                        net.minecraft.nbt.NbtCompound propertiesNbt = new net.minecraft.nbt.NbtCompound();
                        for (net.minecraft.state.property.Property<?> property : state.getProperties()) {
                            String propertyName = property.getName();
                            String propertyValue = state.get(property).toString();
                            propertiesNbt.putString(propertyName, propertyValue);
                        }
                        fullNbt.put("Properties", propertiesNbt);
                    }
                    if (blockEntity != null) {
                        fullNbt.put("BlockEntityTag", blockEntity.createNbt());
                    }
                    nbt = fullNbt.toString();
                }
                if (blockEntity instanceof SignBlockEntity) {
                    SignBlockEntity sign = (SignBlockEntity) blockEntity;
                    String[] lines = new String[4];
                    for (int i = 0; i < 4; i++) {
                        try {
                            lines[i] = sign.getFrontText().getMessage(i, false).getString();
                        } catch (Exception e) {
                            lines[i] = "";
                        }
                    }
                    String beforeText = GSON.toJson(lines);
                    OptimizedLogStorage.logSignAction("broke", player, pos, beforeText, sign.createNbt().toString());
                } else {
                    OptimizedLogStorage.logBlockAction("broke", player, pos, blockId.toString(), nbt);
                }
            } catch (Exception e) {
            }
        }, OptimizedLogStorage.getAsyncExecutor());
    }
    @org.spongepowered.asm.mixin.Unique
    private void minetracer$inspectorModeInteract(ServerPlayerEntity player, net.minecraft.world.World world,
            net.minecraft.item.ItemStack stack, net.minecraft.util.Hand hand,
            net.minecraft.util.hit.BlockHitResult hitResult,
            CallbackInfoReturnable<net.minecraft.util.ActionResult> cir) {
        BlockPos pos = hitResult.getBlockPos();
        CompletableFuture.supplyAsync(() -> {
            java.util.List<OptimizedLogStorage.BlockLogEntry> blockLogs = OptimizedLogStorage.getBlockLogsInRange(pos, 0, null);
            java.util.List<OptimizedLogStorage.SignLogEntry> signLogs = OptimizedLogStorage.getSignLogsInRange(pos, 0, null);
            java.util.List<OptimizedLogStorage.LogEntry> containerLogs = OptimizedLogStorage.getLogsInRange(pos, 0);
            java.util.List<OptimizedLogStorage.KillLogEntry> killLogs = OptimizedLogStorage.getKillLogsInRange(pos, 0, null);
            boolean found = false;
            StringBuilder message = new StringBuilder(
                    "┬º6[Inspector] Block at " + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ":");
            for (OptimizedLogStorage.BlockLogEntry entry : blockLogs) {
                message.append("\n┬º7").append(entry.action).append(" by ").append(entry.playerName).append(" - ")
                        .append(entry.blockId);
                found = true;
            }
            for (OptimizedLogStorage.SignLogEntry entry : signLogs) {
                message.append("\n┬º7Sign ").append(entry.action).append(" by ").append(entry.playerName);
                found = true;
            }
            for (OptimizedLogStorage.LogEntry entry : containerLogs) {
                message.append("\n┬º7Container ").append(entry.action).append(" by ").append(entry.playerName);
                found = true;
            }
            for (OptimizedLogStorage.KillLogEntry entry : killLogs) {
                if (entry.pos.equals(pos)) {
                    message.append("\n┬º7Kill: ").append(entry.killerName).append(" -> ").append(entry.victimName);
                    found = true;
                }
            }
            if (!found) {
                message.append("\n┬º8No logged activity found.");
            }
            return message.toString();
        }, OptimizedLogStorage.getAsyncExecutor()).thenAccept(message -> {
            player.sendMessage(net.minecraft.text.Text.literal(message), false);
        });
    }
    @org.spongepowered.asm.mixin.Unique
    private void minetracer$inspectorModeBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        ServerPlayerEntity player = ((ServerPlayerInteractionManagerAccessor) this).getPlayer();
        CompletableFuture.supplyAsync(() -> {
            java.util.List<OptimizedLogStorage.BlockLogEntry> blockLogs = OptimizedLogStorage.getBlockLogsInRange(pos, 0, null);
            java.util.List<OptimizedLogStorage.SignLogEntry> signLogs = OptimizedLogStorage.getSignLogsInRange(pos, 0, null);
            java.util.List<OptimizedLogStorage.LogEntry> containerLogs = OptimizedLogStorage.getLogsInRange(pos, 0);
            java.util.List<OptimizedLogStorage.KillLogEntry> killLogs = OptimizedLogStorage.getKillLogsInRange(pos, 0, null);
            boolean found = false;
            StringBuilder message = new StringBuilder(
                    "┬º6[Inspector] Block at " + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ":");
            for (OptimizedLogStorage.BlockLogEntry entry : blockLogs) {
                message.append("\n┬º7").append(entry.action).append(" by ").append(entry.playerName).append(" - ")
                        .append(entry.blockId);
                found = true;
            }
            for (OptimizedLogStorage.SignLogEntry entry : signLogs) {
                message.append("\n┬º7Sign ").append(entry.action).append(" by ").append(entry.playerName);
                found = true;
            }
            for (OptimizedLogStorage.LogEntry entry : containerLogs) {
                message.append("\n┬º7Container ").append(entry.action).append(" by ").append(entry.playerName);
                found = true;
            }
            for (OptimizedLogStorage.KillLogEntry entry : killLogs) {
                if (entry.pos.equals(pos)) {
                    message.append("\n┬º7Kill: ").append(entry.killerName).append(" -> ").append(entry.victimName);
                    found = true;
                }
            }
            if (!found) {
                message.append("\n┬º8No logged activity found.");
            }
            return message.toString();
        }, OptimizedLogStorage.getAsyncExecutor()).thenAccept(message -> {
            player.sendMessage(net.minecraft.text.Text.literal(message), false);
        });
    }
}
