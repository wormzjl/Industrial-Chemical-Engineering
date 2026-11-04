package com.wormzjl.icheme.world.level.block;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.ShaftTrains;
import com.wormzjl.icheme.runtime.fluid.ShaftUnit;
import com.wormzjl.icheme.science.machine.ShaftNetwork;
import com.wormzjl.icheme.science.machine.ShaftTrain;
import java.util.Locale;
import java.util.function.UnaryOperator;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /icheme shaft} (operators only; batch 2026-11-03-rotating-machinery W3, build plan section 5): lists the shaft
 * parts with their status, speed and power, and queues a driver's settings, a start, a stop, a reset or a gearbox's ratio
 * as shaft events; they apply at the next tick drain. The W7 pages will send the same events.
 */
@EventBusSubscriber(modid = IChemE.MOD_ID)
public final class ShaftCommand {
    private ShaftCommand() {
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        var id = Commands.argument("unit", LongArgumentType.longArg(1));
        var shaft = Commands.literal("shaft")
                .then(Commands.literal("list").executes(ShaftCommand::list))
                .then(Commands.literal("start").then(id.executes(c -> settings(c, s -> new ShaftUnit.Settings(true, s.mode(), s.setPoint(), s.powerLimit(), s.ratio())))))
                .then(Commands.literal("stop").then(Commands.argument("unit", LongArgumentType.longArg(1))
                        .executes(c -> settings(c, s -> new ShaftUnit.Settings(false, s.mode(), s.setPoint(), s.powerLimit(), s.ratio())))))
                .then(Commands.literal("reset").then(Commands.argument("unit", LongArgumentType.longArg(1)).executes(ShaftCommand::reset)))
                .then(Commands.literal("speed").then(Commands.argument("unit", LongArgumentType.longArg(1)).then(Commands.argument("value", DoubleArgumentType.doubleArg(0, 1e5))
                        .executes(c -> settings(c, s -> new ShaftUnit.Settings(s.running(), ShaftTrain.Mode.SPEED, DoubleArgumentType.getDouble(c, "value"), s.powerLimit(), s.ratio()))))))
                .then(Commands.literal("limit").then(Commands.argument("unit", LongArgumentType.longArg(1)).then(Commands.argument("watts", DoubleArgumentType.doubleArg(0, 1e9))
                        .executes(c -> settings(c, s -> new ShaftUnit.Settings(s.running(), s.mode(), s.setPoint(), DoubleArgumentType.getDouble(c, "watts"), s.ratio()))))))
                .then(Commands.literal("ratio").then(Commands.argument("unit", LongArgumentType.longArg(1)).then(Commands.argument("value", DoubleArgumentType.doubleArg(0, ShaftNetwork.CREATE_RATIO))
                        .executes(c -> settings(c, s -> new ShaftUnit.Settings(s.running(), s.mode(), s.setPoint(), s.powerLimit(), DoubleArgumentType.getDouble(c, "value")))))))
                .then(Commands.literal("regulate").then(Commands.argument("unit", LongArgumentType.longArg(1))
                        .then(Commands.literal("flow").executes(c -> settings(c, s -> new ShaftUnit.Settings(s.running(), ShaftTrain.Mode.REGULATE_FLOW, s.setPoint(), s.powerLimit(), s.ratio()))))
                        .then(Commands.literal("pressure").executes(c -> settings(c, s -> new ShaftUnit.Settings(s.running(), ShaftTrain.Mode.REGULATE_PRESSURE, s.setPoint(), s.powerLimit(), s.ratio()))))));
        event.getDispatcher().register(Commands.literal("icheme").requires(source -> source.hasPermission(2)).then(shaft));
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        var world = FluidWorldAuthority.find(context.getSource().getServer()).orElse(null);
        if (world == null) return 0;
        var views = world.shafts().views();
        for (var v : views) {
            if (v.kind() == ShaftNetwork.Kind.SHAFT) continue;
            context.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "#%d %s %s: %.0f rpm, share %.3f, demand %.1f kW, shaft %.1f kW%s",
                    v.unit(), v.kind().name().toLowerCase(Locale.ROOT), v.status(), v.speed(), v.share(), v.demand() / 1000, v.shaftPower() / 1000,
                    v.electricPower() > 0 ? String.format(Locale.ROOT, ", electric %.1f kW (%.0f FE/t)", v.electricPower() / 1000, v.energyPerTick()) : "")), false);
        }
        return views.size();
    }

    private static int settings(CommandContext<CommandSourceStack> context, UnaryOperator<ShaftUnit.Settings> change) {
        var world = FluidWorldAuthority.find(context.getSource().getServer()).orElse(null);
        long unit = LongArgumentType.getLong(context, "unit");
        var view = world == null ? null : world.shafts().view(unit);
        if (view == null) {
            context.getSource().sendFailure(Component.literal("No shaft unit " + unit));
            return 0;
        }
        ShaftUnit.Settings next;
        try {
            next = change.apply(view.settings());
        } catch (IllegalArgumentException invalid) {
            context.getSource().sendFailure(Component.literal(invalid.getMessage()));
            return 0;
        }
        world.shafts().queue(new ShaftTrains.Configure(unit, next));
        context.getSource().sendSuccess(() -> Component.literal("Queued settings for shaft unit " + unit), false);
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> context) {
        var world = FluidWorldAuthority.find(context.getSource().getServer()).orElse(null);
        if (world == null) return 0;
        long unit = LongArgumentType.getLong(context, "unit");
        world.shafts().queue(new ShaftTrains.Reset(unit));
        context.getSource().sendSuccess(() -> Component.literal("Queued a reset for shaft unit " + unit), false);
        return 1;
    }
}
