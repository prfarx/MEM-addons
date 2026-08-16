package dev.reinforcedclaims.claim;

import dev.reinforcedclaims.config.Config;
import dev.reinforcedclaims.protection.AccessGrant;
import dev.reinforcedclaims.protection.AccessGrant.FellowshipGrant;
import dev.reinforcedclaims.protection.AccessGrant.PlayerGrant;
import dev.reinforcedclaims.protection.AccessLocks;
import dev.reinforcedclaims.protection.InteractionType;
import dev.reinforcedclaims.protection.ProtectionManager;
import dev.reinforcedclaims.protection.ProtectionModes;
import dev.reinforcedclaims.reinforcement.Reinforcement;
import dev.reinforcedclaims.reinforcement.ReinforcementState;
import dev.reinforcedclaims.fellowship.Fellowship;
import dev.reinforcedclaims.fellowship.FellowshipState;
import dev.reinforcedclaims.fellowship.Role;
import dev.reinforcedclaims.gui.FellowshipPickerScreen;
import dev.reinforcedclaims.ReinforcedClaims;
import dev.reinforcedclaims.util.MultiBlock;
import dev.reinforcedclaims.util.Players;
import dev.reinforcedclaims.util.ProtectionView;
import dev.reinforcedclaims.util.Raycast;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// The access authority for claims and reinforcements: geometry, permissions, HP,
// orphan cleanup, and the snitch scan. An explicit reinforcement overrides any claim.
public final class ClaimManager {

    private static int tickCounter = 0;

    // Identifies one snitch. Keys INSIDE, which is transient.
    private record SnitchKey(RegistryKey<World> world, BlockPos pos) {
    }

    private static final Map<SnitchKey, Set<UUID>> INSIDE = new ConcurrentHashMap<>();

    // Chunks processed per tick by /clm validate all.
    private static final int SWEEP_CHUNKS_PER_TICK = 8;
    private static Sweep sweep;

    private ClaimManager() {
    }

    public static ClaimState getState(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(ClaimState.TYPE);
    }

    // Whether this player is in the grant's fellowship at the rank it requires.
    private static boolean grantApplies(FellowshipState fellowships, FellowshipGrant grant, UUID id) {
        Fellowship f = fellowships.get(grant.fellowship());
        return f != null && grant.covers(f.roleOf(id));
    }

    private static boolean ownerOrBypass(UUID owner, UUID id) {
        return owner.equals(id) || ProtectionModes.isBypassing(id);
    }

    // A refusal with no message: naming what stopped the caller would leak that it exists.
    private static Text silent() {
        return Text.literal("");
    }

    // --- geometry -------------------------------------------------------------------------------

    // Whether the claim's square covers (x, z); false once config drops its tier.
    private static boolean covers(long center, ClaimState.Claim claim, int x, int z) {
        Config.Tier tier = Config.get().tier(claim.tier());
        return tier != null && inSquare(center, tier.size, x, z);
    }

    private static boolean inSquare(long center, int size, int x, int z) {
        return Math.abs(x - BlockPos.unpackLongX(center)) <= size
                && Math.abs(z - BlockPos.unpackLongZ(center)) <= size;
    }

    // Whether any claim covers this position.
    public static boolean isInsideAnyClaim(ClaimState state, BlockPos pos) {
        LongIterator candidates = state.candidatesAt(pos.getX(), pos.getZ()).iterator();
        while (candidates.hasNext()) {
            long b = candidates.nextLong();
            ClaimState.Claim claim = state.get(b);
            if (claim == null) {
                ReinforcedClaims.LOGGER.warn("Claim chunk index references a missing record at {}",
                        BlockPos.fromLong(b));
                continue;
            }
            if (covers(b, claim, pos.getX(), pos.getZ())) {
                return true;
            }
        }
        return false;
    }

    // Whether a move enters a claim that didn't already cover its origin.
    public static boolean crossesIntoClaim(ClaimState state, BlockPos from, BlockPos to) {
        Config config = Config.get();
        LongIterator candidates = state.candidatesAt(to.getX(), to.getZ()).iterator();
        while (candidates.hasNext()) {
            long b = candidates.nextLong();
            ClaimState.Claim claim = state.get(b);
            if (claim == null) {
                continue;
            }
            // One tier lookup for both ends.
            Config.Tier tier = config.tier(claim.tier());
            if (tier == null || !inSquare(b, tier.size, to.getX(), to.getZ())) {
                continue;
            }
            if (!inSquare(b, tier.size, from.getX(), from.getZ())) {
                return true;
            }
        }
        return false;
    }

    // Whether a move crosses a claim edge in either direction.
    public static boolean crossesClaimBoundary(ClaimState state, BlockPos from, BlockPos to) {
        return crossesIntoClaim(state, from, to) || crossesIntoClaim(state, to, from);
    }

    // Whether a block was reinforced by hand rather than carrying a claim's default HP.
    public static boolean isExplicitlyReinforced(ReinforcementState store, BlockPos pos) {
        Reinforcement record = store.get(pos);
        return record != null && record.isExplicit();
    }

    // Claim block positions whose square covers pos.
    private static LongList coveringClaims(ClaimState state, BlockPos pos) {
        LongList covering = new LongArrayList();
        LongIterator candidates = state.candidatesAt(pos.getX(), pos.getZ()).iterator();
        while (candidates.hasNext()) {
            long b = candidates.nextLong();
            ClaimState.Claim claim = state.get(b);
            if (claim != null && covers(b, claim, pos.getX(), pos.getZ())) {
                covering.add(b);
            }
        }
        return covering;
    }

    // Claims covering any of these positions, for a multi-block straddling an edge.
    private static LongList coveringClaims(ClaimState state, List<BlockPos> positions) {
        if (positions.size() == 1) {
            return coveringClaims(state, positions.get(0));
        }
        LongLinkedOpenHashSet union = new LongLinkedOpenHashSet();
        for (BlockPos pos : positions) {
            union.addAll(coveringClaims(state, pos));
        }
        return new LongArrayList(union);
    }

    // Claims a new claim of this tier at pos would overlap.
    private static LongList overlappingClaims(ClaimState state, BlockPos pos, String tier) {
        int size = Math.max(0, Config.get().tierSize(tier));
        long self = pos.asLong();
        LongList overlapping = new LongArrayList();
        LongOpenHashSet seen = new LongOpenHashSet();
        for (int cx = (pos.getX() - size) >> 4; cx <= (pos.getX() + size) >> 4; cx++) {
            for (int cz = (pos.getZ() - size) >> 4; cz <= (pos.getZ() + size) >> 4; cz++) {
                LongIterator candidates = state.candidatesAt(cx << 4, cz << 4).iterator();
                while (candidates.hasNext()) {
                    long b = candidates.nextLong();
                    if (b == self || !seen.add(b)) {
                        continue;
                    }
                    ClaimState.Claim other = state.get(b);
                    if (other == null) {
                        continue;
                    }
                    int reach = Config.get().tierSize(other.tier());
                    if (reach < 0) {
                        continue;
                    }
                    reach += size;
                    if (Math.abs(BlockPos.unpackLongX(b) - pos.getX()) <= reach
                            && Math.abs(BlockPos.unpackLongZ(b) - pos.getZ()) <= reach) {
                        overlapping.add(b);
                    }
                }
            }
        }
        return overlapping;
    }

