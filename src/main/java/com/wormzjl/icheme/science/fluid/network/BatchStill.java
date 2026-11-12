package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.math.DeterministicMath;

/**
 * The batch still's law (batch 2026-11-09-batch-distiller, BATCH_DISTILLER_PLAN.md sections 2, 4 W1 and W3, 5 and 7):
 * its heater, the pot's energy balance, the condenser and the statuses. Minecraft-free; the column is {@link StillColumn}.
 *
 * <ul>
 * <li><b>Heater.</b> {@link Heater#SOLID} 45 %, {@link Heater#LIQUID} 55 %, {@link Heater#ELECTRIC} 90 % (owner B2 A): heat
 * into the pot over the fuel's heat or the power drawn. The heated surface is the pot's bottom and flue-swept lower wall,
 * A = {@value #AREA_FACTOR} D², at the design flux {@value #FLUX} W/m² (direct fire's usual limit before scorching): the
 * rating, the heat into the pot at full firing. Firing is a 0 to 100 % setting of that rating.</li>
 * <li><b>Pot.</b> Below its bubble point the heat raises it: Q = N dh/dt, N the moles, h the liquid's molar enthalpy
 * (Q / (m cp)). At its bubble point the heat splits: the pot's own warming, its bubble point rising as it strips, and the
 * boil-up's. A step closes the pot's energy balance exactly: Q dt = N1 h(x1, Tb(x1)) - N0 h0 + δ e, δ the moles distilled,
 * e the energy that leaves per mole of distillate ({@link StillColumn.Split#energyPerDistillate}). The distillate's
 * composition and e are the step start's (explicit); the pot ends on its bubble point. No heat is lost to ambient (owner
 * B6 A): the setting's losses are inside the efficiency, and the pot keeps its heat until drained.</li>
 * <li><b>Condenser.</b> U = {@value #CONDENSER_U} W/m²K, its area sized for the full boil-up (the rating) plus
 * {@value #EXCESS} at a design LMTD of {@value #DESIGN_LMTD} K. It condenses at most ε C_w (T_c - T_w), ε = 1 - exp(-U A /
 * C_w), C_w the water's capacity rate and T_c the mean of the head and condensate temperatures. Short of that, it condenses
 * the share φ of the top vapour; the valve still divides the condensate (reflux R / (R + 1) of it), so the column runs at the
 * effective reflux φ R / (R + 1 - φ R), and the rest of the top vapour leaves by the vent: COOLING LIMITED.</li>
 * <li><b>Column.</b> Sized at an F-factor of {@value #DESIGN_F_FACTOR} Pa^0.5 at the full boil-up of a naphtha
 * ({@value #DESIGN_LATENT} J/kg, vapour {@value #DESIGN_VAPOUR_DENSITY} kg/m³). Above {@link #FLOOD_F_FACTOR} it floods:
 * the firing is held at the flood boil-up (FLOODING). A column with no condenser has no reflux: its trays pass the pot's
 * vapour, one stage.</li>
 * <li><b>Statuses</b> ({@link Status}): OVERHEAT cuts the firing at a {@value #OVERHEAT} K pot (cracking is not
 * modelled, owner B8 A); BOIL DRY at {@value #DRY_SHARE} of the pot's volume in liquid, where the heated surface uncovers;
 * NO FUEL with the firing asked for and nothing delivered.</li>
 * </ul>
 */
public final class BatchStill {
    private BatchStill() {
    }

    /** The pot's heat source and its efficiency, heat into the pot over the fuel's heat or the power drawn (owner B2 A). */
    public enum Heater {
        SOLID(.45), LIQUID(.55), ELECTRIC(.90);

        private final double efficiency;

        Heater(double efficiency) {
            this.efficiency = efficiency;
        }

        public double efficiency() {
            return efficiency;
        }

        /** The fuel's heat or the power drawn, W, for {@code heat} W into the pot. */
        public double drawn(double heat) {
            return Math.max(0, heat) / efficiency;
        }
    }

