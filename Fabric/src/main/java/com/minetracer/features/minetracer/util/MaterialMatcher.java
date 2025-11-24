package com.minetracer.features.minetracer.util;

import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Utility class for matching materials/items with include/exclude support
 * Supports comma-separated lists and exact matching like CoreProtect
 */
public class MaterialMatcher {
    
    /**
     * Check if an item/block ID matches the include filter
     * Supports comma-separated lists: "stone,dirt,diamond_ore"
     */
    public static boolean matchesIncludeFilter(String itemId, String includeFilter) {
        if (includeFilter == null || includeFilter.isEmpty()) {
            return true; // No filter means include all
        }
        
        // Support multiple filters separated by commas
        String[] filters = includeFilter.split(",");
        for (String filter : filters) {
            if (matchesSingleFilter(itemId, filter.trim())) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Check if an item/block ID matches the exclude filter
     * Returns true if item should be EXCLUDED
     * Supports comma-separated lists: "tnt,bedrock,netherite"
     */
    public static boolean matchesExcludeFilter(String itemId, String excludeFilter) {
        if (excludeFilter == null || excludeFilter.isEmpty()) {
            return false; // No filter means exclude nothing
        }
        
        // Support multiple filters separated by commas
        String[] filters = excludeFilter.split(",");
        for (String filter : filters) {
            if (matchesSingleFilter(itemId, filter.trim())) {
                return true; // Found a match, so exclude this item
            }
        }
        return false;
    }
    
    /**
     * Match a single filter against an item ID (exact matching only)
     */
    private static boolean matchesSingleFilter(String itemId, String filter) {
        if (filter == null || filter.isEmpty()) {
            return false;
        }
        
        String filterLower = filter.toLowerCase();
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