    // The nearest of these claims to pos.
    private static ClaimState.Claim nearestClaim(ClaimState state, BlockPos pos, LongList positions) {
        long best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < positions.size(); i++) {
            long b = positions.getLong(i);
            long dx = BlockPos.unpackLongX(b) - pos.getX();
            long dz = BlockPos.unpackLongZ(b) - pos.getZ();
            long distance = dx * dx + dz * dz;
            // The packed position breaks ties, so iteration order doesn't matter.
            if (distance < bestDistance || (distance == bestDistance && b < best)) {
                best = b;
                bestDistance = distance;
            }
        }
        return state.get(best);
    }

    // Blocks an explosion must not touch: inside a claim, or explicitly reinforced.
    public static boolean isExplosionProtected(ClaimState claims, ReinforcementState reinforcements, BlockPos pos) {
        Reinforcement r = reinforcements.get(pos);
        if (r != null && r.isExplicit()) {
            return true;
        }
        return isInsideAnyClaim(claims, pos);
    }

    // --- access ---------------------------------------------------------------------------------

    // Whether a grant on these lists reaches this player. A null type asks only whether
    // they are listed at all.
    private static boolean granted(FellowshipState fellowships, List<FellowshipGrant> fellowshipGrants,
                                   List<PlayerGrant> playerGrants, UUID id, InteractionType type) {
        for (FellowshipGrant g : fellowshipGrants) {
            if ((type == null || g.allowed().contains(type)) && grantApplies(fellowships, g, id)) {
                return true;
            }
        }
        for (PlayerGrant g : playerGrants) {
            if (g.player().equals(id) && (type == null || g.allowed().contains(type))) {
                return true;
            }
        }
        return false;
    }

    // Whether this player holds every category on the claim, required to reinforce or overlap.
    private static boolean canAdminister(FellowshipState fellowships, ClaimState.Claim c, UUID id) {
        if (c == null) {
            return false;
        }
        if (c.owner().equals(id)) {
            return true;
        }
        EnumSet<InteractionType> held = EnumSet.noneOf(InteractionType.class);
        for (FellowshipGrant g : c.fellowshipGrants()) {
            if (!g.allowed().isEmpty() && grantApplies(fellowships, g, id)) {
                held.addAll(g.allowed());
            }
        }
        for (PlayerGrant g : c.playerGrants()) {
            if (g.player().equals(id)) {
                held.addAll(g.allowed());
            }
        }
        return held.size() == InteractionType.values().length;
    }

    // Whether the player may interact here. An explicit reinforcement wins over any claim.
    public static boolean canInteract(ServerWorld world, BlockPos pos, PlayerEntity player, InteractionType type) {
        UUID id = player.getUuid();
        if (ProtectionModes.isBypassing(id)) {
            return true;
        }
        MinecraftServer server = world.getServer();
        Reinforcement rec = ProtectionManager.reinforcements(world).get(pos);
        if (rec != null && rec.isExplicit()) {
            return rec.owner().get().equals(id)
                    || granted(ProtectionManager.fellowships(server), rec.fellowshipGrants(),
                            rec.playerGrants(), id, type);
        }
        ClaimState state = getState(world);
        LongSet candidates = state.candidatesAt(pos.getX(), pos.getZ());
        if (candidates.isEmpty()) {
            // The common case: nothing indexed nearby, so don't resolve the fellowship store.
            return true;
        }
        FellowshipState fellowships = ProtectionManager.fellowships(server);
        LongIterator it = candidates.iterator();
        while (it.hasNext()) {
            long b = it.nextLong();
            ClaimState.Claim claim = state.get(b);
            if (claim != null && covers(b, claim, pos.getX(), pos.getZ())
                    && !claim.owner().equals(id)
                    && !granted(fellowships, claim.fellowshipGrants(), claim.playerGrants(), id, type)) {
                return false;
            }
        }
        return true;
    }

    public static boolean handleEnemyPlacement(ServerWorld world, BlockPos placePos, PlayerEntity player) {
        if (canInteract(world, placePos, player, InteractionType.PLACE_BREAK)) {
            return false;
        }
        if (player instanceof ServerPlayerEntity sp) {
            sp.sendMessage(Text.literal("This area is claimed").formatted(Formatting.RED), true);
        }
        return true;
    }

    // Default HP from the covering claims; -1 if any is infinite, 0 if none covers it.
    public static int claimDefaultHealth(ServerWorld world, BlockPos pos) {
        ClaimState state = getState(world);
        Config config = Config.get();
        int best = 0;
        LongIterator candidates = state.candidatesAt(pos.getX(), pos.getZ()).iterator();
        while (candidates.hasNext()) {
            long b = candidates.nextLong();
            ClaimState.Claim claim = state.get(b);
            if (claim == null) {
                continue;
            }
            // One tier lookup for both the square and its HP.
            Config.Tier tier = config.tier(claim.tier());
            if (tier == null || !inSquare(b, tier.size, pos.getX(), pos.getZ())) {
                continue;
            }
            if (Reinforcement.isInfinite(tier.defaultHealth)) {
                return Reinforcement.INFINITE;
            }
            best = Math.max(best, tier.defaultHealth);
        }
        return best;
    }

    // --- multi-block records ----------------------------------------------------------------------

    // Writes one record to every part of a multi-block, so they agree on owner, grants and HP.
    private static void putParts(ReinforcementState store, List<BlockPos> parts, Reinforcement record) {
        for (BlockPos part : parts) {
            store.put(part, record);
        }
        store.markDirty();
    }

    // Drops every part's record. Called before the break, while the object is whole.
    private static void removeParts(ReinforcementState store, List<BlockPos> parts) {
        boolean removed = false;
        for (BlockPos part : parts) {
            removed |= store.remove(part) != null;
        }
        if (removed) {
            store.markDirty();
        }
    }

    // What one hit on a block's protection did.
    private enum Chip {
        // Not protected at all.
        UNPROTECTED,
        // Protected and unbreakable; nothing written.
        INFINITE,
        // HP came down but the block survives.
        DAMAGED,
        // The last point went; records gone, block still standing.
        BROKEN
    }

    // Takes a point of HP off whatever protects pos, materializing a base record from a
    // covering claim if there isn't one yet. Shared by the break and arrow paths.
    private static Chip chip(ServerWorld world, BlockPos pos, ReinforcementState store, PlayerEntity actor) {
        Reinforcement current = store.get(pos);
        if (current == null) {
            int hp = claimDefaultHealth(world, pos);
            if (hp == 0) {
                return Chip.UNPROTECTED;
            }
            if (Reinforcement.isInfinite(hp)) {
                return Chip.INFINITE;
            }
            current = new Reinforcement(Optional.empty(), List.of(), List.of(), hp, hp);
        } else if (current.isInfinite()) {
            return Chip.INFINITE;
        }
        // Resolved while the object is whole, in case this hit finishes it.
        List<BlockPos> parts = MultiBlock.parts(world, pos);
        Reinforcement damaged = current.damaged(1);
        if (damaged.isDepleted()) {
            removeParts(store, parts);
            return Chip.BROKEN;
        }
        putParts(store, parts, damaged);
        actor.sendMessage(Text.literal(damaged.healthString()).formatted(Formatting.RED), true);
        return Chip.DAMAGED;
    }

    // Break handler: chips HP and cancels the break until it reaches zero.
    public static boolean onBreakAttempt(ServerWorld world, BlockPos pos, ServerPlayerEntity player) {
        ReinforcementState store = ProtectionManager.reinforcements(world);
        if (canInteract(world, pos, player, InteractionType.PLACE_BREAK)) {
            // Resolved before the break: once the block is gone, so is the object's shape.
            removeParts(store, MultiBlock.parts(world, pos));
            return true;
        }
        return switch (chip(world, pos, store, player)) {
            case UNPROTECTED -> {
                removeParts(store, MultiBlock.parts(world, pos));
                yield true;
            }
            case BROKEN -> true;
            case INFINITE -> {
                player.sendMessage(Text.literal("This area is claimed").formatted(Formatting.RED), true);
                yield false;
            }
            case DAMAGED -> {
                player.getMainHandStack().postMine(world, world.getBlockState(pos), pos, player);
                yield false;
            }
        };
    }

    // A player's arrow hit a block: chips HP like a break attempt. True when protected,
    // so the caller consumes the arrow.
    public static boolean onProjectileHit(ServerWorld world, BlockPos pos, PlayerEntity shooter) {
        if (shooter == null || canInteract(world, pos, shooter, InteractionType.PLACE_BREAK)) {
            return false;
        }
        ReinforcementState store = ProtectionManager.reinforcements(world);
        return switch (chip(world, pos, store, shooter)) {
            case UNPROTECTED -> false;
            // Unbreakable: the arrow stops but does no damage.
            case INFINITE, DAMAGED -> true;
            case BROKEN -> {
                world.breakBlock(pos, true);
                // No break event fires here, so tear the claim down directly.
                onBlockBroken(world, pos);
                yield true;
            }
        };
    }

    // --- reinforcement (anywhere) -----------------------------------------------------------------

    // The HP an item grants as a material; null when it isn't one.
    private static Integer materialHealth(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        Integer configured = Config.get().reinforcementForItem(stack.getItem());
        return configured == null || configured == 0 ? null : configured;
    }

    private static Text needsFullClaimAccess() {
        return Text.literal("Insufficient permissions")
                .formatted(Formatting.RED);
    }

    // Whether a covering claim denies this player the right to reinforce here.
    private static boolean claimForbidsReinforcing(ServerWorld world, BlockPos pos, UUID id) {
        if (ProtectionModes.isBypassing(id)) {
            return false;
        }
        ClaimState state = getState(world);
        LongList covering = coveringClaims(state, pos);
        if (covering.isEmpty()) {
            return false;
        }
        FellowshipState fellowships = ProtectionManager.fellowships(world.getServer());
        for (int i = 0; i < covering.size(); i++) {
            if (!canAdminister(fellowships, state.get(covering.getLong(i)), id)) {
                return true;
            }
        }
        return false;
    }

    // The record for reinforcing this object, or null when a covering claim forbids it.
    // Inside a claim the claim's owner owns it and the player gets full access instead.
    private static Reinforcement reinforcementFor(ServerWorld world, List<BlockPos> parts, BlockPos pos,
                                                  ServerPlayerEntity player, int hp) {
        UUID id = player.getUuid();
        ClaimState state = getState(world);
        LongList covering = ProtectionModes.isBypassing(id) ? LongList.of() : coveringClaims(state, parts);
        if (covering.isEmpty()) {
            return new Reinforcement(Optional.of(id), List.of(), List.of(), hp, hp);
        }
        FellowshipState fellowships = ProtectionManager.fellowships(world.getServer());
        for (int i = 0; i < covering.size(); i++) {
            if (!canAdminister(fellowships, state.get(covering.getLong(i)), id)) {
                player.sendMessage(needsFullClaimAccess(), true);
                return null;
            }
        }
        UUID owner = nearestClaim(state, pos, covering).owner();
        List<PlayerGrant> grants = owner.equals(id) ? List.of() : AccessGrant.addPlayerWithFullAccess(List.of(), id);
        return new Reinforcement(Optional.of(owner), List.of(), grants, hp, hp);
    }

    // Feedback for a new reinforcement.
    private static Text reinforceFeedback(Reinforcement record) {
        return Text.literal(record.healthString()).formatted(Formatting.GREEN);
    }

    // Whether this placement would reinforce a block a covering claim forbids. Checked
    // before placement, so the block and material stay in hand.
    public static boolean handleBlockedReinforcedPlacement(ServerWorld world, BlockPos pos, PlayerEntity player, Hand placingHand) {
        UUID id = player.getUuid();
        if (!ProtectionModes.isReinforcing(id)) {
            return false;
        }
        Hand other = placingHand == Hand.MAIN_HAND ? Hand.OFF_HAND : Hand.MAIN_HAND;
        if (materialHealth(player.getStackInHand(other)) == null) {
            return false;
        }
        if (!claimForbidsReinforcing(world, pos, id)) {
            return false;
        }
        if (player instanceof ServerPlayerEntity sp) {
            sp.sendMessage(needsFullClaimAccess(), true);
        }
        return true;
    }

    // Reinforces a block as it is placed, from material in the other hand.
    public static void autoReinforce(ServerWorld world, BlockPos pos, ServerPlayerEntity player, Hand placingHand) {
        if (!ProtectionModes.isReinforcing(player.getUuid())) {
            return;
        }
        Hand other = placingHand == Hand.MAIN_HAND ? Hand.OFF_HAND : Hand.MAIN_HAND;
        ItemStack material = player.getStackInHand(other);
        Integer hp = materialHealth(material);
        if (hp == null) {
            return;
        }
        List<BlockPos> parts = MultiBlock.parts(world, pos);
        Reinforcement record = reinforcementFor(world, parts, pos, player, hp);
        if (record == null) {
            return;
        }
        if (!player.getAbilities().creativeMode) {
            material.decrement(1);
        }
        putParts(ProtectionManager.reinforcements(world), parts, record);
        player.sendMessage(reinforceFeedback(record), true);
    }

    // Right-click reinforce: material in the main hand, off hand empty. Null means this
    // wasn't a reinforce click, so normal use handling takes over.
    public static ActionResult tryReinforceExisting(ServerWorld world, BlockPos pos, ServerPlayerEntity player) {
        if (!player.getOffHandStack().isEmpty()) {
            return null;
        }
        ItemStack material = player.getMainHandStack();
        Integer hp = materialHealth(material);
        if (hp == null) {
            return null;
        }
        ReinforcementState store = ProtectionManager.reinforcements(world);
        Reinforcement existing = store.get(pos);
        if (existing != null && existing.isExplicit()) {
            // The material may be a block item, so undo the client's predicted placement.
            player.playerScreenHandler.syncState();
            return ActionResult.FAIL;
        }
        if (!canInteract(world, pos, player, InteractionType.PLACE_BREAK)) {
            return null;
        }
        List<BlockPos> parts = MultiBlock.parts(world, pos);
        Reinforcement record = reinforcementFor(world, parts, pos, player, hp);
        if (record == null) {
            // FAIL, not null: spends the click so a block-item material isn't placed.
            player.playerScreenHandler.syncState();
            return ActionResult.FAIL;
        }
        if (!player.getAbilities().creativeMode) {
            material.decrement(1);
        }
        putParts(store, parts, record);
        // No block changed, so nothing else would tell the overlay to redraw.
        ProtectionView.invalidate(world, pos);
        player.sendMessage(reinforceFeedback(record), true);
        return ActionResult.SUCCESS;
    }

    // --- lifecycle ------------------------------------------------------------------------------

    public static void onBlockPlaced(ServerWorld world, BlockPos pos, BlockState placed, ServerPlayerEntity player, Hand hand) {
        Block claimBlock = Config.get().claimBlock();
        if (placed.isOf(claimBlock)) {
            createClaim(world, pos, player);
        } else if (placed.isOf(Blocks.JUKEBOX)) {
            linkSnitch(world, pos.down(), player);
        } else if (world.getBlockState(pos.up()).isOf(claimBlock)) {
            createClaim(world, pos.up(), player);
        }
        autoReinforce(world, pos, player, hand);
    }

    // Creates a claim. Overlapping an existing one gives it to the nearest claim's owner,
    // with the placer granted full access; without full access there, no claim is created.
    private static void createClaim(ServerWorld world, BlockPos pos, ServerPlayerEntity player) {
        String tier = Config.get().tierForBlock(world.getBlockState(pos.down()).getBlock());
        if (tier == null) {
            return;
        }
        ClaimState state = getState(world);
        UUID id = player.getUuid();
        UUID owner = id;
        List<PlayerGrant> playerGrants = List.of();
        LongList overlapping = ProtectionModes.isBypassing(id) ? LongList.of() : overlappingClaims(state, pos, tier);
        if (!overlapping.isEmpty()) {
            FellowshipState fellowships = ProtectionManager.fellowships(world.getServer());
            for (int i = 0; i < overlapping.size(); i++) {
                if (!canAdminister(fellowships, state.get(overlapping.getLong(i)), id)) {
                    player.sendMessage(Text.literal("Insufficient permissions")
                            .formatted(Formatting.RED), true);
                    return;
                }
            }
            owner = nearestClaim(state, pos, overlapping).owner();
            if (!owner.equals(id)) {
                playerGrants = AccessGrant.addPlayerWithFullAccess(List.of(), id);
            }
        }
        boolean snitch = world.getBlockState(pos.up()).isOf(Blocks.JUKEBOX);
        state.put(pos.toImmutable(), new ClaimState.Claim(tier, List.of(), playerGrants, snitch, owner, ""));
        state.markDirty();
        player.sendMessage(Text.literal("Area claimed" + (snitch ? "; snitch linked" : ""))
                .formatted(Formatting.GREEN), true);
    }

    private static void linkSnitch(ServerWorld world, BlockPos claimPos, ServerPlayerEntity player) {
        ClaimState state = getState(world);
        ClaimState.Claim claim = state.get(claimPos);
        if (claim == null) {
            return;
        }
        if (!canInteract(world, claimPos, player, InteractionType.PLACE_BREAK)) {
            player.sendMessage(Text.literal("Insufficient permissions").formatted(Formatting.RED), true);
            return;
        }
        state.put(claimPos, claim.withSnitch(true));
        state.markDirty();
        player.sendMessage(Text.literal("Snitch linked").formatted(Formatting.GREEN), true);
    }

    // Renames a claim; blank clears the name. Owner or bypass only.
    public static Text setClaimName(ServerPlayerEntity player, ServerWorld world, BlockPos claimPos, String name) {
        ClaimState state = getState(world);
        ClaimState.Claim claim = state.get(claimPos);
        if (claim == null) {
            return silent();
        }
        if (!ownerOrBypass(claim.owner(), player.getUuid())) {
            return notYours();
        }
        state.put(claimPos, claim.withName(name));
        state.markDirty();
        return silent();
    }

    // Whether this player may rename the claim: owner, or bypass.
    public static boolean canRenameClaim(ServerWorld world, BlockPos claimPos, UUID id) {
        ClaimState.Claim claim = getState(world).get(claimPos);
        return claim != null && ownerOrBypass(claim.owner(), id);
    }

    public static void onBlockBroken(ServerWorld world, BlockPos pos) {
        ClaimState state = getState(world);
        if (removeClaim(world, state, pos.toImmutable())) {
            return;
        }
        if (removeClaim(world, state, pos.up())) {
            return;
        }
        BlockPos below = pos.down();
        ClaimState.Claim claim = state.get(below);
        if (claim != null && claim.snitch()) {
            state.put(below, claim.withSnitch(false));
            INSIDE.remove(new SnitchKey(world.getRegistryKey(), below.toImmutable()));
            state.markDirty();
        }
    }

    private static boolean removeClaim(ServerWorld world, ClaimState state, BlockPos claimBlockPos) {
        if (state.remove(claimBlockPos) == null) {
            return false;
        }
        state.clearLogs(claimBlockPos.asLong());
        INSIDE.remove(new SnitchKey(world.getRegistryKey(), claimBlockPos.toImmutable()));
        state.markDirty();
        return true;
    }

    // --- orphan validation ----------------------------------------------------------------------

    private static final int REPORT_LIST_CAP = 10;

    private record Orphan(BlockPos pos, String tier, UUID owner, String reason) {
    }

    // Why a claim record no longer matches the world, or null when it still does.
    private static String orphanReason(WorldChunk chunk, BlockPos pos, ClaimState.Claim claim) {
        if (!chunk.getBlockState(pos).isOf(Config.get().claimBlock())) {
            return "Claim block invalid";
        }
        if (Config.get().tierForBlock(chunk.getBlockState(pos.down()).getBlock()) == null) {
            return "Resource block invalid";
        }
        if (Config.get().tier(claim.tier()) == null) {
            return "Tier " + claim.tier() + " invalid";
        }
        return null;
    }

    // Chunk load/unload: drops claim records whose blocks are gone, and repairs stale grants.
    public static void onChunkValidated(ServerWorld world, WorldChunk chunk) {
        pruneChunk(world, chunk);
        List<Orphan> removed = validateChunk(world, chunk);
        if (removed.isEmpty()) {
            return;
        }
        if (sweep != null) {
            sweep.removed += removed.size();
            logRemovals(world, removed);
            return;
        }
        reportRemovals(world, removed, "chunk load/unload");
    }

    private static List<Orphan> validateChunk(ServerWorld world, WorldChunk chunk) {
        ClaimState state = getState(world);
        if (state.isEmpty()) {
            return List.of();
        }
        ChunkPos cp = chunk.getPos();
        List<Orphan> stale = new ArrayList<>();
        LongIterator candidates = state.candidatesAt(cp.getStartX(), cp.getStartZ()).iterator();
        while (candidates.hasNext()) {
            long packed = candidates.nextLong();
            if ((BlockPos.unpackLongX(packed) >> 4) != cp.x || (BlockPos.unpackLongZ(packed) >> 4) != cp.z) {
                continue;
            }
            ClaimState.Claim claim = state.get(packed);
            BlockPos pos = BlockPos.fromLong(packed);
            if (claim == null) {
                ReinforcedClaims.LOGGER.warn("Claim chunk index references missing claim at {}", pos);
                continue;
            }
            String reason = orphanReason(chunk, pos, claim);
            if (reason != null) {
                stale.add(new Orphan(pos, claim.tier(), claim.owner(), reason));
            }
        }
        for (Orphan orphan : stale) {
            removeClaim(world, state, orphan.pos());
        }
        return stale;
    }

    // Validates every claim in this dimension whose chunk is loaded.
    public static int validateLoaded(ServerWorld world) {
        ClaimState state = getState(world);
        if (state.isEmpty()) {
            return 0;
        }
        List<Orphan> stale = new ArrayList<>();
        for (ObjectIterator<Long2ObjectMap.Entry<ClaimState.Claim>> it = state.fastIterator(); it.hasNext(); ) {
            Long2ObjectMap.Entry<ClaimState.Claim> e = it.next();
            long packed = e.getLongKey();
            WorldChunk chunk = world.getChunkManager()
                    .getWorldChunk(BlockPos.unpackLongX(packed) >> 4, BlockPos.unpackLongZ(packed) >> 4);
            if (chunk == null) {
                continue;
            }
            BlockPos pos = BlockPos.fromLong(packed);
            String reason = orphanReason(chunk, pos, e.getValue());
            if (reason != null) {
                stale.add(new Orphan(pos, e.getValue().tier(), e.getValue().owner(), reason));
            }
        }
        for (Orphan orphan : stale) {
            removeClaim(world, state, orphan.pos());
        }
        if (!stale.isEmpty()) {
            reportRemovals(world, stale, "manual validate");
        }
        return stale.size();
    }

    private static void logRemovals(ServerWorld world, List<Orphan> removed) {
        String dimension = world.getRegistryKey().getValue().toString();
        for (Orphan orphan : removed) {
            ReinforcedClaims.LOGGER.info("Removed claim at {} in {} owned by {}: {}",
                    orphan.pos().toShortString(), dimension, orphan.owner(), orphan.reason());
        }
    }

    // Logs every removal and tells online ops with a capped summary.
    private static void reportRemovals(ServerWorld world, List<Orphan> removed, String context) {
        logRemovals(world, removed);
        MinecraftServer server = world.getServer();
        String dimension = world.getRegistryKey().getValue().toString();
        List<Text> lines = new ArrayList<>();
        lines.add(Text.literal("Removed " + removed.size() + " invalid reinforcement(s)"
                + " in " + dimension + " (" + context + ")")
                .formatted(Formatting.YELLOW));
        int shown = Math.min(removed.size(), REPORT_LIST_CAP);
        for (int i = 0; i < shown; i++) {
            Orphan orphan = removed.get(i);
            String owner = Players.name(server, orphan.owner());
            lines.add(Text.literal("  " + orphan.tier() + " @ " + orphan.pos().toShortString()
                    + " – " + (owner != null ? owner : orphan.owner().toString().substring(0, 8))
                    + " – " + orphan.reason())
                    .formatted(Formatting.GRAY));
        }
        if (removed.size() > shown) {
            lines.add(Text.literal("  ...and " + (removed.size() - shown) + " more")
                    .formatted(Formatting.DARK_GRAY));
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!player.hasPermissionLevel(2)) {
                continue;
            }
            for (Text line : lines) {
                player.sendMessage(line, false);
            }
        }
    }

    // --- full sweep (/clm validate all) ---------------------------------------------------

    private record SweepChunk(ServerWorld world, int x, int z) {
    }

    private static final class Sweep {
        final ServerCommandSource source;
        final Deque<SweepChunk> pending;
        int removed;
        int visited;

        Sweep(ServerCommandSource source, Deque<SweepChunk> pending) {
            this.source = source;
            this.pending = pending;
        }
    }

    // Starts a full sweep of every claim's chunk. False if one is already running.
    public static boolean startSweep(ServerCommandSource source) {
        if (sweep != null) {
            return false;
        }
        Deque<SweepChunk> pending = new ArrayDeque<>();
        for (ServerWorld world : source.getServer().getWorlds()) {
            ClaimState state = getState(world);
            if (state.isEmpty()) {
                continue;
            }
            LongLinkedOpenHashSet seen = new LongLinkedOpenHashSet();
            for (ObjectIterator<Long2ObjectMap.Entry<ClaimState.Claim>> it = state.fastIterator(); it.hasNext(); ) {
                long packed = it.next().getLongKey();
                int cx = BlockPos.unpackLongX(packed) >> 4;
                int cz = BlockPos.unpackLongZ(packed) >> 4;
                if (seen.add(ChunkPos.toLong(cx, cz))) {
                    pending.add(new SweepChunk(world, cx, cz));
                }
            }
        }
        int total = pending.size();
        sweep = new Sweep(source, pending);
        source.sendFeedback(() -> Text.literal("Validating " + total + " chunk(s)")
                .formatted(Formatting.GRAY), false);
        return true;
    }

    private static void pumpSweep() {
        if (sweep == null) {
            return;
        }
        for (int i = 0; i < SWEEP_CHUNKS_PER_TICK && !sweep.pending.isEmpty(); i++) {
            SweepChunk next = sweep.pending.poll();
            WorldChunk chunk = next.world().getChunk(next.x(), next.z());
            List<Orphan> removed = validateChunk(next.world(), chunk);
            if (!removed.isEmpty()) {
                sweep.removed += removed.size();
                logRemovals(next.world(), removed);
            }
            sweep.visited++;
        }
        if (!sweep.pending.isEmpty()) {
            return;
        }
        Sweep done = sweep;
        sweep = null;
        Text summary = Text.literal("Claim validation complete: " + done.visited + " chunk(s) validated, "
                + done.removed + " invalid claim(s) removed").formatted(Formatting.YELLOW);
        done.source.sendFeedback(() -> summary, true);
        ReinforcedClaims.LOGGER.info("Claim validation complete: {} chunk(s) validated, {} invalid claims removed.",
                done.visited, done.removed);
    }

    // --- transient snitch state ------------------------------------------------------------------

    // Logs an EXIT on every snitch that had the disconnecting player inside.
    public static void onPlayerDisconnect(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        UUID id = player.getUuid();
        String name = player.getGameProfile().getName();
        Iterator<Map.Entry<SnitchKey, Set<UUID>>> it = INSIDE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<SnitchKey, Set<UUID>> entry = it.next();
            if (!entry.getValue().remove(id)) {
                continue;
            }
            SnitchKey key = entry.getKey();
            ServerWorld world = server.getWorld(key.world());
            if (world != null) {
                ClaimState state = getState(world);
                ClaimState.Claim claim = state.get(key.pos());
                if (claim != null && claim.snitch()) {
                    state.appendLog(key.pos(), LogEntry.now(LogEntry.Type.EXIT, name));
                    notifyClaim(server, claim, name + " left " + claim.territoryPhrase());
                    state.markDirty();
                }
            }
            if (entry.getValue().isEmpty()) {
                it.remove();
            }
        }
    }

    // Resets the transient snitch and sweep state, e.g. on server stop.
    public static void clearTransient() {
        INSIDE.clear();
        sweep = null;
        tickCounter = 0;
    }

    // --- access editing (looking at a reinforced block or claim block) --------------------------

    // An explicit reinforcement or a claim, whichever a position resolves to. A claim
    // record wins; save() mirrors its grant edits onto any reinforcement at the same block.
    public record AccessTarget(boolean reinforcement, ServerWorld world, BlockPos pos, UUID owner,
                                List<FellowshipGrant> fellowshipGrants, List<PlayerGrant> playerGrants) {

        // Writes new grant lists to the live record and the rest of its object.
        public void save(List<FellowshipGrant> newFellowshipGrants, List<PlayerGrant> newPlayerGrants) {
            if (reinforcement) {
                saveReinforcement(newFellowshipGrants, newPlayerGrants);
            } else {
                ClaimState state = getState(world);
                ClaimState.Claim claim = state.get(pos);
                if (claim == null) {
                    return;
                }
                state.put(pos, claim.withFellowshipGrants(newFellowshipGrants).withPlayerGrants(newPlayerGrants));
                state.markDirty();
                // The claim block may also carry a reinforcement; keep it in step, since
                // canInteract checks the reinforcement first.
                saveReinforcement(newFellowshipGrants, newPlayerGrants);
            }
        }

        private void saveReinforcement(List<FellowshipGrant> newFellowshipGrants, List<PlayerGrant> newPlayerGrants) {
            ReinforcementState store = ProtectionManager.reinforcements(world);
            Reinforcement rec = store.get(pos);
            if (rec == null || !rec.isExplicit()) {
                return;
            }
            Reinforcement edited = rec.withFellowshipGrants(newFellowshipGrants).withPlayerGrants(newPlayerGrants);
            for (BlockPos part : MultiBlock.parts(world, pos)) {
                // Only parts that already have a record.
                if (store.get(part) != null) {
                    store.put(part, edited);
                }
            }
            store.markDirty();
        }
    }

    // The access target at a position, or null if it is neither.
    public static AccessTarget resolveTargetAt(ServerWorld world, BlockPos pos, boolean reinforcement) {
        if (reinforcement) {
            Reinforcement rec = ProtectionManager.reinforcements(world).get(pos);
            if (rec == null || !rec.isExplicit()) {
                return null;
            }
            return new AccessTarget(true, world, pos, rec.owner().orElseThrow(),
                    rec.fellowshipGrants(), rec.playerGrants());
        }
        ClaimState.Claim claim = getState(world).get(pos);
        if (claim == null) {
            return null;
        }
        return new AccessTarget(false, world, pos, claim.owner(),
                claim.fellowshipGrants(), claim.playerGrants());
    }

    // Drops grants naming a fellowship the owner has left. Null if nothing changed.
    private static List<FellowshipGrant> prunedGrants(FellowshipState fellowships,
                                                      List<FellowshipGrant> grants, UUID owner) {
        List<FellowshipGrant> living = new ArrayList<>(grants.size());
        for (FellowshipGrant g : grants) {
            Fellowship f = fellowships.get(g.fellowship());
            if (f != null && f.isMember(owner)) {
                living.add(g);
            }
        }
        return living.size() == grants.size() ? null : living;
    }

    // Repairs stale grants on one chunk's claims and reinforcements.
    public static void pruneChunk(ServerWorld world, WorldChunk chunk) {
        ClaimState claims = getState(world);
        ReinforcementState store = ProtectionManager.reinforcements(world);
        if (claims.isEmpty() && store.isEmpty()) {
            return;
        }
        ChunkPos cp = chunk.getPos();
        LongSet nearbyClaims = claims.candidatesAt(cp.getStartX(), cp.getStartZ());
        LongSet reinforced = store.inChunk(cp.x, cp.z);
        if (nearbyClaims.isEmpty() && reinforced.isEmpty()) {
            // Nothing of ours here. Every chunk load and unload lands on this path.
            return;
        }
        FellowshipState fellowships = ProtectionManager.fellowships(world.getServer());
        // Snapshotted: pruning writes back through put(), which mutates these sets.
        for (long pos : nearbyClaims.toLongArray()) {
            if ((BlockPos.unpackLongX(pos) >> 4) == cp.x && (BlockPos.unpackLongZ(pos) >> 4) == cp.z) {
                pruneClaim(claims, fellowships, pos);
            }
        }
        for (long pos : reinforced.toLongArray()) {
            pruneReinforcement(store, fellowships, pos);
        }
    }

    // pruneStaleGrants for a single fellowship.
    public static void pruneStaleGrants(MinecraftServer server, String fellowshipId) {
        pruneStaleGrants(server, List.of(fellowshipId));
    }

    // Repairs stale grants on loaded records after a leave, kick or disband; unloaded ones
    // fall to pruneChunk. Goes through each store's fellowship index, so callers must pass
    // the ids that changed.
    public static void pruneStaleGrants(MinecraftServer server, Collection<String> fellowshipIds) {
        if (fellowshipIds.isEmpty()) {
            return;
        }
        FellowshipState fellowships = ProtectionManager.fellowships(server);
        for (ServerWorld world : server.getWorlds()) {
            ClaimState claims = getState(world);
            ReinforcementState store = ProtectionManager.reinforcements(world);
            for (String id : fellowshipIds) {
                // Snapshotted: pruning writes back through put(), which mutates this set.
                for (long pos : claims.grantingTo(id).toLongArray()) {
                    if (loaded(world, pos)) {
                        pruneClaim(claims, fellowships, pos);
                    }
                }
                for (long pos : store.grantingTo(id).toLongArray()) {
                    if (loaded(world, pos)) {
                        pruneReinforcement(store, fellowships, pos);
                    }
                }
            }
        }
    }

    private static boolean loaded(ServerWorld world, long pos) {
        return world.getChunkManager()
                .getWorldChunk(BlockPos.unpackLongX(pos) >> 4, BlockPos.unpackLongZ(pos) >> 4) != null;
    }

    private static void pruneClaim(ClaimState claims, FellowshipState fellowships, long pos) {
        ClaimState.Claim claim = claims.get(pos);
        if (claim == null || claim.fellowshipGrants().isEmpty()) {
            return;
        }
        List<FellowshipGrant> living = prunedGrants(fellowships, claim.fellowshipGrants(), claim.owner());
        if (living != null) {
            claims.put(pos, claim.withFellowshipGrants(living));
            claims.markDirty();
        }
    }

    private static void pruneReinforcement(ReinforcementState store, FellowshipState fellowships, long pos) {
        Reinforcement rec = store.get(pos);
        if (rec == null || rec.owner().isEmpty() || rec.fellowshipGrants().isEmpty()) {
            return;
        }
        List<FellowshipGrant> living = prunedGrants(fellowships, rec.fellowshipGrants(), rec.owner().get());
        if (living != null) {
            store.put(pos, rec.withFellowshipGrants(living));
            store.markDirty();
        }
    }

    // The access target the player is looking at, or null. A claim record at the position
    // wins over a reinforcement the same block may carry.
    public static AccessTarget resolveTarget(ServerPlayerEntity player) {
        BlockPos pos = Raycast.lookedAtBlock(player);
        if (pos == null) {
            return null;
        }
        ServerWorld world = (ServerWorld) player.getWorld();
        ClaimState.Claim claim = getState(world).get(pos);
        if (claim != null) {
            return new AccessTarget(false, world, pos, claim.owner(),
                    claim.fellowshipGrants(), claim.playerGrants());
        }
        return resolveTargetAt(world, pos, true);
    }

    private static Text notYours() {
        return Text.literal("Insufficient permissions").formatted(Formatting.RED);
    }

    // Whether this player may edit the target's grants: owner, bypass, or MODIFY_PERMISSIONS.
    public static boolean canModifyPermissions(MinecraftServer server, AccessTarget target, UUID id) {
        return ownerOrBypass(target.owner(), id)
                || granted(ProtectionManager.fellowships(server), target.fellowshipGrants(),
                        target.playerGrants(), id, InteractionType.MODIFY_PERMISSIONS);
    }

    // Whether this edit would leave the editor unable to modify permissions here.
    // Owners and bypassing ops always have their own way in.
    public static boolean revokesOwnModifyAccess(MinecraftServer server, AccessTarget target, UUID id,
                                                  List<FellowshipGrant> fellowshipGrants, List<PlayerGrant> playerGrants) {
        if (ownerOrBypass(target.owner(), id)) {
            return false;
        }
        AccessTarget edited = new AccessTarget(target.reinforcement(), target.world(), target.pos(), target.owner(),
                fellowshipGrants, playerGrants);
        return !canModifyPermissions(server, edited, id);
    }

    // The lock identifying a target, so only one player edits it at a time.
    public static AccessLocks.Key lockKey(AccessTarget target) {
        return new AccessLocks.Key(target.world().getRegistryKey(), target.pos(), target.reinforcement());
    }

    // Refusal for a lock that couldn't be taken and whose holder is unknown.
    public static Text editBusy() {
        return silent();
    }

    // Refusal naming the lock's holder, or null when the caller may proceed.
    public static Text editLockedBy(MinecraftServer server, AccessTarget target, UUID id) {
        UUID holder = AccessLocks.holder(lockKey(target));
        if (holder == null || holder.equals(id)) {
            return null;
        }
        return silent();
    }

    // Why the player may not edit this target now, or null when they may.
    public static Text editRefusal(ServerPlayerEntity player, AccessTarget target) {
        MinecraftServer server = player.getServer();
        if (!canModifyPermissions(server, target, player.getUuid())) {
            return notYours();
        }
        return editLockedBy(server, target, player.getUuid());
    }

    private static Text lookAtSomething() {
        return silent();
    }

    // Opens the fellowship picker for /clm assign, or returns why it can't open.
    public static Text openFellowshipAssign(ServerPlayerEntity player) {
        AccessTarget target = resolveTarget(player);
        if (target == null) {
            return lookAtSomething();
        }
        Text refusal = editRefusal(player, target);
        if (refusal != null) {
            return refusal;
        }
        // Bypass widens the menu to every fellowship on the server.
        FellowshipState fellowships = ProtectionManager.fellowships(player.getServer());
        boolean bypassing = ProtectionModes.isBypassing(player.getUuid());
        List<Fellowship> candidates = bypassing
                ? new ArrayList<>(fellowships.all())
                : new ArrayList<>(fellowships.forMember(player.getUuid()));
        candidates.sort(Comparator.comparing(Fellowship::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Fellowship::id));
        // One row per rank scope not already granted; the picker opens even when empty.
        List<FellowshipPickerScreen.Choice> choices = new ArrayList<>();
        for (Fellowship f : candidates) {
            for (Role scope : Role.grantScopes(f.faction())) {
                if (!hasScope(target.fellowshipGrants(), f.id(), scope)) {
                    choices.add(new FellowshipPickerScreen.Choice(f.id(), scope));
                }
            }
        }
        // Held while the picker is open: the grant list could change under it.
        AccessLocks.Key lock = lockKey(target);
        if (!AccessLocks.acquire(lock, player.getUuid())) {
            Text held = editLockedBy(player.getServer(), target, player.getUuid());
            return held != null ? held : editBusy();
        }
        // Re-resolved by position: the player is looking at a menu now, not the block.
        boolean reinforcement = target.reinforcement();
        ServerWorld world = target.world();
        BlockPos pos = target.pos();
        FellowshipPickerScreen.openScoped(player,
                Text.literal("Fellowships").formatted(Formatting.DARK_GRAY), choices,
                (picked, scope) -> Players.sendIfPresent(player,
                        applyAssign(player, world, pos, reinforcement, picked, scope), false),
                lock);
        return null;
    }

    private static boolean hasScope(List<FellowshipGrant> grants, String fellowship, Role minRole) {
        for (FellowshipGrant g : grants) {
            if (g.sameScope(fellowship, minRole)) {
                return true;
            }
        }
        return false;
    }

    // Re-resolves the target, which may have changed while the chooser was open, then assigns.
    private static Text applyAssign(ServerPlayerEntity player, ServerWorld world, BlockPos pos,
                                    boolean reinforcement, Fellowship f, Role scope) {
        AccessTarget target = resolveTargetAt(world, pos, reinforcement);
        if (target == null) {
            return silent();
        }
        Text refusal = editRefusal(player, target);
        if (refusal != null) {
            return refusal;
        }
        // The owner, not the caller, must be a member; bypass doesn't override it.
        if (!f.isMember(target.owner())) {
            return Text.literal("Claim owner not in " + f.name())
                    .formatted(Formatting.RED);
        }
        // No duplicate check: the picker already excluded existing scopes.
        String label = AccessGrant.scopeLabel(f.name(), scope, f.faction());
        target.save(AccessGrant.addFellowship(target.fellowshipGrants(), f.id(), scope),
                target.playerGrants());
        return Text.literal("Assigned " + label)
                .formatted(Formatting.GREEN);
    }

    public static Text assignPlayer(ServerPlayerEntity player, UUID id, String name) {
        AccessTarget target = resolveTarget(player);
        if (target == null) {
            return lookAtSomething();
        }
        Text refusal = editRefusal(player, target);
        if (refusal != null) {
            return refusal;
        }
        if (id.equals(target.owner())) {
            return Text.literal("Claim owned by " + name).formatted(Formatting.GRAY);
        }
        target.save(target.fellowshipGrants(), AccessGrant.addPlayer(target.playerGrants(), id));
        return Text.literal("Assigned " + name).formatted(Formatting.GREEN);
    }


    // --- per-claim snitch scan ----------------------------------------------------------------

    public static void tick(MinecraftServer server) {
        pumpSweep();
        if (++tickCounter < Config.get().pingIntervalTicks()) {
            return;
        }
        tickCounter = 0;

        for (ServerWorld world : server.getWorlds()) {
            ClaimState state = getState(world);
            if (!state.isEmpty()) {
                scanSnitches(server, world, state);
            }
        }
    }

    // Scans snitches near each player for entry/exit, revisiting any that still owe an EXIT.
    private static void scanSnitches(MinecraftServer server, ServerWorld world, ClaimState state) {
        RegistryKey<World> dimension = world.getRegistryKey();
        // Both stay null until somebody is actually standing in a snitched claim.
        Map<BlockPos, Set<UUID>> nowInside = null;
        Map<UUID, String> names = null;
        FellowshipState fellowships = null;

        for (ServerPlayerEntity player : world.getPlayers()) {
            UUID id = player.getUuid();
            int x = player.getBlockX();
            int z = player.getBlockZ();
            LongIterator candidates = state.candidatesAt(x, z).iterator();
            while (candidates.hasNext()) {
                long packed = candidates.nextLong();
                ClaimState.Claim claim = state.get(packed);
                if (claim == null || !claim.snitch() || !covers(packed, claim, x, z)
                        || claim.owner().equals(id)) {
                    continue;
                }
                if (fellowships == null) {
                    fellowships = ProtectionManager.fellowships(server);
                }
                if (granted(fellowships, claim.fellowshipGrants(), claim.playerGrants(), id, null)) {
                    continue;
                }
                if (nowInside == null) {
                    nowInside = new HashMap<>();
                    names = new HashMap<>();
                }
                // Only a snitch being stood in needs a key object; the rest stays packed.
                nowInside.computeIfAbsent(BlockPos.fromLong(packed), k -> new HashSet<>()).add(id);
                names.put(id, player.getGameProfile().getName());
            }
        }

        Set<BlockPos> touched = nowInside == null ? new HashSet<>() : new HashSet<>(nowInside.keySet());
        for (SnitchKey key : INSIDE.keySet()) {
            if (key.world().equals(dimension)) {
                touched.add(key.pos());
            }
        }
        if (touched.isEmpty()) {
            return;
        }
        if (nowInside == null) {
            nowInside = Map.of();
            names = Map.of();
        }

        boolean dirty = false;
        for (BlockPos pos : touched) {
            SnitchKey insideKey = new SnitchKey(dimension, pos);
            ClaimState.Claim claim = state.get(pos);
            if (claim == null || !claim.snitch()) {
                INSIDE.remove(insideKey);
                continue;
            }
            Set<UUID> current = nowInside.getOrDefault(pos, Set.of());
            Set<UUID> previous = INSIDE.getOrDefault(insideKey, Set.of());

            for (UUID id : current) {
                if (previous.contains(id)) {
                    continue;
                }
                String name = displayName(server, names, id);
                state.appendLog(pos, LogEntry.now(LogEntry.Type.ENTER, name));
                notifyClaim(server, claim, name + " entered " + claim.territoryPhrase());
                dirty = true;
            }
            for (UUID gone : previous) {
                if (current.contains(gone)) {
                    continue;
                }
                String name = displayName(server, names, gone);
                state.appendLog(pos, LogEntry.now(LogEntry.Type.EXIT, name));
                notifyClaim(server, claim, name + " left " + claim.territoryPhrase());
                dirty = true;
            }

            if (current.isEmpty()) {
                INSIDE.remove(insideKey);
            } else {
                INSIDE.put(insideKey, current);
            }
        }
        if (dirty) {
            state.markDirty();
        }
    }

    // Name for a log line: seen this scan, else online somewhere, else a short id.
    private static String displayName(MinecraftServer server, Map<UUID, String> seen, UUID id) {
        String name = seen.get(id);
        if (name != null) {
            return name;
        }
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
        return player != null ? player.getGameProfile().getName() : id.toString().substring(0, 8);
    }

    private static void notifyClaim(MinecraftServer server, ClaimState.Claim claim, String message) {
        Text text = Text.literal("[Snitch] ").formatted(Formatting.RED).append(Text.literal(message).formatted(Formatting.WHITE));
        Set<UUID> recipients = new HashSet<>();
        recipients.add(claim.owner());
        FellowshipState fellowships = ProtectionManager.fellowships(server);
        for (FellowshipGrant g : claim.fellowshipGrants()) {
            Fellowship f = fellowships.get(g.fellowship());
            if (f == null) {
                continue;
            }
            // Only notify the ranks the grant covers.
            if (g.covers(f.topRole())) {
                recipients.add(f.owner());
            }
            for (Fellowship.Member member : f.members()) {
                if (g.covers(member.role())) {
                    recipients.add(member.id());
                }
            }
        }
        for (PlayerGrant g : claim.playerGrants()) {
            recipients.add(g.player());
        }
        for (UUID id : recipients) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
            if (p != null) {
                p.sendMessage(text, false);
            }
        }
    }

    // --- viewers --------------------------------------------------------------------------------

    // A claim's snitch log, newest first.
    public static List<LogEntry> snitchLogs(ServerWorld world, BlockPos claimPos) {
        return getState(world).logsAt(claimPos);
    }
}
