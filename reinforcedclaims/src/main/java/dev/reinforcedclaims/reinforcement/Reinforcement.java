package dev.reinforcedclaims.reinforcement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.reinforcedclaims.protection.AccessGrant.FellowshipGrant;
import dev.reinforcedclaims.protection.AccessGrant.PlayerGrant;
import net.minecraft.util.Uuids;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// A reinforced block. Explicit (has an owner) overrides any covering claim;
// base (no owner) only carries a claim's default HP.
public record Reinforcement(Optional<UUID> owner, List<FellowshipGrant> fellowshipGrants,
                             List<PlayerGrant> playerGrants, int health, int maxHealth) {

    // Sentinel for unbreakable.
    public static final int INFINITE = -1;

    public static boolean isInfinite(int hp) {
        return hp < 0;
    }

    public static final Codec<Reinforcement> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Uuids.CODEC.optionalFieldOf("owner").forGetter(Reinforcement::owner),
            FellowshipGrant.CODEC.listOf().optionalFieldOf("fellowshipGrants", List.of()).forGetter(Reinforcement::fellowshipGrants),
            PlayerGrant.CODEC.listOf().optionalFieldOf("playerGrants", List.of()).forGetter(Reinforcement::playerGrants),
            Codec.INT.fieldOf("health").forGetter(Reinforcement::health),
            Codec.INT.fieldOf("maxHealth").forGetter(Reinforcement::maxHealth)
    ).apply(instance, Reinforcement::new));

    public boolean isExplicit() {
        return owner.isPresent();
    }

    public boolean isInfinite() {
        return isInfinite(maxHealth);
    }

    public Reinforcement withFellowshipGrants(List<FellowshipGrant> newGrants) {
        return new Reinforcement(owner, List.copyOf(newGrants), playerGrants, health, maxHealth);
    }

    public Reinforcement withPlayerGrants(List<PlayerGrant> newGrants) {
        return new Reinforcement(owner, fellowshipGrants, List.copyOf(newGrants), health, maxHealth);
    }

    public Reinforcement damaged(int amount) {
        if (isInfinite()) {
            return this;
        }
        return new Reinforcement(owner, fellowshipGrants, playerGrants, Math.max(0, health - amount), maxHealth);
    }

    public Reinforcement healed() {
        return new Reinforcement(owner, fellowshipGrants, playerGrants, maxHealth, maxHealth);
    }

    public boolean isDepleted() {
        return !isInfinite() && health <= 0;
    }

    // Display string for current health.
    public String healthString() {
        return isInfinite() ? "∞" : health + "/" + maxHealth;
    }
}
