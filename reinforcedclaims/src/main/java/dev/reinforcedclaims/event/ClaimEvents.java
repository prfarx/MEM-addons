package dev.reinforcedclaims.event;

import dev.reinforcedclaims.claim.ClaimManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.server.world.ServerWorld;

// Snitch scan tick, claim teardown on break, orphan validation on chunk load/unload.
public final class ClaimEvents {

    private ClaimEvents() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(ClaimManager::tick);

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (world.isClient()) {
                return;
            }
            ClaimManager.onBlockBroken((ServerWorld) world, pos);
        });

        ServerChunkEvents.CHUNK_LOAD.register(ClaimManager::onChunkValidated);
        ServerChunkEvents.CHUNK_UNLOAD.register(ClaimManager::onChunkValidated);

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ClaimManager.clearTransient());
    }
}
