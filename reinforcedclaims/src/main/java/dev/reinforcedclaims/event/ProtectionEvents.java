package dev.reinforcedclaims.event;

import dev.reinforcedclaims.protection.AccessLocks;
import dev.reinforcedclaims.protection.InteractionClassifier;
import dev.reinforcedclaims.protection.InteractionType;
import dev.reinforcedclaims.protection.ProtectionManager;
import dev.reinforcedclaims.protection.ProtectionModes;
import dev.reinforcedclaims.reinforcement.Reinforcement;
import dev.reinforcedclaims.reinforcement.ReinforcementState;
import dev.reinforcedclaims.claim.ClaimManager;
import dev.reinforcedclaims.util.ProtectionView;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

// Break/use permission checks, chunk-unload regeneration, disconnect cleanup, overlay tick.
public final class ProtectionEvents {

    private ProtectionEvents() {
    }

    public static void register() {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (world.isClient() || !(player instanceof ServerPlayerEntity serverPlayer)) {
                return true;
            }
            boolean allowed = ClaimManager.onBreakAttempt((ServerWorld) world, pos, serverPlayer);
            if (!allowed) {
                serverPlayer.playerScreenHandler.syncState();
            }
            return allowed;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient()) {
                return ActionResult.PASS;
            }
            ServerWorld serverWorld = (ServerWorld) world;
            BlockPos pos = hitResult.getBlockPos();
            // Reinforcing is main-hand only; the permission check below runs for both hands.
            if (hand == Hand.MAIN_HAND && player instanceof ServerPlayerEntity serverPlayer
                    && ProtectionModes.isReinforcing(serverPlayer.getUuid())) {
                ActionResult reinforced = ClaimManager.tryReinforceExisting(serverWorld, pos, serverPlayer);
                if (reinforced != null) {
                    return reinforced;
                }
            }
            InteractionType type = InteractionClassifier.classify(serverWorld, pos, serverWorld.getBlockState(pos));
            if (ClaimManager.canInteract(serverWorld, pos, player, type)) {
                return ActionResult.PASS;
            }
            player.playerScreenHandler.syncState();
            return ActionResult.FAIL;
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ClaimManager.onPlayerDisconnect(handler.getPlayer());
            ProtectionModes.clear(handler.getPlayer().getUuid());
            AccessLocks.clear(handler.getPlayer().getUuid());
            ProtectionView.stop(handler.getPlayer().getUuid());
        });

        ServerTickEvents.END_SERVER_TICK.register(ProtectionView::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ProtectionView.clear();
            AccessLocks.clear();
            ProtectionModes.clear();
        });

        ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            ReinforcementState store = ProtectionManager.reinforcements(world);
            if (store.isEmpty()) {
                return;
            }
            ChunkPos cp = chunk.getPos();
            LongSet positions = store.inChunk(cp.x, cp.z);
            if (positions.isEmpty()) {
                return;
            }
            boolean dirty = false;
            // Snapshot: the loop mutates the store and the index set is live.
            for (long pos : positions.toLongArray()) {
                Reinforcement r = store.get(pos);
                if (r == null) {
                    continue;
                }
                if (!r.isExplicit()) {
                    store.remove(pos);
                    dirty = true;
                } else if (r.health() < r.maxHealth()) {
                    store.put(pos, r.healed());
                    dirty = true;
                }
            }
            if (dirty) {
                store.markDirty();
            }
        });
    }
}
