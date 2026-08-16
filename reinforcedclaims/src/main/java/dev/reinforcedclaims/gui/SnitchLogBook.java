package dev.reinforcedclaims.gui;

import dev.reinforcedclaims.claim.LogEntry;
import dev.reinforcedclaims.util.RelativeTime;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.OpenWrittenBookS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.RawFilteredPair;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;

import java.util.ArrayList;
import java.util.List;

// A snitch's log, shown as a written book the client is told it holds.
// The server inventory is never touched; a resync puts the real item back.
public final class SnitchLogBook {

    // Entries per page; each takes two lines.
    private static final int ENTRIES_PER_PAGE = 6;

    private SnitchLogBook() {
    }

    // Replaces whatever menu is open with the book.
    public static void open(ServerPlayerEntity player, String claimName, List<LogEntry> logs) {
        Menus.dropOpenScreen(player);
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponentTypes.WRITTEN_BOOK_CONTENT, new WrittenBookContentComponent(
                RawFilteredPair.of(claimName.isBlank() ? "Snitch Log" : "Snitch Log of " + claimName),
                "Reinforced Claims", 0, pages(claimName, logs), true));

        int slot = PlayerScreenHandler.HOTBAR_START + player.getInventory().getSelectedSlot();
        player.networkHandler.sendPacket(new ScreenHandlerSlotUpdateS2CPacket(
                player.playerScreenHandler.syncId, player.playerScreenHandler.nextRevision(), slot, book));
        player.networkHandler.sendPacket(new OpenWrittenBookS2CPacket(Hand.MAIN_HAND));
        // After the open packet, so the client has already copied the pages out.
        player.playerScreenHandler.syncState();
    }

    // --- page building ----------------------------------------------------------------------------

    private static List<RawFilteredPair<Text>> pages(String claimName, List<LogEntry> logs) {
        List<RawFilteredPair<Text>> pages = new ArrayList<>();
        // Empty root, heading as a sibling, so later text doesn't inherit its bold style.
        MutableText page = Text.empty().append(
                Text.literal((claimName.isBlank() ? "Snitch Log" : claimName) + "\n\n")
                        .formatted(Formatting.BOLD, Formatting.GRAY));
        if (logs.isEmpty()) {
            pages.add(RawFilteredPair.of(page));
            return pages;
        }
        // The heading costs an entry, so the first page holds one fewer.
        int room = ENTRIES_PER_PAGE - 1;
        for (LogEntry log : logs) {
            if (room == 0) {
                pages.add(RawFilteredPair.of(page));
                page = Text.empty();
                room = ENTRIES_PER_PAGE;
            }
            page.append(line(log));
            room--;
        }
        pages.add(RawFilteredPair.of(page));
        return pages;
    }

    // Timestamp over the name, so a long name can't push it off the page.
    private static MutableText line(LogEntry log) {
        boolean entered = log.type() == LogEntry.Type.ENTER;
        return Text.literal(RelativeTime.compact(log.time()) + "\n").formatted(Formatting.GRAY)
                .append(Text.literal((entered ? "→ " : "← ") + log.player() + "\n")
                        .formatted(entered ? Formatting.GREEN : Formatting.RED));
    }
}
