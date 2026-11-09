package com.wormzjl.icheme.science.material;

import java.util.List;
import java.util.Objects;

/**
 * A consumer without crystal equations was given a package that admits crystal phases (P6, batch
 * 2026-09-24-coolprop-low-temperature): a package whose {@code crystals} list is not empty, such as the network package
 * {@code icheme:refinery} with its CO2-I crystal (or the test-only pilot {@code icheme:pilot_cryogenic}). The V3 column is a vapour-liquid algorithm, and a projected
 * neural inference view is a PR78 axis without crystal records; each refuses such a package with this type rather than
 * running it fluid-only, where a crystal could form that it would never see (plan P6: "do not feed crystals to a VLE-only
 * column algorithm without corresponding equations; give a meaningful unsupported-phase result").
 *
 * <p>It is an {@link IllegalArgumentException} (an unusable package, like any other refused package id); its own type
 * lets a caller tell "this package's phase set is for another consumer" from malformed data or a missing ideal-gas fit
 * ({@link IdealGasFitUnavailable}).</p>
 */
public final class CrystalPhasesUnavailable extends IllegalArgumentException {
    private final String packageId;
    private final String consumer;
    private final List<String> crystals;

    public CrystalPhasesUnavailable(String packageId, List<String> crystals, String consumer) {
        super("Package " + Objects.requireNonNull(packageId, "packageId") + " admits crystal phases " + List.copyOf(crystals)
                + "; " + Objects.requireNonNull(consumer, "consumer") + " has no crystal equations and cannot use it (it would "
                + "answer fluid phases where a crystal can form)");
        this.packageId = packageId;
        this.consumer = consumer;
        this.crystals = List.copyOf(crystals);
    }

    public String packageId() { return packageId; }
    /** What refused the package, for example "the V3 column". */
    public String consumer() { return consumer; }
    /** The crystal records the package admits. */
    public List<String> crystals() { return crystals; }
}
