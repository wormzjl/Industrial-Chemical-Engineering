package com.wormzjl.icheme.client;

import com.wormzjl.icheme.client.fx.sound.SoundBudget;
import com.wormzjl.icheme.world.level.block.AlarmBeaconBlock;
import com.wormzjl.icheme.world.level.block.entity.AlarmBeaconBlockEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * The loaded alarm beacons on the client (batch 2026-11-15-fluid-interfaces, W9, plan section 5.2): a beacon whose block
 * state is {@code ACTIVE} sounds its horn through the loop budget. The index is kept by the block entities' load and
 * unload; the state is read from the block at each choice, so no packet carries it. Client thread only.
 */
public final class AlarmBeaconSounds {
    /**
     * The horn's sound power level, dB: an industrial electronic horn gives about 120 dB at 1 m, about 131 dB of sound
     * power; 130 dB is the budget's volume 3, which Minecraft hears out to 48 blocks (decision A8).
     */
    public static final double HORN = 130;

    private static final Set<AlarmBeaconBlockEntity> BEACONS = Collections.newSetFromMap(new WeakHashMap<>());

    private AlarmBeaconSounds() {}

    public static void track(AlarmBeaconBlockEntity beacon) {
        BEACONS.add(beacon);
    }

    public static void forget(AlarmBeaconBlockEntity beacon) {
        BEACONS.remove(beacon);
    }

    /** The horns sounding within their reach of {@code point}: one emitter per active beacon, at its centre. */
    public static void emitters(Vec3 point, List<SoundBudget.Emitter> into) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        double reach = SoundBudget.reach(HORN);
        for (var beacon : List.copyOf(BEACONS)) {
            if (beacon.isRemoved() || beacon.getLevel() != level) continue;
            var state = beacon.getBlockState();
            if (!state.hasProperty(AlarmBeaconBlock.STATE) || state.getValue(AlarmBeaconBlock.STATE) != AlarmBeaconBlock.Shown.ACTIVE)
                continue;
            var c = beacon.getBlockPos().getCenter();
            if (c.distanceToSqr(point) > reach * reach) continue;
            into.add(new SoundBudget.Emitter("alarm_horn", beacon.getBlockPos().asLong(), c.x, c.y, c.z, HORN, 1));
        }
    }
}
