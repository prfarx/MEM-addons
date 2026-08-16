package dev.reinforcedclaims.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.reinforcedclaims.ReinforcedClaims;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// Loads and holds config/reinforcedclaims.json.
public final class Config {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Config instance;

    public static final class Tier {
        public int size;
        public String block;
        public int defaultHealth = 16;

        public Tier() {
        }

        public Tier(int size, String block, int defaultHealth) {
            this.size = size;
            this.block = block;
            this.defaultHealth = defaultHealth;
        }
    }

    public int snitchPingIntervalTicks = 8;
    public String claimBlock = "minecraft:lodestone";
    public String defaultFellowshipIcon = "minecraft:white_banner";
    public LinkedHashMap<String, Tier> claimTiers = defaultTiers();
    public LinkedHashMap<String, Integer> reinforcementMaterials = defaultMaterials();
    // Named groups of block ids forming one object.
    public LinkedHashMap<String, List<String>> multiBlocks = new LinkedHashMap<>();
    public int multiBlockMaxParts = 64;

    // Block id -> every id in its group, built at load.
    private transient Map<String, Set<String>> multiBlockIndex = Map.of();

    // Registry lookups, resolved on first use.
    private transient Block resolvedClaimBlock;
    private transient Map<Block, Set<Block>> resolvedMultiBlocks;
    private transient Map<Block, String> resolvedTierBlocks;
    private transient Map<Item, Integer> resolvedMaterials;

    private static LinkedHashMap<String, Tier> defaultTiers() {
        LinkedHashMap<String, Tier> m = new LinkedHashMap<>();
        m.put("1", new Tier(8, "minecraft:iron_block", 16));
        m.put("2", new Tier(16, "minecraft:gold_block", 16));
        m.put("3", new Tier(32, "minecraft:diamond_block", 16));
        m.put("admin", new Tier(32, "minecraft:barrier", -1));
        return m;
    }

    private static LinkedHashMap<String, Integer> defaultMaterials() {
        LinkedHashMap<String, Integer> m = new LinkedHashMap<>();
        m.put("minecraft:iron_nugget", 32);
        m.put("minecraft:gold_nugget", 64);
        m.put("minecraft:tadpole_spawn_egg", -1);
        return m;
    }

