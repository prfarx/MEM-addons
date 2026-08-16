package dev.reinforcedclaims.gui;

import dev.reinforcedclaims.claim.ClaimManager;
import dev.reinforcedclaims.claim.ClaimState;
import dev.reinforcedclaims.fellowship.Fellowship;
import dev.reinforcedclaims.fellowship.FellowshipState;
import dev.reinforcedclaims.fellowship.Role;
import dev.reinforcedclaims.protection.AccessGrant;
import dev.reinforcedclaims.protection.AccessGrant.FellowshipGrant;
import dev.reinforcedclaims.protection.AccessGrant.PlayerGrant;
import dev.reinforcedclaims.protection.AccessLocks;
import dev.reinforcedclaims.protection.InteractionType;
import dev.reinforcedclaims.protection.ProtectionManager;
import dev.reinforcedclaims.reinforcement.Reinforcement;
import dev.reinforcedclaims.reinforcement.ReinforcementState;
import dev.reinforcedclaims.util.Players;
import dev.reinforcedclaims.util.Raycast;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// The /clm manage menu: one row per grantee, with a toggle per category, a revoke
// button, and the claim name and snitch log in the header.
public final class ClaimManageScreen {

    private static final int HEADER_ROW = 0;
    // The header's subject icon.
    private static final int HEADER_SLOT = HEADER_ROW * 9 + 3;
    // Name; claims only, owner only.
    private static final int NAME_SLOT = HEADER_ROW * 9 + 4;
    // Snitch log; shown once a jukebox is linked.
    private static final int LOG_SLOT = HEADER_ROW * 9 + 5;
    private static final int FIRST_GRANTEE_ROW = 1;
    private static final int PAGE_SIZE = Menus.NAV_ROW - FIRST_GRANTEE_ROW;

    private static final int ICON_COLUMN = 0;
    private static final int REVOKE_COLUMN = 8;
    private static final InteractionType[] CATEGORY_COLUMNS = {
            InteractionType.PLACE_BREAK, InteractionType.CONTAINER, InteractionType.DOOR,
            InteractionType.REDSTONE, InteractionType.ENDERCHEST, InteractionType.OTHER_INTERACT,
            InteractionType.MODIFY_PERMISSIONS
    };

    private ClaimManageScreen() {
    }

