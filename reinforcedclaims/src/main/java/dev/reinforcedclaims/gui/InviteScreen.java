package dev.reinforcedclaims.gui;

import dev.reinforcedclaims.fellowship.FellowshipInvite;
import dev.reinforcedclaims.fellowship.FellowshipState;
import dev.reinforcedclaims.fellowship.PlayerFactionData;
import dev.reinforcedclaims.fellowship.Role;
import dev.reinforcedclaims.protection.ProtectionManager;
import dev.reinforcedclaims.fellowship.Fellowship;
import dev.reinforcedclaims.util.RelativeTime;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

// The /fs invites menu: one invite per row, with accept and decline buttons.
public final class InviteScreen {

    // One invite per row, minus the page-arrow row.
    private static final int PAGE_SIZE = Menus.NAV_ROW;

    // Columns each part of a row sits in.
    private static final int HEAD_COLUMN = 0;
    private static final int ICON_COLUMN = 1;
    private static final int ACCEPT_COLUMN = 5;
    private static final int DECLINE_COLUMN = 8;

    private InviteScreen() {
    }

    // Opens the menu; an empty list stands open rather than refusing.
    public static void open(ServerPlayerEntity player) {
        Menus.open(player, new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) -> new Handler(syncId, inventory, player),
                Text.literal("Fellowship Invites").formatted(Formatting.DARK_GRAY)));
    }

    // --- screen handler ------------------------------------------------------------------------

    private static final class Handler extends PagedMenu {

        private List<FellowshipInvite> invites = List.of();

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
            invites = ProtectionManager.invites(player.getServer()).invitesFor(player.getUuid());
            // Never false: an empty list is still a list.
            return true;
        }

        @Override
        protected void renderPage() {
            for (int row = 0; row < PAGE_SIZE; row++) {
                int index = page * PAGE_SIZE + row;
                if (index >= invites.size()) {
                    break;
                }
                FellowshipInvite invite = invites.get(index);
                int base = row * 9;
                inventory.setStack(base + HEAD_COLUMN, head(player.getServer(), invite));
                inventory.setStack(base + ICON_COLUMN, icon(player.getServer(), invite));
                inventory.setStack(base + ACCEPT_COLUMN, button(Items.LIME_CONCRETE, "Join", Formatting.GREEN,
                        "Join " + invite.fellowship()));
                inventory.setStack(base + DECLINE_COLUMN, button(Items.RED_CONCRETE, "Decline", Formatting.RED,
                        "Decline " + invite.fellowship()));
            }
        }

        @Override
        protected void onContentClick(int row, int column) {
            if (column != ACCEPT_COLUMN && column != DECLINE_COLUMN) {
                return;
            }
            int index = page * PAGE_SIZE + row;
            if (index >= invites.size()) {
                return;
            }
            FellowshipInvite invite = invites.get(index);
            if (column == ACCEPT_COLUMN) {
                accept(invite);
            } else {
                decline(invite);
            }
            refreshOrClose();
        }

        private void accept(FellowshipInvite invite) {
            MinecraftServer server = player.getServer();
            FellowshipState fellowships = ProtectionManager.fellowships(server);
            Fellowship fellowship = fellowships.get(invite.key());
            if (fellowship == null) {
                ProtectionManager.invites(server).remove(player.getUuid(), invite.key());
                return;
            }
            // Rechecked on accept: a faction change may have invalidated it since.
            Text wrongFaction = PlayerFactionData.refusal(fellowship, player.getUuid());
            if (wrongFaction != null) {
                player.sendMessage(wrongFaction, false);
                return;
            }
            ProtectionManager.invites(server).remove(player.getUuid(), invite.key());
            if (!fellowship.isMember(player.getUuid())) {
                List<Fellowship.Member> members = new ArrayList<>(fellowship.members());
                members.add(new Fellowship.Member(player.getUuid(), Role.GUEST));
                fellowships.put(fellowship.withMembers(members));
                fellowships.markDirty();
            }
            player.sendMessage(Text.literal("Joined " + fellowship.name()).formatted(Formatting.GREEN), false);
            player.playSoundToPlayer(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.MASTER, 0.7f, 1.4f);
            tellInviter(invite, " accepted the invite to ", Formatting.GREEN);
        }

        private void decline(FellowshipInvite invite) {
            ProtectionManager.invites(player.getServer()).remove(player.getUuid(), invite.key());
            click();
            tellInviter(invite, " declined the invite to ", Formatting.RED);
        }

        private void tellInviter(FellowshipInvite invite, String verb, Formatting colour) {
            ServerPlayerEntity inviter = player.getServer().getPlayerManager().getPlayer(invite.inviter());
            if (inviter != null) {
                inviter.sendMessage(Text.literal(player.getGameProfile().getName() + verb + invite.fellowship())
                        .formatted(colour), false);
            }
        }
    }

    // --- menu items ----------------------------------------------------------------------------

    // The sender's head, coloured by their current rank.
    private static ItemStack head(MinecraftServer server, FellowshipInvite invite) {
        Fellowship fellowship = Invites.fellowshipOf(server, invite);
        Role effective = Invites.senderRole(fellowship, invite.inviter());
        return Menus.head(invite.inviter(), invite.inviterName(), Menus.roleColour(effective),
                List.of(Menus.label("Invited you to " + invite.fellowship(), Formatting.DARK_GRAY),
                        Menus.label(Invites.rankLabel(fellowship, effective), Menus.roleColour(effective)),
                        Menus.label("Sent " + RelativeTime.verbose(invite.sent()), Formatting.DARK_GRAY)));
    }

    private static ItemStack icon(MinecraftServer server, FellowshipInvite invite) {
        return Invites.icon(Invites.fellowshipOf(server, invite), invite);
    }

    private static ItemStack button(Item item, String name, Formatting colour, String hint) {
        return Menus.stack(item, Menus.label(name, colour), List.of(Menus.label(hint, Formatting.DARK_GRAY)));
    }
}
