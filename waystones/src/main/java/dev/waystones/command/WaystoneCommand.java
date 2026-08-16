package dev.waystones.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.waystones.item.WaystoneItem;
import dev.waystones.waystone.Waystone;
import dev.waystones.waystone.WaystoneSkin;
import dev.waystones.waystone.WaystoneState;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.concurrent.CompletableFuture;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

// The /waystone command tree; every subcommand is op 4.
public final class WaystoneCommand {

    private WaystoneCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("waystone").requires(s -> s.hasPermissionLevel(4))
                .then(literal("give")
                        .then(argument("skin", StringArgumentType.word())
                                .suggests(WaystoneCommand::suggestSkins)
                                .executes(ctx -> give(ctx, ctx.getSource().getPlayerOrThrow()))
                                .then(argument("player", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .suggests((ctx, builder) -> CommandSource.suggestMatching(
                                                ctx.getSource().getServer().getPlayerNames(), builder))
                                        .executes(WaystoneCommand::giveToNamedPlayer))))
                .then(literal("list").executes(WaystoneCommand::list))
                .then(literal("remove")
                        .then(argument("name", StringArgumentType.greedyString())
                                .suggests(WaystoneCommand::suggestNames)
                                .executes(WaystoneCommand::remove))));
    }

    private static CompletableFuture<Suggestions> suggestSkins(CommandContext<ServerCommandSource> ctx, SuggestionsBuilder builder) {
        return CommandSource.suggestMatching(WaystoneSkin.all().stream().map(WaystoneSkin::id), builder);
    }

    private static CompletableFuture<Suggestions> suggestNames(CommandContext<ServerCommandSource> ctx, SuggestionsBuilder builder) {
        WaystoneState state = WaystoneState.get(ctx.getSource().getServer());
        return CommandSource.suggestMatching(state.all().stream().map(Waystone::name), builder);
    }

    private static int give(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity target) throws CommandSyntaxException {
        WaystoneSkin skin = WaystoneSkin.byId(StringArgumentType.getString(ctx, "skin"));
        if (skin == null) {
            ctx.getSource().sendError(Text.literal("Unknown skin."));
            return 0;
        }
        target.getInventory().offerOrDrop(WaystoneItem.create(skin));
        ctx.getSource().sendFeedback(() -> Text.literal("Gave a " + skin.id() + " waystone to " + target.getName().getString() + "."), false);
        return 1;
    }

    private static int giveToNamedPlayer(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        String name = StringArgumentType.getString(ctx, "player");
        ServerPlayerEntity target = ctx.getSource().getServer().getPlayerManager().getPlayer(name);
        if (target == null) {
            ctx.getSource().sendError(Text.literal("Player '" + name + "' isn't online."));
            return 0;
        }
        return give(ctx, target);
    }

    private static int list(CommandContext<ServerCommandSource> ctx) {
        WaystoneState state = WaystoneState.get(ctx.getSource().getServer());
        if (state.all().isEmpty()) {
            ctx.getSource().sendFeedback(() -> Text.literal("No waystones are placed."), false);
            return 0;
        }
        for (Waystone waystone : state.all()) {
            String label = waystone.named() ? waystone.name() : waystone.name() + " (unnamed)";
            ctx.getSource().sendFeedback(() -> Text.literal(label + " - " + waystone.dimension()
                    + " " + waystone.pos().toShortString()).formatted(Formatting.GRAY), false);
        }
        return state.all().size();
    }

    private static int remove(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        WaystoneState state = WaystoneState.get(ctx.getSource().getServer());
        Waystone waystone = state.get(name);
        if (waystone == null) {
            ctx.getSource().sendError(Text.literal("No waystone is named '" + name + "'."));
            return 0;
        }
        state.remove(waystone.name());
        var world = ctx.getSource().getServer().getWorld(
                net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD, waystone.dimension()));
        if (world != null) {
            dev.waystones.waystone.WaystoneStructure.remove(world, waystone.pos(), waystone.displayEntities());
        }
        ctx.getSource().sendFeedback(() -> Text.literal("Removed " + waystone.name() + "."), false);
        return 1;
    }
}
