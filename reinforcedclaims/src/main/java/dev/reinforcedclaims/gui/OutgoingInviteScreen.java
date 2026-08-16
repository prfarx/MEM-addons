package dev.reinforcedclaims.gui;

import dev.reinforcedclaims.fellowship.Fellowship;
import dev.reinforcedclaims.fellowship.FellowshipInvite;
import dev.reinforcedclaims.fellowship.InviteState;
import dev.reinforcedclaims.fellowship.Role;
import dev.reinforcedclaims.protection.ProtectionManager;
import dev.reinforcedclaims.protection.ProtectionModes;
import dev.reinforcedclaims.util.Players;
import dev.reinforcedclaims.util.RelativeTime;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

// The /fs uninvite menu: unanswered invites the viewer may withdraw, one per row.
public final class OutgoingInviteScreen {

    // One invite per row, minus the page-arrow row.
    private static final int PAGE_SIZE = Menus.NAV_ROW;

    private static final int HEAD_COLUMN = 0;
    private static final int ICON_COLUMN = 1;
    private static final int WITHDRAW_COLUMN = 8;

    private OutgoingInviteScreen() {
    }

    // Opens the menu; an empty list stands open rather than refusing.
    public static void open(ServerPlayerEntity player) {
        Menus.open(player, new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) -> new Handler(syncId, inventory, player),
                Text.literal("Pending Invites").formatted(Formatting.DARK_GRAY)));
    }

    // Unanswered invites to fellowships this player may invite to; bypass widens it server-wide.
    private static List<InviteState.Outgoing> pending(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();
        boolean bypass = ProtectionModes.isBypassing(player.getUuid());
        Set<String> reachable = new HashSet<>();
        for (Fellowship fellowship : bypass
                ? ProtectionManager.fellowships(server).all()
                : ProtectionManager.fellowships(server).forMember(player.getUuid())) {
            Role role = fellowship.roleOf(player.getUuid());
            if (bypass || (role != null && role.canInvite())) {
                reachable.add(fellowship.id());
            }
        }
        return ProtectionManager.invites(server).outgoing(reachable);
    }

    // --- screen handler ------------------------------------------------------------------------

    private static final class Handler extends PagedMenu {

        private List<InviteState.Outgoing> invites = List.of();

        private Handler(int syncId, PlayerInventory playerInventory, ServerPlayerEntity player) {
            super(syncId, playerInventory, player);
            refresh();
        }

        @Override
        protected int pageSize() {
            return PAGE_SIZE;
        }

        @Override
        protected int itemCount() {
            return invites.size();
        }

        @Override
        protected boolean reload() {
            invites = pending(player);
            // Never false: an empty list is still a list.
            return true;
        }

        @Override
        protected void renderPage() {
            MinecraftServer server = player.getServer();
            for (int row = 0; row < PAGE_SIZE; row++) {
                int index = page * PAGE_SIZE + row;
                if (index >= invites.size()) {
                    break;
                }
                InviteState.Outgoing outgoing = invites.get(index);
                int base = row * 9;
                inventory.setStack(base + HEAD_COLUMN, head(server, outgoing));
                inventory.setStack(base + ICON_COLUMN, icon(server, outgoing.invite()));
                inventory.setStack(base + WITHDRAW_COLUMN, Menus.stack(Items.RED_CONCRETE,
                        Menus.label("Withdraw", Formatting.RED),
                        List.of()));
            }
        }

        @Override
        protected void onContentClick(int row, int column) {
            if (column != WITHDRAW_COLUMN) {
                return;
            }
            int index = page * PAGE_SIZE + row;
            if (index >= invites.size()) {
                return;
            }
            InviteState.Outgoing outgoing = invites.get(index);
            withdraw(outgoing);
            click();
            refreshOrClose();
        }

        private void withdraw(InviteState.Outgoing outgoing) {
            MinecraftServer server = player.getServer();
            FellowshipInvite removed = ProtectionManager.invites(server)
                    .remove(outgoing.player(), outgoing.invite().key());
            if (removed == null) {
                // Answered while the menu sat open.
                return;
            }
            String name = Players.name(server, outgoing.player());
            player.sendMessage(Text.literal("Withdrew " + (name == null ? "that" : name + "'s")
                    + " invite to " + removed.fellowship()).formatted(Formatting.GRAY), false);
        }
    }

    // --- menu items ----------------------------------------------------------------------------

    // The invitee's head, with who sent it and when.
    private static ItemStack head(MinecraftServer server, InviteState.Outgoing outgoing) {
        FellowshipInvite invite = outgoing.invite();
        String name = Players.name(server, outgoing.player());
        Fellowship fellowship = Invites.fellowshipOf(server, invite);
        Role effective = Invites.senderRole(fellowship, invite.inviter());
        return Menus.head(outgoing.player(), name == null ? "Unknown player" : name, Formatting.WHITE,
                List.of(Menus.label("Invited to " + invite.fellowship(), Formatting.DARK_GRAY),
                        Menus.label("By " + invite.inviterName(), Menus.roleColour(effective)),
                        Menus.label(Invites.rankLabel(fellowship, effective), Menus.roleColour(effective)),
                        Menus.label("Sent " + RelativeTime.verbose(invite.sent()),
                                Formatting.DARK_GRAY)));
    }

    private static ItemStack icon(MinecraftServer server, FellowshipInvite invite) {
        return Invites.icon(Invites.fellowshipOf(server, invite), invite);
    }
}