    /** The design flux on the heated surface at full firing, W/m². */
    public static final double FLUX = 25e3;
    /** The heated surface over the pot's diameter squared: its bottom and flue-swept lower wall. */
    public static final double AREA_FACTOR = 1.73;
    /** The condenser's overall coefficient, hydrocarbon vapour to water, W/m²K. */
    public static final double CONDENSER_U = 500;
    /** The condenser's design LMTD, K: vapour at about 75 °C, water 25 to 35 °C. */
    public static final double DESIGN_LMTD = 40;
    /** The design excess (owner rule of 2026-11-03): sizes carry 20 % over the full load. */
    public static final double EXCESS = 1.2;
    /** The design water rise through the condenser, K, and water's heat capacity, J/kgK, for its design flow. */
    public static final double DESIGN_WATER_RISE = 10, WATER_HEAT_CAPACITY = 4186;
    /** The column's F-factor at the full design boil-up, Pa^0.5, on a naphtha's latent heat (J/kg) and vapour density (kg/m³). */
    public static final double DESIGN_F_FACTOR = 1.5, DESIGN_LATENT = 330e3, DESIGN_VAPOUR_DENSITY = 3.3;
    /** The F-factor at which the column floods, Pa^0.5: its design load with the design excess. */
    public static final double FLOOD_F_FACTOR = DESIGN_F_FACTOR * EXCESS;
    /** The pot temperature that cuts the firing, K: 350 °C (owner B8 A). */
    public static final double OVERHEAT = 623.15;
    /** The liquid share of the pot's volume below which it boils dry: the heated surface uncovers. */
    public static final double DRY_SHARE = .05;
    /** The usual charge, a share of the pot's volume (a fuller pot pukes liquid into the neck). */
    public static final double FILL = .70;
    /** An item's heat without a fuel record, J per burn tick (owner B1 B: the coal anchor, 2.31 GJ over 1600 ticks). */
    public static final double JOULES_PER_BURN_TICK = 1.45e6;
    /** The pot's pressure without another, Pa: it vents to the atmosphere. */
    public static final double ATMOSPHERIC = 101325;
    /** Stages of the optional column: none, or {@value #MIN_STAGES} to {@link StillColumn#MAX_STAGES}. */
    public static final int MIN_STAGES = 2;

    /** The pot's diameter for {@code volume} m³: a cylinder as tall as it is wide (100 L 0.50 m, 500 L 0.86 m, 2000 L 1.37 m). */
    public static double potDiameter(double volume) {
        return DeterministicMath.cbrt(4 * volume / Math.PI);
    }

    /** The heated surface of a pot {@code diameter} m across, m². */
    public static double heatedArea(double diameter) {
        return AREA_FACTOR * diameter * diameter;
    }

    /** The heater's rating, W into the pot at full firing, for a pot {@code diameter} m across. */
    public static double rating(double diameter) {
        return FLUX * heatedArea(diameter);
    }

    /** The condenser's area for a still of {@code rating} W, m²: the full boil-up plus the excess at the design LMTD. */
    public static double condenserArea(double rating) {
        return EXCESS * rating / (CONDENSER_U * DESIGN_LMTD);
    }

    /** The condenser's design water flow, kg/s: the full boil-up plus the excess at the design rise. */
    public static double designWaterFlow(double rating) {
        return EXCESS * rating / (WATER_HEAT_CAPACITY * DESIGN_WATER_RISE);
    }

    /** The column's diameter for a still of {@code rating} W, m: the design F-factor at the full boil-up of the design naphtha. */
    public static double columnDiameter(double rating) {
        double mass = rating / DESIGN_LATENT, area = mass / (DESIGN_F_FACTOR * Math.sqrt(DESIGN_VAPOUR_DENSITY));
        return Math.sqrt(4 * area / Math.PI);
    }

    /** The F-factor, Pa^0.5, of {@code massFlow} kg/s of vapour of {@code density} kg/m³ through a column {@code diameter} m across. */
    public static double fFactor(double massFlow, double density, double diameter) {
        if (!(massFlow > 0) || !(density > 0) || !(diameter > 0)) return 0;
        return massFlow / (Math.PI * diameter * diameter / 4) / Math.sqrt(density);
    }

    /** The electric heater's draw, FE per tick, for {@code heat} W into the pot at {@code joulesPerFE}. */
    public static double electricPerTick(double heat, double joulesPerFE) {
        return Heater.ELECTRIC.drawn(heat) / (20 * joulesPerFE);
    }

