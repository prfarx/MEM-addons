package dev.reinforcedclaims.protection;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.reinforcedclaims.ReinforcedClaims;
import dev.reinforcedclaims.fellowship.Role;
import dev.reinforcedclaims.util.Enums;
import net.minecraft.util.Uuids;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// Per-grantee category access for reinforcements and claims, plus the grant-list mutators.
public final class AccessGrant {

    private AccessGrant() {
    }

    private static final InteractionType[] TYPES = InteractionType.values();

    // Lenient: an unknown category is dropped rather than failing the store's decode.
    private static final Codec<Set<InteractionType>> ALLOWED_CODEC = Codec.STRING.listOf().xmap(
            AccessGrant::parseCategories, AccessGrant::categoryNames);

    private static EnumSet<InteractionType> parseCategories(List<String> names) {
        EnumSet<InteractionType> allowed = EnumSet.noneOf(InteractionType.class);
        for (String name : names) {
            InteractionType type = Enums.byName(TYPES, name, null);
            if (type != null) {
                allowed.add(type);
            } else {
                ReinforcedClaims.LOGGER.warn("Dropping unknown interaction category '{}' from a saved grant", name);
            }
        }
        return allowed;
    }

    private static List<String> categoryNames(Set<InteractionType> allowed) {
        List<String> names = new ArrayList<>(allowed.size());
        for (InteractionType type : allowed) {
            names.add(type.name());
        }
        return names;
    }


