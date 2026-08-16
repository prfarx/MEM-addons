package dev.reinforcedclaims.fellowship;

import dev.reinforcedclaims.claim.ClaimManager;
import dev.reinforcedclaims.protection.ProtectionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Evicts members and invites a Middle-earth faction change has disqualified.
// The Administrator is always exempt.
public final class FactionSync {

    private FactionSync() {
    }

    // A player's faction changed: drop them from gated factions and withdraw dead invites.
    public static void onFactionChanged(PlayerEntity player) {
        if (!(player instanceof ServerPlayerEntity serverPlayer)) {
            return;
        }
        MinecraftServer server = serverPlayer.getServer();
        if (server == null) {
            return;
        }
        UUID id = serverPlayer.getUuid();
        FellowshipState fellowships = ProtectionManager.fellowships(server);
        List<String> evicted = new ArrayList<>();
        for (Fellowship fellowship : fellowships.forMember(id)) {
            if (PlayerFactionData.qualifies(fellowship, id) || fellowship.roleOf(id) == Role.ADMINISTRATOR) {
                continue;
            }
            List<Fellowship.Member> members = new ArrayList<>(fellowship.members());
            if (!members.removeIf(m -> m.id().equals(id))) {
                continue;
            }
            fellowships.put(fellowship.withMembers(members));
            evicted.add(fellowship.id());
            tell(serverPlayer, "Left " + fellowship.name());
        }
        if (!evicted.isEmpty()) {
            fellowships.markDirty();
            ClaimManager.pruneStaleGrants(server, evicted);
        }
        revokeInvites(server, serverPlayer, fellowships);
    }

    // Withdraws invites their new faction shuts them out of.
    private static void revokeInvites(MinecraftServer server, ServerPlayerEntity player, FellowshipState fellowships) {
        InviteState invites = ProtectionManager.invites(server);
        UUID id = player.getUuid();
        for (FellowshipInvite invite : invites.invitesFor(id)) {
            Fellowship fellowship = fellowships.get(invite.key());
            if (fellowship == null || PlayerFactionData.qualifies(fellowship, id)) {
                continue;
            }
            invites.remove(id, invite.key());
            tell(player, "Invite to " + invite.fellowship() + " is no longer valid");
        }
    }

    // Drops every member the required faction disqualifies, after /fs faction repoints one.
    public static Fellowship evictMismatched(ServerPlayerEntity actor, Fellowship fellowship) {
        MinecraftServer server = actor.getServer();
        if (server == null || fellowship.requiredFaction().isBlank() || !PlayerFactionData.isAvailable()) {
            return fellowship;
        }
        List<Fellowship.Member> kept = new ArrayList<>();
        List<UUID> removed = new ArrayList<>();
        for (Fellowship.Member member : fellowship.members()) {
            if (PlayerFactionData.qualifies(fellowship, member.id())) {
                kept.add(member);
            } else {
                removed.add(member.id());
            }
        }
        if (removed.isEmpty()) {
            return fellowship;
        }
        FellowshipState fellowships = ProtectionManager.fellowships(server);
        Fellowship updated = fellowship.withMembers(kept);
        fellowships.put(updated);
        fellowships.markDirty();
        ClaimManager.pruneStaleGrants(server, fellowship.id());
        InviteState invites = ProtectionManager.invites(server);
        for (UUID id : removed) {
            invites.remove(id, fellowship.id());
            ServerPlayerEntity online = server.getPlayerManager().getPlayer(id);
            if (online != null) {
                tell(online, fellowship.name() + " membership invalidated");
            }
        }
        actor.sendMessage(Text.literal("Removed " + removed.size() + " member(s)"
                + " from " + fellowship.name())
                .formatted(Formatting.RED), false);
        return updated;
    }

    // Send to chat.
    private static void tell(ServerPlayerEntity player, String message) {
        player.sendMessage(Text.literal(message).formatted(Formatting.RED), false);
    }
}
