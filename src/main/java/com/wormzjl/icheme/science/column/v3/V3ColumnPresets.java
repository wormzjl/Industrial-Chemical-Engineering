package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialPresets;
import java.util.List;

/**
 * The column's reading of a server column preset. {@link MaterialPresets.Column} holds plain numbers (the catalog imports
 * no column type, codebase review C2, THERMO_ADAPTERS_REVIEW F4); this class builds the {@link V3ColumnInput} a preset
 * stands for and refuses there what the column's records refuse.
 */
public final class V3ColumnPresets {
    private V3ColumnPresets() {}

    /**
     * The preset's input on {@code catalog}: the assay's mole fractions, scaled to the preset's standard liquid feed volume
     * by the package's molar volumes. The records are built first, in the order the parser once built them (the three
     * specifications, the draws, the steam feeds, the pumparounds), then the diameter is checked against the column's
     * bound, with the parser's text.
     */
    public static V3ColumnInput input(MaterialPresets.Column preset, MaterialCatalog catalog) {
        var set = preset.specifications();
        var specifications = List.<V3ColumnSpecification>of(new V3ColumnSpecification.CondenserOutletTemperature(set.condenserTemperature()),
                new V3ColumnSpecification.OrganicRefluxRatio(set.refluxRatio()), new V3ColumnSpecification.ReboilerDuty(set.reboilerDuty()));
        var draws = new java.util.ArrayList<V3SideDrawSpec>();
        for (var draw : preset.draws()) draws.add(new V3SideDrawSpec(draw.tray(), draw.rate()));
        var steam = new java.util.ArrayList<V3SteamFeedSpec>();
        for (var feed : preset.steam()) steam.add(new V3SteamFeedSpec(feed.stage(), feed.rate(), feed.temperature()));
        var pumparounds = new java.util.ArrayList<V3PumparoundSpec>();
        for (var cooler : preset.pumparounds())
            pumparounds.add(new V3PumparoundSpec(cooler.returnTray(), cooler.drawTray(), cooler.duty(), V3PumparoundSpec.Split.valueOf(cooler.split())));
        if (!(preset.diameter() > 0) || preset.diameter() > V3ColumnInput.MAX_COLUMN_DIAMETER_METRES)
            throw new IllegalArgumentException("operating.columnDiameterMetres: not above 0 and at most " + V3ColumnInput.MAX_COLUMN_DIAMETER_METRES + " m");
        var descriptor = preset.descriptor();
        var p = catalog.requirePackage(descriptor.packageId());
        double[] fractions = MaterialPresets.moleFractions(p, descriptor.assayId());
        double volumePerMole = 0;
        for (int i = 0; i < fractions.length; i++) volumePerMole += fractions[i] * p.properties().get(i).molecularWeight() / p.properties().get(i).density();
        double rate = preset.feedVolume() / volumePerMole;
        for (int i = 0; i < fractions.length; i++) fractions[i] *= rate;
        return new V3ColumnInput(1, p.id(), descriptor.assayId(), new V3ComponentBasis(p.components()), fractions,
                preset.feedTemperature(), preset.stages(), preset.feedStage(), preset.pressure(), preset.pressureDrop(),
                specifications, draws, steam, pumparounds, preset.diameter());
    }

    /** Whether {@code input} is on the preset's package and assay. */
    public static boolean matches(MaterialPresets.Descriptor descriptor, V3ColumnInput input) {
        return descriptor.packageId().equals(input.packageId()) && descriptor.assayId().equals(input.assayId());
    }
}
