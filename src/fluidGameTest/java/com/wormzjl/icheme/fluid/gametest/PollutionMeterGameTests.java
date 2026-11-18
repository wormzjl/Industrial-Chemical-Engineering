package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.runtime.pollution.PollutionMeters;
import com.wormzjl.icheme.runtime.pollution.PollutionService;
import com.wormzjl.icheme.science.pollution.Channel;
import com.wormzjl.icheme.science.pollution.Environment;
import com.wormzjl.icheme.science.pollution.Reading;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * The handheld pollution meter (batch 2026-11-15-pollution-system, WP8): the action bar line of a cube at toxic 1.2 names
 * LETHAL in the toxic segment, a clean cube names no danger, the full reading's lines, and only a player holding the meter
 * (either hand) is served. Built from four indices, so no real player and no field pass are needed; the field is still
 * cleared at the start and at the end, as every test of the {@code pollution} batch does.
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class PollutionMeterGameTests {
    private PollutionMeterGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 100, batch = "pollution_meter")
    public static void meterReadsLethalAtToxicOnePointTwo(GameTestHelper helper) {
        var level = helper.getLevel();
        var dimension = level.dimension();
        var service = PollutionService.find(level.getServer()).orElse(null);
        if (service != null) {
            service.clear(dimension);
        }
        var lethal = keys(PollutionMeters.actionBar(1.2, 0.03, 0, 0.42));
        var clean = keys(PollutionMeters.actionBar(0, 0, 0, 0));
        var flammable = keys(PollutionMeters.actionBar(0, 1.5, 0, 0));
        var lines = new ArrayList<String>();
        for (var line : PollutionMeters.fullReading(new BlockPos(40, 70, -20), 1.2, 0, 0, 0, new Environment.Cell(0.87, true, true))) {
            lines.addAll(keys(line));
        }
        // Batch 2026-11-18-ground-pollution, G3: the ground of the column underfoot, one line on the toxic scale.
        var withGround = new ArrayList<String>();
        for (var line : PollutionMeters.fullReading(new BlockPos(40, 70, -20), 0, 0, 0, 0, new Environment.Cell(1, true, true), 0.46)) {
            withGround.addAll(keys(line));
        }
        var noGround = new ArrayList<String>();
        for (var line : PollutionMeters.fullReading(new BlockPos(40, 70, -20), 0, 0, 0, 0, new Environment.Cell(1, true, true), 0.0)) {
            noGround.addAll(keys(line));
        }
        var unread = new ArrayList<String>();
        for (var line : PollutionMeters.fullReading(BlockPos.ZERO, 0, 0, 0, 0, Environment.Cell.UNLOADED)) {
            unread.addAll(keys(line));
        }
        var empty = helper.makeMockPlayer(GameType.SURVIVAL);
        var holder = helper.makeMockPlayer(GameType.SURVIVAL);
        holder.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(ModItems.POLLUTION_METER.get()));
        boolean emptyHolds = PollutionMeters.holds(empty);
        boolean holderHolds = PollutionMeters.holds(holder);
        if (service != null) {
            service.clear(dimension);
        }
        // The mock players were never added to the level: nothing to remove.
        String lethalKey = PollutionMeters.wordKey(Reading.Word.LETHAL);
        helper.assertTrue(lethal.contains(lethalKey), "Toxic 1.2 reads LETHAL: " + lethal);
        helper.assertTrue(lethal.contains("1.20"), "The toxic value shows as 1.20: " + lethal);
        helper.assertTrue(lethal.contains(PollutionMeters.wordKey(Reading.Word.DANGER)), "Particulate 0.42 reads DANGER on its own: " + lethal);
        helper.assertTrue(lethal.contains(PollutionMeters.shortKey(Channel.PARTICULATE)), "The particulate segment is there: " + lethal);
        helper.assertFalse(clean.contains(lethalKey) || clean.contains(PollutionMeters.wordKey(Reading.Word.WARNING)), "A clean cube is SAFE: " + clean);
        helper.assertTrue(clean.contains(PollutionMeters.wordKey(Reading.Word.SAFE)), "A clean cube is SAFE: " + clean);
        helper.assertTrue(flammable.contains(PollutionMeters.wordKey(Reading.Word.FLAMMABLE)) && !flammable.contains(lethalKey),
                "Combustible 1.5 reads FLAMMABLE: " + flammable);
        helper.assertTrue(lines.contains("pollution.icheme.meter.header") && lines.contains(lethalKey)
                && lines.contains("pollution.icheme.meter.air") && lines.contains("87") && lines.contains("pollution.icheme.air.open"),
                "The full reading holds the cube, the words and the air: " + lines);
        helper.assertTrue(unread.contains("pollution.icheme.air.unread"), "An unread cube says so: " + unread);
        helper.assertTrue(withGround.contains("pollution.icheme.meter.ground") && withGround.contains("2") && withGround.contains("-2")
                && withGround.contains("0.46") && withGround.contains("pollution.icheme.word.danger"), "The full reading adds the ground of chunk 2, -2: " + withGround);
        helper.assertFalse(noGround.contains("pollution.icheme.meter.ground"), "No ground, no ground line: " + noGround);
        helper.assertTrue(keys(PollutionMeters.off()).contains("pollution.icheme.meter.off"), "Off reads OFF");
        helper.assertFalse(emptyHolds, "A player without the meter is not served");
        helper.assertTrue(holderHolds, "A player with the meter in the off hand is served");
        helper.succeed();
    }

    /** Every translation key and plain argument in a component tree, depth first. */
    private static List<String> keys(Component component) {
        var out = new ArrayList<String>();
        collect(component, out);
        return out;
    }

    private static void collect(Object part, List<String> out) {
        if (part instanceof Component component) {
            if (component.getContents() instanceof TranslatableContents translatable) {
                out.add(translatable.getKey());
                for (var arg : translatable.getArgs()) {
                    collect(arg, out);
                }
            }
            for (var sibling : component.getSiblings()) {
                collect(sibling, out);
            }
        } else if (part != null) {
            out.add(String.valueOf(part));
        }
    }
}