    // Access for one fellowship, narrowed to minRole and above (GUEST = every member).
    public record FellowshipGrant(String fellowship, Role minRole, Set<InteractionType> allowed) {
        public static final Codec<FellowshipGrant> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("fellowship").forGetter(FellowshipGrant::fellowship),
                Role.CODEC.optionalFieldOf("minRole", Role.GUEST).forGetter(FellowshipGrant::minRole),
                ALLOWED_CODEC.optionalFieldOf("allowed", Set.of()).forGetter(FellowshipGrant::allowed)
        ).apply(instance, FellowshipGrant::new));

        public FellowshipGrant withAllowed(Set<InteractionType> allowed) {
            return new FellowshipGrant(fellowship, minRole, allowed);
        }

        // Same fellowship at the same rank scope.
        public boolean sameScope(String fellowshipId, Role scope) {
            return fellowship.equals(fellowshipId) && minRole == scope;
        }

        // Whether a member holding this role is reached by the grant.
        public boolean covers(Role held) {
            return held != null && held.atLeast(minRole);
        }

        public String scopeLabel(String displayName, boolean faction) {
            return AccessGrant.scopeLabel(displayName, minRole, faction);
        }
    }

    // How a rank scope reads in the menus, e.g. "Knights: Guide+".
    public static String scopeLabel(String displayName, Role minRole, boolean faction) {
        return displayName + ": " + minRole.label(faction) + "+";
    }

    public record PlayerGrant(UUID player, Set<InteractionType> allowed) {
        public static final Codec<PlayerGrant> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Uuids.CODEC.fieldOf("player").forGetter(PlayerGrant::player),
                ALLOWED_CODEC.optionalFieldOf("allowed", Set.of()).forGetter(PlayerGrant::allowed)
        ).apply(instance, PlayerGrant::new));

        public PlayerGrant withAllowed(Set<InteractionType> allowed) {
            return new PlayerGrant(player, allowed);
        }
    }

    private static EnumSet<InteractionType> mutableCopy(Collection<InteractionType> allowed) {
        return allowed.isEmpty() ? EnumSet.noneOf(InteractionType.class) : EnumSet.copyOf(allowed);
    }

    private static EnumSet<InteractionType> toggled(Set<InteractionType> allowed, InteractionType type) {
        EnumSet<InteractionType> copy = mutableCopy(allowed);
        if (copy.remove(type)) {
            return copy;
        }
        // MODIFY_PERMISSIONS implies the rest, so granting it turns them all on.
        if (type == InteractionType.MODIFY_PERMISSIONS) {
            return EnumSet.allOf(InteractionType.class);
        }
        copy.add(type);
        return copy;
    }

    // --- fellowship grant list mutators ----------------------------------------------------------

    // Adds a fellowship grantee at this rank scope with no categories; no-op if already listed.
    public static List<FellowshipGrant> addFellowship(List<FellowshipGrant> grants, String fellowship,
                                                      Role minRole) {
        for (FellowshipGrant g : grants) {
            if (g.sameScope(fellowship, minRole)) {
                return grants;
            }
        }
        List<FellowshipGrant> copy = new ArrayList<>(grants);
        copy.add(new FellowshipGrant(fellowship, minRole, EnumSet.noneOf(InteractionType.class)));
        return copy;
    }

    // Removes one scope, or every grant naming the fellowship when minRole is null.
    public static List<FellowshipGrant> removeFellowship(List<FellowshipGrant> grants, String fellowship,
                                                         Role minRole) {
        List<FellowshipGrant> copy = new ArrayList<>(grants);
        copy.removeIf(g -> minRole == null
                ? g.fellowship().equals(fellowship)
                : g.sameScope(fellowship, minRole));
        return copy;
    }

    // Moves a grant to another rank scope, keeping its categories.
    public static List<FellowshipGrant> rescopeFellowship(List<FellowshipGrant> grants, String fellowship,
                                                          Role from, Role to) {
        for (FellowshipGrant g : grants) {
            if (g.sameScope(fellowship, to)) {
                return grants;
            }
        }
        List<FellowshipGrant> copy = new ArrayList<>(grants.size());
        for (FellowshipGrant g : grants) {
            copy.add(g.sameScope(fellowship, from)
                    ? new FellowshipGrant(fellowship, to, g.allowed())
                    : g);
        }
        return copy;
    }

    public static List<FellowshipGrant> toggleFellowshipCategory(List<FellowshipGrant> grants, String fellowship,
                                                                 Role minRole, InteractionType type) {
        List<FellowshipGrant> copy = new ArrayList<>(grants.size());
        for (FellowshipGrant g : grants) {
            copy.add(g.sameScope(fellowship, minRole) ? g.withAllowed(toggled(g.allowed(), type)) : g);
        }
        return copy;
    }

    // --- player grant list mutators ---------------------------------------------------------------

    // Adds a player grantee with no categories; no-op if already present.
    public static List<PlayerGrant> addPlayer(List<PlayerGrant> grants, UUID id) {
        for (PlayerGrant g : grants) {
            if (g.player().equals(id)) {
                return grants;
            }
        }
        List<PlayerGrant> copy = new ArrayList<>(grants);
        copy.add(new PlayerGrant(id, EnumSet.noneOf(InteractionType.class)));
        return copy;
    }

    // Adds a player grantee with every category, replacing any existing entry.
    public static List<PlayerGrant> addPlayerWithFullAccess(List<PlayerGrant> grants, UUID id) {
        List<PlayerGrant> copy = new ArrayList<>(grants.size() + 1);
        for (PlayerGrant g : grants) {
            if (!g.player().equals(id)) {
                copy.add(g);
            }
        }
        copy.add(new PlayerGrant(id, EnumSet.allOf(InteractionType.class)));
        return copy;
    }

    public static List<PlayerGrant> removePlayer(List<PlayerGrant> grants, UUID id) {
        List<PlayerGrant> copy = new ArrayList<>(grants);
        copy.removeIf(g -> g.player().equals(id));
        return copy;
    }

    public static List<PlayerGrant> togglePlayerCategory(List<PlayerGrant> grants, UUID id, InteractionType type) {
        List<PlayerGrant> copy = new ArrayList<>(grants.size());
        for (PlayerGrant g : grants) {
            copy.add(g.player().equals(id) ? g.withAllowed(toggled(g.allowed(), type)) : g);
        }
        return copy;
    }
}
