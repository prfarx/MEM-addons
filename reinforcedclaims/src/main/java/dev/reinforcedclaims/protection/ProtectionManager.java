package dev.reinforcedclaims.protection;

import dev.reinforcedclaims.fellowship.FellowshipState;
import dev.reinforcedclaims.fellowship.InviteState;
import dev.reinforcedclaims.reinforcement.ReinforcementState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

// Accessors for the persistent stores. Access logic lives in ClaimManager.
public final class ProtectionManager {

    private ProtectionManager() {
    }

    // Per-dimension reinforcement store.
    public static ReinforcementState reinforcements(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(ReinforcementState.TYPE);
    }

    // Server-wide fellowship store, pinned to the overworld save.
    public static FellowshipState fellowships(MinecraftServer server) {
        ServerWorld overworld = server.getWorld(World.OVERWORLD);
        return overworld.getPersistentStateManager().getOrCreate(FellowshipState.TYPE);
    }

    // Server-wide store of unanswered invites, pinned to the overworld save.
    public static InviteState invites(MinecraftServer server) {
        ServerWorld overworld = server.getWorld(World.OVERWORLD);
        return overworld.getPersistentStateManager().getOrCreate(InviteState.TYPE);
    }
}
