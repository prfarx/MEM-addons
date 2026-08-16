# Reinforced Claims (Fabric 1.21.8)

Server-side block reinforcement, territory claims, and intrusion logging.

## `/fs` — fellowships (alias `/fellowship`)

| Command | Effect |
| --- | --- |
| `/fs list` | chest menu of your fellowships: create, roster, PvP, leave |

( op 2 )
| `/fs list all` | the same for every fellowship |

| `/fs invites` | accept or decline invites you've been sent |
| `/fs uninvite` | withdraw invites you've sent |

( op 4 )
| `/fs faction <name> [<faction-id>]` | create a faction, or rebind/clear its Middle-earth mod faction restriction |
| `/fs bypass` | toggle bypass |

Roster buttons: Rename, Icon, Invite, Disband, Promote, Demote/Kick.

## `/clm` — blocks and claims (alias `/claim`)

| Command | Effect |
| --- | --- |
| `/clm reinforce` | toggle reinforce mode |
| `/clm assign` | add a fellowship to the block/claim you're looking at |
| `/clm assign <player>` | add a player to it |
| `/clm manage` | toggle permission categories per grantee; Name and Snitch Log buttons |

( op 2 )
| `/clm view` | toggle the protection overlay |
| `/clm validate [all]` | drop claim records whose blocks are gone |

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
