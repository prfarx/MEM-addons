package dev.reinforcedclaims.fellowship;

import dev.reinforcedclaims.ReinforcedClaims;
import dev.reinforcedclaims.compat.MiddleEarthFactions;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Set;
import java.util.UUID;

// Read-only view of each player's Middle-earth faction.
// Without that mod, isAvailable() is false and no check ever refuses.
public final class PlayerFactionData {

    private static final String MIDDLE_EARTH = "middle-earth";

    private static MinecraftServer server;
    private static boolean available;

    private PlayerFactionData() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(started -> {
            available = FabricLoader.getInstance().isModLoaded(MIDDLE_EARTH);
            server = available ? started : null;
            if (available) {
                ReinforcedClaims.LOGGER.info("Middle-earth player data found; factions restricted.");
            } else {
                ReinforcedClaims.LOGGER.info("Middle-earth player data not found; factions unrestricted.");
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> {
            server = null;
            available = false;
        });
    }

    // Whether Middle-earth is installed.
    public static boolean isAvailable() {
        return available;
    }

    // The faction id this player chose, or blank.
    public static String factionOf(UUID id) {
        return available ? MiddleEarthFactions.factionOf(server, id) : "";
    }

    // Every faction id picked on this server, for command suggestions.
    public static Set<String> knownFactions() {
        return available ? MiddleEarthFactions.knownFactions(server) : Set.of();
    }

    // Whether this player's faction lets them be in the fellowship.
    public static boolean qualifies(Fellowship fellowship, UUID id) {
        return !barred(fellowship, id);
    }

    // Why the fellowship bars this player, or null. Checked on send and again on accept.
    public static Text refusal(Fellowship fellowship, UUID id) {
        if (!barred(fellowship, id)) {
            return null;
        }
        return Text.literal(fellowship.name() + " restricted to "
                + fellowship.requiredFaction()).formatted(Formatting.RED);
    }

    // Whether the required faction shuts this player out.
    private static boolean barred(Fellowship fellowship, UUID id) {
        if (!available || fellowship.requiredFaction().isBlank()) {
            return false;
        }
        return !fellowship.requiredFaction().equalsIgnoreCase(factionOf(id));
    }
}
