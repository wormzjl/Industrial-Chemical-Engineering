package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.science.material.*;

/**
 * The configured network package. A world's saved basis names it by compound ids and fluid identity, never by its id
 * ({@code WorldTopologyLedger.FluidBasis}): a renamed package keeps the world's state, and another compound list restates
 * the world's contents by id when it loads.
 */
public final class FluidMaterialCatalog {
    /** Bundled fixture IDs; runtime code resolves the selected network from its catalog snapshot. */
    public static final String NETWORK_PACKAGE="icheme:tjl20_methane_nitrogen";
    public static final String NITROGEN="Nitrogen";
    private FluidMaterialCatalog() {}
    public static String networkPackage(MaterialCatalog catalog){return catalog.presets().network().packageId();}
    public static int conservedCount(){return MaterialRuntime.current().requirePackage(networkPackage(MaterialRuntime.current())).components().size()+1;}
    public static int nitrogenIndex(){return new MaterialAxis(MaterialRuntime.current().requirePackage(networkPackage(MaterialRuntime.current())).components()).requireIndex(NITROGEN);}
    public static int waterIndex(){return conservedCount()-1;}
}
