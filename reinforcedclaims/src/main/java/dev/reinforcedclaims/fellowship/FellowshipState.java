package dev.reinforcedclaims.fellowship;

import com.mojang.serialization.Codec;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

// Server-wide store of fellowships, keyed by id (names aren't unique).
// Mutate only through put/remove so the member index stays correct.
public class FellowshipState extends PersistentState {

    // Insertion-ordered so the save file doesn't reshuffle between writes.
    private final Map<String, Fellowship> fellowships = new LinkedHashMap<>();

    // Player -> the ids of every fellowship they're in. Not serialized.
    private final Map<UUID, Set<String>> byMember = new HashMap<>();

    public FellowshipState() {
    }

    // --- accessors ------------------------------------------------------------------------------

    public Fellowship get(String id) {
        return id == null ? null : fellowships.get(id);
    }

    // Read-only view.
    public Collection<Fellowship> all() {
        return Collections.unmodifiableCollection(fellowships.values());
    }

    // Every fellowship this player belongs to, via the index.
    public List<Fellowship> forMember(UUID player) {
        Set<String> ids = byMember.get(player);
        if (ids == null) {
            return List.of();
        }
        List<Fellowship> found = new ArrayList<>(ids.size());
        for (String id : ids) {
            Fellowship fellowship = fellowships.get(id);
            if (fellowship != null) {
                found.add(fellowship);
            }
        }
        return found;
    }

    // Fellowships with this display name. Op-gated callers only: a name lookup would
    // otherwise enumerate other players' fellowships.
    public List<Fellowship> named(String name) {
        List<Fellowship> found = new ArrayList<>(1);
        for (Fellowship fellowship : fellowships.values()) {
            if (fellowship.name().equalsIgnoreCase(name)) {
                found.add(fellowship);
            }
        }
        return found;
    }

    // An unused id for a new fellowship.
    public String freshId(String name) {
        String id;
        do {
            id = Fellowship.freshId(name);
        } while (fellowships.containsKey(id));
        return id;
    }

    // --- mutators -------------------------------------------------------------------------------

    // Adds or replaces a fellowship, reindexing its membership.
    public void put(Fellowship fellowship) {
        Fellowship old = fellowships.put(fellowship.id(), fellowship);
        if (old != null) {
            unindex(old);
        }
        index(fellowship);
    }

    public Fellowship remove(String id) {
        Fellowship old = fellowships.remove(id);
        if (old != null) {
            unindex(old);
        }
        return old;
    }

    // --- member index ---------------------------------------------------------------------------

    private void index(Fellowship fellowship) {
        forEachMember(fellowship, member ->
                byMember.computeIfAbsent(member, k -> new HashSet<>()).add(fellowship.id()));
    }

    private void unindex(Fellowship fellowship) {
        forEachMember(fellowship, member -> {
            Set<String> ids = byMember.get(member);
            if (ids != null && ids.remove(fellowship.id()) && ids.isEmpty()) {
                byMember.remove(member);
            }
        });
    }

    private static void forEachMember(Fellowship fellowship, Consumer<UUID> action) {
        action.accept(fellowship.owner());
        for (Fellowship.Member member : fellowship.members()) {
            action.accept(member.id());
        }
    }

    // --- persistence ----------------------------------------------------------------------------

    public static final Codec<FellowshipState> CODEC = Fellowship.CODEC.listOf().xmap(
            list -> {
                FellowshipState state = new FellowshipState();
                for (Fellowship fellowship : list) {
                    state.put(fellowship);
                }
                return state;
            },
            state -> List.copyOf(state.fellowships.values())
    );

    public static final PersistentStateType<FellowshipState> TYPE = new PersistentStateType<>(
            "reinforcedclaims_fellowships",
            FellowshipState::new,
            CODEC,
            null
    );
}
