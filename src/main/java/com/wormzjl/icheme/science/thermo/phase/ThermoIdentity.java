package com.wormzjl.icheme.science.thermo.phase;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.Objects;

/**
 * The thermodynamic identity of a package as the phase engine evaluates it: everything that decides a phase state's
 * numbers. Two results with the same {@link #revision()} come from the same data and the same formulation.
 *
 * <p>Parts:</p>
 * <ul>
 *   <li>{@code packageRevision}: the package's data identity, the declared revision of every record its fluid models
 *   read; {@link #of} takes {@link MaterialCatalog#fluidRevision(String)}.</li>
 *   <li>{@code evaluatorFamily}: the residual formulation, {@link CubicPhaseEvaluator#family()}.</li>
 *   <li>{@code spineRevision}: the ideal-gas and formation data (the data spine, decision D6),
 *   {@link MaterialCatalog#spineRevision(String)}, the spine and crystal records' declared revisions; {@link #NO_SPINE} for a package without either.</li>
 *   <li>{@code waterModelRevision}: the water model and how it participates ({@link WaterParticipation}).</li>
 * </ul>
 *
 * <p>Nothing is hashed (owner decision 2026-10-26): every part is a declared revision, so a change to a package's data
 * changes the identity only through the bump of the revision that covers it.</p>
 *
 * <p>Cache rule: anything that stores a number computed from a phase state, or a decision derived from one, keys on
 * {@link #revision()} and drops it, never migrates it, when it changes: the fluid network's thermodynamic revision
 * ({@code FluidThermodynamics.thermodynamicRevision}; saved contents keep their amounts and temperature and restate),
 * regression fixtures and pins, and any approximation state (warm starts, factorizations) built on the phase engine. The
 * column's neural models key instead on {@link MaterialCatalog.Package#scientificRevision()} and compare package content
 * directly ({@link MaterialCatalog#samePhysics(String, String, java.util.List)}).</p>
 *
 * <p>The revision encodes every part length-prefixed, so no two different identities share a revision whatever
 * characters the parts contain.</p>
 */
public final class ThermoIdentity {
    /** The spine revision of a package without a spine. */
    public static final String NO_SPINE = "spine:none";
    /** The water model revision of a package without water. */
    public static final String NO_WATER = "water:none";
    /** The formulation label of the separate free-water approximation; bumped when its equations change (v2: ice Ih below
     * the melting line, WP4 of batch 2026-09-24-coolprop-low-temperature; v3: the steam a second-virial gas from
     * IAPWS-IF97 Region 2, {@link SteamVirial}, batch 2026-10-27-heat-exchanger-design). */
    public static final String SEPARATE_FREE_WATER_FORMULATION = "separate-free-water-v3";
    private static final String FORMAT = "thermo-identity-v2";

    private final String packageRevision;
    private final String evaluatorFamily;
    private final String spineRevision;
    private final String waterModelRevision;
    private final String revision;

    public ThermoIdentity(String packageRevision, String evaluatorFamily, String spineRevision,
                          String waterModelRevision) {
        this.packageRevision = requirePart(packageRevision, "packageRevision");
        this.evaluatorFamily = requirePart(evaluatorFamily, "evaluatorFamily");
        this.spineRevision = requirePart(spineRevision, "spineRevision");
        this.waterModelRevision = requirePart(waterModelRevision, "waterModelRevision");
        this.revision = FORMAT + part(this.packageRevision) + part(this.evaluatorFamily) + part(this.spineRevision)
                + part(this.waterModelRevision);
    }

    /** The identity of a catalog package evaluated by {@code evaluatorFamily} with {@code water} participation. */
    public static ThermoIdentity of(MaterialCatalog catalog, String packageId, String evaluatorFamily,
                                    String spineRevision, WaterParticipation water) {
        Objects.requireNonNull(water, "water");
        MaterialCatalog.Package propertyPackage = catalog.requirePackage(packageId);
        String waterRevision = water == WaterParticipation.NONE ? NO_WATER
                : SEPARATE_FREE_WATER_FORMULATION + ":" + propertyPackage.water().revision();
        return new ThermoIdentity(catalog.fluidRevision(packageId), evaluatorFamily, spineRevision, waterRevision);
    }

    public String packageRevision() { return packageRevision; }
    public String evaluatorFamily() { return evaluatorFamily; }
    public String spineRevision() { return spineRevision; }
    public String waterModelRevision() { return waterModelRevision; }
    /** The cache key; see the class comment. */
    public String revision() { return revision; }

    @Override public boolean equals(Object other) {
        return other instanceof ThermoIdentity identity && revision.equals(identity.revision);
    }
    @Override public int hashCode() { return revision.hashCode(); }
    @Override public String toString() { return revision; }

    private static String requirePart(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static String part(String value) { return ";" + value.length() + ":" + value; }
}
