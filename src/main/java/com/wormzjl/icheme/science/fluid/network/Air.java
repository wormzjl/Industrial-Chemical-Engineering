package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.List;

/**
 * The ambient air a gas intake draws: the standard atmosphere's dry air, wetted to the game's ambient humidity at its
 * ambient temperature and pressure ({@link CoolingTower#AIR_TEMPERATURE}, {@link CoolingTower#RELATIVE_HUMIDITY},
 * {@link CoolingTower#PRESSURE}: 25 °C, 50 %, 101 325 Pa).
 *
 * <p>The dry mole fractions are the standard atmosphere's (ISO 2533 and the U.S. Standard Atmosphere 1976, Table 3),
 * nitrogen the balance: oxygen 0.20946, argon 0.00934, carbon dioxide 314 ppm, neon 18.18 ppm, helium 5.24 ppm,
 * krypton 1.14 ppm, xenon 0.087 ppm. Water is {@code y_w = RH p_sat(T) / p} on the model's own water (about 0.0156); the
 * dry gases share {@code 1 - y_w}. The dew point of that air at 1 atm is 13.9 °C.</p>
 */
public final class Air {
    /** The dry gases other than nitrogen, mole fractions of dry air; nitrogen is the balance. */
    static final List<Share> DRY = List.of(new Share("Oxygen", .20946), new Share("Argon", .00934), new Share("CarbonDioxide", 314e-6),
            new Share("Neon", 18.18e-6),
            new Share("Helium", 5.24e-6), new Share("Krypton", 1.14e-6), new Share("Xenon", .087e-6));
    static final String NITROGEN = "Nitrogen", WATER = "Water";

    /** One dry gas and its mole fraction of dry air. */
    record Share(String id, double fraction) {}

    private Air() {}

    /**
     * The intake's air on {@code model}'s component list: mole fractions summing to 1. A gas the list lacks (a package without
     * the rare gases) is left to nitrogen, the balance, so the air stays whole.
     */
    public static double[] wet(FluidThermodynamics model) {
        var ids = model.components();
        double[] y = new double[ids.size()];
        int nitrogen = ids.indexOf(NITROGEN), water = ids.indexOf(WATER);
        if (nitrogen < 0) throw new IllegalArgumentException("The network holds no nitrogen: no air");
        double wet = water < 0 ? 0 : CoolingTower.RELATIVE_HUMIDITY * model.saturationPressure(CoolingTower.AIR_TEMPERATURE) / CoolingTower.PRESSURE;
        double dry = 1 - wet, balance = 1;
        for (var share : DRY) {
            int i = ids.indexOf(share.id());
            if (i < 0) continue;
            y[i] = share.fraction() * dry;
            balance -= share.fraction();
        }
        y[nitrogen] = balance * dry;
        if (water >= 0) y[water] = wet;
        return y;
    }
}
