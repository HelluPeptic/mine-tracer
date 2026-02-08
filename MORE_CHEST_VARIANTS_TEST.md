# Testing MineTracer with More Chest Variants Mod

## Prerequisites
- MineTracer v1.10.27 or higher
- More Chest Variants mod installed
- Server with both mods loaded

## Quick Test Steps

### 1. Place Modded Chests
```
/give @s morechestVariants:spruce_chest 1
/give @s morechestVariants:crimson_trapped_chest 1
```

### 2. Test Container Interactions
1. Place the spruce chest
2. Right-click to open it
3. Put some items in (e.g., 64 cobblestone)
4. Close the chest
5. Open it again and take items out

### 3. Verify Logging
```
/minetracer inspect
```
You should see logs like:
```
[PlayerName] deposited 64x Cobblestone into container at (x, y, z)
[PlayerName] withdrew 64x Cobblestone from container at (x, y, z)
```

### 4. Test Rollback (Optional)
```
/minetracer rollback radius:5 time:10m action:container
```

## Debug Information

If containers aren't being detected, check the server console for:
```
[MineTracer] Detected More Chest Variants container: morechestVariants:spruce_chest at [x, y, z]
```

## Supported Containers

### Regular Chests
- `morechestVariants:oak_chest`
- `morechestVariants:spruce_chest`
- `morechestVariants:birch_chest`
- `morechestVariants:jungle_chest`
- `morechestVariants:acacia_chest`
- `morechestVariants:dark_oak_chest`
- `morechestVariants:mangrove_chest`
- `morechestVariants:cherry_chest`
- `morechestVariants:pale_oak_chest`
- `morechestVariants:bamboo_chest`
- `morechestVariants:crimson_chest`
- `morechestVariants:warped_chest`

### Trapped Chests
- All regular chests with `_trapped_chest` suffix
- Example: `morechestVariants:spruce_trapped_chest`

## Troubleshooting

### Container not detected?
1. Check if the mod ID might be different (debug logs will show the actual ID)
2. Verify the container implements Inventory or extends ChestBlock
3. Check for error messages in server console

### Items not logging?
1. Ensure the container position was properly tracked when opened
2. Verify MineTracer configuration has `container-transactions: true`
3. Check database connectivity

### Test passed ✅
If you see proper logging for More Chest Variants containers, the integration is working correctly!