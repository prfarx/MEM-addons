package dev.reinforcedclaims.fellowship;

import com.mojang.serialization.Codec;
import dev.reinforcedclaims.util.Enums;

import java.util.List;

// A member's rank, most privileged first, with the promotion and permission rules.
// ADMINISTRATOR exists only in a faction.
public enum Role {
    ADMINISTRATOR, OWNER, GUIDE, MEMBER, GUEST;

    private static final Role[] VALUES = values();
    private static final List<Role> FACTION_SCOPES = List.of(GUEST, MEMBER, GUIDE, OWNER, ADMINISTRATOR);
    private static final List<Role> FELLOWSHIP_SCOPES = List.of(GUEST, MEMBER, GUIDE, OWNER);

    // Lenient: an unreadable rank falls back to GUEST rather than failing the decode.
    public static final Codec<Role> CODEC = Enums.lenientCodec(VALUES, GUEST);

    // Whether this rank satisfies a grant reaching down to minimum.
    public boolean atLeast(Role minimum) {
        return ordinal() <= minimum.ordinal();
    }

    // The rank scopes a grant may take, widest first.
    public static List<Role> grantScopes(boolean faction) {
        return faction ? FACTION_SCOPES : FELLOWSHIP_SCOPES;
    }

    public boolean canInvite() {
        return this == ADMINISTRATOR || this == OWNER || this == GUIDE;
    }

    public boolean canTogglePvp() {
        return this != GUEST;
    }

    // In a faction only the Administrator may disband.
    public boolean canDisband(boolean faction) {
        return faction ? this == ADMINISTRATOR : this == OWNER;
    }

    // Also governs demotion; demoting a Guest is a kick.
    public boolean canKick(Role target) {
        return switch (this) {
            case ADMINISTRATOR, OWNER -> target.ordinal() > ordinal();
            case GUIDE -> target == MEMBER || target == GUEST;
            default -> false;
        };
    }

    // Whether this rank may promote target one step; ceiling is the highest rank it can reach.
    public boolean canPromote(Role target, boolean faction) {
        Role ceiling = switch (this) {
            case ADMINISTRATOR -> OWNER;
            case OWNER -> faction ? GUIDE : OWNER;
            case GUIDE -> MEMBER;
            default -> null;
        };
        if (ceiling == null || target == ADMINISTRATOR) {
            return false;
        }
        return promoted(target).ordinal() >= ceiling.ordinal();
    }

    // The role after one promotion step.
    public static Role promoted(Role target) {
        return VALUES[target.ordinal() - 1];
    }

    // The role after one demotion step, or null when it should be a kick.
    public static Role demoted(Role target) {
        return target == GUEST ? null : VALUES[target.ordinal() + 1];
    }

    // Display name; OWNER reads as "Leader" in a faction.
    public String label(boolean faction) {
        return switch (this) {
            case ADMINISTRATOR -> "Administrator";
            case OWNER -> faction ? "Leader" : "Owner";
            case GUIDE -> "Guide";
            case MEMBER -> "Member";
            case GUEST -> "Guest";
        };
    }
}
