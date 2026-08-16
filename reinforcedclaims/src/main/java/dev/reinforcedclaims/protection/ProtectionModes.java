package dev.reinforcedclaims.protection;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Per-player /clm bypass and /clm reinforce toggles. Not persisted.
public final class ProtectionModes {

    private static final Set<UUID> BYPASS = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> REINFORCE_MODE = ConcurrentHashMap.newKeySet();

    private ProtectionModes() {
    }

    public static boolean isBypassing(UUID player) {
        return BYPASS.contains(player);
    }

    public static boolean toggleBypass(UUID player) {
        return toggle(BYPASS, player);
    }

    public static boolean isReinforcing(UUID player) {
        return REINFORCE_MODE.contains(player);
    }

    public static boolean toggleReinforceMode(UUID player) {
        return toggle(REINFORCE_MODE, player);
    }

    // Flips membership; returns the state the player is left in.
    private static boolean toggle(Set<UUID> mode, UUID player) {
        return !mode.remove(player) && mode.add(player);
    }

    public static void clear(UUID player) {
        BYPASS.remove(player);
        REINFORCE_MODE.remove(player);
    }

    // Clears every player's modes, e.g. on server stop.
    public static void clear() {
        BYPASS.clear();
        REINFORCE_MODE.clear();
    }
}
