package com.wormzjl.icheme.science.equipment;

import java.util.Locale;

/** Per design of NewSweep's grid: refused (and why) or every side nozzle's role, side and height over the bottom tangent. */
public class PerDesign {
    public static void main(String[] args) {
        for (double d : new double[]{.5, 1, 1.5, 2, 3, 4, 6, 8, 15})
            for (int n : new int[]{1, 3, 5, 10, 15, 20, 30, 40, 64})
                for (String config : new String[]{"kettle", "kettle+steam+draw+PA", "steam+draw", "full"}) {
                    ColumnTrays t;
                    try {
                        t = NewSweep.trays(n, config);
                    } catch (IllegalArgumentException invalid) {
                        continue;
                    }
                    var s = EquipmentShape.column(d, t, EquipmentShape.Heading.NORTH, EquipmentShape.minimumSkirt(d));
                    var line = new StringBuilder(String.format(Locale.ROOT, "d %4.1f n %2d %-22s", d, n, config));
                    var misfit = ColumnDesign.misfit(s);
                    if (misfit.isPresent()) line.append("REFUSED ").append(misfit.get().replaceAll(" of a [0-9]+-tray column", ""));
                    else for (var nozzle : s.nozzles())
                        if (nozzle.axis() != 1) line.append(String.format(Locale.ROOT, " %s/%d@%.1f", nozzle.role().name().toLowerCase(Locale.ROOT), NewSweep.side(nozzle.axis(), nozzle.sign()), nozzle.base()[1] - s.bottomTangent()));
                    System.out.println(line);
                }
    }
}
