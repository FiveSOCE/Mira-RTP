# MiraRTP

MiraRTP is the cross-world random teleport system for the Mira Paper server suite. Players can run `/rtp` from a spawn/lobby or any other world and be safely teleported into a configured gameplay world such as the Multiverse-managed `factions` world.

## Download

[**Download MiraRTP v0.1.0**](https://github.com/FiveSOCE/Mira-RTP/releases/download/v0.1.0/MiraRTP-0.1.0.jar)

## Requirements / Dependencies

- Paper 1.21.11
- Java 21
- Multiverse-Core optional but recommended for multi-world server layouts
- MiraFactions optional; when installed, RTP can reject faction claims, SafeZone and WarZone territory

## How MiraRTP Works

When a player runs `/rtp`, MiraRTP resolves the configured target world, applies the configured cooldown and searches for a random X/Z position between the minimum and maximum radius. Candidate chunks are loaded asynchronously. The plugin finds a safe surface, rejects dangerous landing blocks such as water, lava, cactus, powder snow, magma and leaves according to configuration, respects the world border and retries until a valid location is found or the maximum attempt count is reached.

With MiraFactions installed, MiraRTP queries its public territory API before teleporting. By default it refuses faction-owned chunks, SafeZone and WarZone so players land in unclaimed wilderness. The final teleport is performed asynchronously and the cooldown is only applied after a successful teleport.

The default target world is `factions`, making the intended flow: player starts in the spawn world, runs `/rtp`, and is transported into safe wilderness in the factions gameplay world.

## Commands

| Command | Permission | What it does |
| --- | --- | --- |
| `/rtp` | `mirartp.use` | Searches for a safe random location and teleports the player into the configured gameplay world. |
| `/rtp reload` | `mirartp.admin` | Reloads MiraRTP configuration and refreshes the MiraFactions bridge. |

## Permissions

| Permission | Default | What it does |
| --- | --- | --- |
| `mirartp.use` | Everyone | Allows use of `/rtp`. |
| `mirartp.bypass.cooldown` | OP | Bypasses the configured RTP cooldown. |
| `mirartp.admin` | OP | Allows `/rtp reload`. |
