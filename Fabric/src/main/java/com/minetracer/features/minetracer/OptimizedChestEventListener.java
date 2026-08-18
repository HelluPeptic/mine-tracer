package com.minetracer.features.minetracer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
public class OptimizedChestEventListener {
    public static void register() {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            Block block = state.getBlock();
            if (isTrackedContainer(block) && blockEntity instanceof Inventory inv) {

                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    // Use canonical position for consistent double chest handling
                    BlockPos canonicalPos = ContainerPositionTracker.getContainerPosition(world, pos);
                    BlockPos entityPos = canonicalPos != null ? canonicalPos : blockEntity.getPos();

                    for (int i = 0; i < inv.size(); i++) {
                        ItemStack stack = inv.getStack(i);
                        if (!stack.isEmpty()) {
                            NewOptimizedLogStorage.logContainerAction("withdrew", player, entityPos, stack);
                        }
                    }
                });
            }
            return true;
        });
    }
    /**
     * Check if a block is a trackable container.
     * This method dynamically detects containers including modded ones like More Chest Variants.
     * 
     * @param block The block to check
     * @return true if this block should be tracked as a container
     */
    private static boolean isTrackedContainer(Block block) {
        // Fast path: Check vanilla containers first
        if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || 
            block == Blocks.BARREL || block == Blocks.ENDER_CHEST) {
            return true;
        }
        
        // Check shulker boxes
        if (block == Blocks.SHULKER_BOX || block == Blocks.WHITE_SHULKER_BOX || 
            block == Blocks.ORANGE_SHULKER_BOX || block == Blocks.MAGENTA_SHULKER_BOX || 
            block == Blocks.LIGHT_BLUE_SHULKER_BOX || block == Blocks.YELLOW_SHULKER_BOX || 
            block == Blocks.LIME_SHULKER_BOX || block == Blocks.PINK_SHULKER_BOX || 
            block == Blocks.GRAY_SHULKER_BOX || block == Blocks.LIGHT_GRAY_SHULKER_BOX || 
            block == Blocks.CYAN_SHULKER_BOX || block == Blocks.PURPLE_SHULKER_BOX || 
            block == Blocks.BLUE_SHULKER_BOX || block == Blocks.BROWN_SHULKER_BOX || 
            block == Blocks.GREEN_SHULKER_BOX || block == Blocks.RED_SHULKER_BOX || 
            block == Blocks.BLACK_SHULKER_BOX) {
            return true;
        }
        
        // Dynamic detection for modded containers
        try {
            String blockId = net.minecraft.registry.Registries.BLOCK.getId(block).toString();
            
            // More Chest Variants compatibility - check multiple possible mod IDs
            if ((blockId.startsWith("morechestVariants:") || 
                 blockId.startsWith("more-chest-variants:") ||
                 blockId.startsWith("mcv:")) && 
                (blockId.endsWith("_chest") || blockId.endsWith("_trapped_chest"))) {
                return true;
            }
            
            // Generic chest detection (for any mod)
            if (blockId.contains("chest") || blockId.contains("barrel") || 
                blockId.contains("storage") || blockId.contains("container")) {
                return true;
            }
            
            // Check if it's a ChestBlock (common base class for chest-like blocks)
            if (block instanceof net.minecraft.block.ChestBlock) {
                return true;
            }
            
            // Legacy detection using translation key
            String translationKey = block.getTranslationKey();
            return translationKey.contains("shulker_box") ||
                   translationKey.contains("backpack") ||
                   translationKey.contains("chest");
                   
        } catch (Exception e) {
            // Silently ignore errors in dynamic detection
            return false;
        }
    }
}
