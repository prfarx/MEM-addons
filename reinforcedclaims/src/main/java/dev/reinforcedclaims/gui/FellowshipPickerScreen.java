package dev.reinforcedclaims.gui;

import dev.reinforcedclaims.fellowship.Fellowship;
import dev.reinforcedclaims.fellowship.FellowshipState;
import dev.reinforcedclaims.fellowship.Role;
import dev.reinforcedclaims.protection.AccessLocks;
import dev.reinforcedclaims.protection.ProtectionManager;
import dev.reinforcedclaims.util.Raycast;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

// Picks a fellowship, one row each: click the icon (/fs faction name collisions),
// or a rank-scope button (/clm assign).
public final class FellowshipPickerScreen {

    // One fellowship per row, minus the page-arrow row.
    private static final int PAGE_SIZE = Menus.NAV_ROW;

    private static final int ICON_COLUMN = 0;
    // Widest scope first; a column is empty when that scope isn't offered.
    private static final Role[] RANK_COLUMNS = {Role.GUEST, Role.MEMBER, Role.GUIDE, Role.OWNER, Role.ADMINISTRATOR};
    private static final int RANK_BASE_COLUMN = 1;

    private FellowshipPickerScreen() {
    }

    // One offered scope: a fellowship id and the rank it grants down to (GUEST = all members).
    public record Choice(String fellowship, Role minRole) {
    }

    // Asks which candidate was meant; click the icon to pick.
    public static void open(ServerPlayerEntity player, Text title, List<Fellowship> candidates,
                            Consumer<Fellowship> onPick) {
        List<Choice> choices = new ArrayList<>(candidates.size());
        for (Fellowship candidate : candidates) {
            choices.add(new Choice(candidate.id(), Role.GUEST));
        }
        openInternal(player, title, choices, (picked, scope) -> onPick.accept(picked), null, false);
    }

    // As above, with a row of rank buttons per fellowship. lock is the caller's
    // edit lock, released on close; null when not bound to a block.
    public static void openScoped(ServerPlayerEntity player, Text title, List<Choice> choices,
                                  BiConsumer<Fellowship, Role> onPick, AccessLocks.Key lock) {
        openInternal(player, title, choices, onPick, lock, true);
    }

    private static void openInternal(ServerPlayerEntity player, Text title, List<Choice> choices,
                                     BiConsumer<Fellowship, Role> onPick, AccessLocks.Key lock, boolean ranked) {
        List<Choice> offered = List.copyOf(choices);
        Menus.open(player, new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) -> new Handler(syncId, inventory, player, offered, onPick, lock, ranked),
                title));
    }

    // Disambiguates a typed name.
    public static void open(ServerPlayerEntity player, String query, List<Fellowship> candidates,
                            Consumer<Fellowship> onPick) {
        open(player, Text.literal("Which '" + query + "'?").formatted(Formatting.DARK_GRAY), candidates, onPick);
    }

    // --- screen handler ------------------------------------------------------------------------

    private static final class Handler extends PagedMenu {

        // A fellowship and the scopes still offered on it.
        private record Row(Fellowship fellowship, Set<Role> scopes) {
        }

        private final List<Choice> offered;
        private final BiConsumer<Fellowship, Role> onPick;
        private final AccessLocks.Key lock;
        // Whether rows show rank buttons; false when only Guest was offered.
        private final boolean ranked;
        private List<Row> rows = List.of();

        private Handler(int syncId, PlayerInventory playerInventory, ServerPlayerEntity player,
                        List<Choice> offered, BiConsumer<Fellowship, Role> onPick, AccessLocks.Key lock,
                        boolean ranked) {
            super(syncId, playerInventory, player);
            this.offered = offered;
            this.onPick = onPick;
            this.lock = lock;
            this.ranked = ranked;
            refresh();
        }

        // Frees the target for the next editor.
        @Override
        public void onClosed(PlayerEntity closing) {
            super.onClosed(closing);
            if (lock != null) {
                AccessLocks.release(lock, player.getUuid());
            }
        }

        // Closes once the player walks out of reach; a menu with no target stays open.
        @Override
        public boolean canUse(PlayerEntity clicker) {
            return lock == null
                    || (player.getWorld().getRegistryKey().equals(lock.world())
                    && player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(lock.pos())) <= Raycast.REACH * Raycast.REACH);
        }

        @Override
        protected int pageSize() {
            return PAGE_SIZE;
        }

        @Override
        protected int itemCount() {
            return rows.size();
        }

        // Groups the offered scopes by fellowship, re-read on every redraw.
        @Override
        protected boolean reload() {
            FellowshipState store = ProtectionManager.fellowships(player.getServer());
            Map<String, Set<Role>> byFellowship = new LinkedHashMap<>();
            for (Choice choice : offered) {
                byFellowship.computeIfAbsent(choice.fellowship(), id -> EnumSet.noneOf(Role.class)).add(choice.minRole());
            }
            List<Row> live = new ArrayList<>(byFellowship.size());
            for (Map.Entry<String, Set<Role>> entry : byFellowship.entrySet()) {
                Fellowship fellowship = store.get(entry.getKey());
                if (fellowship != null) {
                    live.add(new Row(fellowship, entry.getValue()));
                }
            }
            rows = live;
            return !rows.isEmpty();
        }

        @Override
        protected void renderPage() {
            MinecraftServer server = player.getServer();
            for (int row = 0; row < PAGE_SIZE; row++) {
                int index = page * PAGE_SIZE + row;
                if (index >= rows.size()) {
                    break;
                }
                Row entry = rows.get(index);
                int base = row * 9;
                inventory.setStack(base + ICON_COLUMN, icon(server, entry.fellowship()));
                if (!ranked) {
                    continue;
                }
                for (int i = 0; i < RANK_COLUMNS.length; i++) {
                    Role rank = RANK_COLUMNS[i];
                    if (entry.scopes().contains(rank)) {
                        inventory.setStack(base + RANK_BASE_COLUMN + i, rankStack(entry.fellowship(), rank));
                    }
                }
            }
        }

        @Override
        protected void onContentClick(int row, int column) {
            int index = page * PAGE_SIZE + row;
            if (index >= rows.size()) {
                return;
            }
            Row entry = rows.get(index);
            Role rank;
            if (ranked) {
                int i = column - RANK_BASE_COLUMN;
                if (i < 0 || i >= RANK_COLUMNS.length || !entry.scopes().contains(RANK_COLUMNS[i])) {
                    return;
                }
                rank = RANK_COLUMNS[i];
            } else {
                if (column != ICON_COLUMN) {
                    return;
                }
                rank = Role.GUEST;
            }
            click();
            // Resumed off the click, so the continuation may open its own menu.
            player.getServer().execute(() -> {
                ScreenHandler open = player.currentScreenHandler;
                onPick.accept(entry.fellowship(), rank);
                if (player.currentScreenHandler == open) {
                    player.closeHandledScreen();
                }
            });
        }
    }

    // --- menu items ------------------------------------------------------------------------------

    // The fellowship's icon, over enough roster to tell it from a namesake.
    private static ItemStack icon(MinecraftServer server, Fellowship f) {
        return Menus.stack(f.iconItem(), Menus.label(f.name(), Formatting.WHITE), List.of(Menus.kindLabel(f)));
    }

    // One rank button, coloured to match that rank elsewhere.
    private static ItemStack rankStack(Fellowship f, Role rank) {
        return Menus.stack(Items.PAPER, Menus.label(f.labelOf(rank) + "+", Menus.roleColour(rank)), List.of());
    }
}
