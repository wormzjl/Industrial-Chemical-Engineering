package com.wormzjl.icheme.science.machine;


import java.util.List;
import java.util.Locale;

/**
 * A fuelled prime mover at process level (batch 2026-11-10-prime-movers, PRIME_MOVERS_PLAN.md sections 2 to 8): a
 * liquid-fuel combustion engine or a gas turbine, one catalogue line per model. Pure and Minecraft-free.
 *
 * <p>Fuel heat follows a Willans line (owner P1 A): {@code Q_f = Q_r (c₀ + (1 − c₀) x)}, {@code x = W/W_r} the gross shaft
 * load share and {@code Q_r = W_r/η_r}; so a set at no load burns {@code c₀ Q_r} holding its speed. The fuel's heat leaves as
 * shaft work, exhaust ({@link #EXHAUST_CP} from the air at {@link #AIR} to the model's exhaust temperature, held at every
 * load, S2), the engine's radiator, and {@link #LOSS} of casing and lube heat. An engine's radiator fans run off its own
 * shaft (owner P4 A): {@link #FAN_PER_COOLING} of the radiator's heat, so its net shaft power is the gross less the fans.</p>
 *
 * <p>Fuel supply (owner P5 A): the fuel enters a void at the combustor pressure {@link Model#combustor} (an engine's at
 * the atmosphere, its injection pump inside) behind a valve that passes the rated fuel wide open at {@link Model#required};
 * below it the most fuel falls as {@code √((P_s − P_c)/(P_req − P_c))}, none at {@code P_c}.</p>
 */
public record PrimeMover(Model model) {
    /** The families: a liquid-fuel combustion engine, a gas turbine on gas fuel. Saved by name. */
    public enum Family {
        ENGINE, GAS_TURBINE;

        /** The no-load fuel's share of the rated fuel heat on the Willans line. */
        public double idle() {
            return this == ENGINE ? .10 : .28;
        }
    }

    /**
     * A catalogue line: its name, family, rated gross shaft power W, rated speed rpm, rated efficiency (shaft, LHV), exhaust
     * temperature K, pressure ratio (1 for an engine) and an engine's cylinders (the sound's firing pulses; 0 for a GT).
     */
    public record Model(String name, Family family, double power, double speed, double efficiency, double exhaust, double ratio, int cylinders) {
        public Model {
            if (!(power > 0) || !(speed > 0) || !(efficiency > 0 && efficiency < 1) || !(exhaust > AIR) || !(ratio >= 1)) throw new IllegalArgumentException("Invalid prime mover " + name);
        }

        /** The combustor's pressure, Pa absolute: the pressure ratio times the atmosphere (S3: at every load). */
        public double combustor() {
            return ratio * ATMOSPHERE;
        }

        /** The supply the fuel line must deliver at full fuel, Pa absolute: 1.4 P_c for a GT, 1 bar over the atmosphere for an engine. */
        public double required() {
            return family == Family.GAS_TURBINE ? SUPPLY_RATIO * combustor() : ATMOSPHERE + ENGINE_SUPPLY;
        }

        /** The rated fuel heat, W. */
        public double ratedFuel() {
            return power / efficiency;
        }

        /** The model's key in lang lines and saved nowhere: {@code hs_500}, {@code hd_300}. */
        public String key() {
            return name.toLowerCase(Locale.ROOT).replace('-', '_');
        }
    }

    /** The combustion air, K: 25 °C, as the burners' ({@code Burners.AIR}). */
    public static final double AIR = 298.15;
    /** The atmosphere, Pa. */
    public static final double ATMOSPHERE = 101325;
    /** The exhaust's mean heat capacity from the air to the exhaust, J/(kg K) (S2). */
    public static final double EXHAUST_CP = 1120;
    /** Casing, lube and radiation heat, share of the fuel heat. */
    public static final double LOSS = .02;
    /** An engine's exhaust heat, share of the fuel heat; its radiator takes the rest. */
    public static final double ENGINE_EXHAUST = .28;
    /** An engine's radiator fans' shaft power per watt of radiator heat: 20 K of air rise at 250 Pa over a 60 % fan. */
    public static final double FAN_PER_COOLING = 250 / (1.185 * 1005 * 20 * .6);
    /** A GT's fuel supply at full fuel over its combustor pressure (the fuel nozzles and the gas valves). */
    public static final double SUPPLY_RATIO = 1.4;
    /** An engine's fuel supply over the atmosphere at full fuel, Pa. */
    public static final double ENGINE_SUPPLY = 1e5;
    /** The fuel's heating value until a fuel has arrived, J/kg: diesel's for an engine, methane's for a GT. */
    public static final double DIESEL_LHV = 42.8e6, METHANE_LHV = 50.0e6;
    /** The lowest set speed, share of rated; the highest is the rated speed (owner P11: a prime mover never overspeeds). */
    public static final double LOWEST_SPEED = .5;

