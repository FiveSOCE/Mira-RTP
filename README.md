# MiraRTP

Cross-world random teleport for **Paper 1.21.11 / Java 21**, built for the Mira plugin suite.

## Purpose

Players can run `/rtp` from any world, including a dedicated Multiverse spawn world, and MiraRTP will always teleport them into the configured gameplay world.

Default target world: `factions`

## Features

- Cross-world `/rtp`
- Configurable target world
- Configurable minimum/maximum radius
- Async chunk loading and teleporting
- Safe surface detection
- Water, lava, cactus, powder snow, magma and leaf avoidance
- World border support
- Configurable cooldown
- Cooldown bypass permission
- Optional MiraFactions integration
- Avoids faction claims, SafeZone and WarZone by default
- `/rtp reload` admin command

## Commands

- `/rtp` - Randomly teleport to the configured gameplay world
- `/rtp reload` - Reload configuration

## Permissions

- `mirartp.use` - use `/rtp`
- `mirartp.bypass.cooldown` - bypass RTP cooldown
- `mirartp.admin` - reload configuration

## Requirements

- Paper 1.21.11
- Java 21
- Multiverse-Core optional but recommended for multi-world server layouts
- MiraFactions optional; when installed, wilderness protection checks are enabled

## Default flow

```text
Spawn world
   ↓
/rtp
   ↓
MiraRTP targets `factions`
   ↓
Loads candidate chunk
   ↓
Checks safe terrain + world border + MiraFactions territory
   ↓
Teleports player into unclaimed wilderness
```

## Build

```bash
gradle clean build
```

Output:

```text
build/libs/MiraRTP-0.1.0.jar
```
