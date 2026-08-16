package dev.reinforcedclaims.util;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.UUID;

// Name and profile lookups for players, online or not.
public final class Players {

    private Players() {
    }

    // The single wording for a name the server has never seen.
    public static Text unknownPlayer(String name) {
        return Text.literal("No player named '" + name + "' has been on this server")
                .formatted(Formatting.RED);
    }

    // A named player's profile from the online list or the user cache; null if unknown.
    public static GameProfile profile(MinecraftServer server, String name) {
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(name);
        if (online != null) {
            return online.getGameProfile();
        }
        return server.getUserCache() == null ? null : server.getUserCache().findByName(name).orElse(null);
    }

    // A player's name from the online list or the user cache; null if unknown.
    public static String name(MinecraftServer server, UUID id) {
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(id);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        return server.getUserCache() == null ? null
                : server.getUserCache().getByUuid(id).map(GameProfile::getName).orElse(null);
    }

    // Sends text unless null or blank; some refusals carry no message.
    public static void sendIfPresent(ServerPlayerEntity player, Text text, boolean actionBar) {
        if (text != null && !text.getString().isEmpty()) {
            player.sendMessage(text, actionBar);
        }
    }
}
