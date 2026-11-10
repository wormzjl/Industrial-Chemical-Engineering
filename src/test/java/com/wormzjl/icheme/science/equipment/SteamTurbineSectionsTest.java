package com.wormzjl.icheme.science.equipment;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.equipment.SteamTurbineDesign.Section;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A steam turbine above one casing, drawn as tandem sections (batch 2026-11-10-prime-movers, owner P12: "HP=HP+MP+LP, MP=MP+LP, LP=LP"). */
class SteamTurbineSectionsTest {
    private static List<Section> of(double kw, MachineDuty.Steam steam, double exhaust) {
        return SteamTurbineDesign.sections(MachineDuty.turbine(kw, steam, exhaust));
    }

    @Test void sectionsFollowTheInletAndTheExhaust() {
        assertEquals(List.of(), of(45000, MachineDuty.Steam.HP, 10), "one casing to 45 MW: today's turbines stand as they are");
        assertEquals(List.of(Section.MP, Section.LP_DOUBLE), of(300000, MachineDuty.Steam.HP, 10), "HP = HP + MP + LP");
        assertEquals(List.of(Section.MP, Section.LP_DOUBLE), of(300000, MachineDuty.Steam.VHP, 10));
        assertEquals(List.of(Section.LP), of(80000, MachineDuty.Steam.MP, 10), "MP = MP + LP");
        assertEquals(List.of(Section.MP), of(150000, MachineDuty.Steam.HP, 450), "an exhaust into the LP header leaves the LP out");
        assertEquals(List.of(), of(150000, MachineDuty.Steam.HP, 2100), "above the MP class: the HP casing alone");
        assertEquals(List.of(), of(100000, MachineDuty.Steam.MP, 650), "LP = LP: MP steam into a header above the LP");
        assertEquals(List.of(Section.MP, Section.LP), of(100000, MachineDuty.Steam.HP, 10), "single flow to 100 MW");
    }

    @Test void theRangeRunsTo300MegawattsAndKeepsTheCasingAt3Metres() {
        var design = UnitDesign.of(EquipmentShape.Kind.STEAM_TURBINE);
        var top = design.step(MachineDuty.turbine(45000, MachineDuty.Steam.HP, 10), "power", 100);
        assertEquals(300000, top.power(), 1e-9);
        assertEquals(3, design.size(top), 1e-9, "the front casing stays 3 m; the sections carry the rest");
        assertThrows(IllegalArgumentException.class, () -> EquipmentShape.unit(EquipmentShape.Kind.STEAM_TURBINE, 3, 1, EquipmentShape.Heading.NORTH, 0)
                .withDuty(MachineDuty.turbine(315000, MachineDuty.Steam.HP, 10)));
    }
}
