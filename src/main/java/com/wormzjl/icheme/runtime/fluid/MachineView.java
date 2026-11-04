package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.machine.ShaftNetwork;
import com.wormzjl.icheme.science.machine.ShaftTrain;
import java.util.Objects;

/**
 * One shaft part as its own page shows it (batch 2026-11-03-rotating-machinery, W7, reading A85): a read of what the trains
 * last decided, published on the engine's presentation buckets to the clients with that machine's page open. Its status
 * word; its speed, rpm; its shaft power, W (a driver's train demand, a driven member's own); a motor's electric draw or a
 * generator's output, W, and FE per tick; its settings and trip; its rating (W, rpm, poles) and whether a motor runs on a
 * variable drive; its electric store's share full and the seconds it lasts at the current draw (NaN for none); a turbine's
 * governor valve's name (empty for none); a gearbox's ratio as compiled (0 for another part); a turbine's steam flow,
 * kg/s, and its inlet and exhaust pressure, Pa (NaN for another part, or before the turbine's first run).
 */
public record MachineView(long unit, ShaftNetwork.Kind kind, String status, double speed, double shaftPower, double electricPower, double energyPerTick,
                          ShaftUnit.Settings settings, ShaftTrain.Trip trip, double ratedPower, double designSpeed, int poles, boolean variable,
                          double storeShare, double storeSeconds, String governor, double ratio,
                          double steamFlow, double inletPressure, double exhaustPressure) {
    /** The longest status word and governor name a view carries. */
    public static final int MAX_TEXT = 256;

    public MachineView {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(status);
        Objects.requireNonNull(settings);
        Objects.requireNonNull(trip);
        Objects.requireNonNull(governor);
        if (unit <= 0 || status.length() > MAX_TEXT || governor.length() > MAX_TEXT || !(speed >= 0) || !Double.isFinite(speed + shaftPower + electricPower + energyPerTick + ratedPower + designSpeed + ratio)
                || ratedPower < 0 || designSpeed < 0 || ratio < 0 || poles < 0 || storeShare < 0 || storeSeconds < 0
                || steamFlow < 0 || inletPressure < 0 || exhaustPressure < 0 || Double.isInfinite(steamFlow + inletPressure + exhaustPressure))
            throw new IllegalArgumentException("Invalid machine view");
    }

    /**
     * Part {@code unit} of {@code trains} as its page shows it, or null when it is gone: the trains' read
     * ({@link ShaftTrains#view}) with the part's rating and its store. {@code governor} names the bound governor valve.
     */
    public static MachineView of(ShaftTrains trains, long unit, String governor) {
        var v = trains.view(unit);
        var u = v == null ? null : trains.unit(unit);
        if (u == null) return null;
        double share = Double.NaN, seconds = Double.NaN;
        var store = v.electric();
        if (store != null && store.capacity() > 0) {
            share = Math.clamp(store.joules() / store.capacity(), 0, 1);
            // How long the store lasts at the current draw (reading A92); a generator's store fills, so it has no time.
            if (v.kind() == ShaftNetwork.Kind.MOTOR && v.electricPower() > 0) seconds = store.joules() / v.electricPower();
        }
        String status = v.status().length() > MAX_TEXT ? v.status().substring(0, MAX_TEXT) : v.status();
        // A turbine running with no governor valve says so on the display (plan section 3: UNGOVERNED).
        if (v.kind() == ShaftNetwork.Kind.STEAM_TURBINE && governor.isEmpty() && status.equals("RUNNING")) status = "UNGOVERNED";
        var steam = trains.steam(unit);
        String name = governor.length() > MAX_TEXT ? governor.substring(0, MAX_TEXT) : governor;
        return new MachineView(unit, v.kind(), status, v.speed(), v.shaftPower(), v.electricPower(), v.energyPerTick(), v.settings(), v.trip(), u.ratedPower(),
                u.designSpeed(), u.poles(), u.drive() == com.wormzjl.icheme.science.equipment.MachineDuty.Drive.VARIABLE, share, seconds, name, trains.ratio(unit),
                steam == null ? Double.NaN : steam.flow(), steam == null ? Double.NaN : steam.inlet(), steam == null ? Double.NaN : steam.exhaust());
    }
}