    /** The cooling water: its inlet temperature, K, and capacity rate, W/K (mass flow times heat capacity; infinite for no rise). */
    public record Cooling(double waterTemperature, double capacityRate) {
        public Cooling {
            if (!(waterTemperature > 0) || !Double.isFinite(waterTemperature) || !(capacityRate >= 0))
                throw new IllegalArgumentException("Invalid still cooling");
        }
    }

    /**
     * The most a condenser of {@code area} m² condenses, W, at {@code condensing} K against {@code cooling}: the
     * condensing side at one temperature, ε C_w (T_c - T_w).
     */
    public static double condenserCapacity(double area, double condensing, Cooling cooling) {
        double drive = condensing - cooling.waterTemperature(), ua = CONDENSER_U * area;
        if (!(drive > 0) || !(area > 0)) return 0;
        double c = cooling.capacityRate();
        if (c == Double.POSITIVE_INFINITY) return ua * drive;
        if (!(c > 0)) return 0;
        return (1 - DeterministicMath.exp(-ua / c)) * c * drive;
    }

    /**
     * A still as built: its pot's volume (m³), heater, built-in condenser and column trays (0 for none). The sizes follow
     * from the pot: diameter, heated area, rating, condenser area, column diameter.
     */
    public record Design(double volume, Heater heater, boolean condenser, int stages) {
        public Design {
            if (!(volume > 0) || !Double.isFinite(volume) || heater == null || !(stages == 0 || stages >= MIN_STAGES && stages <= StillColumn.MAX_STAGES))
                throw new IllegalArgumentException("Invalid batch still design");
        }

        public double diameter() {
            return potDiameter(volume);
        }

        public double heatedArea() {
            return BatchStill.heatedArea(diameter());
        }

        /** W into the pot at full firing. */
        public double rating() {
            return BatchStill.rating(diameter());
        }

        public double condenserArea() {
            return condenser ? BatchStill.condenserArea(rating()) : 0;
        }

        public double columnDiameter() {
            return stages > 0 ? BatchStill.columnDiameter(rating()) : 0;
        }

        /**
         * Whether the still has a reflux valve: a column under the built-in condenser. A pot still's condensate all goes to
         * the distillate; returned to the pot it would only cut the distillate, as the pot is the one equilibrium stage
         * (owner, 2026-11-09).
         */
        public boolean refluxes() {
            return condenser && stages > 0;
        }
    }

    /**
     * The still's settings and supplies for a step: {@code firing} 0 to 1 of the rating; {@code available} 0 to 1, the share
     * of the asked-for fuel or power delivered; {@code reflux} the valve's reflux ratio (infinite: closed, total reflux);
     * {@code cooling} the condenser's water (null: no condenser, or no water).
     */
    public record Inputs(double firing, double available, double reflux, Cooling cooling) {
        public Inputs {
            if (!(firing >= 0 && firing <= 1) || !(available >= 0 && available <= 1) || !(reflux >= 0))
                throw new IllegalArgumentException("Invalid still inputs");
        }
    }

    /** The pot's liquid (moles per component of the column's basis) and temperature, and the column's last answer (state, may be null). */
    public record Pot(double[] moles, double temperature, StillColumn.Split last) {
        public Pot {
            moles = moles.clone();
            if (!(temperature > 0) || !Double.isFinite(temperature)) throw new IllegalArgumentException("Invalid pot temperature");
        }

        @Override
        public double[] moles() {
            return moles.clone();
        }

        public double total() {
            double sum = 0;
            for (double e : moles) sum += e;
            return sum;
        }
    }

    /**
     * What the still shows, most severe first: an alarm stands before the run's state. IDLE is a pot with no heat asked
     * for; HEATING below its bubble point; BOILING at it with nothing distilled (a closed valve); DISTILLING otherwise.
     */
    public enum Status {
        NO_FUEL, OVERHEAT, BOIL_DRY, FLOODING, COOLING_LIMITED, DISTILLING, BOILING, HEATING, IDLE;

        /** This status's bit in {@link Step#alarms}. */
        public int bit() {
            return 1 << ordinal();
        }