    /** The catalogue (owner P2): seven engines and nine gas turbines. */
    public static final List<Model> MODELS = List.of(
            new Model("HS-500", Family.ENGINE, 5e5, 1500, .41, 753.15, 1, 12),
            new Model("HS-1000", Family.ENGINE, 1e6, 1500, .42, 753.15, 1, 16),
            new Model("HS-2000", Family.ENGINE, 2e6, 1500, .435, 743.15, 1, 20),
            new Model("MS-5000", Family.ENGINE, 5e6, 750, .455, 633.15, 1, 9),
            new Model("MS-10000", Family.ENGINE, 1e7, 750, .465, 623.15, 1, 18),
            new Model("MS-15000", Family.ENGINE, 1.5e7, 500, .475, 613.15, 1, 16),
            new Model("MS-20000", Family.ENGINE, 2e7, 500, .48, 613.15, 1, 20),
            new Model("IND-1", Family.GAS_TURBINE, 1.2e6, 3000, .25, 773.15, 7, 0),
            new Model("IND-5", Family.GAS_TURBINE, 5.7e6, 3000, .31, 763.15, 12, 0),
            new Model("IND-15", Family.GAS_TURBINE, 1.5e7, 3000, .34, 763.15, 17, 0),
            new Model("IND-30", Family.GAS_TURBINE, 3e7, 3000, .36, 773.15, 20, 0),
            new Model("AE-35", Family.GAS_TURBINE, 3.5e7, 3000, .39, 823.15, 24, 0),
            new Model("HD-45", Family.GAS_TURBINE, 4.5e7, 3000, .33, 818.15, 12.5, 0),
            new Model("AE-50", Family.GAS_TURBINE, 5e7, 3000, .41, 723.15, 31, 0),
            new Model("HD-130", Family.GAS_TURBINE, 1.3e8, 3000, .35, 813.15, 13, 0),
            new Model("HD-300", Family.GAS_TURBINE, 3e8, 3000, .39, 873.15, 18, 0));

    /** A family's models, in rising power. */
    public static List<Model> models(Family family) {
        return MODELS.stream().filter(m -> m.family() == family).toList();
    }

    /** The family's model rated nearest {@code kilowatts}. */
    public static Model model(Family family, double kilowatts) {
        Model best = null;
        for (var m : models(family)) if (best == null || Math.abs(m.power() / 1000 - kilowatts) < Math.abs(best.power() / 1000 - kilowatts)) best = m;
        return best;
    }

    /** The family's model whose rated net shaft power is {@code net} W (a shaft unit's rating), or null. */
    public static Model ofRating(Family family, double net) {
        for (var m : models(family)) if (Math.abs(new PrimeMover(m).ratedNet() - net) <= 1e-6 * net + 1) return m;
        return null;
    }

    /** The smallest model of {@code family} that holds {@code load} W with the 20 % spare ({@link Motor#SPARE}); the largest above. */
    public static Model sized(Family family, double load) {
        var list = models(family);
        for (var m : list) if (m.power() >= Motor.SPARE * load * (1 - 1e-12)) return m;
        return list.getLast();
    }

    /** The fuel heat, W, at gross shaft power {@code gross} W (at least none, at most the rated). */
    public double fuel(double gross) {
        double x = Math.clamp(gross / model.power(), 0, 1), c = model.family().idle();
        return model.ratedFuel() * (c + (1 - c) * x);
    }

    /** The gross shaft power, W, that {@code fired} W of fuel gives on the Willans line: none below the idle fuel. */
    public double gross(double fired) {
        double c = model.family().idle();
        return Math.clamp(model.power() * (Math.max(0, fired) / model.ratedFuel() - c) / (1 - c), 0, model.power());
    }

