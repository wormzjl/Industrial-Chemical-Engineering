package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.machine.ShaftNetwork;
import com.wormzjl.icheme.science.machine.ShaftTrain;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * One shaft part as its own page shows it (batch 2026-11-03-rotating-machinery, W7, reading A85): a read of what the trains
 * last decided, published on the engine's presentation buckets to the clients with that machine's page open. Its status
 * word; its speed, rpm; its shaft power, W (a driver's train demand, a driven member's own); a motor's electric draw or a
 * generator's output, W, and FE per tick; its settings and trip; its rating (W, rpm, poles) and whether a motor runs on a
 * variable drive; its electric store's share full and the seconds it lasts at the current draw (NaN for none); a turbine's
 * governor valve's name (empty for none); a gearbox's ratio as compiled (0 for another part); a turbine's steam flow,
 * kg/s, and its inlet and exhaust pressure, Pa (NaN for another part, or before the turbine's first run); a Create
 * coupling's Create side (null for another part).
 */
public record MachineView(long unit, ShaftNetwork.Kind kind, String status, double speed, double shaftPower, double electricPower, double energyPerTick,
                          ShaftUnit.Settings settings, ShaftTrain.Trip trip, double ratedPower, double designSpeed, int poles, boolean variable,
                          double storeShare, double storeSeconds, String governor, double ratio,
                          double steamFlow, double inletPressure, double exhaustPressure, @Nullable Coupling coupling) {
    /** The longest status word and governor name a view carries. */
    public static final int MAX_TEXT = 256;

    /**
     * A Create coupling's page reads (W6b, D40), all engine state from its last train step: Create's |rpm| and the stress the
     * coupling may take, su (its last {@link ShaftTrains.CreateInput}); the impact the engine last sent, su per rpm (NaN for
     * none yet); the speed of its train's first driven machine, rpm (NaN for none); and the stress rate, W per su.
     */
    public record Coupling(double createSpeed, double stress, double impact, double trainSpeed, double wattsPerStress) {
        public Coupling {
            if (!(createSpeed >= 0) || !Double.isFinite(createSpeed + stress + wattsPerStress) || !(wattsPerStress > 0) || impact < 0 || Double.isInfinite(impact)
                    || trainSpeed < 0 || Double.isInfinite(trainSpeed))
                throw new IllegalArgumentException("Invalid coupling view");
        }

        /** The stress the coupling draws, su: the impact sent times Create's |rpm| (NaN before the first impact). */
        public double drawn() {
            return impact * createSpeed;
        }

        /**
         * What the network still spares, su: the stress the coupling may take less what it draws; NaN at rest (Create sends no
         * spare then) and where Create's stress is off ({@link com.wormzjl.icheme.science.machine.CreateCoupling#UNLIMITED_STRESS}).
         */
        public double spare() {
            if (!(createSpeed > 0) || stress >= com.wormzjl.icheme.science.machine.CreateCoupling.UNLIMITED_STRESS) return Double.NaN;
            return Math.max(0, stress - (Double.isNaN(impact) ? 0 : drawn()));
        }
    }

    public MachineView {
        Objects.requireNonNull(kind);
        if ((coupling != null) != (kind == ShaftNetwork.Kind.CREATE_COUPLING)) throw new IllegalArgumentException("A coupling view belongs to a coupling");
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
        var steam = trains.steam(unit);
        String name = governor.length() > MAX_TEXT ? governor.substring(0, MAX_TEXT) : governor;
        return new MachineView(unit, trains.kindOf(unit), status, v.speed(), v.shaftPower(), v.electricPower(), v.energyPerTick(), v.settings(), v.trip(), u.ratedPower(),
                u.designSpeed(), u.poles(), u.kind()==ShaftNetwork.Kind.MOTOR||u.drive() == com.wormzjl.icheme.science.equipment.MachineDuty.Drive.VARIABLE, share, seconds, name, trains.ratio(unit),
                steam == null ? Double.NaN : steam.flow(), steam == null ? Double.NaN : steam.inlet(), steam == null ? Double.NaN : steam.exhaust(), coupling(trains, unit));
    }

    /** Coupling unit {@code unit}'s Create side as the engine last held it (a read: no step, no Create call), or null for another part. */
    private static @Nullable Coupling coupling(ShaftTrains trains, long unit) {
        var c = trains.coupling(unit);
        return c == null ? null : new Coupling(c.rpm(), c.stress(), c.impact(), trains.drivenSpeed(unit), trains.wattsPerStress());
    }
}
