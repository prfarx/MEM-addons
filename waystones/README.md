# Waystones (Fabric 1.21.8)

Server-side placeable waystones with a config-defined teleport network.

## Commands

(op 4)
| Command | Effect |
| --- | --- |
| `/waystone give <skin> [player]` | get a waystone-placer item |
| `/waystone list` | list every placed waystone and its position |
| `/waystone remove <name>` | delete a waystone and its structure |

## Interactions

| Action | Effect |
| --- | --- |
| Right-click a block face with a placer | place a waystone |
| Shift-right-click a waystone | rename it |
| Right-click a named waystone | open travel menu |
| Break a waystone | remove it |

## Config (`config/waystones.json`)

| Key | Effect |
| --- | --- |
| `xpCost` | experience levels per teleport; `0` disables |
| `tpCooldownSeconds` | seconds between teleports; `0` disables |
| `waystoneConnections` | directed graph of `name -> [destinations]` |
| `skins` | per-skin `base`/`wall`/`slab` block ids, `runeText`, `textColor` |
