package dev.reinforcedclaims.gui;

import dev.reinforcedclaims.fellowship.Fellowship;
import dev.reinforcedclaims.fellowship.FellowshipState;
import dev.reinforcedclaims.fellowship.Role;
import dev.reinforcedclaims.protection.ProtectionManager;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// The /fs list menu: one fellowship per row, with roster, PvP and Leave buttons.
public final class FellowshipListScreen {

    // One fellowship per row, minus the page-arrow row.
    private static final int PAGE_SIZE = Menus.NAV_ROW;

    private static final int ICON_COLUMN = 0;
    private static final int PVP_COLUMN = 5;
    private static final int ACTION_COLUMN = 8;

    private FellowshipListScreen() {
    }

    // Opens the menu; an empty list stands open rather than refusing.
    public static void open(ServerPlayerEntity player, boolean all) {
        Menus.open(player, new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) -> new Handler(syncId, inventory, player, all),
                Text.literal(all ? "All Fellowships" : "Fellowships").formatted(Formatting.DARK_GRAY)));
    }

    // --- screen handler ------------------------------------------------------------------------

    private static final class Handler extends PagedMenu {

        private final boolean all;
        private List<Fellowship> fellowships = List.of();

        private Handler(int syncId, PlayerInventory playerInventory, ServerPlayerEntity player, boolean all) {
            super(syncId, playerInventory, player);
            this.all = all;
            refresh();
        }

        @Override
        protected int pageSize() {
            return PAGE_SIZE;
        }

        @Override
        protected int itemCount() {
            return fellowships.size();
        }

        @Override
        protected boolean reload() {
            FellowshipState store = ProtectionManager.fellowships(player.getServer());
            fellowships = (all ? store.all() : store.forMember(player.getUuid())).stream()
                    .sorted(Comparator.comparing(Fellowship::name, String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(Fellowship::id))
                    .toList();
            // Never false: an empty list is still a list.
            return true;
        }

        @Override
        protected void renderPage() {
            MinecraftServer server = player.getServer();
            if (fellowships.isEmpty()) {
                return;
            }
            for (int row = 0; row < PAGE_SIZE; row++) {
                int index = page * PAGE_SIZE + row;
                if (index >= fellowships.size()) {
                    break;
                }
                Fellowship f = fellowships.get(index);
                int base = row * 9;
                inventory.setStack(base + ICON_COLUMN, icon(server, f));
                inventory.setStack(base + PVP_COLUMN, pvpStack(f));
                if (canLeave(f)) {
                    inventory.setStack(base + ACTION_COLUMN, leaveStack());
                }
            }
        }

        @Override
        protected void onContentClick(int row, int column) {
            int index = page * PAGE_SIZE + row;
            if (index >= fellowships.size()) {
                return;
            }
            Fellowship f = fellowships.get(index);
            if (column == ICON_COLUMN) {
                click();
                FellowshipInfoScreen.open(player, f, all);
                return;
            }
            if (column == PVP_COLUMN && canTogglePvp(f)) {
                togglePvp(f);
            } else if (column == ACTION_COLUMN && canLeave(f)) {
                click();
                FellowshipLeaveConfirmScreen.open(player, f, all);
                return;
            } else {
                return;
            }
            click();
            refreshOrClose();
        }

        // Any member but the owner, who hands over or disbands instead.
        private boolean canLeave(Fellowship f) {
            return f.isMember(player.getUuid()) && !f.owner().equals(player.getUuid());
        }

        // A member whose role allows it, or an op in the all view.
        private boolean canTogglePvp(Fellowship f) {
            if (all && !f.isMember(player.getUuid())) {
                return true;
            }
            Role role = f.roleOf(player.getUuid());
            return role != null && role.canTogglePvp();
        }

        private void togglePvp(Fellowship f) {
            boolean off = !f.pvpOff();
            FellowshipState store = ProtectionManager.fellowships(player.getServer());
            store.put(f.withPvpOff(off));
            store.markDirty();
            player.sendMessage(Text.literal("PvP in " + f.name()
                            + (off ? " enabled" : " disabled"))
                    .formatted(off ? Formatting.YELLOW : Formatting.GRAY), false);
        }
    }

    // --- creation ----------------------------------------------------------------------------------

    // Opens the create prompt, then reopens the list.
    public static void promptCreate(ServerPlayerEntity player, boolean all) {
        TextPromptScreen.open(player,
                Text.literal("Create fellowship").formatted(Formatting.DARK_GRAY),
                "", "Fellowship name",
                name -> {
                    create(player, name);
                    open(player, all);
                });
    }

    // An ordinary fellowship the caller owns; two may share a name.
    private static void create(ServerPlayerEntity player, String name) {
        FellowshipState fellowships = ProtectionManager.fellowships(player.getServer());
        Fellowship created = new Fellowship(fellowships.freshId(name), name, player.getUuid(), new ArrayList<>());
        fellowships.put(created);
        fellowships.markDirty();
        player.sendMessage(Text.literal("Created " + name).formatted(Formatting.GREEN), false);
    }

    // --- menu items ------------------------------------------------------------------------------

    private static ItemStack icon(MinecraftServer server, Fellowship f) {
        int memberCount = f.members().size() + 1;
        return Menus.stack(f.iconItem(), Menus.label(f.name(), Formatting.WHITE),
                List.of(Menus.kindLabel(f), Menus.label(memberCount + " member(s)", Formatting.DARK_GRAY)));
    }

    private static ItemStack pvpStack(Fellowship f) {
        boolean off = f.pvpOff();
        Item item = off ? Items.YELLOW_CONCRETE : Items.GRAY_CONCRETE;
        return Menus.stack(item, Menus.label("PvP " + (off ? "enabled" : "disabled"), off ? Formatting.YELLOW : Formatting.GRAY), List.of());
    }

    private static ItemStack leaveStack() {
        return Menus.stack(Items.RED_CONCRETE, Menus.label("Leave", Formatting.RED), List.of());
    }

}
