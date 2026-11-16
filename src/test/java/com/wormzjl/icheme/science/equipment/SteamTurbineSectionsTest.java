package com.wormzjl.icheme.science.equipment;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.equipment.SteamTurbineDesign.Section;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A steam turbine's sections, one rule for every size (batch 2026-11-15-equipment-visual-design; owner: "HP turbine should
 * include MP and LP section, MP should include LP section", straight through, an LP class, every size): HP steam expands
 * through its HP, MP and LP sections, MP steam through its MP and LP sections, LP steam through its LP section. Up to one
 * casing's 45 MW they are barrels of the one casing, above it casings on one shaft.
 */
class SteamTurbineSectionsTest {
    private static List<Section> of(double kw, MachineDuty.Steam steam, double exhaust) {
        return SteamTurbineDesign.sections(MachineDuty.turbine(kw, steam, exhaust));
    }

    @Test void everySizeTakesTheSectionsItsSteamExpandsThrough() {
        for (double kw : new double[]{55, 12800, 45000, 63000, 100000}) {
            assertEquals(List.of(Section.MP, Section.LP), of(kw, MachineDuty.Steam.HP, 10), "HP = HP + MP + LP at " + kw + " kW");
            assertEquals(List.of(Section.MP, Section.LP), of(kw, MachineDuty.Steam.VHP, 350), "VHP enters an HP section");
            assertEquals(List.of(Section.LP), of(kw, MachineDuty.Steam.MP, 150), "MP = MP + LP");
            assertEquals(List.of(), of(kw, MachineDuty.Steam.LP, 10), "LP = LP: the front section is the LP");
        }
        assertEquals(List.of(Section.MP, Section.LP_DOUBLE), of(300000, MachineDuty.Steam.HP, 10), "double-flow above 100 MW");
        assertEquals(List.of(Section.MP, Section.LP_DOUBLE), of(300000, MachineDuty.Steam.VHP, 10));
        assertEquals(List.of(Section.LP_DOUBLE), of(150000, MachineDuty.Steam.MP, 20));
        for (var steam : MachineDuty.Steam.values())
            assertEquals(steam == MachineDuty.Steam.LP ? Section.LP : steam == MachineDuty.Steam.MP ? Section.MP : Section.HP,
                    SteamTurbineDesign.front(MachineDuty.turbine(5000, steam, 10)));
    }

    /** A turbine saved with a back pressure keeps loading and running; the same rule gives it fewer sections, with no special case. */
    @Test void aSavedBackPressureTurbineHasFewerSectionsByTheSameRule() {
        assertEquals(List.of(Section.MP), of(12800, MachineDuty.Steam.HP, 450), "into the LP header: no LP section");
        assertEquals(List.of(Section.MP), of(150000, MachineDuty.Steam.HP, 1000));
        assertEquals(List.of(), of(150000, MachineDuty.Steam.HP, 2100), "above the MP header: the HP section alone");
        assertEquals(List.of(), of(100000, MachineDuty.Steam.MP, 650), "MP above the LP header: the MP section alone");
        var design = UnitDesign.of(EquipmentShape.Kind.STEAM_TURBINE);
        assertDoesNotThrow(() -> design.check(design.size(MachineDuty.turbine(12800, MachineDuty.Steam.HP, 1700)), 1, MachineDuty.turbine(12800, MachineDuty.Steam.HP, 1700)));
    }

    /** Each section's drop runs between the header pressures, 200 kJ/kg per e-fold (plan section 2: 181 + 266 + 761 from HP to 10 kPa). */
    @Test void theDropSplitsAtTheHeaders() {
        var hp = MachineDuty.turbine(12800, MachineDuty.Steam.HP, 10);
        double[] drops = SteamTurbineDesign.drops(hp);
        assertArrayEquals(new double[]{181, 266, 761}, drops, 1);
        assertEquals(SteamTurbineDesign.drop(hp), drops[0] + drops[1] + drops[2], 1e-9, "the sections share the whole drop");
        assertArrayEquals(new double[]{354, 266, 761}, SteamTurbineDesign.drops(MachineDuty.turbine(12800, MachineDuty.Steam.VHP, 10)), 1);
        assertArrayEquals(new double[]{266, 761}, SteamTurbineDesign.drops(MachineDuty.turbine(12800, MachineDuty.Steam.MP, 10)), 1);
        assertArrayEquals(new double[]{761}, SteamTurbineDesign.drops(MachineDuty.turbine(12800, MachineDuty.Steam.LP, 10)), 1);
        assertEquals(12, SteamTurbineDesign.stages(hp));
        assertEquals(11, SteamTurbineDesign.stages(MachineDuty.turbine(12800, MachineDuty.Steam.MP, 10)));
        assertEquals(8, SteamTurbineDesign.stages(MachineDuty.turbine(12800, MachineDuty.Steam.LP, 10)));
    }

