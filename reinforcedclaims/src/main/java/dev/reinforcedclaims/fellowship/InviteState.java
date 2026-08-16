package dev.reinforcedclaims.fellowship;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Uuids;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Server-wide store of unanswered invites, keyed by the invited player.
public class InviteState extends PersistentState {

    public final Map<UUID, List<FellowshipInvite>> pending = new HashMap<>();

    public InviteState() {
    }

    private record Entry(UUID player, List<FellowshipInvite> invites) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Uuids.CODEC.fieldOf("player").forGetter(Entry::player),
                FellowshipInvite.CODEC.listOf().fieldOf("invites").forGetter(Entry::invites)
        ).apply(instance, Entry::new));
    }

    public static final Codec<InviteState> CODEC = Entry.CODEC.listOf().xmap(
            entries -> {
                InviteState state = new InviteState();
                for (Entry entry : entries) {
                    if (!entry.invites().isEmpty()) {
                        state.pending.put(entry.player(), new ArrayList<>(entry.invites()));
                    }
                }
                return state;
            },
            state -> state.pending.entrySet().stream()
                    .filter(e -> !e.getValue().isEmpty())
                    .map(e -> new Entry(e.getKey(), List.copyOf(e.getValue())))
                    .toList()
    );

    public static final PersistentStateType<InviteState> TYPE = new PersistentStateType<>(
            "reinforcedclaims_invites",
            InviteState::new,
            CODEC,
            null
    );

    // The invites awaiting an answer from this player, oldest first.
    public List<FellowshipInvite> invitesFor(UUID player) {
        return List.copyOf(pending.getOrDefault(player, List.of()));
    }

    // An unanswered invite and the player it's waiting on.
    public record Outgoing(UUID player, FellowshipInvite invite) {
    }

    // Every unanswered invite to these fellowships, oldest first.
    public List<Outgoing> outgoing(Set<String> fellowshipKeys) {
        List<Outgoing> out = new ArrayList<>();
        for (Map.Entry<UUID, List<FellowshipInvite>> entry : pending.entrySet()) {
            for (FellowshipInvite invite : entry.getValue()) {
                if (fellowshipKeys.contains(invite.key())) {
                    out.add(new Outgoing(entry.getKey(), invite));
                }
            }
        }
        out.sort(Comparator.comparingLong(o -> o.invite().sent()));
        return out;
    }

    // Adds an invite, replacing any to the same fellowship; returns the replaced one.
    public FellowshipInvite put(UUID player, FellowshipInvite invite) {
        List<FellowshipInvite> list = pending.computeIfAbsent(player, k -> new ArrayList<>());
        FellowshipInvite replaced = null;
        for (Iterator<FellowshipInvite> it = list.iterator(); it.hasNext(); ) {
            FellowshipInvite existing = it.next();
            if (existing.key().equals(invite.key())) {
                replaced = existing;
                it.remove();
                break;
            }
        }
        // Appended: the list is oldest-first.
        list.add(invite);
        markDirty();
        return replaced;
    }

    // Removes and returns this player's invite to a fellowship.
    public FellowshipInvite remove(UUID player, String fellowshipKey) {
        List<FellowshipInvite> list = pending.get(player);
        if (list == null) {
            return null;
        }
        FellowshipInvite removed = null;
        for (Iterator<FellowshipInvite> it = list.iterator(); it.hasNext(); ) {
            FellowshipInvite invite = it.next();
            if (invite.key().equals(fellowshipKey)) {
                removed = invite;
                it.remove();
                break;
            }
        }
        if (removed != null) {
            if (list.isEmpty()) {
                pending.remove(player);
            }
            markDirty();
        }
        return removed;
    }

    // Drops every outstanding invite to a fellowship.
    public void removeFellowship(String fellowshipKey) {
        boolean dirty = false;
        for (Iterator<Map.Entry<UUID, List<FellowshipInvite>>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, List<FellowshipInvite>> entry = it.next();
            if (entry.getValue().removeIf(invite -> invite.key().equals(fellowshipKey))) {
                dirty = true;
            }
            if (entry.getValue().isEmpty()) {
                it.remove();
            }
        }
        if (dirty) {
            markDirty();
        }
    }
}
