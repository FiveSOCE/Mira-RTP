# MiraRTP

MiraRTP is the safe asynchronous wilderness teleport system for the Mira Paper server suite. Players can run `/rtp` from a lobby, spawn world or gameplay world and be moved into validated unclaimed wilderness in the configured target world.

## Download

[**Download MiraRTP v0.1.1**](https://github.com/FiveSOCE/Mira-RTP/releases/download/v0.1.1/MiraRTP-0.1.1.jar)

[View All Releases](https://github.com/FiveSOCE/Mira-RTP/releases)

## Requirements / Dependencies

- Paper 1.21.11
- Java 21
- MiraCore 0.2.0 or newer
- MiraFactions 0.2.8 or newer
- Multiverse-Core optional when the configured target world is managed by Multiverse
- MiraCosmetics optional/recommended for centralized teleport effects

MiraRTP deliberately contains no combat-tag logic. CombatTag remains a separate future plugin.

## Search and Safety

A request picks random X/Z candidates in an area-uniform ring between the configured minimum and maximum radius. v0.1.1 resolves that ring around the world-border center by default instead of hardcoding world coordinate 0,0. The center can also be set to world spawn or custom coordinates.

Candidate chunks load asynchronously. The final landing position must have:

- solid ground
- passable feet/head space
- no liquid in the player space
- no configured hazardous material
- no dangerous tree-top landing when enabled
- an allowed world-border position
- no faction claim, SafeZone or WarZone under the default policy

The default hazard list includes bedrock, fire, soul fire, campfires, berry bushes, wither roses, pointed dripstone and portal blocks in addition to the existing water/lava/cactus/powder-snow/magma/leaves checks.

## Request Concurrency and Cooldowns

Only one RTP search may run for a player at a time. Repeated `/rtp` commands while an asynchronous search is already active are rejected, preventing competing successful teleports.

Cooldowns use MiraCore's shared `CooldownService` and start only after a successful teleport. `/rtp status` shows whether a search is active or how much cooldown remains.

## Cosmetics

MiraRTP does not create its own teleport particles. MiraCosmetics v0.1.1 listens to successful Paper player teleport events globally, so an RTP teleport automatically uses the player's configured TELEPORT cosmetic without duplicated visual logic.

## Commands

| Command | Permission | What it does |
| --- | --- | --- |
| `/rtp` | `mirartp.use` | Starts one safe wilderness search. |
| `/rtp status` | `mirartp.use` | Shows active-search/cooldown state. |
| `/rtp reload` | `mirartp.admin` | Reloads the RTP configuration and safety material set. |

## API / Events

`RtpApi` is registered through Bukkit ServicesManager and MiraCore. It exposes programmatic requests, active-search state and remaining cooldown.

A typed `RtpTeleportEvent` fires after a successful random teleport.

Successful teleports can be recorded in MiraCore audit history, including destination coordinates and the number of search attempts.

## Configuration

`config.yml` controls the target world, center/radius, attempt count, cooldown, hazard policy, Factions territory policy, world-border padding and successful-teleport auditing.

## Building

```bash
gradle clean build
```

The output JAR is created in `build/libs/`.