    /**
     * Up to 45 MW one casing, stepped: a barrel per section (the HP end 0.65 Dc, the MP 0.85 Dc, the exhaust end Dc) between
     * the end walls one section had, each about as long as its share of the drop; the steam chest stays on the front
     * barrel and the exhaust branch on the last, so the stations that place nozzles and solids do not move.
     */
    @Test void oneCasingStepsABarrelPerSection() {
        var duty = MachineDuty.turbine(12800, MachineDuty.Steam.HP, 10);
        double d = 1.6;
        var one = new SteamTurbineTrain(d, SteamTurbineDesign.stages(duty), true, 1);
        var stepped = new SteamTurbineTrain(d, SteamTurbineDesign.stages(duty), true, 1, SteamTurbineDesign.drops(duty));
        assertArrayEquals(new double[]{.325 * d, .425 * d, .5 * d}, stepped.barrelRadius, 1e-12);
        assertArrayEquals(new double[]{one.hpEnd, one.exhaustEnd, one.coneEnd, one.exhaust, one.chest, one.pedestal1},
                new double[]{stepped.hpEnd, stepped.exhaustEnd, stepped.coneEnd, stepped.exhaust, stepped.chest, stepped.pedestal1}, 0, "the stations stay");
        assertEquals(one.hpEnd, stepped.barrelStart[0], 1e-12);
        assertEquals(one.exhaustEnd, stepped.barrelEnd[2], 1e-12);
        for (int k = 0; k < 2; k++) assertTrue(stepped.barrelEnd[k] < stepped.barrelStart[k + 1] && stepped.barrelStart[k] < stepped.barrelEnd[k], "barrels in order");
        assertTrue(stepped.barrelEnd[0] >= stepped.chest + stepped.chestRadius(), "the steam chest on the front barrel");
        assertTrue(stepped.lastBarrel() < stepped.exhaust - .2 * d, "the exhaust branch on the last barrel");
        double lp = stepped.barrelEnd[2] - stepped.barrelStart[2], mp = stepped.barrelEnd[1] - stepped.barrelStart[1];
        assertTrue(lp > mp && lp > stepped.barrelEnd[0] - stepped.barrelStart[0], "the LP barrel, 63 % of the drop, the longest");
        for (double z = stepped.hpEnd; z <= stepped.exhaustEnd; z += .01) assertTrue(stepped.radius(z) <= d / 2 + 1e-12, "inside one casing's envelope at " + z);
        // One section: the barrels one casing always had.
        var lpOnly = new SteamTurbineTrain(1, 8, true, 1, new double[]{761});
        assertArrayEquals(new double[]{.325, .5}, lpOnly.barrelRadius, 1e-12);
        assertEquals(lpOnly.coneEnd, lpOnly.lastBarrel(), 1e-12);
    }

