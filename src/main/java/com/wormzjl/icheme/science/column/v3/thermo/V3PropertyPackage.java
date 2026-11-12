package com.wormzjl.icheme.science.column.v3.thermo;

import com.wormzjl.icheme.science.column.v3.V3ComponentBasis;
import com.wormzjl.icheme.science.material.DerivedPairs;
import java.util.List;

/** Immutable property package selected by V3 before a solve begins. */
interface V3PropertyPackage {
    String packageId();
    String datasetRevision();
    V3ComponentBasis componentBasis();
    V3PropertyComponent component(int publicComponent);
    V3CrudeFeed crudeFeed(String assayId);
    double minimumTemperatureKelvin();
    double maximumTemperatureKelvin();
    double minimumPressurePascal();
    double maximumPressurePascal();
    /** The package's authored pair matrix (its pair libraries), for the readers that compare packages by it. */
    double[][] binaryInteractions();

    /**
     * The derivation of this package's pairs over its components in order ({@link DerivedPairs#derive}, batch
     * 2026-11-11-aqueous-solvents, W5 D1): the kernel the column evaluates, the network's. Null for a package built
     * outside a catalog: its kernel is the classical one of {@link #binaryInteractions()}.
     */
    default DerivedPairs.Result derivedPairs() {
        return null;
    }

    /** Non-blocking, dataset-owned evidence copied into every calculation's audit and diagnostics. */
    default List<String> advisoryEvidence() {
        return List.of();
    }
}
