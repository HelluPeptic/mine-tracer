package com.minetracer.features.minetracer.util;

import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Utility class for exact matching of materials/items
 * Only matches exact item names, not partial matches
 */
public class MaterialMatcher {
    
    /**
     * Check if an item/block ID matches the include filter (exact matching only)
     * Only matches exact item names, not partial matches
     */
    public static boolean matchesIncludeFilter(String itemId, String includeFilter) {
        if (includeFilter == null || includeFilter.isEmpty()) {
            return true; // No filter means include all
        }
        
        String filterLower = includeFilter.toLowerCase();
        String itemIdLower = itemId.toLowerCase();
        
        // 1. Exact match with full namespace (e.g., "minecraft:diamond")
        if (itemIdLower.equals(filterLower)) {
            return true;
        }
        
        // 2. Exact match without namespace (e.g., "diamond" matches "minecraft:diamond" only)
        if (itemIdLower.contains(":")) {
            String itemNameOnly = itemIdLower.substring(itemIdLower.indexOf(':') + 1);
            if (itemNameOnly.equals(filterLower)) {
                return true;
            }
        }
        
        // 3. If filter has namespace, only exact match
        if (filterLower.contains(":")) {
            return itemIdLower.equals(filterLower);
        } else {
            // If no namespace in filter, try matching it as "minecraft:filter"
            String minecraftPrefixed = "minecraft:" + filterLower;
            if (itemIdLower.equals(minecraftPrefixed)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Get suggestions for include filter based on partial input (like CoreProtect)
     */
    public static List<String> getSuggestions(String partialName) {
        String partialLower = partialName.toLowerCase();
        
        return Registries.ITEM.getIds()
                .stream()
                .map(Identifier::toString)
                .filter(id -> {
                    String idLower = id.toLowerCase();
                    // Match either full ID or just the name part after ":"
                    if (idLower.contains(partialLower)) {
                        return true;
                    }
                    if (idLower.contains(":")) {
                        String nameOnly = idLower.substring(idLower.indexOf(':') + 1);
                        return nameOnly.contains(partialLower);
                    }
                    return false;
                })
                .limit(10) // Limit suggestions like CoreProtect does
                .collect(Collectors.toList());
    }
    
    /**
     * Similar to getSuggestions but for block registries
     */
    public static List<String> getBlockSuggestions(String partialName) {
        String partialLower = partialName.toLowerCase();
        
        return Registries.BLOCK.getIds()
                .stream()
                .map(Identifier::toString)
                .filter(id -> {
                    String idLower = id.toLowerCase();
                    // Match either full ID or just the name part after ":"
                    if (idLower.contains(partialLower)) {
                        return true;
                    }
                    if (idLower.contains(":")) {
                        String nameOnly = idLower.substring(idLower.indexOf(':') + 1);
                        return nameOnly.contains(partialLower);
                    }
                    return false;
                })
                .limit(10) // Limit suggestions like CoreProtect does
                .collect(Collectors.toList());
    }
}