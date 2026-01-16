# PurgatoryRules

Manages the purgatory world flow: punishments, timed release, bossbar countdown, and world reset.

## What it does
- Teleports players to purgatory on “sins” (configurable triggers).
- Supports warnings broadcast by “Lord™”.
- Tracks a timed sentence (bossbar countdown + title/subtitle).
- Restores the purgatory world from a template after the last prisoner leaves.
- Optional portal redirect for a custom nether portal flow.

## Config (`plugins/PurgatoryRules/config.yml`)
### Core
- `purgatory.world`: purgatory world name.
- `purgatory.portal-redirect.enabled`: enable portal redirection.
- `purgatory.portal-redirect.target`: portal destination coordinates.

### Punishment
- `purgatory.punish.target`: teleport target in purgatory.
- `purgatory.punish.worlds`: worlds where punishment can trigger.
- `purgatory.punish.cooldown-ms`: cooldown between punishments.
- `purgatory.punish.release-after-seconds`: sentence length.
- `purgatory.punish.backup-command`: command to backup player (InventoryRollbackPlus).
- `purgatory.punish.clear-inventory`, `clear-xp`: whether to wipe inventory/xp.
- `purgatory.punish.release-message`: private message on release.
- `purgatory.punish.title.*`: title + timings.
- `purgatory.punish.bossbar.*`: bossbar settings.

### Reset
- `purgatory.reset.enabled`: enable reset behavior.
- `purgatory.reset.template-path`: source template directory.
- `purgatory.reset.restore-on-empty`: restore when last prisoner leaves.

### Sins (Triggers)
Each sin entry supports:
- `enabled`
- `type`: `pvp-hit` or `entity-hit`
- `worlds` / `entities`
- `hits-required`, `hits-window-seconds`
- `critical-only`
- `cooldown-ms`
- `warning-message`, `warning-always`
- `ce-events`: ConditionalEvents call events
- `hologram-commands`: commands (e.g., DecentHolograms updates)

### Variables usable in warning text
`{nickname}`, `{player}`, `{uuid}`, `{sin_id}`, `{sin_reason}`, `{sin_hits}`, `{sin_hits_required}`,
`{sin_critical}`, `{sin_world}`, `{sin_victim}`, `{sin_victim_type}`

## Commands
- `/purgatoryrules reload` (permission `purgatoryrules.reload`)

## Build
- Java 21 (class file version 65)
- Paper 1.21.x

## Related
- InventoryRollbackPlus: https://www.spigotmc.org/resources/inventory-rollback-plus-1-8-1-20-4.85811/
- ConditionalEvents: https://www.spigotmc.org/resources/conditional-events.82274/
- DecentHolograms: https://www.spigotmc.org/resources/decent-holograms.96927/
