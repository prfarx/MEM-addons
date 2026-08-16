# Reinforced Claims (Fabric 1.21.8)

Server-side block reinforcement, territory claims, and intrusion logging.

## `/fs` — fellowships (alias `/fellowship`)

| Command | Effect |
| --- | --- |
| `/fs list` | chest gui of your fellowships |

( op 2 )
| `/fs list all` | chest gui of all fellowships |

| `/fs invites` | open incoming invites list |
| `/fs uninvite` | open outgoing invites list |

( op 4 )
| `/fs faction <name> [<faction-id>]` | create a faction, or rebind/clear restrictions |
| `/fs bypass` | toggle bypass |

Roster buttons: Rename, Icon, Invite, Disband, Promote, Demote/Kick.

## `/clm` — blocks and claims (alias `/claim`)

| Command | Effect |
| --- | --- |
| `/clm reinforce` | toggle reinforce mode |
| `/clm assign` | add fellowship permissions |
| `/clm assign <player>` | add player specific permissions |
| `/clm manage` | open claim or reinforced block management chest gui |

( op 2 )
| `/clm view` | toggle protection overlay |
| `/clm validate [all]` | validate broken claims (worldedit etc) |

(op 4)
| `/clm bypass` | toggle bypass |

## Interactions

| Action | Effect |
| --- | --- |
| Place a block with a material in your off hand | reinforces the placed block |
| Right-click a block holding a material, off hand empty | reinforces that block |
| Place the claim block on a tier's resource block | creates a claim |
| Place a jukebox on a claim | links a snitch |
| Break the claim block or its resource block | destroys the claim |

## Permission categories

`PLACE_BREAK`, `CONTAINER`, `DOOR`, `REDSTONE`, `ENDERCHEST`, `OTHER_INTERACT`, `MODIFY_PERMISSIONS`.

## Roles

`Administrator` (factions only), `Owner`/`Leader`, `Guide`, `Member`, `Guest`.

## Config (`config/reinforcedclaims.json`)

| Key | Effect |
| --- | --- |
| `snitchPingIntervalTicks` | ticks between snitch scans |
| `claimBlock` | block that creates a claim |
| `defaultFellowshipIcon` | default menu icon for a fellowship |
| `claimTiers` | per-tier `size`, `block`, `defaultHealth` (`-1` = infinite) |
| `reinforcementMaterials` | item id -> HP (`-1` = infinite) |
| `multiBlocks` | named groups of block ids treated as one object |
| `multiBlockMaxParts` | cap on a multi-block group's size |
