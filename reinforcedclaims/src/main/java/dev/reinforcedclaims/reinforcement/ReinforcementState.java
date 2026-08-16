package dev.reinforcedclaims.reinforcement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.reinforcedclaims.protection.AccessGrant.FellowshipGrant;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Per-dimension store of reinforced blocks, keyed by packed BlockPos.
// Mutate only through put/remove so the indices stay correct.
public class ReinforcementState extends PersistentState {

    private final Long2ObjectOpenHashMap<Reinforcement> reinforcements = new Long2ObjectOpenHashMap<>();

    // Chunk key -> the packed positions in that chunk. Not serialized.
    private final Long2ObjectOpenHashMap<LongOpenHashSet> byChunk = new Long2ObjectOpenHashMap<>();

    // Fellowship id -> the packed positions granting to it. Not serialized.
    private final Map<String, LongOpenHashSet> byFellowship = new HashMap<>();

    public ReinforcementState() {
    }

    // --- accessors ------------------------------------------------------------------------------

    public Reinforcement get(BlockPos pos) {
        return reinforcements.get(pos.asLong());
    }

    public Reinforcement get(long pos) {
        return reinforcements.get(pos);
    }

    public boolean isEmpty() {
        return reinforcements.isEmpty();
    }

    // Every record. The entry object is reused: never retain it, and snapshot keys before mutating.
    public ObjectIterator<Long2ObjectMap.Entry<Reinforcement>> fastIterator() {
        return reinforcements.long2ObjectEntrySet().fastIterator();
    }

    // The packed positions in one chunk. Backed by the live index.
    public LongSet inChunk(int chunkX, int chunkZ) {
        LongOpenHashSet positions = byChunk.get(ChunkPos.toLong(chunkX, chunkZ));
        return positions == null ? LongSets.EMPTY_SET : positions;
    }

    // Records granting to this fellowship. Backed by the live index.
    public LongSet grantingTo(String fellowshipId) {
        LongOpenHashSet positions = byFellowship.get(fellowshipId);
        return positions == null ? LongSets.EMPTY_SET : positions;
    }

    // --- mutators -------------------------------------------------------------------------------

    public void put(BlockPos pos, Reinforcement reinforcement) {
        put(pos.asLong(), reinforcement);
    }

    public void put(long pos, Reinforcement reinforcement) {
        Reinforcement old = reinforcements.put(pos, reinforcement);
        if (old == null) {
            // Only a new position changes the chunk index.
            long chunk = chunkKey(pos);
            LongOpenHashSet positions = byChunk.get(chunk);
            if (positions == null) {
                positions = new LongOpenHashSet(2);
                byChunk.put(chunk, positions);
            }
            positions.add(pos);
        } else {
            // A replacement can carry different grants, so always reindex.
            unindexFellowships(pos, old);
        }
        indexFellowships(pos, reinforcement);
    }

    public Reinforcement remove(BlockPos pos) {
        return remove(pos.asLong());
    }

    public Reinforcement remove(long pos) {
        Reinforcement old = reinforcements.remove(pos);
        if (old != null) {
            long chunk = chunkKey(pos);
            LongOpenHashSet positions = byChunk.get(chunk);
            if (positions != null && positions.remove(pos) && positions.isEmpty()) {
                byChunk.remove(chunk);
            }
            unindexFellowships(pos, old);
        }
        return old;
    }

    private void indexFellowships(long pos, Reinforcement record) {
        for (FellowshipGrant g : record.fellowshipGrants()) {
            byFellowship.computeIfAbsent(g.fellowship(), id -> new LongOpenHashSet(2)).add(pos);
        }
    }

    private void unindexFellowships(long pos, Reinforcement record) {
        for (FellowshipGrant g : record.fellowshipGrants()) {
            LongOpenHashSet positions = byFellowship.get(g.fellowship());
            // Grants at different ranks share one entry; a second removal is a miss.
            if (positions != null && positions.remove(pos) && positions.isEmpty()) {
                byFellowship.remove(g.fellowship());
            }
        }
    }

    private static long chunkKey(long pos) {
        return ChunkPos.toLong(BlockPos.unpackLongX(pos) >> 4, BlockPos.unpackLongZ(pos) >> 4);
    }

    // --- persistence ----------------------------------------------------------------------------

    private record Entry(BlockPos pos, Reinforcement data) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(Entry::pos),
                Reinforcement.CODEC.fieldOf("data").forGetter(Entry::data)
        ).apply(instance, Entry::new));
    }

    public static final Codec<ReinforcementState> CODEC = Entry.CODEC.listOf().xmap(
            entries -> {
                ReinforcementState state = new ReinforcementState();
                for (Entry entry : entries) {
                    state.put(entry.pos(), entry.data());
                }
                return state;
            },
            state -> {
                List<Entry> out = new ArrayList<>(state.reinforcements.size());
                for (ObjectIterator<Long2ObjectMap.Entry<Reinforcement>> it = state.fastIterator(); it.hasNext(); ) {
                    Long2ObjectMap.Entry<Reinforcement> entry = it.next();
                    out.add(new Entry(BlockPos.fromLong(entry.getLongKey()), entry.getValue()));
                }
                return out;
            }
    );

    public static final PersistentStateType<ReinforcementState> TYPE = new PersistentStateType<>(
            "reinforcedclaims_reinforcements",
            ReinforcementState::new,
            CODEC,
            null
    );
}
