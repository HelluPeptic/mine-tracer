package com.minetracer.features.minetracer.inspector;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;

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
    private static ActionResult onBlockRightClick(net.minecraft.entity.player.PlayerEntity player, World world, Hand hand, BlockHitResult hitResult) {
        // Only process server-side players
        if (!(player instanceof ServerPlayerEntity)) {
            return ActionResult.PASS;
        }
        
        ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
        
        // Only process if player is in inspector mode
        if (!isInspectorMode(serverPlayer)) {
            return ActionResult.PASS; // Continue normal behavior
        }
        
        // Only process main hand to avoid double triggers
        if (hand != Hand.MAIN_HAND) {
            return ActionResult.PASS;
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
        
        return ActionResult.CONSUME; // Prevent normal block interaction
    }
    
    /**
     * Handle left-click on blocks (CoreProtect style)
     * Note: This needs to be implemented via mixin or AttackBlockCallback
     */
    public static void onBlockLeftClick(ServerPlayerEntity player, BlockPos pos) {
        if (!isInspectorMode(player)) {
            return;
        }
        
        // Left-click always shows block placement/break history
        blockInspector.performBlockLookup(player, pos);
    }
    
    /**
     * Check if player is in inspector mode
     */
    private static boolean isInspectorMode(ServerPlayerEntity player) {
        // Use your existing inspector mode system from OptimizedLogStorage
        return com.minetracer.features.minetracer.OptimizedLogStorage.isInspectorMode(player);
    }
    
    /**
     * Check if block entity is a container
     */
    private static boolean isContainer(BlockEntity blockEntity) {
        // Check common container types
        return blockEntity instanceof net.minecraft.block.entity.ChestBlockEntity ||
               blockEntity instanceof net.minecraft.block.entity.FurnaceBlockEntity ||
               blockEntity instanceof net.minecraft.block.entity.HopperBlockEntity ||
               blockEntity instanceof net.minecraft.block.entity.DropperBlockEntity ||
               blockEntity instanceof net.minecraft.block.entity.DispenserBlockEntity ||
               blockEntity instanceof net.minecraft.block.entity.ShulkerBoxBlockEntity ||
               blockEntity instanceof net.minecraft.block.entity.BarrelBlockEntity ||
               blockEntity instanceof net.minecraft.inventory.Inventory;
    }
}