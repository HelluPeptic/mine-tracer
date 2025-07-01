package com.flowframe.mixin;

import com.flowframe.features.minetracer.LogStorage;
import com.flowframe.features.minetracer.MineTracerCommand;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import com.flowframe.mixin.ServerPlayerInteractionManagerAccessor;
import com.google.gson.Gson;

@Mixin(ServerPlayerInteractionManager.class)
public class MixinServerPlayerInteractionManager {
    private static final Gson GSON = new Gson();

    @Inject(method = "interactBlock", at = @At("HEAD"))
    private void flowframe$cacheBlockPlaceState(ServerPlayerEntity player, net.minecraft.world.World world, net.minecraft.item.ItemStack stack, net.minecraft.util.Hand hand, net.minecraft.util.hit.BlockHitResult hitResult, CallbackInfoReturnable<net.minecraft.util.ActionResult> cir) {
        // Store the block state before interaction
        BlockPos placedPos = hitResult.getBlockPos().offset(hitResult.getSide());
        this.flowframe$prevPlacedState = world.getBlockState(placedPos);
    }

    @Inject(method = "interactBlock", at = @At("RETURN"))
    private void flowframe$logBlockPlace(ServerPlayerEntity player, net.minecraft.world.World world, net.minecraft.item.ItemStack stack, net.minecraft.util.Hand hand, net.minecraft.util.hit.BlockHitResult hitResult, CallbackInfoReturnable<net.minecraft.util.ActionResult> cir) {
        BlockPos placedPos = hitResult.getBlockPos().offset(hitResult.getSide());
        BlockState placedState = world.getBlockState(placedPos);
        BlockState prevState = this.flowframe$prevPlacedState;
        this.flowframe$prevPlacedState = null;
        // Only log if the block actually changed (was air or different block before, now not air and different)
        if (placedState.isAir() || (prevState != null && placedState.getBlock() == prevState.getBlock())) return;
        Identifier blockId = Registries.BLOCK.getId(placedState.getBlock());
        net.minecraft.block.entity.BlockEntity blockEntity = world.getBlockEntity(placedPos);
        
        // Create NBT compound that includes both block state properties and block entity data
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
    }
    @org.spongepowered.asm.mixin.Unique
    private BlockState flowframe$prevPlacedState = null;

    @Inject(method = "tryBreakBlock", at = @At("HEAD"))
    private void flowframe$logBlockBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        ServerPlayerEntity player = ((ServerPlayerInteractionManagerAccessor)this).getPlayer();
        net.minecraft.world.World world = player.getWorld();
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return;
        Identifier blockId = Registries.BLOCK.getId(state.getBlock());
        BlockEntity blockEntity = world.getBlockEntity(pos);
        
        // Create NBT compound that includes both block state properties and block entity data
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
    }

    @Inject(method = "interactBlock", at = @At("HEAD"), cancellable = true)
    private void flowframe$inspectorModeInteract(ServerPlayerEntity player, net.minecraft.world.World world, net.minecraft.item.ItemStack stack, net.minecraft.util.Hand hand, net.minecraft.util.hit.BlockHitResult hitResult, CallbackInfoReturnable<net.minecraft.util.ActionResult> cir) {
        if (LogStorage.isInspectorMode(player)) {
            BlockPos pos = hitResult.getBlockPos();
            java.util.List<LogStorage.BlockLogEntry> blockLogs = LogStorage.getBlockLogsInRange(pos, 0, null);
            java.util.List<LogStorage.SignLogEntry> signLogs = LogStorage.getSignLogsInRange(pos, 0, null);
            java.util.List<LogStorage.LogEntry> containerLogs = LogStorage.getLogsInRange(pos, 0);
            java.util.List<LogStorage.KillLogEntry> killLogs = LogStorage.getKillLogsInRange(pos, 0, null);
            boolean found = false;
            for (Object entry : blockLogs) {
                player.sendMessage(MineTracerCommand.formatLogEntryForChat(entry), false);
                found = true;
            }
            for (Object entry : signLogs) {
                player.sendMessage(MineTracerCommand.formatLogEntryForChat(entry), false);
                found = true;
            }
            for (Object entry : containerLogs) {
                player.sendMessage(MineTracerCommand.formatLogEntryForChat(entry), false);
                found = true;
            }
            for (Object entry : killLogs) {
                if (((LogStorage.KillLogEntry)entry).pos.equals(pos)) {
                    player.sendMessage(MineTracerCommand.formatLogEntryForChat(entry), false);
                    found = true;
                }
            }
            if (!found) {
                player.sendMessage(net.minecraft.text.Text.literal("[MineTracer] No logs found for this block."), false);
            }
            cir.setReturnValue(net.minecraft.util.ActionResult.SUCCESS);
        }
    }

    @Inject(method = "tryBreakBlock", at = @At("HEAD"), cancellable = true)
    private void flowframe$inspectorModeBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        ServerPlayerEntity player = ((ServerPlayerInteractionManagerAccessor)this).getPlayer();
        if (LogStorage.isInspectorMode(player)) {
            java.util.List<LogStorage.BlockLogEntry> blockLogs = LogStorage.getBlockLogsInRange(pos, 0, null);
            java.util.List<LogStorage.SignLogEntry> signLogs = LogStorage.getSignLogsInRange(pos, 0, null);
            java.util.List<LogStorage.LogEntry> containerLogs = LogStorage.getLogsInRange(pos, 0);
            java.util.List<LogStorage.KillLogEntry> killLogs = LogStorage.getKillLogsInRange(pos, 0, null);
            boolean found = false;
            for (Object entry : blockLogs) {
                player.sendMessage(MineTracerCommand.formatLogEntryForChat(entry), false);
                found = true;
            }
            for (Object entry : signLogs) {
                player.sendMessage(MineTracerCommand.formatLogEntryForChat(entry), false);
                found = true;
            }
            for (Object entry : containerLogs) {
                player.sendMessage(MineTracerCommand.formatLogEntryForChat(entry), false);
                found = true;
            }
            for (Object entry : killLogs) {
                if (((LogStorage.KillLogEntry)entry).pos.equals(pos)) {
                    player.sendMessage(MineTracerCommand.formatLogEntryForChat(entry), false);
                    found = true;
                }
            }
            if (!found) {
                player.sendMessage(net.minecraft.text.Text.literal("[MineTracer] No logs found for this block."), false);
            }
            cir.setReturnValue(false);
        }
    }
}
