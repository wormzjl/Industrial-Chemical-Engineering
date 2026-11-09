package com.wormzjl.icheme.science.material;
/** Structural dimensions only. Scientific expectations belong to independent fixtures. */
public final class MaterialTestBasis {
    private MaterialTestBasis() {}
    public static final int CRUDE = MaterialCatalog.bundled().requirePackage("icheme:tjl20_methane").components().size();
    public static final int NETWORK = MaterialCatalog.bundled().requirePackage(MaterialCatalog.bundled().presets().network().packageId()).components().size();
    public static final int NITROGEN = MaterialCatalog.bundled().requirePackage(MaterialCatalog.bundled().presets().network().packageId()).components().indexOf("Nitrogen");
}