    // Opens the menu and takes the target's edit lock; false when somebody else holds it.
    public static boolean open(ServerPlayerEntity player, ClaimManager.AccessTarget target) {
        AccessLocks.Key lock = ClaimManager.lockKey(target);
        if (!AccessLocks.acquire(lock, player.getUuid())) {
            return false;
        }
        Menus.open(player, new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) -> new Handler(syncId, inventory, player, target.reinforcement(), target.pos(), lock),
                title(target)));
        return true;
    }

    // A named claim titles the menu with its name.
    private static Text title(ClaimManager.AccessTarget target) {
        if (target.reinforcement()) {
            return Text.literal("Manage Reinforcement").formatted(Formatting.DARK_GRAY);
        }
        ClaimState.Claim claim = ClaimManager.getState(target.world()).get(target.pos());
        String name = claim == null ? "" : claim.name();
        return Text.literal(name.isBlank() ? "Manage Claim" : "Manage " + name)
                .formatted(Formatting.DARK_GRAY);
    }

    // --- row model --------------------------------------------------------------------------------

    // fellowshipName is display-only; null on a player row.
    private record Row(boolean fellowship, String fellowshipId, Role minRole, String fellowshipName,
                       UUID playerId, String playerName, Set<InteractionType> allowed) {
    }

    // --- screen handler ------------------------------------------------------------------------

    private static final class Handler extends PagedMenu {

        private final boolean reinforcement;
        private final BlockPos pos;
        private final AccessLocks.Key lock;
        private ClaimManager.AccessTarget target;
        private List<Row> rows = List.of();

        private Handler(int syncId, PlayerInventory playerInventory, ServerPlayerEntity player, boolean reinforcement,
                        BlockPos pos, AccessLocks.Key lock) {
            super(syncId, playerInventory, player);
            this.reinforcement = reinforcement;
            this.pos = pos;
            this.lock = lock;
            refresh();
        }

        // Frees the target for the next editor.
        @Override
        public void onClosed(PlayerEntity closing) {
            super.onClosed(closing);
            AccessLocks.release(lock, player.getUuid());
        }

        @Override
        protected int pageSize() {
            return PAGE_SIZE;
        }

        @Override
        protected int itemCount() {
            return rows.size();
        }

        @Override
        protected boolean reload() {
            ServerWorld world = (ServerWorld) player.getWorld();
            target = ClaimManager.resolveTargetAt(world, pos, reinforcement);
            // Closes once the subject, or the viewer's MODIFY_PERMISSIONS, is gone.
            if (target == null || !ClaimManager.canModifyPermissions(player.getServer(), target, player.getUuid())) {
                return false;
            }
            MinecraftServer server = player.getServer();
            FellowshipState fellowships = ProtectionManager.fellowships(server);
            List<Row> list = new ArrayList<>();
            for (FellowshipGrant g : target.fellowshipGrants()) {
                Fellowship granted = fellowships.get(g.fellowship());
                // A grant can outlive its fellowship until the chunk next cycles.
                String label = granted != null
                        ? g.scopeLabel(granted.name(), granted.faction())
                        : g.fellowship();
                list.add(new Row(true, g.fellowship(), g.minRole(), label, null, null, g.allowed()));
            }
            for (PlayerGrant g : target.playerGrants()) {
                list.add(new Row(false, null, null, null, g.player(),
                        Players.name(server, g.player()), g.allowed()));
            }
            rows = list;
            return true;
        }

        @Override
        protected void renderPage() {
            inventory.setStack(HEADER_SLOT, headerStack());
            ClaimState.Claim claim = claim();
            if (claim != null && canRename()) {
                boolean named = !claim.name().isBlank();
                inventory.setStack(NAME_SLOT, Menus.stack(Items.NAME_TAG,
                        Menus.label(named ? "Rename" : "Name", Formatting.YELLOW), List.of()));
            }
            if (claim != null && claim.snitch()) {
                inventory.setStack(LOG_SLOT, Menus.stack(Items.WRITTEN_BOOK,
                        Menus.label("Snitch Log", Formatting.WHITE),
                        List.of()));
            }
            MinecraftServer server = player.getServer();
            for (int row = 0; row < PAGE_SIZE; row++) {
                int index = page * PAGE_SIZE + row;
                if (index >= rows.size()) {
                    break;
                }
                Row r = rows.get(index);
                int base = (FIRST_GRANTEE_ROW + row) * 9;
                inventory.setStack(base + ICON_COLUMN, icon(server, r));
                for (int c = 0; c < CATEGORY_COLUMNS.length; c++) {
                    inventory.setStack(base + 1 + c, categoryStack(CATEGORY_COLUMNS[c], r.allowed().contains(CATEGORY_COLUMNS[c])));
                }
                inventory.setStack(base + REVOKE_COLUMN, Menus.stack(Items.BARRIER, Menus.label("Revoke", Formatting.RED),
                        List.of(Menus.label("Revoke " + granteeName(r) + " permissions", Formatting.DARK_GRAY))));
            }
        }

        @Override
        protected void onContentClick(int row, int column) {
            if (row == HEADER_ROW) {
                onHeaderClick(row * 9 + column);
                return;
            }
            int index = page * PAGE_SIZE + (row - FIRST_GRANTEE_ROW);
            if (index >= rows.size()) {
                return;
            }
            Row r = rows.get(index);
            if (column == REVOKE_COLUMN) {
                revoke(r);
            } else if (column >= 1 && column <= CATEGORY_COLUMNS.length) {
                toggle(r, CATEGORY_COLUMNS[column - 1]);
            } else if (column == ICON_COLUMN && r.fellowship()) {
                cycleRank(r);
            } else {
                return;
            }
            click();
            refreshOrClose();
        }

        // The claim being managed, or null when the subject is a reinforcement.
        private ClaimState.Claim claim() {
            return reinforcement ? null : ClaimManager.getState((ServerWorld) player.getWorld()).get(pos);
        }

        // Renaming is the owner's alone; MODIFY_PERMISSIONS doesn't grant it.
        private boolean canRename() {
            return ClaimManager.canRenameClaim((ServerWorld) player.getWorld(), pos, player.getUuid());
        }

        private void onHeaderClick(int slot) {
            ClaimState.Claim claim = claim();
            if (claim == null) {
                return;
            }
            // Acted off the click: each replaces this screen, which mustn't happen mid-click.
            if (slot == NAME_SLOT && canRename()) {
                click();
                player.getServer().execute(() -> promptName(claim));
            } else if (slot == LOG_SLOT && claim.snitch()) {
                click();
                player.getServer().execute(() -> SnitchLogBook.open(player, claim.name(),
                        ClaimManager.snitchLogs((ServerWorld) player.getWorld(), pos)));
            }
        }

        // Asks for a name, then reopens this menu. The prompt drops the edit lock.
        private void promptName(ClaimState.Claim claim) {
            ClaimManager.AccessTarget reopenOn = target;
            boolean named = !claim.name().isBlank();
            TextPromptScreen.open(player,
                    Text.literal("Name this claim").formatted(Formatting.DARK_GRAY),
                    named ? claim.name() : "",
                    named ? null : "Claim",
                    named ? "Clear the name" : null,
                    typed -> {
                        Players.sendIfPresent(player, ClaimManager.setClaimName(player,
                                (ServerWorld) player.getWorld(), pos, typed), false);
                        ClaimManageScreen.open(player, reopenOn);
                    });
        }

        // Narrows a fellowship grant one rank at a time, then wraps round.
        private void cycleRank(Row r) {
            Fellowship f = ProtectionManager.fellowships(player.getServer()).get(r.fellowshipId());
            Role next = r.minRole();
            // Skips ranks this fellowship can't hold, and scopes another row already has.
            for (int step = 0; step < Role.grantScopes(f != null && f.faction()).size(); step++) {
                next = nextRank(next, f != null && f.faction());
                Role candidate = next;
                boolean taken = target.fellowshipGrants().stream()
                        .anyMatch(g -> g.sameScope(r.fellowshipId(), candidate));
                if (!taken) {
                    save(AccessGrant.rescopeFellowship(target.fellowshipGrants(), r.fellowshipId(),
                            r.minRole(), next), target.playerGrants());
                    return;
                }
            }
        }

        // GUEST -> MEMBER -> GUIDE -> OWNER -> [ADMINISTRATOR] -> back round.
        private static Role nextRank(Role current, boolean faction) {
            List<Role> ladder = Role.grantScopes(faction);
            int i = ladder.indexOf(current);
            return i < 0 ? Role.GUEST : ladder.get((i + 1) % ladder.size());
        }

        // Closes once the player walks out of reach.
        @Override
        public boolean canUse(PlayerEntity clicker) {
            return player.getWorld().getRegistryKey().equals(lock.world())
                    && player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) <= Raycast.REACH * Raycast.REACH;
        }

        private void toggle(Row r, InteractionType type) {
            if (r.fellowship()) {
                save(AccessGrant.toggleFellowshipCategory(target.fellowshipGrants(), r.fellowshipId(), r.minRole(), type),
                        target.playerGrants());
            } else {
                save(target.fellowshipGrants(), AccessGrant.togglePlayerCategory(target.playerGrants(), r.playerId(), type));
            }
        }

        private void revoke(Row r) {
            if (r.fellowship()) {
                save(AccessGrant.removeFellowship(target.fellowshipGrants(), r.fellowshipId(), r.minRole()),
                        target.playerGrants());
            } else {
                save(target.fellowshipGrants(), AccessGrant.removePlayer(target.playerGrants(), r.playerId()));
            }
        }

        // Writes an edit back, unless it would revoke the editor's own access to edit.
        private void save(List<FellowshipGrant> fellowshipGrants, List<PlayerGrant> playerGrants) {
            if (ClaimManager.revokesOwnModifyAccess(player.getServer(), target, player.getUuid(), fellowshipGrants, playerGrants)) {
                return;
            }
            target.save(fellowshipGrants, playerGrants);
        }

        private ItemStack headerStack() {
            if (reinforcement) {
                ReinforcementState store = ProtectionManager.reinforcements((ServerWorld) player.getWorld());
                Reinforcement rec = store.get(pos);
                String hp = rec != null ? rec.healthString() : "?";
                String owner = rec != null && rec.owner().isPresent() ? Players.name(player.getServer(), rec.owner().get()) : "unknown";
                return Menus.stack(Items.SHIELD, Menus.label("Reinforcement", Formatting.WHITE),
                        List.of(Menus.label(owner, Formatting.DARK_GRAY),
                                Menus.label(hp, Formatting.DARK_GRAY)));
            }
            ClaimState state = ClaimManager.getState((ServerWorld) player.getWorld());
            ClaimState.Claim claim = state.get(pos);
            String owner = claim != null ? Players.name(player.getServer(), claim.owner()) : "unknown";
            List<Text> lore = new ArrayList<>();
            if (claim != null && !claim.name().isBlank()) {
                lore.add(Menus.label(claim.name(), Formatting.DARK_GRAY));
            }
            lore.add(Menus.label(owner, Formatting.DARK_GRAY));
            return Menus.stack(Items.BEACON, Menus.label("Claim", Formatting.WHITE), lore);
        }
    }

    // --- menu items ------------------------------------------------------------------------------

    // How a row's grantee reads in a button's lore.
    private static String granteeName(Row r) {
        if (r.fellowship()) {
            return r.fellowshipName();
        }
        return r.playerName() != null ? r.playerName() : "this player";
    }

    private static ItemStack icon(MinecraftServer server, Row r) {
        if (r.fellowship()) {
            Fellowship fellowship = ProtectionManager.fellowships(server).get(r.fellowshipId());
            Item item = fellowship != null ? fellowship.iconItem() : Items.SHIELD;
            boolean faction = fellowship != null && fellowship.faction();
            List<Text> lore = new ArrayList<>();
            lore.add(fellowship != null ? Menus.kindLabel(fellowship)
                    : Menus.label("Fellowship", Formatting.DARK_GRAY));
            if (r.minRole() != Role.GUEST) {
                lore.add(Menus.label("Restricted to " + r.minRole().label(faction) + "+", Formatting.DARK_GRAY));
            }
            return Menus.stack(item, Menus.label(r.fellowshipName(), Menus.roleColour(r.minRole())), lore);
        }
        return Menus.head(r.playerId(), r.playerName(), Formatting.WHITE, List.of(Menus.label("Player", Formatting.DARK_GRAY)));
    }

    private static ItemStack categoryStack(InteractionType type, boolean on) {
        Item item = on ? Items.GREEN_CONCRETE : Items.GRAY_CONCRETE;
        return Menus.stack(item, Menus.label(label(type), on ? Formatting.GREEN : Formatting.GRAY),
                List.of(Menus.label(on ? "Revoke" : "Grant", Formatting.DARK_GRAY)));
    }

    private static String label(InteractionType type) {
        return switch (type) {
            case PLACE_BREAK -> "Building";
            case CONTAINER -> "Containers";
            case DOOR -> "Doors";
            case REDSTONE -> "Redstone";
            case ENDERCHEST -> "Ender Chest";
            case OTHER_INTERACT -> "Other";
            case MODIFY_PERMISSIONS -> "Modify Permissions";
        };
    }
}
