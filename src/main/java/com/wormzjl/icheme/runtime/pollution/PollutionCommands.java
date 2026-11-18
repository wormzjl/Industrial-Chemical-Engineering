package com.wormzjl.icheme.runtime.pollution;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.science.pollution.Channel;
import com.wormzjl.icheme.science.pollution.CubeKey;
import com.wormzjl.icheme.science.pollution.PollutionLedger;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /icheme pollution} (operators only; batch 2026-11-15-pollution-system, WP3, plan section 5): {@code get} the
 * cube at the source, {@code add <channel> <index>} to it at the next pass, {@code clear} the source's dimension, and
 * {@code ledger}. A debug tool: literal text, no translation keys, as {@code /icheme shaft}.
 */
@EventBusSubscriber(modid = IChemE.MOD_ID)
public final class PollutionCommands {
    private PollutionCommands() {
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        var add = Commands.literal("add");
        for (Channel channel : Channel.values()) {
            add.then(Commands.literal(channel.name().toLowerCase(Locale.ROOT))
                    .then(Commands.argument("index", DoubleArgumentType.doubleArg(0, 1000))
                            .executes(c -> add(c, channel))));
        }
        var pollution = Commands.literal("pollution")
                .then(Commands.literal("get").executes(PollutionCommands::get))
                .then(add)
                .then(Commands.literal("clear").executes(PollutionCommands::clear))
                .then(Commands.literal("ledger").executes(PollutionCommands::ledger));
        event.getDispatcher().register(Commands.literal("icheme").requires(source -> source.hasPermission(2)).then(pollution));
    }

    private static PollutionService service(CommandContext<CommandSourceStack> context) {
        var service = PollutionService.find(context.getSource().getServer()).orElse(null);
        if (service == null) {
            context.getSource().sendFailure(Component.literal("Pollution is not running on this server"));
        } else if (!service.enabled()) {
            context.getSource().sendFailure(Component.literal("Pollution is disabled for this session (see the server log)"));
            return null;
        }
        return service;
    }

    private static int get(CommandContext<CommandSourceStack> context) {
        try {
            var service = service(context);
            if (service == null) return 0;
            var source = context.getSource();
            var dimension = source.getLevel().dimension();
            long cube = PollutionService.cube(BlockPos.containing(source.getPosition()));
            var cell = service.cell(dimension, cube);
            var text = new StringBuilder(String.format(Locale.ROOT, "Cube %d %d %d:", CubeKey.x(cube), CubeKey.y(cube), CubeKey.z(cube)));
            for (Channel channel : Channel.values()) {
                text.append(String.format(Locale.ROOT, " %s %.4g", channel.name().toLowerCase(Locale.ROOT), service.index(dimension, cube, channel)));
            }
            text.append(cell.loaded() ? String.format(Locale.ROOT, "; air share %.3f, %s", cell.airShare(), cell.open() ? "open" : "enclosed")
                    : "; no cell read yet (unloaded)");
            source.sendSuccess(() -> Component.literal(text.toString()), false);
            return 1;
        } catch (RuntimeException failure) {
            return failed(context, failure);
        }
    }

    private static int add(CommandContext<CommandSourceStack> context, Channel channel) {
        try {
            var service = service(context);
            if (service == null) return 0;
            var source = context.getSource();
            double index = DoubleArgumentType.getDouble(context, "index");
            long cube = PollutionService.cube(BlockPos.containing(source.getPosition()));
            service.add(source.getLevel().dimension(), cube, channel, index);
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "Added %s %.4g to cube %d %d %d; it lands at the next pass",
                    channel.name().toLowerCase(Locale.ROOT), index, CubeKey.x(cube), CubeKey.y(cube), CubeKey.z(cube))), true);
            return 1;
        } catch (RuntimeException failure) {
            return failed(context, failure);
        }
    }

    private static int clear(CommandContext<CommandSourceStack> context) {
        try {
            var service = service(context);
            if (service == null) return 0;
            var dimension = context.getSource().getLevel().dimension();
            int cubes = service.field(dimension).size();
            service.clear(dimension);
            context.getSource().sendSuccess(() -> Component.literal("Cleared " + cubes + " pollution cubes in " + dimension.location()), true);
            return 1;
        } catch (RuntimeException failure) {
            return failed(context, failure);
        }
    }

    private static int ledger(CommandContext<CommandSourceStack> context) {
        try {
            var service = service(context);
            if (service == null) return 0;
            var source = context.getSource();
            var dimension = source.getLevel().dimension();
            var ledger = service.ledger(dimension);
            var field = service.field(dimension);
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "Pollution in %s: %d cubes, %d pending releases (air-mole equivalents, mol)",
                    dimension.location(), field.size(), service.pending(dimension))), false);
            for (Channel channel : Channel.values()) {
                var line = new StringBuilder(channel.name().toLowerCase(Locale.ROOT)).append(':');
                line.append(String.format(Locale.ROOT, " stored %.4g", field.stored(channel)));
                for (PollutionLedger.Total total : PollutionLedger.Total.values()) {
                    line.append(String.format(Locale.ROOT, ", %s %.4g", total.name().toLowerCase(Locale.ROOT), ledger.get(channel, total)));
                }
                source.sendSuccess(() -> Component.literal(line.toString()), false);
            }
            // The ground (batch 2026-11-18-ground-pollution): its columns and its own ledger, deposited = stored + decayed + drawn + capped.
            var ground = service.ground(dimension);
            var groundLedger = service.groundLedger(dimension);
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "Ground in %s: %d columns, %d compound entries (air-mole equivalents, mol)",
                    dimension.location(), ground.size(), ground.entries().size())), false);
            var line = new StringBuilder("ground:");
            line.append(String.format(Locale.ROOT, " stored %.4g", ground.stored()));
            for (var total : com.wormzjl.icheme.science.pollution.GroundLedger.Total.values()) {
                line.append(String.format(Locale.ROOT, ", %s %.4g", total.name().toLowerCase(Locale.ROOT), groundLedger.get(total)));
            }
            source.sendSuccess(() -> Component.literal(line.toString()), false);
            return 1;
        } catch (RuntimeException failure) {
            return failed(context, failure);
        }
    }

    private static int failed(CommandContext<CommandSourceStack> context, RuntimeException failure) {
        IChemE.LOGGER.error("pollution status=COMMAND_FAILED detail={}", String.valueOf(failure));
        context.getSource().sendFailure(Component.literal("Pollution command failed: " + failure.getMessage()));
        return 0;
    }
}