        public boolean alarm() {
            return ordinal() <= COOLING_LIMITED.ordinal();
        }
    }

    /**
     * One step of the still: the pot's end state; its primary status and every alarm raised ({@link Status#bit}); the heat
     * into the pot (J), the part of it that warmed the pot (J) and the fuel's heat or the energy drawn for it (J); the
     * distillate to the spout (liquid, moles per component) and the vapour to the vent (or, without a condenser, to the
     * vapour nozzle), each with its enthalpy (J); the condenser's duty (J); the head temperature (K, NaN with no vapour at
     * the head); the column's F-factor (Pa^0.5); the share of the top vapour the condenser took; the column's answer and
     * the reason its last solve failed (null if none).
     *
     * <p>The ledger closes exactly: heat = (pot's end enthalpy - its start enthalpy) + liquidEnthalpy + vapourEnthalpy +
     * condenserDuty.</p>
     */
    public record Step(Pot pot, Status status, int alarms, double heat, double warming, double fuel, double[] liquid, double liquidEnthalpy,
                       double[] vapour, double vapourEnthalpy, double condenserDuty, double headTemperature, double fFactor, double condensed,
                       StillColumn.Split split, String columnFailure) {
        public boolean alarm(Status status) {
            return (alarms & status.bit()) != 0;
        }

        /** The heat that boiled the distillate up, J: the heat less the pot's warming. */
        public double boilUp() {
            return heat - warming;
        }
    }

    /**
     * The still over {@code dt} s from {@code pot}: its heater fires at {@code inputs}, the pot heats to its bubble point
     * and boils, the column (on {@code column}'s model and pressure) and the condenser split what leaves.
     */
    public static Step step(StillColumn column, Design design, Pot pot, Inputs inputs, double dt) {
        if (!(dt > 0) || !Double.isFinite(dt)) throw new IllegalArgumentException("Invalid still step");
        int n = column.componentCount();
        double[] n0 = pot.moles();
        if (n0.length != n) throw new IllegalArgumentException("Still basis mismatch");
        double total = pot.total();
        double requested = inputs.firing() * design.rating(), supplied = requested * inputs.available();
        int alarms = requested > 0 && supplied == 0 ? Status.NO_FUEL.bit() : 0;
        if (!(total > 0)) return idle(pot, alarms | Status.BOIL_DRY.bit(), n);
        double[] x0 = column.fractions(n0);
        double t0 = pot.temperature(), h0 = column.liquidEnthalpy(t0, x0);
        double dry = DRY_SHARE * design.volume() / column.liquidVolume(t0, x0);
        if (total <= dry) alarms |= Status.BOIL_DRY.bit();
        if (t0 >= OVERHEAT) alarms |= Status.OVERHEAT.bit();
        double budget = (alarms & (Status.BOIL_DRY.bit() | Status.OVERHEAT.bit())) != 0 ? 0 : supplied * dt;
        if (!(budget > 0)) return idle(pot, alarms, n);
        var last = pot.last() != null && pot.last().converged() ? pot.last() : null;
        var bubble = column.bubble(x0, last != null ? last.potTemperature() : t0);
        double tb = bubble == null ? Double.POSITIVE_INFINITY : bubble.temperature(), ceiling = Math.min(tb, OVERHEAT);
        // Below the bubble point the heat raises the pot: N (h(T1) - h(T0)).
        double sensible = 0, hStart = h0, start = t0;
        if (t0 < ceiling) {
            double hTop = column.liquidEnthalpy(ceiling, x0);
            sensible = total * (hTop - h0);
            if (budget < sensible || bubble == null || ceiling == OVERHEAT) {
                double t1 = temperatureAt(column, x0, h0 + Math.min(budget, sensible) / total, t0, ceiling, h0, hTop);
                int raised = budget >= sensible && ceiling == OVERHEAT ? alarms | Status.OVERHEAT.bit() : alarms;
                double heat = total * (column.liquidEnthalpy(t1, x0) - h0);
                return new Step(new Pot(n0, t1, pot.last()), primary(raised, false, false, heat > 0), raised, heat, heat, design.heater().drawn(heat),
                        new double[n], 0, new double[n], 0, 0, Double.NaN, 0, 1, pot.last(), null);
            }
            hStart = hTop;
            start = ceiling;
        }
        // At the bubble point the rest boils; the column and the condenser split what leaves.
        double boiling = budget - sensible;
        var boil = boil(column, design, n0, total, start, hStart, boiling, dry, inputs, last, dt);
        if (boil.flood > FLOOD_F_FACTOR) {
            // The firing held at the flood boil-up: every flow of the column is proportional to it.
            boil = boil(column, design, n0, total, start, hStart, boiling * FLOOD_F_FACTOR / boil.flood * (1 - 1e-9), dry, inputs, last, dt);
            boil.alarms |= Status.FLOODING.bit();
        }
        int raised = alarms | boil.alarms;
        double[] liquid = new double[n], vapour = new double[n];
        double removed = 0;
        for (int i = 0; i < n; i++) {
            liquid[i] = boil.liquidShare * boil.removal[i];
            vapour[i] = boil.removal[i] - liquid[i];
            removed += boil.removal[i];
        }
        var split = boil.split;
        double liquidEnthalpy = 0, vapourEnthalpy = 0, condenser = boil.used;
        if (removed > 0) {
            // Out of the column per mole of distillate: e = (R + 1) H1 - R hR; the distillate leaves at its own hD (h0 with one
            // liquid; of two layers what the reflux leaves), the vented vapour at H1, and the condenser takes the rest.
            liquidEnthalpy = boil.liquidShare * removed * split.distillateEnthalpy();
            vapourEnthalpy = (1 - boil.liquidShare) * removed * split.headEnthalpy();
            condenser = removed * split.energyPerDistillate() - liquidEnthalpy - vapourEnthalpy;
        }
        double heat = sensible + boil.used;
        return new Step(boil.end, primary(raised, removed > 0, true, true), raised, heat, sensible + boil.warming, design.heater().drawn(heat),
                liquid, liquidEnthalpy, vapour, vapourEnthalpy, condenser, split.headTemperature(), boil.flood, boil.condensed, split, boil.failure);
    }

