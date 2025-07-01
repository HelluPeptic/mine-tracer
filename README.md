# MineTracer

Advanced block and container logging mod with rollback functionality for Minecraft servers.

## Features

- **Block Logging**: Track block break/place actions with full NBT support
- **Container Logging**: Monitor chest, furnace, and other container interactions
- **Sign Editing**: Log sign text changes with before/after comparison
- **Kill Logging**: Record player vs player/mob combat events
- **Advanced Query System**: Filter logs by user, time, range, action type, and items
- **Rollback Functionality**: Undo player actions within specified parameters
- **Inspector Mode**: Real-time block history inspection
- **Permission-Based**: All commands require proper permissions

## Commands

- `/flowframe minetracer lookup <filters>` - Search through logs
- `/flowframe minetracer rollback <filters>` - Rollback player actions
- `/flowframe minetracer page <number>` - Navigate lookup results
- `/flowframe minetracer inspector` - Toggle inspector mode

## Filters

- `user:<player>` - Filter by player name
- `time:<duration>` - Filter by time (e.g., 1h, 30m, 2d)
- `range:<blocks>` - Filter by radius in blocks
- `action:<type>` - Filter by action type (broke, placed, withdrew, deposited, kill)
- `include:<item>` - Filter by specific item/block ID

## Permissions

- `flowframe.command.minetracer.lookup` - Access to lookup command
- `flowframe.command.minetracer.rollback` - Access to rollback command
- `flowframe.command.minetracer.inspector` - Access to inspector mode
- `flowframe.command.minetracer.page` - Access to pagination

## Installation

1. Download the latest JAR from releases
2. Place in your server's `mods` folder
3. Restart the server
4. Configure permissions as needed

## Building

```bash
./gradlew build
```

The built mod will be in `Fabric/build/libs/`

## Requirements

- Minecraft 1.20.1
- Fabric Loader 0.15.7+
- Fabric API 0.92.0+1.20.1
- fabric-permissions-api

## License

MIT License - see LICENSE file for details
