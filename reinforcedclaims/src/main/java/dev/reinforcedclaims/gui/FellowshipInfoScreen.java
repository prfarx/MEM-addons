package dev.reinforcedclaims.gui;

import com.mojang.authlib.GameProfile;
import dev.reinforcedclaims.claim.ClaimManager;
import dev.reinforcedclaims.fellowship.FellowshipInvite;
import dev.reinforcedclaims.fellowship.FellowshipState;
import dev.reinforcedclaims.fellowship.PlayerFactionData;
import dev.reinforcedclaims.protection.ProtectionManager;
import dev.reinforcedclaims.protection.ProtectionModes;
import dev.reinforcedclaims.fellowship.Fellowship;
import dev.reinforcedclaims.fellowship.Role;
import dev.reinforcedclaims.util.Players;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// A fellowship's roster: rename/icon/invite/disband buttons and per-row promote/demote/kick.
public final class FellowshipInfoScreen {

    // Back to the list this was opened from.
    private static final int BACK_SLOT = 0;
    // The icon, which doubles as the set-icon button.
    private static final int ICON_SLOT = 4;
    // Rename.
    private static final int RENAME_SLOT = 5;
    // Invite; shown only to whoever may.
    private static final int INVITE_SLOT = 7;
    // Disband; shown only to whoever may.
    private static final int DISBAND_SLOT = 8;
    // Member rows start here; row 2 is blank.
    private static final int FIRST_MEMBER_ROW = 2;
    private static final int PAGE_SIZE = Menus.NAV_ROW - FIRST_MEMBER_ROW;

    private static final int HEAD_COLUMN = 0;
    private static final int PROMOTE_COLUMN = 5;
    private static final int DEMOTE_COLUMN = 8;

    private FellowshipInfoScreen() {
    }