    private static Step idle(Pot pot, int alarms, int components) {
        return new Step(pot, primary(alarms, false, false, false), alarms, 0, 0, 0, new double[components], 0, new double[components], 0, 0,
                Double.NaN, 0, 1, pot.last(), null);
    }

    private static Status primary(int alarms, boolean distilled, boolean boiling, boolean heated) {
        for (var status : Status.values()) if (status.alarm() && (alarms & status.bit()) != 0) return status;
        if (distilled) return Status.DISTILLING;
        if (boiling) return Status.BOILING;
        if (heated) return Status.HEATING;
        return Status.IDLE;
    }

    /** The boiling part of a step. */
    private static final class Boil {
        Pot end;
        StillColumn.Split split;
        double[] removal;
        /** Heat used (J), the pot's warming in it (J), the column's boil-up heat rate (W), its F-factor. */
        double used, warming, boilUp, flood;
        /** The share of the distillate that leaves as liquid, and of the top vapour the condenser takes. */
        double liquidShare = 1, condensed = 1;
        int alarms;
        String failure;
    }

    /** The column's reflux ratio when the condenser takes the share {@code condensed} of the top vapour at the valve's {@code reflux}. */
    public static double effectiveReflux(double reflux, double condensed) {
        if (condensed >= 1) return reflux;
        if (!(condensed > 0)) return 0;
        if (reflux == Double.POSITIVE_INFINITY) return condensed / (1 - condensed);
        return condensed * reflux / (reflux + 1 - condensed * reflux);
    }

