package com.wormzjl.icheme.runtime.pollution;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.Integrity;
import com.wormzjl.icheme.runtime.fluid.WorldTopologyLedger;
import com.wormzjl.icheme.science.integrity.FoulingLaw;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.Positions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /icheme integrity} (operators only; batch 2026-11-16-fouling-corrosion, WP2), registered beside
 * {@code /icheme pollution}: {@code get}, {@code set <wall loss mm> <fouling m2K/W> <wrecked>} and {@code clear} the wall
 * facts of a device, named by its identity ({@code id <device>}) or by a block ({@code at <pos>}: the device registered
 * there, or every device of the structure whose cell it is). A debug and test tool: literal text, no translation keys.
 */
@EventBusSubscriber(modid = IChemE.MOD_ID)
public final class IntegrityCommands {
    private IntegrityCommands() {
    }

    private interface Action {
        int run(CommandContext<CommandSourceStack> context, List<Long> devices);
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        var integrity = Commands.literal("integrity")
                .then(target(Commands.literal("get"), IntegrityCommands::get))
                .then(Commands.literal("set").then(Commands.argument("loss", DoubleArgumentType.doubleArg(0, WorldTopologyLedger.IntegrityFacts.MAX_LOSS))
                        .then(Commands.argument("fouling", DoubleArgumentType.doubleArg(0, FoulingLaw.CAP))
                                .then(target(Commands.argument("wrecked", BoolArgumentType.bool()), IntegrityCommands::set)))))
                .then(target(Commands.literal("clear"), IntegrityCommands::clear));
        event.getDispatcher().register(Commands.literal("icheme").requires(source -> source.hasPermission(2)).then(integrity));
    }

    /** {@code node} followed by {@code id <device>} or {@code at <pos>}, each running {@code action}. */
    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T target(T node, Action action) {
        node.then(Commands.literal("id").then(Commands.argument("device", LongArgumentType.longArg(1)).executes(c -> action.run(c, List.of(LongArgumentType.getLong(c, "device"))))));
        node.then(Commands.literal("at").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> action.run(c, at(c)))));
        return node;
    }

    private static List<Long> at(CommandContext<CommandSourceStack> context) {
        var world = world(context);
        if (world == null) return List.of();
        var level = context.getSource().getLevel();
        BlockPos pos;
        try {
            pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            context.getSource().sendFailure(Component.literal(e.getMessage()));
            return List.of();
        }
        var ids = new ArrayList<Long>();
        var vessel = EquipmentStructure.find(level, pos);
        if (vessel != null) for (long id : EquipmentStructure.sideIdentities(level, vessel)) if (id > 0 && !ids.contains(id)) ids.add(id);
        if (ids.isEmpty()) world.at(Positions.of(level, pos)).ifPresent(r -> ids.add(r.device().id()));
        if (ids.isEmpty()) context.getSource().sendFailure(Component.literal("No fluid device at " + pos.toShortString()));
        return ids;
    }

    private static FluidWorldAuthority world(CommandContext<CommandSourceStack> context) {
        var world = FluidWorldAuthority.find(context.getSource().getServer()).orElse(null);
        if (world == null) context.getSource().sendFailure(Component.literal("The fluid world is not running"));
        return world;
    }

    private static int get(CommandContext<CommandSourceStack> context, List<Long> devices) {
        var world = world(context);
        if (world == null || devices.isEmpty()) return 0;
        var integrity = world.integrity();
        for (long id : devices) {
            var wall = integrity.wall(id);
            String text = wall == null
                    ? String.format(Locale.ROOT, "Device %d: no wall booked (%s)%s", id, integrity.kindOf(id) == null ? "not constructed equipment" : integrity.kindOf(id), integrity.wrecked(id) ? ", wrecked" : "")
                    : String.format(Locale.ROOT, "Device %d (%s): wall %.3f of %.2f mm, %s, %.4g mm/y%s, life %.1f process h; fouling %.5f m2K/W%s; %s", id, integrity.kindOf(id),
                    wall.loss(), wall.allowance(), wall.stage(), wall.rate(), wall.mechanism() == null ? "" : " " + wall.mechanism(), wall.life() / 3600,
                    integrity.fouling(id), " (level " + integrity.foulingLevel(id) + ")", integrity.wrecked(id) ? "wrecked" : "not wrecked");
            context.getSource().sendSuccess(() -> Component.literal(text), false);
        }
        return devices.size();
    }

    private static int set(CommandContext<CommandSourceStack> context, List<Long> devices) {
        var world = world(context);
        if (world == null || devices.isEmpty()) return 0;
        double loss = DoubleArgumentType.getDouble(context, "loss"), fouling = DoubleArgumentType.getDouble(context, "fouling");
        boolean wrecked = BoolArgumentType.getBool(context, "wrecked");
        return apply(context, world.integrity(), devices, loss, fouling, wrecked);
    }

    private static int clear(CommandContext<CommandSourceStack> context, List<Long> devices) {
        var world = world(context);
        if (world == null || devices.isEmpty()) return 0;
        return apply(context, world.integrity(), devices, 0, 0, false);
    }

    private static int apply(CommandContext<CommandSourceStack> context, Integrity integrity, List<Long> devices, double loss, double fouling, boolean wrecked) {
        int done = 0;
        for (long id : devices) if (integrity.set(id, loss, fouling, wrecked)) done++;
        int count = done;
        if (count == 0) context.getSource().sendFailure(Component.literal("No device of constructed equipment among " + devices));
        else context.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "Set %d device(s): wall loss %.3f mm, fouling %.5f m2K/W, wrecked %s", count, loss, fouling, wrecked)), true);
        return count;
    }
}