    // Opens the roster; all says which list Back returns to.
    public static void open(ServerPlayerEntity player, Fellowship fellowship, boolean all) {
        Menus.open(player, new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) -> new Handler(syncId, inventory, player, fellowship.id(), all),
                Text.literal(fellowship.name()).formatted(Formatting.DARK_GRAY)));
    }

    // --- screen handler ------------------------------------------------------------------------

    private static final class Handler extends PagedMenu {

        private final String key;
        private final boolean all;
        private Fellowship fellowship;
        private List<UUID> roster = List.of();

        private Handler(int syncId, PlayerInventory playerInventory, ServerPlayerEntity player, String key,
                        boolean all) {
            super(syncId, playerInventory, player);
            this.key = key;
            this.all = all;
            refresh();
        }

        @Override
        protected int pageSize() {
            return PAGE_SIZE;
        }

        @Override
        protected int itemCount() {
            return roster.size();
        }

        @Override
        protected boolean reload() {
            fellowship = ProtectionManager.fellowships(player.getServer()).get(key);
            if (fellowship == null) {
                return false;
            }
            roster = new ArrayList<>();
            roster.add(fellowship.owner());
            for (Fellowship.Member member : fellowship.members()) {
                roster.add(member.id());
            }
            return true;
        }

        @Override
        protected void renderPage() {
            MinecraftServer server = player.getServer();
            inventory.setStack(BACK_SLOT, Menus.stack(Items.ARROW, Menus.label("◀ Back", Formatting.WHITE),
                    List.of(Menus.label("Fellowships list", Formatting.DARK_GRAY))));
            List<Text> lore = new ArrayList<>();
            lore.add(Menus.label(roster.size() + " member(s)", Formatting.DARK_GRAY));
            if (fellowship.faction()) {
                lore.add(Menus.label("Faction", Formatting.DARK_GRAY));
            }
            boolean identity = canEditIdentity();
            if (identity) {
                lore.add(Menus.label("Change icon", Formatting.DARK_GRAY));
            }
            inventory.setStack(ICON_SLOT, Menus.stack(fellowship.iconItem(),
                    Menus.label(fellowship.name(), Formatting.WHITE), lore));
            if (identity) {
                inventory.setStack(RENAME_SLOT, Menus.stack(Items.NAME_TAG,
                        Menus.label("Rename", Formatting.YELLOW),
                        List.of()));
            }
            if (canInvite()) {
                inventory.setStack(INVITE_SLOT, Menus.stack(Items.WRITABLE_BOOK,
                        Menus.label("Invite", Formatting.GREEN),
                        List.of()));
            }
            if (canDisband()) {
                inventory.setStack(DISBAND_SLOT, Menus.stack(Items.RED_CONCRETE,
                        Menus.label("Disband", Formatting.RED),
                        List.of()));
            }

            Role viewerRole = fellowship.roleOf(player.getUuid());
            for (int row = 0; row < PAGE_SIZE; row++) {
                int index = page * PAGE_SIZE + row;
                if (index >= roster.size()) {
                    break;
                }
                UUID id = roster.get(index);
                Role role = fellowship.roleOf(id);
                int base = (FIRST_MEMBER_ROW + row) * 9;
                inventory.setStack(base + HEAD_COLUMN, Menus.head(id, Players.name(server, id), Menus.roleColour(role),
                        List.of(Menus.label(fellowship.labelOf(role), Menus.roleColour(role)))));
                if (viewerRole != null && !id.equals(player.getUuid())) {
                    if (viewerRole.canPromote(role, fellowship.faction())) {
                        inventory.setStack(base + PROMOTE_COLUMN, promoteStack(fellowship, role));
                    }
                    if (viewerRole.canKick(role)) {
                        inventory.setStack(base + DEMOTE_COLUMN, demoteStack(fellowship, role));
                    }
                }
            }
        }

        @Override
        protected void onContentClick(int row, int column) {
            int slot = row * 9 + column;
            if (slot == BACK_SLOT) {
                click();
                FellowshipListScreen.open(player, all);
                return;
            }
            if (slot == ICON_SLOT && canEditIdentity()) {
                click();
                setIcon();
                refreshOrClose();
                return;
            }
            if (slot == RENAME_SLOT && canEditIdentity()) {
                click();
                promptRename();
                return;
            }
            if (slot == INVITE_SLOT && canInvite()) {
                click();
                promptInvite();
                return;
            }
            if (slot == DISBAND_SLOT && canDisband()) {
                click();
                FellowshipDisbandConfirmScreen.open(player, fellowship, all);
                return;
            }
            int memberRow = row - FIRST_MEMBER_ROW;
            if (memberRow < 0 || memberRow >= PAGE_SIZE) {
                return;
            }
            int index = page * PAGE_SIZE + memberRow;
            if (index >= roster.size()) {
                return;
            }
            UUID id = roster.get(index);
            Role role = fellowship.roleOf(id);
            Role viewerRole = fellowship.roleOf(player.getUuid());
            if (viewerRole == null || id.equals(player.getUuid())) {
                return;
            }
            if (column == PROMOTE_COLUMN && viewerRole.canPromote(role, fellowship.faction())) {
                Role next = Role.promoted(role);
                if (isHandover(fellowship, next)) {
                    click();
                    FellowshipTransferConfirmScreen.open(player, fellowship, id, all);
                    return;
                }
                setRole(id, next);
            } else if (column == DEMOTE_COLUMN && viewerRole.canKick(role)) {
                Role demoted = Role.demoted(role);
                if (demoted == null) {
                    kick(id);
                } else {
                    setRole(id, demoted);
                }
            } else {
                return;
            }
            click();
            refreshOrClose();
        }

        // Name and icon belong to whoever created the fellowship; bypass always may.
        private boolean canEditIdentity() {
            if (ProtectionModes.isBypassing(player.getUuid())) {
                return true;
            }
            return fellowship.roleOf(player.getUuid()) == fellowship.topRole();
        }

        // Adopts the held item as the icon; an empty hand resets it.
        private void setIcon() {
            ItemStack held = player.getMainHandStack();
            FellowshipState store = ProtectionManager.fellowships(player.getServer());
            fellowship = fellowship.withIcon(held.isEmpty() ? "" : Registries.ITEM.getId(held.getItem()).toString());
            store.put(fellowship);
            store.markDirty();
        }

        // Asks for the new name, then returns to this roster.
        private void promptRename() {
            String id = fellowship.id();
            boolean fromAll = all;
            TextPromptScreen.open(player,
                    Text.literal("Rename " + fellowship.name()).formatted(Formatting.DARK_GRAY),
                    fellowship.name(),
                    typed -> {
                        rename(id, typed);
                        reopen(id, fromAll);
                    });
        }

        // Back to this roster once a prompt is answered.
        private void reopen(String fellowshipId, boolean fromAll) {
            Fellowship live = ProtectionManager.fellowships(player.getServer()).get(fellowshipId);
            if (live != null) {
                open(player, live, fromAll);
            }
        }

        // Applies the new name, re-reading the fellowship in case it changed while the prompt was open.
        private void rename(String fellowshipId, String name) {
            FellowshipState store = ProtectionManager.fellowships(player.getServer());
            Fellowship live = store.get(fellowshipId);
            if (live == null) {
                return;
            }
            // Names are parsed as Brigadier quoted strings, so only " and \ are refused.
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (c == '"' || c == '\\') {
                    player.sendMessage(Text.literal("Fellowship names cannot contain \" or \\").formatted(Formatting.RED), false);
                    return;
                }
            }
            if (name.equals(live.name())) {
                return;
            }
            String was = live.name();
            store.put(live.withName(name));
            store.markDirty();
            player.sendMessage(Text.literal(was + " renamed to " + name)
                    .formatted(Formatting.GREEN), false);
        }

        // Guide and above; bypass always may.
        private boolean canInvite() {
            if (ProtectionModes.isBypassing(player.getUuid())) {
                return true;
            }
            Role role = fellowship.roleOf(player.getUuid());
            return role != null && role.canInvite();
        }

        // Asks for a name, then invites that player, online or not.
        private void promptInvite() {
            String id = fellowship.id();
            boolean fromAll = all;
            TextPromptScreen.open(player,
                    Text.literal("Invite to '" + fellowship.name() + "'").formatted(Formatting.DARK_GRAY),
                    "", "Invite",
                    name -> {
                        Players.sendIfPresent(player, invite(id, name), false);
                        reopen(id, fromAll);
                    });
        }

        // Re-reads the fellowship: it may have changed while the prompt was open.
        private Text invite(String fellowshipId, String name) {
            MinecraftServer server = player.getServer();
            Fellowship live = ProtectionManager.fellowships(server).get(fellowshipId);
            if (live == null) {
                return Text.literal("");
            }
            GameProfile target = Players.profile(server, name);
            if (target == null) {
                return Players.unknownPlayer(name);
            }
            if (target.getId().equals(player.getUuid()) || live.isMember(target.getId())) {
                return Text.literal((target.getId().equals(player.getUuid()) ? "You are" : target.getName() + " is")
                        + " already in " + live.name()).formatted(Formatting.GRAY);
            }
            Text wrongFaction = PlayerFactionData.refusal(live, target.getId());
            if (wrongFaction != null) {
                return wrongFaction;
            }
            // Recorded against the invitee; the display name is snapshotted beside the id.
            FellowshipInvite replaced = ProtectionManager.invites(server).put(target.getId(),
                    new FellowshipInvite(live.id(), live.name(), player.getUuid(),
                            player.getGameProfile().getName(), System.currentTimeMillis()));
            ServerPlayerEntity online = server.getPlayerManager().getPlayer(target.getId());
            if (online != null) {
                InvitePrompt.notifyPending(online);
            }
            return Text.literal((replaced == null ? "Invited " : "Re-invited ")
                    + target.getName() + " to " + live.name()).formatted(Formatting.GREEN);
        }

        // The Owner, or a faction's Administrator but not its Leaders; bypass always may.
        private boolean canDisband() {
            if (ProtectionModes.isBypassing(player.getUuid())) {
                return true;
            }
            Role role = fellowship.roleOf(player.getUuid());
            return role != null && role.canDisband(fellowship.faction());
        }

        private void setRole(UUID id, Role newRole) {
            List<Fellowship.Member> members = new ArrayList<>(fellowship.members());
            members.replaceAll(m -> m.id().equals(id) ? new Fellowship.Member(id, newRole) : m);
            save(members);
            player.sendMessage(Text.literal(Players.name(player.getServer(), id) + " assigned "
                    + fellowship.labelOf(newRole) + " in " + fellowship.name()).formatted(Formatting.GREEN), false);
        }

        private void kick(UUID id) {
            List<Fellowship.Member> members = new ArrayList<>(fellowship.members());
            members.removeIf(m -> m.id().equals(id));
            save(members);
            // Only on a kick: drops grants naming a fellowship the owner has left.
            ClaimManager.pruneStaleGrants(player.getServer(), fellowship.id());
            String name = Players.name(player.getServer(), id);
            player.sendMessage(Text.literal("Removed " + name + " from '" + fellowship.name() + "'")
                    .formatted(Formatting.GREEN), false);
            ServerPlayerEntity online = player.getServer().getPlayerManager().getPlayer(id);
            if (online != null) {
                online.sendMessage(Text.literal("You were removed from '" + fellowship.name() + "'").formatted(Formatting.RED), false);
            }
        }

        private void save(List<Fellowship.Member> members) {
            FellowshipState store = ProtectionManager.fellowships(player.getServer());
            fellowship = fellowship.withMembers(members);
            store.put(fellowship);
            store.markDirty();
        }
    }

    // Whether this promotion hands the fellowship over: only Owner, outside a faction.
    private static boolean isHandover(Fellowship fellowship, Role next) {
        return next == Role.OWNER && !fellowship.faction();
    }

    private static ItemStack promoteStack(Fellowship fellowship, Role current) {
        Role next = Role.promoted(current);
        List<Text> lore = isHandover(fellowship, next)
                ? List.of(Menus.label("Transfers fellowship", Formatting.YELLOW))
                : List.of();
        return Menus.stack(Items.LIME_DYE, Menus.label("Promote to " + fellowship.labelOf(next), Formatting.GREEN), lore);
    }

    private static ItemStack demoteStack(Fellowship fellowship, Role current) {
        Role next = Role.demoted(current);
        if (next == null) {
            return Menus.stack(Items.BARRIER, Menus.label("Kick", Formatting.RED),
                    List.of());
        }
        return Menus.stack(Items.GRAY_DYE, Menus.label("Demote to " + fellowship.labelOf(next), Formatting.YELLOW),
                List.of());
    }
}