    /**
     * The pot at its bubble point {@code start} (enthalpy {@code hStart}) boils on {@code budget} J: the column's split at the
     * effective reflux, the pot's energy balance, then the condenser's share of the top vapour, to its fixed point.
     */
    private static Boil boil(StillColumn column, Design design, double[] n0, double total, double start, double hStart, double budget,
                             double dry, Inputs inputs, StillColumn.Split last, double dt) {
        boolean condenser = design.condenser() && inputs.cooling() != null;
        // A column with no condenser has no reflux: its trays run dry and pass the pot's vapour, one stage.
        double nominal = condenser && design.refluxes() ? inputs.reflux() : 0, condensed = 1;
        var warm = last;
        Boil boil = null;
        for (int pass = 0; pass < 6; pass++) {
            double reflux = effectiveReflux(nominal, condensed);
            String failure = null;
            var split = column.solve(n0, design.stages(), reflux, 1, warm != null ? warm.profile() : null);
            if (!split.converged()) {
                // A refused solve keeps the column's last answer at the same setting (state), else one equilibrium stage.
                failure = split.failure();
                split = last != null && last.stages() == split.stages() && last.refluxRatio() == split.refluxRatio() && last.closed() == split.closed()
                        ? last : column.solve(n0, 0, 0, 1, null);
            }
            if (!split.converged()) {
                boil = new Boil();
                boil.split = split;
                boil.end = new Pot(n0, start, last);
                boil.removal = new double[n0.length];
                boil.failure = split.failure();
                return boil;
            }
            warm = split;
            boil = boiled(column, design, n0, total, start, hStart, budget, dry, split, dt);
            boil.failure = failure;
            boil.condensed = condensed;
            // The product's liquid share of the column's net top product: φ (R_eff + 1) / (R + 1); a closed valve sends no
            // condensate out, so all of its top product is the vented vapour.
            boil.liquidShare = nominal == Double.POSITIVE_INFINITY ? (condensed < 1 ? 0 : 1) : condensed * (reflux + 1) / (nominal + 1);
            if (!condenser) {
                boil.condensed = 0;
                boil.liquidShare = 0;
                return boil;
            }
            double need = boil.boilUp * split.topVapourRate() * (split.headEnthalpy() - split.condensateEnthalpy());
            double capacity = condenserCapacity(design.condenserArea(), (split.headTemperature() + split.condensateTemperature()) / 2, inputs.cooling());
            double next = need > capacity ? capacity / need : 1;
            if (Math.abs(next - condensed) <= 1e-6) break;
            condensed = next;
        }
        if (boil.condensed < 1 - 1e-6) boil.alarms |= Status.COOLING_LIMITED.bit();
        return boil;
    }

    /** The moles distilled on {@code budget} J with {@code split}: the pot's energy balance closed by its end state. */
    private static Boil boiled(StillColumn column, Design design, double[] n0, double total, double start, double hStart, double budget,
                               double dry, StillColumn.Split split, double dt) {
        var boil = new Boil();
        boil.split = split;
        int n = n0.length;
        double e = split.energyPerDistillate();
        if (split.closed() || !Double.isFinite(e) || !(budget > 0)) {
            // Total reflux: the pot stays as it is and all the heat goes up to the condenser.
            boil.end = new Pot(n0, start, split);
            boil.removal = new double[n];
            boil.used = Math.max(0, budget);
            boil.boilUp = boil.used / dt;
            boil.flood = flood(column, design, split, boil.boilUp);
            return boil;
        }
        var g = new Balance(column, n0, split.distillate(), total, hStart, e, start);
        double most = total - dry;
        double low = 0, gLow = g.used(0), high = Math.min(most, budget / (e - hStart)), gHigh = g.used(high);
        while (gHigh < budget && high < most) {
            low = high;
            gLow = gHigh;
            high = Math.min(most, 2 * high);
            gHigh = g.used(high);
        }
        double delta = high;
        if (gHigh <= budget) boil.alarms |= Status.BOIL_DRY.bit();
        else {
            // Illinois regula falsi on the heat used, which rises with the moles distilled.
            int side = 0;
            for (int iteration = 0; iteration < 60; iteration++) {
                delta = (low * (gHigh - budget) - high * (gLow - budget)) / (gHigh - gLow);
                double gd = g.used(delta);
                if (Math.abs(gd - budget) <= 1e-10 * budget || high - low <= 1e-14 * total) break;
                if (gd > budget) {
                    high = delta;
                    gHigh = gd;
                    if (side == -1) gLow = budget + (gLow - budget) / 2;
                    side = -1;
                } else {
                    low = delta;
                    gLow = gd;
                    if (side == 1) gHigh = budget + (gHigh - budget) / 2;
                    side = 1;
                }
            }
        }
        g.used(delta);
        if (g.temperature > OVERHEAT) {
            // The firing cut where the pot's bubble point reaches the limit.
            double lo = 0, hi = delta;
            for (int iteration = 0; iteration < 80 && hi - lo > 1e-12 * total; iteration++) {
                double mid = (lo + hi) / 2;
                g.used(mid);
                if (g.temperature > OVERHEAT) hi = mid;
                else lo = mid;
            }
            delta = lo;
            boil.alarms |= Status.OVERHEAT.bit();
        }
        boil.used = g.used(delta);
        boil.removal = g.removal.clone();
        boil.end = new Pot(g.remaining, g.temperature, split);
        boil.warming = g.warming();
        boil.boilUp = g.removed * split.boilUpHeat() / dt;
        boil.flood = flood(column, design, split, boil.boilUp);
        return boil;
    }

