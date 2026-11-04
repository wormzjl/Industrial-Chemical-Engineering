package com.wormzjl.icheme.science.material;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FluidAppearanceTest {
    private static final String PACKAGE = "icheme:tjl20_methane";

    @AfterEach void reset() { MaterialRuntime.reset(); }

    @Test void bundledCrudeFractionsDarkenWithNormalBoilingPoint(){
        var catalog=MaterialCatalog.bundled();
        var fractions=catalog.requirePackage(PACKAGE).properties().stream().filter(p->catalog.name(p.component()).kind().equals("petroleum_fraction"))
                .sorted(java.util.Comparator.comparingDouble(MaterialCatalog.Property::normalBoilingPoint)).toList();
        assertTrue(fractions.size()>1);int previous=0xFFFFFF;
        for(var fraction:fractions){
            int rgb=catalog.componentAppearance(PACKAGE,fraction.component()).rgb();
            for(int shift:new int[]{0,8,16})assertTrue((rgb>>shift&255)<(previous>>shift&255),fraction.component()+" gets darker as NBP increases");
            previous=rgb;
        }
    }

    @Test void bundledComponentsWaterAndAssaysHaveIndependentAppearances() {
        var catalog = MaterialCatalog.bundled();
        assertThrows(IllegalArgumentException.class,()->catalog.componentAppearance(PACKAGE,"TJL_PC07"));
        assertEquals(.75, catalog.componentAppearance(PACKAGE, "Water").transparency());
        for (var p : catalog.packages().values()) {
            for (String component : p.components()) assertTrue(catalog.componentAppearance(p.id(), component).estimated());
            for (String assay : p.assays().keySet()) assertTrue(catalog.assayAppearance(p.id(), assay).estimated());
        }
        assertEquals(0x16120E, catalog.assayAppearance(PACKAGE, "icheme:oil_sand_dilbit").rgb());
        assertThrows(IllegalArgumentException.class, () -> catalog.componentAppearance(PACKAGE, "Unknown"));
        // An assay applies to every package that holds its compounds; the pilot holds no crude fraction.
        assertEquals(0x16120E, catalog.assayAppearance("icheme:tjl20_methane_nitrogen", "icheme:oil_sand_dilbit").rgb());
        assertThrows(IllegalArgumentException.class, () -> catalog.assayAppearance("icheme:pilot_cryogenic", "icheme:oil_sand_dilbit"));
    }

    @Test void appearanceEditsPreserveThePackagesScienceAndTransport() {
        var original = MaterialCatalog.bundled();
        for (String path : new String[]{"components/crude_pc07.json", "assays/medium_crude.json"}) {
            var changed = MaterialCatalog.parse(MaterialCatalogTest.changed(path, o -> o.add("appearance", appearance("#123456", .4))));
            assertEquals(original.requirePackage(PACKAGE), changed.requirePackage(PACKAGE), "the package record, its assays included");
            assertEquals(original.liquidMixture(PACKAGE), changed.liquidMixture(PACKAGE));
            for (String component : original.requirePackage(PACKAGE).components())
                for (var phase : ViscosityCorrelation.Phase.values())
                    assertEquals(original.viscosity(PACKAGE, component, phase), changed.viscosity(PACKAGE, component, phase), component);
            var result = path.startsWith("components") ? changed.componentAppearance(PACKAGE, "crude_pc07")
                    : changed.assayAppearance(PACKAGE, "icheme:medium_crude");
            assertEquals(new MaterialCatalog.FluidAppearance(0x123456, .4, true), result);
        }
    }

    @Test void recordsWithoutAppearanceAreRefused() {
        for (String path : new String[]{"components/water.json", "assays/medium_crude.json"})
            assertThrows(IllegalArgumentException.class, () -> MaterialCatalog.parse(MaterialCatalogTest.changed(path, o -> o.remove("appearance"))), path);
    }

    @Test void invalidAppearanceIdentifiesResourceAndRetainsPublishedSnapshot() {
        var original = MaterialCatalog.bundled();
        MaterialRuntime.publish(original);
        for (String path : new String[]{"components/water.json", "assays/medium_crude.json"}) {
            for (JsonObject appearance : new JsonObject[]{appearance("blue", .5), appearance("#00112233", .5),
                    appearance("#123456", -.1), appearance("#123456", 1.1)}) {
                var resources = MaterialCatalogTest.changed(path, o -> o.add("appearance", appearance));
                var error = assertThrows(IllegalArgumentException.class, () -> MaterialRuntime.publish(MaterialCatalog.parse(resources)));
                assertTrue(error.getMessage().contains(path));
                assertTrue(error.getMessage().contains("appearance"));
                assertSame(original, MaterialRuntime.active());
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new MaterialCatalog.FluidAppearance(0, Double.NaN, true));
        assertThrows(IllegalArgumentException.class, () -> new MaterialCatalog.FluidAppearance(0, Double.POSITIVE_INFINITY, true));
    }

    @Test void transparencyHasExplicitOpaqueAndInvisibleEndpoints() {
        assertEquals(0xFF123456, new MaterialCatalog.FluidAppearance(0x123456, 0, true).argb());
        assertEquals(0x00123456, new MaterialCatalog.FluidAppearance(0x123456, 1, true).argb());
        assertEquals(0x80123456, new MaterialCatalog.FluidAppearance(0x123456, .5, true).argb());
    }

    private static JsonObject appearance(String color, double transparency) {
        var o = new JsonObject();
        o.addProperty("color", color); o.addProperty("transparency", transparency); o.addProperty("estimated", true);
        return o;
    }
}
