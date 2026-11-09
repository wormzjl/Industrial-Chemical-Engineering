package com.wormzjl.icheme.runtime.fluid;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;

/**
 * {@code /icheme devices} (batch 2026-11-03-save-corruption, REGISTRY_FIRST_PLAN.md 4.5), for operators: {@code held} lists
 * the registrations the registry could not place (decision S11), then the held shaft parts, cables, controllers and
 * consoles (W5c C5), each with what stands in its way, read from the world now; {@code drop <id>} removes a held
 * registration, what it holds booked as removed, nothing dropped (S9). Operator text, in English, as the other
 * {@code /icheme} commands.
 */
public final class HeldDevicesCommand {
    /** The most held devices one listing names. */
    static final int LISTED = 20;

    private HeldDevicesCommand() {}

    /** The {@code devices} node, under the {@code /icheme} root. */
    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("devices")
                .then(Commands.literal("held").executes(HeldDevicesCommand::held))
                .then(Commands.literal("drop").then(Commands.argument("id", LongArgumentType.longArg(1)).executes(HeldDevicesCommand::drop)));
    }

    private static int held(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var world = FluidWorldAuthority.find(source.getServer()).orElse(null);
        if (world == null) {
            source.sendFailure(Component.literal("The fluid world is not running"));
            return 0;
        }
        var held = new java.util.ArrayList<String>();
        for (var record : world.held()) held.add(describe(record.device()) + ": " + reason(source, record.device()));
        world.shafts().units().stream().filter(ShaftUnit::held).sorted(java.util.Comparator.comparingLong(ShaftUnit::id))
                .forEach(u -> held.add(describe(u) + ": " + reason(source, u.position().dimension(), u.position().x(), u.position().y(), u.position().z())));
        var facts = world.controls().facts();
        facts.held().stream().sorted(java.util.Comparator.comparing(Object::toString)).forEach(site -> held.add(describe(site, facts.cable(site)) + ": "
                + reason(source, site.dimension(), site.cell().getX(), site.cell().getY(), site.cell().getZ())));
        facts.heldPanels().stream().sorted().map(facts::panel).filter(java.util.Objects::nonNull).forEach(p -> held.add(describe(p) + ": "
                + reason(source, p.dimension(), p.position().getX(), p.position().getY(), p.position().getZ())));
        if (held.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No device is held"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(held.size() + (held.size() == 1 ? " device is held:" : " devices are held:")), false);
        for (var line : held.subList(0, Math.min(LISTED, held.size()))) source.sendSuccess(() -> Component.literal(line), false);
        if (held.size() > LISTED) source.sendSuccess(() -> Component.literal("and " + (held.size() - LISTED) + " more"), false);
        return held.size();
    }

    private static int drop(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        long id = LongArgumentType.getLong(context, "id");
        var world = FluidWorldAuthority.find(source.getServer()).orElse(null);
        var record = world == null ? null : world.registrations().get(id);
        if (record == null || !world.drop(id)) {
            source.sendFailure(Component.literal("Device #" + id + " is not held; only a held device is dropped"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Dropped " + describe(record.device()) + "; what it held is booked as removed"), true);
        return 1;
    }

    private static String describe(PhysicalFluidTopology.Device device) {
        var p = device.position();
        return "#" + device.id() + " " + device.kind().name().toLowerCase(Locale.ROOT) + " at " + p.dimension() + " " + p.x() + ", " + p.y() + ", " + p.z();
    }

    private static String describe(ShaftUnit unit) {
        var p = unit.position();
        return "#" + unit.id() + " " + unit.kind().name().toLowerCase(Locale.ROOT).replace('_', ' ') + " at " + p.dimension() + " " + p.x() + ", " + p.y() + ", " + p.z();
    }

    private static String describe(com.wormzjl.icheme.runtime.control.DataBus.Site site, com.wormzjl.icheme.runtime.control.DataBus.Cable cable) {
        var c = site.cell();
        String medium = cable == null ? "" : cable.geometry().medium().name().toLowerCase(Locale.ROOT) + " ";
        return medium + "cable at " + site.dimension() + " " + c.getX() + ", " + c.getY() + ", " + c.getZ() + (site.lane() > 0 ? " lane " + site.lane() : "");
    }

    private static String describe(com.wormzjl.icheme.runtime.control.ControlFacts.Panel panel) {
        var c = panel.position();
        return (panel.dcs() ? "DCS console " : "controller ") + panel.id() + " at " + panel.dimension() + " " + c.getX() + ", " + c.getY() + ", " + c.getZ();
    }

    /** What stands in a held device's way, as the world shows it now. */
    static String reason(CommandSourceStack source, PhysicalFluidTopology.Device device) {
        var p = device.position();
        return reason(source, p.dimension(), p.x(), p.y(), p.z());
    }

    /** What stands in the cell at {@code x, y, z} of {@code dimension}, as the world shows it now. */
    static String reason(CommandSourceStack source, String dimension, int x, int y, int z) {
        var level = source.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension)));
        if (level == null) return "its dimension has no level";
        var at = new BlockPos(x, y, z);
        if (!level.hasChunkAt(at)) return "its chunk is not loaded";
        var block = level.getBlockState(at).getBlock();
        return "its cell holds " + BuiltInRegistries.BLOCK.getKey(block);
    }
}