    public static Config get() {
        if (instance == null) {
            instance = new Config();
        }
        return instance;
    }

    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("reinforcedclaims.json");
        Config cfg = new Config();
        try {
            if (Files.exists(path)) {
                Config parsed = GSON.fromJson(Files.readString(path), Config.class);
                if (parsed != null) {
                    cfg = parsed;
                }
            } else {
                Files.createDirectories(path.getParent());
                Files.writeString(path, GSON.toJson(cfg));
            }
        } catch (IOException | RuntimeException e) {
            ReinforcedClaims.LOGGER.warn("ReinforcedClaims failed to read config, using defaults", e);
            cfg = new Config();
        }
        if (cfg.claimBlock == null || cfg.claimBlock.isBlank()) {
            cfg.claimBlock = "minecraft:lodestone";
        }
        if (cfg.defaultFellowshipIcon == null || cfg.defaultFellowshipIcon.isBlank()) {
            cfg.defaultFellowshipIcon = "minecraft:white_banner";
        }
        if (cfg.claimTiers == null || cfg.claimTiers.isEmpty()) {
            cfg.claimTiers = defaultTiers();
        }
        cfg.claimTiers.values().removeIf(t -> t == null || t.block == null || t.size <= 0);
        cfg.claimTiers.values().forEach(t -> {
            if (t.defaultHealth == 0) {
                t.defaultHealth = 16;
            }
        });
        cfg.claimTiers = normaliseKeys(cfg.claimTiers, true, "claim tier");
        if (cfg.claimTiers.isEmpty()) {
            cfg.claimTiers = defaultTiers();
        }
        if (cfg.reinforcementMaterials == null || cfg.reinforcementMaterials.isEmpty()) {
            cfg.reinforcementMaterials = defaultMaterials();
        }
        cfg.reinforcementMaterials = normaliseKeys(cfg.reinforcementMaterials, false, "reinforcement material");
        if (cfg.multiBlocks == null) {
            cfg.multiBlocks = new LinkedHashMap<>();
        }
        cfg.multiBlockIndex = indexMultiBlocks(cfg.multiBlocks);
        instance = cfg;
    }

    // Folds keys to one case so lookups are case-insensitive; duplicates keep the first.
    private static <V> LinkedHashMap<String, V> normaliseKeys(LinkedHashMap<String, V> entries,
                                                              boolean upperCase, String what) {
        LinkedHashMap<String, V> normalised = new LinkedHashMap<>();
        entries.forEach((key, value) -> {
            String folded = upperCase ? key.toUpperCase(Locale.ROOT) : key.toLowerCase(Locale.ROOT);
            if (normalised.putIfAbsent(folded, value) != null) {
                ReinforcedClaims.LOGGER.warn("Duplicate {} '{}' in config; keeping the first", what, key);
            }
        });
        return normalised;
    }

    // Flattens the named groups into member id -> its whole group.
    private static Map<String, Set<String>> indexMultiBlocks(LinkedHashMap<String, List<String>> groups) {
        Map<String, Set<String>> index = new HashMap<>();
        groups.forEach((name, ids) -> {
            if (ids == null) {
                return;
            }
            Set<String> members = new HashSet<>();
            for (String id : ids) {
                if (id != null && !id.isBlank()) {
                    members.add(id.toLowerCase(Locale.ROOT));
                }
            }
            for (String id : members) {
                if (index.putIfAbsent(id, members) != null) {
                    ReinforcedClaims.LOGGER.warn("Block '{}' is in more than one multiBlocks group ('{}' repeats it);"
                            + " keeping the first", id, name);
                }
            }
        });
        return index;
    }

    public int pingIntervalTicks() {
        return Math.max(1, snitchPingIntervalTicks);
    }

    // Every block forming one object with this one, or null if it isn't in a group.
    public Set<Block> multiBlockGroup(Block block) {
        if (resolvedMultiBlocks == null) {
            resolvedMultiBlocks = resolveMultiBlocks(multiBlockIndex);
        }
        return resolvedMultiBlocks.get(block);
    }

    // Resolves the group ids once; an id no loaded mod provides drops out.
    private static Map<Block, Set<Block>> resolveMultiBlocks(Map<String, Set<String>> index) {
        // Members of one group share a Set instance, so identity keys resolve each group once.
        Map<Set<String>, Set<Block>> groups = new IdentityHashMap<>();
        Map<Block, Set<Block>> resolved = new HashMap<>();
        index.forEach((id, ids) -> {
            Block block = block(id);
            if (block != null) {
                resolved.put(block, groups.computeIfAbsent(ids, Config::resolveGroup));
            }
        });
        return resolved;
    }

    private static Set<Block> resolveGroup(Set<String> ids) {
        Set<Block> blocks = new HashSet<>();
        for (String id : ids) {
            Block block = block(id);
            if (block != null) {
                blocks.add(block);
            }
        }
        return blocks;
    }

    // The block a configured id names, or null when nothing is registered under it.
    private static Block block(String id) {
        Identifier parsed = identifier(id);
        Block block = parsed != null ? Registries.BLOCK.get(parsed) : null;
        return block != null && block != Blocks.AIR ? block : null;
    }

    // Parses a hand-written id, lowercased first.
    private static Identifier identifier(String id) {
        return id != null && !id.isBlank() ? Identifier.tryParse(id.toLowerCase(Locale.ROOT)) : null;
    }

    // How far a configured group may spread before the search gives up.
    public int multiBlockCap() {
        return Math.max(1, multiBlockMaxParts);
    }

    // The configured claim block, or the default.
    public Block claimBlock() {
        if (resolvedClaimBlock == null) {
            Block block = block(claimBlock);
            resolvedClaimBlock = block != null ? block : Blocks.LODESTONE;
        }
        return resolvedClaimBlock;
    }

    // A fellowship's menu icon: its own, else the configured default, else a banner.
    public Item fellowshipIcon(String icon) {
        Item item = item(icon);
        if (item == null) {
            item = item(defaultFellowshipIcon);
        }
        return item != null ? item : Items.WHITE_BANNER;
    }

    private static Item item(String id) {
        Identifier parsed = identifier(id);
        Item item = parsed != null ? Registries.ITEM.get(parsed) : null;
        return item != null && item != Items.AIR ? item : null;
    }

    // The named tier, or null if the config no longer defines it.
    public Tier tier(String name) {
        return name != null ? claimTiers.get(name.toUpperCase(Locale.ROOT)) : null;
    }

    // A tier's half-extent, or -1 if it doesn't exist.
    public int tierSize(String name) {
        Tier t = tier(name);
        return t != null ? t.size : -1;
    }

    // Per-block default HP inside a tier's claim; -1 = infinite.
    public int tierDefaultHealth(String name) {
        Tier t = tier(name);
        return t != null ? t.defaultHealth : 0;
    }

    // The tier this block creates, or null if it isn't a resource block.
    public String tierForBlock(Block block) {
        if (resolvedTierBlocks == null) {
            resolvedTierBlocks = resolveTierBlocks(claimTiers);
        }
        return resolvedTierBlocks.get(block);
    }

    // Two tiers naming the same block keep the first.
    private static Map<Block, String> resolveTierBlocks(Map<String, Tier> tiers) {
        Map<Block, String> resolved = new HashMap<>();
        tiers.forEach((name, tier) -> {
            Block block = block(tier.block);
            if (block != null) {
                resolved.putIfAbsent(block, name);
            }
        });
        return resolved;
    }

    // HP an item grants as a reinforcement material, or null if it isn't one.
    public Integer reinforcementForItem(Item material) {
        if (resolvedMaterials == null) {
            resolvedMaterials = resolveMaterials(reinforcementMaterials);
        }
        return resolvedMaterials.get(material);
    }

    private static Map<Item, Integer> resolveMaterials(Map<String, Integer> materials) {
        Map<Item, Integer> resolved = new HashMap<>();
        materials.forEach((id, health) -> {
            Item item = item(id);
            if (item != null) {
                resolved.putIfAbsent(item, health);
            }
        });
        return resolved;
    }
}
