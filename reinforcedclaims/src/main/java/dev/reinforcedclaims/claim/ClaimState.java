package dev.reinforcedclaims.claim;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.reinforcedclaims.ReinforcedClaims;
import dev.reinforcedclaims.config.Config;
import dev.reinforcedclaims.protection.AccessGrant.FellowshipGrant;
import dev.reinforcedclaims.protection.AccessGrant.PlayerGrant;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.util.Uuids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongConsumer;

// Per-dimension store of claims and their snitch logs, keyed by the claim block's packed position.
// Mutate only through put/remove so the indices stay correct.
public class ClaimState extends PersistentState {

    // Log entries one claim keeps, and how long one lives, whichever runs out first.
    private static final int LOG_CAP = 200;
    private static final long LOG_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000;

    public record Claim(String tier, List<FellowshipGrant> fellowshipGrants, List<PlayerGrant> playerGrants,
                         boolean snitch, UUID owner, String name) {
        public static final Codec<Claim> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("tier").forGetter(Claim::tier),
                FellowshipGrant.CODEC.listOf().optionalFieldOf("fellowshipGrants", List.of()).forGetter(Claim::fellowshipGrants),
                PlayerGrant.CODEC.listOf().optionalFieldOf("playerGrants", List.of()).forGetter(Claim::playerGrants),
                Codec.BOOL.optionalFieldOf("snitch", false).forGetter(Claim::snitch),
                Uuids.CODEC.fieldOf("owner").forGetter(Claim::owner),
                Codec.STRING.optionalFieldOf("name", "").forGetter(Claim::name)
        ).apply(instance, Claim::new));

        public Claim withSnitch(boolean value) {
            return new Claim(tier, fellowshipGrants, playerGrants, value, owner, name);
        }

        public Claim withFellowshipGrants(List<FellowshipGrant> newGrants) {
            return new Claim(tier, List.copyOf(newGrants), playerGrants, snitch, owner, name);
        }

        public Claim withPlayerGrants(List<PlayerGrant> newGrants) {
            return new Claim(tier, fellowshipGrants, List.copyOf(newGrants), snitch, owner, name);
        }

        public Claim withName(String newName) {
            return new Claim(tier, fellowshipGrants, playerGrants, snitch, owner, newName);
        }

        // The claim's name, or a generic fallback, for snitch messages.
        public String territoryPhrase() {
            return name.isBlank() ? "your claim" : name;
        }
    }

    private final Long2ObjectOpenHashMap<Claim> claims = new Long2ObjectOpenHashMap<>();

    // Chunk key -> the claims whose square overlaps that chunk. Not serialized.
    private final Long2ObjectOpenHashMap<LongOpenHashSet> byChunk = new Long2ObjectOpenHashMap<>();

    // Fellowship id -> the claims granting to it. Not serialized.
    private final Map<String, LongOpenHashSet> byFellowship = new HashMap<>();

    // Claim position -> its snitch log, newest first. A deque: appends head, expiries tail.
    private final Long2ObjectOpenHashMap<Deque<LogEntry>> logs = new Long2ObjectOpenHashMap<>();

    public ClaimState() {
    }

    // --- accessors ------------------------------------------------------------------------------

    public Claim get(BlockPos pos) {
        return claims.get(pos.asLong());
    }

    public Claim get(long pos) {
        return claims.get(pos);
    }

    public boolean isEmpty() {
        return claims.isEmpty();
    }

    // Every claim. The entry object is reused: never retain it, and snapshot keys before mutating.
    public ObjectIterator<Long2ObjectMap.Entry<Claim>> fastIterator() {
        return claims.long2ObjectEntrySet().fastIterator();
    }

    // Claims that might cover (x, z); callers still check the exact square.
    public LongSet candidatesAt(int x, int z) {
        LongOpenHashSet found = byChunk.get(ChunkPos.toLong(x >> 4, z >> 4));
        return found == null ? LongSets.EMPTY_SET : found;
    }

    // Claims granting to this fellowship. Backed by the live index.
    public LongSet grantingTo(String fellowshipId) {
        LongOpenHashSet found = byFellowship.get(fellowshipId);
        return found == null ? LongSets.EMPTY_SET : found;
    }

    // --- mutators -------------------------------------------------------------------------------

    public void put(BlockPos pos, Claim claim) {
        put(pos.asLong(), claim);
    }

    public void put(long pos, Claim claim) {
        Claim old = claims.put(pos, claim);
        if (old != null) {
            unindex(pos, old);
        }
        index(pos, claim);
    }

    public Claim remove(BlockPos pos) {
        return remove(pos.asLong());
    }

    public Claim remove(long pos) {
        Claim old = claims.remove(pos);
        if (old != null) {
            unindex(pos, old);
        }
        return old;
    }

    // --- snitch logs ----------------------------------------------------------------------------

    // This claim's entry/exit log, newest first.
    public List<LogEntry> logsAt(BlockPos claimPos) {
        Deque<LogEntry> found = logs.get(claimPos.asLong());
        return found == null ? List.of() : List.copyOf(found);
    }

    // Records an entry, dropping whatever has aged or overflowed.
    public void appendLog(BlockPos claimPos, LogEntry entry) {
        long key = claimPos.asLong();
        Deque<LogEntry> log = logs.get(key);
        if (log == null) {
            log = new ArrayDeque<>();
            logs.put(key, log);
        }
        log.addFirst(entry);
        long cutoff = System.currentTimeMillis() - LOG_MAX_AGE_MS;
        while (!log.isEmpty() && (log.size() > LOG_CAP || log.peekLast().time() < cutoff)) {
            log.removeLast();
        }
    }

    public void clearLogs(long claimPos) {
        logs.remove(claimPos);
    }

    // --- indices --------------------------------------------------------------------------------

    private void index(long pos, Claim claim) {
        forEachChunk(pos, claim, key -> chunkSet(key).add(pos));
        for (FellowshipGrant g : claim.fellowshipGrants()) {
            byFellowship.computeIfAbsent(g.fellowship(), id -> new LongOpenHashSet(2)).add(pos);
        }
    }

    private void unindex(long pos, Claim claim) {
        forEachChunk(pos, claim, key -> {
            LongOpenHashSet set = byChunk.get(key);
            if (set != null && set.remove(pos) && set.isEmpty()) {
                byChunk.remove(key);
            }
        });
        for (FellowshipGrant g : claim.fellowshipGrants()) {
            LongOpenHashSet set = byFellowship.get(g.fellowship());
            // Grants at different ranks share one entry; a second removal is a miss.
            if (set != null && set.remove(pos) && set.isEmpty()) {
                byFellowship.remove(g.fellowship());
            }
        }
    }

    // Sized for one: a claim's square usually reaches a chunk no other claim's does.
    private LongOpenHashSet chunkSet(long key) {
        LongOpenHashSet positions = byChunk.get(key);
        if (positions == null) {
            positions = new LongOpenHashSet(2);
            byChunk.put(key, positions);
        }
        return positions;
    }

    private static void forEachChunk(long pos, Claim claim, LongConsumer action) {
        int size = Math.max(0, Config.get().tierSize(claim.tier()));
        int x = BlockPos.unpackLongX(pos);
        int z = BlockPos.unpackLongZ(pos);
        int minX = (x - size) >> 4;
        int maxX = (x + size) >> 4;
        int minZ = (z - size) >> 4;
        int maxZ = (z + size) >> 4;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                action.accept(ChunkPos.toLong(cx, cz));
            }
        }
    }

    // --- persistence ----------------------------------------------------------------------------

    private record Entry(BlockPos pos, Claim claim) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(Entry::pos),
                Claim.CODEC.fieldOf("claim").forGetter(Entry::claim)
        ).apply(instance, Entry::new));
    }

    private static List<Entry> encodeClaims(ClaimState state) {
        List<Entry> out = new ArrayList<>(state.claims.size());
        for (ObjectIterator<Long2ObjectMap.Entry<Claim>> it = state.fastIterator(); it.hasNext(); ) {
            Long2ObjectMap.Entry<Claim> entry = it.next();
            out.add(new Entry(BlockPos.fromLong(entry.getLongKey()), entry.getValue()));
        }
        return out;
    }

    // Logs serialize as a string-keyed map; NBT has no other kind.
    private static Map<String, List<LogEntry>> encodeLogs(ClaimState state) {
        Map<String, List<LogEntry>> out = new LinkedHashMap<>(state.logs.size());
        for (Long2ObjectMap.Entry<Deque<LogEntry>> entry : state.logs.long2ObjectEntrySet()) {
            out.put(Long.toString(entry.getLongKey()), List.copyOf(entry.getValue()));
        }
        return out;
    }

    private static void decodeLogs(ClaimState state, Map<String, List<LogEntry>> saved) {
        saved.forEach((key, list) -> {
            long pos;
            try {
                pos = Long.parseLong(key);
            } catch (NumberFormatException e) {
                ReinforcedClaims.LOGGER.warn("Dropping a snitch log under unreadable key '{}'", key);
                return;
            }
            state.logs.put(pos, new ArrayDeque<>(list));
        });
    }

    public static final Codec<ClaimState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Entry.CODEC.listOf().fieldOf("claims").forGetter(ClaimState::encodeClaims),
            Codec.unboundedMap(Codec.STRING, LogEntry.CODEC.listOf()).optionalFieldOf("logs", Map.of())
                    .forGetter(ClaimState::encodeLogs)
    ).apply(instance, (claimList, logMap) -> {
        ClaimState state = new ClaimState();
        for (Entry entry : claimList) {
            state.put(entry.pos(), entry.claim());
        }
        decodeLogs(state, logMap);
        return state;
    }));

    public static final PersistentStateType<ClaimState> TYPE = new PersistentStateType<>(
            "reinforcedclaims_claims",
            ClaimState::new,
            CODEC,
            null
    );
}