    /** The pot's energy balance as a function of the moles distilled. */
    private static final class Balance {
        final StillColumn column;
        final double[] n0, xd, removal, remaining;
        final double total, h0, e, start;
        double temperature, enthalpy, removed;

        Balance(StillColumn column, double[] n0, double[] xd, double total, double h0, double e, double start) {
            this.column = column;
            this.n0 = n0;
            this.xd = xd;
            this.total = total;
            this.h0 = h0;
            this.e = e;
            this.start = start;
            removal = new double[n0.length];
            remaining = new double[n0.length];
            temperature = start;
        }

        /** The heat that distils {@code delta} moles, J: N1 h(x1, Tb(x1)) + δ e - N0 h0, the pot ending at its bubble point. */
        double used(double delta) {
            removed = 0;
            for (int i = 0; i < n0.length; i++) {
                removal[i] = Math.min(n0[i], delta * xd[i]);
                remaining[i] = n0[i] - removal[i];
                removed += removal[i];
            }
            var bubble = column.bubble(remaining, temperature);
            if (bubble == null) bubble = column.bubble(remaining, Double.NaN);
            if (bubble == null) throw new IllegalStateException("The still's pot lost its bubble point while boiling");
            temperature = bubble.temperature();
            enthalpy = column.liquidEnthalpy(temperature, remaining);
            return (total - removed) * enthalpy + removed * e - total * h0;
        }

        /** The pot's own warming in the last evaluation, J: its end liquid from the start's temperature to its own. */
        double warming() {
            return (total - removed) * (enthalpy - column.liquidEnthalpy(start, remaining));
        }
    }

    private static double flood(StillColumn column, Design design, StillColumn.Split split, double rate) {
        if (split.stages() == 0 || !(rate > 0)) return 0;
        double d = design.columnDiameter();
        double bottom = split.vapourRate() * rate, top = split.topVapourRate() * rate;
        double bottomMass = bottom * column.molarMass(split.vapour()), topMass = top * column.molarMass(split.distillate());
        double bottomDensity = column.molarMass(split.vapour()) / column.vapourVolume(split.potTemperature(), split.vapour());
        double topDensity = column.molarMass(split.distillate()) / column.vapourVolume(split.headTemperature(), split.distillate());
        return Math.max(fFactor(bottomMass, bottomDensity, d), fFactor(topMass, topDensity, d));
    }

    /** The liquid's temperature at molar enthalpy {@code target} between {@code low} (enthalpy {@code hLow}) and {@code high}. */
    private static double temperatureAt(StillColumn column, double[] x, double target, double low, double high, double hLow, double hHigh) {
        if (target <= hLow) return low;
        if (target >= hHigh) return high;
        double a = low, b = high, fa = hLow - target, fb = hHigh - target, t = low;
        int side = 0;
        for (int iteration = 0; iteration < 60; iteration++) {
            t = (a * fb - b * fa) / (fb - fa);
            double f = column.liquidEnthalpy(t, x) - target;
            if (Math.abs(f) <= 1e-9 * Math.abs(target) + 1e-9 || b - a < 1e-10) break;
            if (f > 0) {
                b = t;
                fb = f;
                if (side == -1) fa /= 2;
                side = -1;
            } else {
                a = t;
                fa = f;
                if (side == 1) fb /= 2;
                side = 1;
            }
        }
        return t;
    }
}