    /** The efficiency at gross shaft power {@code gross}: {@code η_r x/(c₀ + (1 − c₀) x)}. */
    public double efficiency(double gross) {
        double f = fuel(gross);
        return f > 0 ? Math.clamp(gross, 0, model.power()) / f : 0;
    }

    /** The exhaust heat, W, over the air at {@code fired} W of fuel and {@code gross} W of work. */
    public double exhaustHeat(double fired, double gross) {
        fired = Math.max(0, fired);
        return model.family() == Family.ENGINE ? ENGINE_EXHAUST * fired : Math.max(0, fired * (1 - LOSS) - gross);
    }

    /** An engine's radiator heat, W, at {@code fired} W of fuel and {@code gross} W of work; none for a GT. */
    public double cooling(double fired, double gross) {
        return model.family() == Family.ENGINE ? Math.max(0, Math.max(0, fired) * (1 - ENGINE_EXHAUST - LOSS) - gross) : 0;
    }

    /** An engine's radiator fans' shaft power, W ({@link #FAN_PER_COOLING}); none for a GT. */
    public double fans(double fired, double gross) {
        return FAN_PER_COOLING * cooling(fired, gross);
    }

    /** The net shaft power, W, that {@code fired} W of fuel gives: the gross less the radiator fans. */
    public double net(double fired) {
        double g = gross(fired);
        return Math.max(0, g - fans(fired, g));
    }

    /**
     * The gross shaft power, W, that delivers {@code net} W at the coupling: the demand and the fans the fuel for both cools.
     * Closed form on the Willans line: {@code W_g = (D + k(1−e−l) a)/(1 + k − k(1−e−l) b)}, {@code Q_f = a + b W_g}.
     */
    public double grossFor(double net) {
        net = Math.max(0, net);
        if (model.family() != Family.ENGINE) return Math.min(model.power(), net);
        double c = model.family().idle(), a = model.ratedFuel() * c, b = model.ratedFuel() * (1 - c) / model.power(), s = 1 - ENGINE_EXHAUST - LOSS, k = FAN_PER_COOLING;
        double g = (net + k * s * a) / (1 + k - k * s * b);
        return Math.clamp(g, 0, model.power());
    }

    /** The rated net shaft power, W: the gross rating less its fans. */
    public double ratedNet() {
        return net(model.ratedFuel());
    }

    /** The exhaust's mass flow, kg/s, carrying {@code exhaustHeat} W at the model's exhaust temperature. */
    public double exhaustFlow(double exhaustHeat) {
        return Math.max(0, exhaustHeat) / (EXHAUST_CP * (model.exhaust() - AIR));
    }

    /** The exhaust's capacity rate, W/K, at {@code exhaustHeat} W (the steam coil's gas side). */
    public double flue(double exhaustHeat) {
        return EXHAUST_CP * exhaustFlow(exhaustHeat);
    }

    /** The share of the rated fuel a supply at {@code supply} Pa can pass: 1 at or over {@link Model#required}, none at or under the combustor's. */
    public double supplyShare(double supply) {
        double low = model.family() == Family.GAS_TURBINE ? model.combustor() : ATMOSPHERE, high = model.required();
        if (!(supply > low)) return 0;
        return supply >= high ? 1 : Math.sqrt((supply - low) / (high - low));
    }

    /** The fuel's heat, J/kg, a set uses until its fuel has arrived. */
    public double defaultHeatingValue() {
        return model.family() == Family.ENGINE ? DIESEL_LHV : METHANE_LHV;
    }

    /** The stack's diameter, m, for the rated exhaust at {@link #STACK_SPEED} at its temperature and the atmosphere (at most 10 m). */
    public double stackDiameter() {
        double flow = exhaustFlow(exhaustHeat(model.ratedFuel(), model.power())), density = ATMOSPHERE * .0288 / (8.314 * model.exhaust());
        return Math.min(10, Math.sqrt(4 * flow / density / STACK_SPEED / Math.PI));
    }

    /** The exhaust's velocity in the stack at the rated point, m/s. */
    public static final double STACK_SPEED = 25;
}
