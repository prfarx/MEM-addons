package dev.reinforcedclaims.event;

import dev.reinforcedclaims.fellowship.Fellowship;
import dev.reinforcedclaims.protection.ProtectionManager;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

// Cancels combat damage between members of a fellowship with PvP off.
public final class PvpEvents {

    private PvpEvents() {
    }

    public static void register() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayerEntity victim)) {
                return true;
            }
            Entity attackerEntity = source.getAttacker();
            if (!(attackerEntity instanceof ServerPlayerEntity attacker) || attacker == victim) {
                return true;
            }
            MinecraftServer server = victim.getServer();
            if (server == null) {
                return true;
            }
            for (Fellowship fellowship : ProtectionManager.fellowships(server).forMember(attacker.getUuid())) {
                if (fellowship.pvpOff() && fellowship.isMember(victim.getUuid())) {
                    return false;
                }
            }
            return true;
        });
    }
}