    /**
     * W6, the LP class above one casing (owner: "really need to improve its visual", exhaust "Top, short neck"): a single
     * flow's inlet belt and flare both have length at every LP duty (the flare at least 0.15 of the casing), and the inlet
     * riser and the exhaust neck stand in cell columns of their own, both on the shaft line, the neck under the hood's
     * cover. 350 kPa, which the plan names, is not an LP exhaust (at most half its 4.5 bar): 150 kPa is its highest.
     */
    @Test void anLpTurbineHasItsBeltFlareInletAndNeck() {
        for (double kw : new double[]{46000, 63000, 100000, 150000})
            for (double exhaust : new double[]{10, 150}) {
                var duty = MachineDuty.turbine(kw, MachineDuty.Steam.LP, exhaust);
                var design = UnitDesign.of(EquipmentShape.Kind.STEAM_TURBINE);
                double d = design.size(duty), half = (kw > SteamTurbineDesign.DOUBLE_FLOW ? SteamTurbineDesign.LP_DOUBLE_WIDTH : SteamTurbineDesign.LP_WIDTH) * d / 2;
                var t = new SteamTurbineTrain(d, SteamTurbineDesign.stages(duty), duty.vacuum(), 1, new double[]{1}, half);
                System.out.printf(java.util.Locale.ROOT, "LP_CASING %.0f kW to %.0f kPa: d %.1f, stages %d, casing %.3f m, belt %.3f m, flare %.3f m, %s%n",
                        kw, exhaust, d, SteamTurbineDesign.stages(duty), t.casing, t.beltLength(), t.flareLength(), t.doubleFlowHood() ? "double flow" : "single flow");
                assertEquals(3, d, 1e-9, "every turbine above one casing is 3 m");
                assertTrue(t.beltLength() > 0 && t.flareLength() >= .15 * t.casing - 1e-12, "belt and flare at " + kw + " kW, " + exhaust + " kPa");
                assertTrue(t.domeRadius() <= t.beltLength() / 2 || t.doubleFlowHood(), "the dome on the belt");
                var shape = EquipmentShape.unit(EquipmentShape.Kind.STEAM_TURBINE, d, 1, EquipmentShape.Heading.NORTH, 0).withDuty(duty);
                var ports = ShapeRasterizer.ports(shape);
                assertEquals(2, ports.size());
                var steam = ports.get(0).cell();
                var out = ports.get(1).cell();
                assertEquals(steam.x(), out.x(), "both on the shaft line");
                assertNotEquals(steam.z(), out.z(), "each in a cell column of its own at " + kw + " kW, " + exhaust + " kPa");
                assertEquals(1, ports.get(0).dy());
                assertEquals(1, ports.get(1).dy(), "both up");
            }
    }

    @Test void theRangeRunsTo300MegawattsAndKeepsTheCasingAt3Metres() {
        var design = UnitDesign.of(EquipmentShape.Kind.STEAM_TURBINE);
        var top = design.step(MachineDuty.turbine(45000, MachineDuty.Steam.HP, 10), "power", 100);
        assertEquals(300000, top.power(), 1e-9);
        assertEquals(3, design.size(top), 1e-9, "the front casing stays 3 m; the sections carry the rest");
        assertThrows(IllegalArgumentException.class, () -> EquipmentShape.unit(EquipmentShape.Kind.STEAM_TURBINE, 3, 1, EquipmentShape.Heading.NORTH, 0)
                .withDuty(MachineDuty.turbine(315000, MachineDuty.Steam.HP, 10)));
    }

    /** The exhaust steps along the turbine exhausts, below the LP header and at most half the inlet; a saved back pressure steps onto them. */
    @Test void theExhaustStepsBelowTheLpHeader() {
        var design = UnitDesign.of(EquipmentShape.Kind.STEAM_TURBINE);
        assertEquals(350, design.step(MachineDuty.turbine(12800, MachineDuty.Steam.HP, 10), "exhaust", 100).exhaust(), 0);
        assertEquals(150, design.step(MachineDuty.turbine(1000, MachineDuty.Steam.LP, 10), "exhaust", 100).exhaust(), 0, "LP: 10 to 150 kPa");
        assertEquals(350, design.step(MachineDuty.turbine(12800, MachineDuty.Steam.HP, 1000), "exhaust", -1).exhaust(), 0);
        assertEquals(150, MachineDuty.turbine(1000, MachineDuty.Steam.HP, 350).withSteam(MachineDuty.Steam.LP).exhaust(), 0);
        assertEquals(350, MachineDuty.turbine(1000, MachineDuty.Steam.HP, 1000).withSteam(MachineDuty.Steam.VHP).exhaust(), 0);
        assertEquals(20, MachineDuty.turbine(1000, MachineDuty.Steam.HP, 20).withSteam(MachineDuty.Steam.LP).exhaust(), 0);
    }
}
