package dev.waystones.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.waystones.Waystones;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Loads and holds config/waystones.json.
public final class Config {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Config instance;

    public int xpCost = 0;
    public int tpCooldownSeconds = 0;
    // Waystone name -> its destinations. Directed: list both ways for round trips.
    public LinkedHashMap<String, List<String>> waystoneConnections = defaultConnections();
    // Skin id -> its block ids and rune-band appearance.
    public LinkedHashMap<String, SkinVariant> skins = defaultSkins();

    // waystoneConnections, lowercased once at load.
    private transient Map<String, List<String>> normalisedConnections = Map.of();

    // One skin: base/wall/slab block ids plus rune text and color.
    public static final class SkinVariant {
        public String base;
        public String wall;
        public String slab;
        public String runeText;
        public String textColor;

        public SkinVariant() {
        }

        public SkinVariant(String base, String wall, String slab, String runeText, String textColor) {
            this.base = base;
            this.wall = wall;
            this.slab = slab;
            this.runeText = runeText;
            this.textColor = textColor;
        }
    }

    // Example graph seeded on first run.
    private static LinkedHashMap<String, List<String>> defaultConnections() {
        LinkedHashMap<String, List<String>> map = new LinkedHashMap<>();
        map.put("bree", List.of("rivendell", "weathertop"));
        return map;
    }

    private static LinkedHashMap<String, SkinVariant> defaultSkins() {
        LinkedHashMap<String, SkinVariant> map = new LinkedHashMap<>();
        map.put("andesite", new SkinVariant("minecraft:andesite", "minecraft:andesite_wall", "minecraft:andesite_slab", "ᚨᚾᛞ", "#E8E4DC"));
        return map;
    }

    public static Config get() {
        if (instance == null) {
            instance = new Config();
        }
        return instance;
    }

    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("waystones.json");
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
            Waystones.LOGGER.warn("Waystones failed to read config, using defaults", e);
            cfg = new Config();
        }
        if (cfg.xpCost < 0) {
            cfg.xpCost = 0;
        }
        if (cfg.tpCooldownSeconds < 0) {
            cfg.tpCooldownSeconds = 0;
        }
        if (cfg.waystoneConnections == null) {
            cfg.waystoneConnections = new LinkedHashMap<>();
        }
        if (cfg.skins == null || cfg.skins.isEmpty()) {
            cfg.skins = defaultSkins();
        }
        cfg.normalisedConnections = normalise(cfg.waystoneConnections);
        instance = cfg;
    }

    // Lowercases names; duplicate keys merge their destination lists.
    private static Map<String, List<String>> normalise(Map<String, List<String>> raw) {
        Map<String, List<String>> normalised = new LinkedHashMap<>();
        raw.forEach((name, destinations) -> {
            if (name == null || name.isBlank()) {
                return;
            }
            List<String> cleaned = new ArrayList<>();
            if (destinations != null) {
                for (String destination : destinations) {
                    if (destination != null && !destination.isBlank()) {
                        cleaned.add(destination.trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
            normalised.merge(name.trim().toLowerCase(Locale.ROOT), cleaned, (a, b) -> {
                a.addAll(b);
                return a;
            });
        });
        return normalised;
    }

    // The names this waystone may teleport to.
    public List<String> destinationsFrom(String waystoneName) {
        if (waystoneName == null) {
            return List.of();
        }
        return normalisedConnections.getOrDefault(waystoneName.toLowerCase(Locale.ROOT), List.of());
    }
}
