package com.wormzjl.icheme.science.material;
/** Structural dimensions only. Scientific expectations belong to independent fixtures. */
public final class MaterialTestBasis {
    private MaterialTestBasis() {}
    public static final int CRUDE = MaterialCatalog.bundled().requirePackage("icheme:tjl20_methane").components().size();
    /** The network's compounds (W3b: its package carries Water in its cubic; the conserved basis is these, then water, at this index). */
    public static final int NETWORK = MaterialCatalog.bundled().conservedComponents(MaterialCatalog.bundled().presets().network().packageId()).size() - 1;
    public static final int NITROGEN = MaterialCatalog.bundled().requirePackage(MaterialCatalog.bundled().presets().network().packageId()).components().indexOf("Nitrogen");
}
