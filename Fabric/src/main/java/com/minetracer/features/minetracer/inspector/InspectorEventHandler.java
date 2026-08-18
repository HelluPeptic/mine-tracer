package com.minetracer.features.minetracer.inspector;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Event handler for inspector interactions (CoreProtect style)
 * Handles left-click and right-click events when inspector mode is active
 */
public class InspectorEventHandler {
    
    private static final BlockInspector blockInspector = new BlockInspector();
    private static final ContainerInspector containerInspector = new ContainerInspector();
    private static final InteractionInspector interactionInspector = new InteractionInspector();
    
    /**
     * Initialize inspector event handlers
     */
    public static void init() {
        // Register right-click handler
        UseBlockCallback.EVENT.register(InspectorEventHandler::onBlockRightClick);
        
        // Note: Left-click handler requires different approach - will need to use AttackBlockCallback
        // or implement via mixin to override left-click behavior when inspector is active
    }
    
    /**
     * Handle right-click on blocks (CoreProtect style)
     */
    private static InteractionResult onBlockRightClick(net.minecraft.world.entity.player.Player player, Level world, InteractionHand hand, BlockHitResult hitResult) {
        // Only process server-side players
        if (!(player instanceof ServerPlayer)) {
            return InteractionResult.PASS;
        }
        
        ServerPlayer serverPlayer = (ServerPlayer) player;
        
        // Only process if player is in inspector mode
        if (!isInspectorMode(serverPlayer)) {
            return InteractionResult.PASS; // Continue normal behavior
        }
        
        // Only process main hand to avoid double triggers
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        
        BlockPos pos = hitResult.getBlockPos();
        
        // Check if block has a block entity (container)
        BlockEntity blockEntity = world.getBlockEntity(pos);
        
        if (blockEntity != null && isContainer(blockEntity)) {
            // Container right-click - show transaction history
            containerInspector.performContainerLookup(serverPlayer, pos);
        } else {
            // Regular block right-click - show general interaction history
            interactionInspector.performInteractionLookup(serverPlayer, pos);
        }
        
        return InteractionResult.CONSUME; // Prevent normal block interaction
    }
    
    /**
     * Handle left-click on blocks (CoreProtect style)
     * Note: This needs to be implemented via mixin or AttackBlockCallback
     */
    public static void onBlockLeftClick(ServerPlayer player, BlockPos pos) {
        if (!isInspectorMode(player)) {
            return;
        }
        
        // Left-click always shows block placement/break history
        blockInspector.performBlockLookup(player, pos);
    }
    
    /**
     * Check if player is in inspector mode
     */
    private static boolean isInspectorMode(ServerPlayer player) {
        // Use your existing inspector mode system from OptimizedLogStorage
        return com.minetracer.features.minetracer.OptimizedLogStorage.isInspectorMode(player);
    }
    
    /**
     * Check if block entity is a container
     */
    private static boolean isContainer(BlockEntity blockEntity) {
        // Check common container types
        return blockEntity instanceof net.minecraft.world.level.block.entity.ChestBlockEntity ||
               blockEntity instanceof net.minecraft.world.level.block.entity.FurnaceBlockEntity ||
               blockEntity instanceof net.minecraft.world.level.block.entity.HopperBlockEntity ||
               blockEntity instanceof net.minecraft.world.level.block.entity.DropperBlockEntity ||
               blockEntity instanceof net.minecraft.world.level.block.entity.DispenserBlockEntity ||
               blockEntity instanceof net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity ||
               blockEntity instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity ||
               blockEntity instanceof net.minecraft.world.Container;
    }
}