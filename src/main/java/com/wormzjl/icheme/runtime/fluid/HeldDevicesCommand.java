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
 * the registrations the registry could not place (decision S11), each with what stands in its way, read from the world
 * now; {@code drop <id>} removes one, what it holds booked as removed, nothing dropped (S9). Operator text, in English, as
 * the other {@code /icheme} commands.
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
        var held = world.held();
        if (held.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No device is held"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(held.size() + (held.size() == 1 ? " device is held:" : " devices are held:")), false);
        for (var record : held.subList(0, Math.min(LISTED, held.size())))
            source.sendSuccess(() -> Component.literal(describe(record.device()) + ": " + reason(source, record.device())), false);
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

    /** What stands in a held device's way, as the world shows it now. */
    static String reason(CommandSourceStack source, PhysicalFluidTopology.Device device) {
        var p = device.position();
        var level = source.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(p.dimension())));
        if (level == null) return "its dimension has no level";
        var at = new BlockPos(p.x(), p.y(), p.z());
        if (!level.hasChunkAt(at)) return "its chunk is not loaded";
        var block = level.getBlockState(at).getBlock();
        return "its cell holds " + BuiltInRegistries.BLOCK.getKey(block);
    }
}
