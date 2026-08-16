package dev.waystones.waystone;

import dev.waystones.config.Config;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

// A waystone's config-defined blocks and rune-band appearance.
public final class WaystoneSkin {

    private static final Map<String, WaystoneSkin> REGISTRY = new LinkedHashMap<>();

    public final String id;
    public final String base;
    public final String wall;
    public final String slab;
    public final String runeText;
    public final int textColor;

    private WaystoneSkin(String id, String base, String wall, String slab, String runeText, int textColor) {
        this.id = id;
        this.base = base;
        this.wall = wall;
        this.slab = slab;
        this.runeText = runeText;
        this.textColor = textColor;
    }

    // Rebuilds the skin registry from config. Call once after config load.
    public static void load(Config config) {
        REGISTRY.clear();
        config.skins.forEach((rawId, variant) -> {
            if (rawId == null || rawId.isBlank() || variant == null) {
                return;
            }
            String id = rawId.trim().toLowerCase(Locale.ROOT);
            REGISTRY.put(id, new WaystoneSkin(id, variant.base, variant.wall, variant.slab,
                    variant.runeText == null ? "" : variant.runeText, parseColor(variant.textColor)));
        });
    }

    private static int parseColor(String hex) {
        if (hex == null || hex.isBlank()) {
            return 0xFFFFFF;
        }
        try {
            return Integer.parseInt(hex.replace("#", ""), 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return 0xFFFFFF;
        }
    }

    public String id() {
        return id;
    }

    public static WaystoneSkin byId(String id) {
        return id == null ? null : REGISTRY.get(id.trim().toLowerCase(Locale.ROOT));
    }

    // Every configured skin, in config file order.
    public static Collection<WaystoneSkin> all() {
        return REGISTRY.values();
    }

    public Item baseItem() {
        return resolve(base);
    }

    public Item wallItem() {
        return resolve(wall);
    }

    public Item slabItem() {
        return resolve(slab);
    }

    private static Item resolve(String id) {
        if (id == null || id.isBlank()) {
            return Items.BARRIER;
        }
        // Unresolvable ids fall back to BARRIER.
        Identifier identifier = Identifier.tryParse(id.trim());
        if (identifier == null) {
            return Items.BARRIER;
        }
        Item item = Registries.ITEM.get(identifier);
        return item != Items.AIR ? item : Items.BARRIER;
    }
}
