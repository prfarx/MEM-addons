package dev.reinforcedclaims.fellowship;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.reinforcedclaims.config.Config;
import net.minecraft.item.Item;
import net.minecraft.util.Uuids;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

// A fellowship of players sharing access. id is the stable key; name is not unique.
// A faction has an Administrator above Owner (labelled "Leader") and may require a
// Middle-earth faction id to join.
public record Fellowship(String id, String name, UUID owner, List<Member> members, String icon, boolean pvpOff,
                         boolean faction, String requiredFaction) {

    // Suffix alphabet, free of easily-confused glyphs.
    private static final String ID_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";
    private static final int ID_SUFFIX_LENGTH = 6;

    // A fresh id: the lowercased name plus a random suffix.
    public static String freshId(String name) {
        StringBuilder id = new StringBuilder(name.toLowerCase(Locale.ROOT)).append('#');
        for (int i = 0; i < ID_SUFFIX_LENGTH; i++) {
            id.append(ID_ALPHABET.charAt(ThreadLocalRandom.current().nextInt(ID_ALPHABET.length())));
        }
        return id.toString();
    }

    // A non-owner member and their role.
    public record Member(UUID id, Role role) {
        public static final Codec<Member> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Uuids.CODEC.fieldOf("id").forGetter(Member::id),
                Role.CODEC.fieldOf("role").forGetter(Member::role)
        ).apply(instance, Member::new));
    }

    public static final Codec<Fellowship> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(Fellowship::id),
            Codec.STRING.fieldOf("name").forGetter(Fellowship::name),
            Uuids.CODEC.fieldOf("owner").forGetter(Fellowship::owner),
            Member.CODEC.listOf().fieldOf("members").forGetter(Fellowship::members),
            Codec.STRING.optionalFieldOf("icon", "").forGetter(Fellowship::icon),
            Codec.BOOL.optionalFieldOf("pvpOff", false).forGetter(Fellowship::pvpOff),
            Codec.BOOL.optionalFieldOf("faction", false).forGetter(Fellowship::faction),
            Codec.STRING.optionalFieldOf("requiredFaction", "").forGetter(Fellowship::requiredFaction)
    ).apply(instance, Fellowship::new));

    // A new fellowship with the default icon and PvP on.
    public Fellowship(String id, String name, UUID owner, List<Member> members) {
        this(id, name, owner, members, "", false, false, "");
    }

    // A new faction, optionally gated on a Middle-earth faction id.
    public static Fellowship faction(String id, String name, UUID administrator, String requiredFaction) {
        return new Fellowship(id, name, administrator, new ArrayList<>(), "", false, true, requiredFaction);
    }

    public boolean isMember(UUID id) {
        return roleOf(id) != null;
    }

    // The rank the owner holds: Administrator in a faction, else Owner.
    public Role topRole() {
        return faction ? Role.ADMINISTRATOR : Role.OWNER;
    }

    // This player's rank, or null if they aren't a member.
    public Role roleOf(UUID id) {
        if (owner.equals(id)) {
            return topRole();
        }
        for (Member m : members) {
            if (m.id().equals(id)) {
                return m.role();
            }
        }
        return null;
    }

    // How a rank reads here; OWNER is "Leader" in a faction.
    public String labelOf(Role role) {
        return role.label(faction);
    }

    // New owner; members must already reflect the swap. Never used on a faction.
    public Fellowship withOwner(UUID owner, List<Member> members) {
        return new Fellowship(id, name, owner, members, icon, pvpOff, faction, requiredFaction);
    }

    // New display name; the id never changes.
    public Fellowship withName(String name) {
        return new Fellowship(id, name, owner, members, icon, pvpOff, faction, requiredFaction);
    }

    public Fellowship withMembers(List<Member> members) {
        return new Fellowship(id, name, owner, members, icon, pvpOff, faction, requiredFaction);
    }

    public Fellowship withIcon(String icon) {
        return new Fellowship(id, name, owner, members, icon, pvpOff, faction, requiredFaction);
    }

    // Repoints or, when blank, clears the faction restriction.
    public Fellowship withRequiredFaction(String requiredFaction) {
        return new Fellowship(id, name, owner, members, icon, pvpOff, faction, requiredFaction);
    }

    public Fellowship withPvpOff(boolean pvpOff) {
        return new Fellowship(id, name, owner, members, icon, pvpOff, faction, requiredFaction);
    }

    // The menu icon: its own, or the configured default.
    public Item iconItem() {
        return Config.get().fellowshipIcon(icon);
    }
}
