package dev.reinforcedclaims.gui;

import com.mojang.authlib.properties.PropertyMap;
import dev.reinforcedclaims.fellowship.Fellowship;
import dev.reinforcedclaims.fellowship.Role;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// The stacks the chest menus are built from, and how they open.
final class Menus {

    // Double chest; the bottom row is reserved for page arrows.
    static final int ROWS = 6;
    static final int SIZE = ROWS * 9;
    static final int NAV_ROW = ROWS - 1;

    private Menus() {
    }

    // Opens a menu in place of whatever is already open.
    static void open(ServerPlayerEntity player, NamedScreenHandlerFactory factory) {
        dropOpenScreen(player);
        player.openHandledScreen(factory);
    }

    // Drops the open handler server-side only; no close packet, so the cursor doesn't re-centre.
    static void dropOpenScreen(ServerPlayerEntity player) {
        if (player.currentScreenHandler != player.playerScreenHandler) {
            player.onHandledScreenClosed();
        }
    }

    // A player's head; a null name shows an unknown player.
    static ItemStack head(UUID id, String name, Formatting colour, List<Text> lore) {
        return head(id, name, label(name != null ? name : "Unknown player",
                name != null ? colour : Formatting.GRAY), lore);
    }

    // A player's head; a null name shows an unknown player.
    static ItemStack head(UUID id, String name, TextColor colour, List<Text> lore) {
        return head(id, name, label(name != null ? name : "Unknown player",
                name != null ? colour : TextColor.fromFormatting(Formatting.GRAY)), lore);
    }

    private static ItemStack head(UUID id, String name, Text customName, List<Text> lore) {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        stack.set(DataComponentTypes.PROFILE, new ProfileComponent(
                Optional.ofNullable(name), Optional.of(id), new PropertyMap()));
        stack.set(DataComponentTypes.CUSTOM_NAME, customName);
        stack.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return stack;
    }

    // A page arrow; target is the 1-based page it leads to.
    static ItemStack arrow(String name, int target, int pages) {
        return stack(Items.ARROW, label(name, Formatting.WHITE),
                List.of(label("Page " + target + " of " + pages, Formatting.DARK_GRAY)));
    }

    static ItemStack stack(Item item, Text name, List<Text> lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, name);
        stack.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return stack;
    }

    // A non-italic label.
    static Text label(String text, Formatting colour) {
        return Text.literal(text).formatted(colour).styled(style -> style.withItalic(false));
    }

    // A non-italic label in a custom colour.
    static Text label(String text, TextColor colour) {
        return Text.literal(text).styled(style -> style.withColor(colour).withItalic(false));
    }

    // "Faction" or "Fellowship", the first lore line of every row that lists one.
    static Text kindLabel(Fellowship fellowship) {
        return label(fellowship.faction() ? "Faction" : "Fellowship", Formatting.DARK_GRAY);
    }

    // The colour a rank shows in: a dark-grey-to-gold privilege ramp.
    static TextColor roleColour(Role role) {
        return switch (role) {
            case ADMINISTRATOR -> TextColor.fromRgb(0xFFD966);
            case OWNER -> TextColor.fromRgb(0xFFFFFF);
            case GUIDE -> TextColor.fromRgb(0xC0C0C0);
            case MEMBER -> TextColor.fromRgb(0x808080);
            case GUEST -> TextColor.fromRgb(0x404040);
        };
    }
}
