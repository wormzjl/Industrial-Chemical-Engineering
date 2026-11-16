package com.wormzjl.icheme.science.column.v3.thermo;

import com.wormzjl.icheme.science.column.v3.V3ComponentBasis;
import com.wormzjl.icheme.science.column.v3.V3TestInputs;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.List;

/**
 * The medium crude's package of the bundled catalog ({@link V3TestInputs#MEDIUM_CRUDE_PACKAGE}) with every binary
 * interaction zero and no derived pairs: the kernel of its constants takes the rank-one mixing path. A mechanism fixture
 * of the kernel tests; it replaces the compiled zero-pair sixteen-component test package removed on 2026-11-16.
 */
final class V3ZeroPairCrudePackage implements V3PropertyPackage {
    static final V3ZeroPairCrudePackage INSTANCE = new V3ZeroPairCrudePackage();

    private final V3PropertyPackage delegate = new V3CatalogPropertyPackage(MaterialCatalog.bundled(), V3TestInputs.MEDIUM_CRUDE_PACKAGE);

    private V3ZeroPairCrudePackage() {}

    @Override public String packageId() { return delegate.packageId(); }
    @Override public String datasetRevision() { return delegate.datasetRevision() + ";zero-pairs"; }
    @Override public V3ComponentBasis componentBasis() { return delegate.componentBasis(); }
    @Override public V3PropertyComponent component(int publicComponent) { return delegate.component(publicComponent); }
    @Override public V3CrudeFeed crudeFeed(String assayId) { return delegate.crudeFeed(assayId); }
    @Override public double minimumTemperatureKelvin() { return delegate.minimumTemperatureKelvin(); }
    @Override public double maximumTemperatureKelvin() { return delegate.maximumTemperatureKelvin(); }
    @Override public double minimumPressurePascal() { return delegate.minimumPressurePascal(); }
    @Override public double maximumPressurePascal() { return delegate.maximumPressurePascal(); }
    @Override public List<String> advisoryEvidence() { return delegate.advisoryEvidence(); }

    @Override
    public double[][] binaryInteractions() {
        int count = componentBasis().componentCount();
        return new double[count][count];
    }

    /** The medium crude's assay as mole fractions on this package's basis. */
    double[] mediumCrude() {
        return crudeFeed(V3TestInputs.MEDIUM_CRUDE_ASSAY).moleFractions();
    }

    /** The index of component {@code id}. */
    int index(String id) {
        int i = componentBasis().componentIds().indexOf(id);
        if (i < 0) throw new IllegalArgumentException(id + " is not on " + packageId());
        return i;
    }
}
