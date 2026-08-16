package dev.waystones.teleport;

import dev.waystones.config.Config;
import dev.waystones.waystone.Waystone;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Teleports between waystones, gated on the config XP cost and cooldown.
public final class TeleportService {

    // Player -> the epoch second their cooldown ends. Never saved.
    private static final Map<UUID, Long> cooldownUntil = new ConcurrentHashMap<>();

    private TeleportService() {
    }

    public static void clear(UUID player) {
        cooldownUntil.remove(player);
    }

    public static void attempt(ServerPlayerEntity player, Waystone from, Waystone to) {
        int cooldownSeconds = Config.get().tpCooldownSeconds;
        long now = System.currentTimeMillis() / 1000L;
        if (cooldownSeconds > 0) {
            Long until = cooldownUntil.get(player.getUuid());
            if (until != null && now < until) {
                player.sendMessage(Text.literal("You must wait " + (until - now) + "s before teleporting again.")
                        .formatted(Formatting.RED), true);
                return;
            }
        }

        int cost = Config.get().xpCost;
        boolean chargeXp = cost > 0 && !player.isCreative();
        if (chargeXp && player.experienceLevel < cost) {
            player.sendMessage(Text.literal("You need level " + cost + " to teleport to " + to.name() + ".")
                    .formatted(Formatting.RED), true);
            return;
        }

        RegistryKey<World> dimension = RegistryKey.of(RegistryKeys.WORLD, to.dimension());
        ServerWorld targetWorld = player.getServer().getWorld(dimension);
        if (targetWorld == null) {
            player.sendMessage(Text.literal("That waystone's dimension isn't currently loaded.")
                    .formatted(Formatting.RED), true);
            return;
        }

        if (chargeXp) {
            player.addExperienceLevels(-cost);
        }
        Vec3d landing = landingPoint(to);
        player.teleport(targetWorld, landing.x, landing.y, landing.z, Set.of(), player.getYaw(), player.getPitch(), true);
        if (cooldownSeconds > 0) {
            cooldownUntil.put(player.getUuid(), now + cooldownSeconds);
        }
        targetWorld.playSound(null, landing.x, landing.y, landing.z, SoundEvents.BLOCK_PORTAL_TRAVEL,
                SoundCategory.PLAYERS, 0.5f, 1.2f);
    }

    // One block out from the waystone, in the direction it faces.
    private static Vec3d landingPoint(Waystone waystone) {
        BlockPos pos = waystone.pos();
        Direction facing = waystone.facing();
        return new Vec3d(pos.getX() + 0.5 + facing.getOffsetX(), pos.getY(), pos.getZ() + 0.5 + facing.getOffsetZ());
    }
}
