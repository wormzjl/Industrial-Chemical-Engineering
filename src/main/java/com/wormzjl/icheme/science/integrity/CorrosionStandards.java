package com.wormzjl.icheme.science.integrity;

import com.wormzjl.icheme.science.material.MaterialCatalog;

import java.util.List;
import java.util.function.Function;

/**
 * The corrosion standards of a package's compound lists (batch 2026-11-16-fouling-corrosion, plan section 6.1): one function
 * for the world's walls ({@code FluidWorldAuthority}) and the process simulator's Materials tab, so both read a compound the
 * same way. Pure; no Minecraft types.
 */
public final class CorrosionStandards {
    private CorrosionStandards() {}

    /**
     * The standards of package {@code packageId}'s lists: its molar masses {@code molarMasses} (kg/mol, on {@code components},
     * the model's conserved order), and its formulas, sulfur shares, acid and fouling numbers taken by component name onto
     * {@code components}. A component the package lacks has no formula and reads 0.
     */
    public static Function<List<String>, CorrosionStandard> of(MaterialCatalog catalog, String packageId, List<String> components, double[] molarMasses) {
        var names = catalog.conservedComponents(packageId);
        var values = catalog.formulas(packageId);
        var formulas = new double[components.size()][];
        for (int c = 0; c < formulas.length; c++) {
            int i = names.indexOf(components.get(c));
            formulas[c] = i < 0 ? new double[MaterialCatalog.FORMULA_ELEMENTS.size()] : values[i];
        }
        return on(components, molarMasses, formulas, onList(catalog.sulfurShares(packageId), names, components),
                onList(catalog.acidNumbers(packageId), names, components), onList(catalog.foulingNumbers(packageId), names, components));
    }

    /**
     * The compound lists' standards, from per-compound columns on the list {@code world}: each list takes its compounds' columns
     * by name; a list that names a compound {@code world} lacks has none (null).
     */
    public static Function<List<String>, CorrosionStandard> on(List<String> world, double[] molarMasses, double[][] formulas, double[] sulfur, double[] acid, double[] fouling) {
        return compounds -> {
            int n = compounds.size();
            var m = new double[n];
            var f = new double[n][];
            var s = new double[n];
            var a = new double[n];
            var o = new double[n];
            for (int c = 0; c < n; c++) {
                int i = world.indexOf(compounds.get(c));
                if (i < 0) return null;
                m[c] = molarMasses[i];
                f[c] = formulas[i];
                s[c] = sulfur[i];
                a[c] = acid[i];
                o[c] = fouling[i];
            }
            return new CorrosionStandard(m, f, s, a, o);
        };
    }

    /** {@code values} on the package's list {@code names}, on {@code components} by name; 0 where a component is not on it. */
    private static double[] onList(double[] values, List<String> names, List<String> components) {
        var out = new double[components.size()];
        for (int c = 0; c < out.length; c++) {
            int i = names.indexOf(components.get(c));
            out[c] = i < 0 ? 0 : values[i];
        }
        return out;
    }
}
