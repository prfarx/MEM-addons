package dev.reinforcedclaims.compat;

import dev.reinforcedclaims.mixin.middleearth.StateSaverAndLoaderAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.sevenstars.middleearth.resources.StateSaverAndLoader;
import net.sevenstars.middleearth.resources.persistent_datas.PlayerData;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

// Live faction reads out of Middle-earth's own state.
// Compile-only dependency: callers must check PlayerFactionData.isAvailable() first.
public final class MiddleEarthFactions {

    private MiddleEarthFactions() {
    }

    // Every player Middle-earth holds data for.
    private static Map<UUID, PlayerData> players(MinecraftServer server) {
        StateSaverAndLoader state = StateSaverAndLoader.getServerState(server);
        return ((StateSaverAndLoaderAccessor) state).reinforcedclaims$players();
    }

    // The faction id this player picked, or blank.
    public static String factionOf(MinecraftServer server, UUID id) {
        PlayerData data = players(server).get(id);
        Identifier faction = data == null ? null : data.getFaction();
        return faction == null ? "" : faction.toString();
    }

    // Every faction id picked on this server, for command suggestions.
    public static Set<String> knownFactions(MinecraftServer server) {
        Set<String> found = new TreeSet<>();
        for (PlayerData data : players(server).values()) {
            Identifier faction = data == null ? null : data.getFaction();
            if (faction != null) {
                found.add(faction.toString());
            }
        }
        return found;
    }
}
