package dev.waystones.gui;

import dev.waystones.config.Config;
import dev.waystones.teleport.TeleportService;
import dev.waystones.waystone.Waystone;
import dev.waystones.waystone.WaystoneSkin;
import dev.waystones.waystone.WaystoneState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

// Chest menu of the destinations config allows from this waystone.
public final class TravelMenu extends GenericContainerScreenHandler {

    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;

    private final ServerPlayerEntity player;
    private final SimpleInventory inventory;
    private final Waystone from;
    private final List<Waystone> destinations;

    public static void open(ServerPlayerEntity player, Waystone from) {
        Menus.open(player, new SimpleNamedScreenHandlerFactory(
                (syncId, inv, p) -> new TravelMenu(syncId, inv, player, from),
                Text.literal(from.name())));
    }

    private TravelMenu(int syncId, PlayerInventory playerInventory, ServerPlayerEntity player, Waystone from) {
        this(syncId, playerInventory, player, from, new SimpleInventory(SIZE));
    }

    private TravelMenu(int syncId, PlayerInventory playerInventory, ServerPlayerEntity player, Waystone from,
                        SimpleInventory inventory) {
        super(ScreenHandlerType.GENERIC_9X6, syncId, playerInventory, inventory, ROWS);
        this.player = player;
        this.inventory = inventory;
        this.from = from;
        this.destinations = resolveDestinations(player, from);
        render();
    }

    private static List<Waystone> resolveDestinations(ServerPlayerEntity player, Waystone from) {
        WaystoneState state = WaystoneState.get(player.getServer());
        List<Waystone> found = new ArrayList<>();
        for (String name : Config.get().destinationsFrom(from.name())) {
            Waystone destination = state.get(name);
            if (destination != null && destination.named() && !destination.name().equalsIgnoreCase(from.name())) {
                found.add(destination);
            }
        }
        return found;
    }

    private void render() {
        inventory.clear();
        for (int i = 0; i < destinations.size() && i < SIZE; i++) {
            inventory.setStack(i, destinationStack(destinations.get(i)));
        }
        sendContentUpdates();
    }

    private static ItemStack destinationStack(Waystone waystone) {
        WaystoneSkin skin = WaystoneSkin.byId(waystone.skin());
        Item icon = skin != null ? skin.baseItem() : Items.MAP;
        return Menus.stack(icon, Menus.label(waystone.name(), Formatting.AQUA), List.of(
                Menus.label(waystone.dimension().toString(), Formatting.DARK_GRAY)));
    }

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity clicker) {
        if (slotIndex < 0 || slotIndex >= destinations.size()) {
            return;
        }
        Waystone destination = destinations.get(slotIndex);
        player.playSoundToPlayer(SoundEvents.UI_BUTTON_CLICK.value(), SoundCategory.MASTER, 0.4f, 1.0f);
        player.getServer().execute(() -> {
            if (player.currentScreenHandler == this) {
                player.closeHandledScreen();
            }
            TeleportService.attempt(player, from, destination);
        });
    }

    @Override
    public ItemStack quickMove(PlayerEntity clicker, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse(PlayerEntity clicker) {
        return true;
    }
}
