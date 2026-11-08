package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The fresh-calculator preset is the literature column at its published duties; a cold top carries its water over to the drum. */
@org.junit.jupiter.api.extension.ExtendWith(com.wormzjl.icheme.science.material.TiaJuanaExtension.class)
class V3LiteraturePresetTest {
    @Test
    void theLiteraturePresetIsTheFortyTrayThesisColumnWithItsCoolersAtTheDraws() {
        V3ColumnInput input = V3TestInputs.literatureCduInput();
        V3ColumnProblemResolver.validateInput(input);
        assertEquals(V3TestInputs.LITERATURE_PACKAGE, input.packageId());
        assertEquals(40, input.stageCount());
        assertEquals(37, input.feedStageNumber());
        assertEquals(250_000.0, input.topPressurePascal());
        assertEquals(0.0, input.stagePressureDropPascal());
        assertEquals(V3ColumnInput.DEFAULT_COLUMN_DIAMETER_METRES, input.columnDiameterMetres());
        assertEquals(3, input.sideDraws().size());
        assertEquals(3, input.pumparounds().size());
        for (int index = 0; index < 3; index++) {
            V3SideDrawSpec draw = input.sideDraws().get(index);
            V3PumparoundSpec cooler = input.pumparounds().get(index);
            assertEquals(draw.trayNumber(), cooler.drawTray(), "cooler drawn at the product draw stage");
            assertEquals(draw.trayNumber() - 2, cooler.returnTray(), "cooler returned two stages above");
            assertTrue(cooler.dutyWatts() < 0.0, "coolers remove heat");
        }
        assertEquals(-12.84e6, input.pumparounds().get(0).dutyWatts(), 1.0, "published top-cooler duty");
        assertEquals(-41.93e6, input.pumparounds().stream().mapToDouble(V3PumparoundSpec::dutyWatts).sum(), 1.0e3);
        assertEquals(1, input.steamFeeds().size());
        assertEquals(41, input.steamFeeds().get(0).stageNumber());
        assertEquals(1_200.0 / 3.6, input.steamFeeds().get(0).molarFlowMolPerSecond(), 1.0e-9);
    }

    /**
     * At the published duties tray 1 is below the water dew point of its steam-laden overhead (83 C against
     * 87 C, ratio 1.16 on the dry solve) because the reconstruction carries no side-stripper reboiler heat. Before
     * E31 (batch 2026-11-04-open-thermo-data) no free-water phase could lift that tray and the column published dry
     * with a dew-point warning. With condenser carry-over the excess water leaves tray 1 as entrained liquid, the
     * drum decants it with its own condensate, tray 1 sits on its saturation line, and the accepted profile grades
     * WET_EQUILIBRIUM with no warning.
     *
     * <p>Pinned in the prescribed-drop mode the numbers were measured in, so the carry-over is unlimited. The shipped
     * preset authors a column diameter, and the flow-dependent tray pressure drop moves every one of them; that
     * published state is covered by {@link V3TrayHydraulicsColumnTest} instead.</p>
     */
    @Test
    void theLiteraturePresetCarriesTrayOnesFreeWaterOverAndGradesWet() {
        AtomicReference<V3NeuralSeed> profile = new AtomicReference<>();
        V3ColumnOutcome outcome = V3ColumnCalculator.calculateWithAcceptedProfile(
                V3TestInputs.literatureCduInput().withColumnDiameter(0.0), () -> {}, profile::set);
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);
        assertTrue(outcome.diagnostics().maximumScaledResidual() < 1.0e-10, outcome::toString);
        V3AcceptanceAudit audit = success.diagnostics().acceptanceAudit();
        assertTrue(audit.accepted(), () -> audit.checks().toString());
        V3WaterPhaseQualification.Assessment water = V3WaterPhaseQualification.assess(profile.get());
        assertEquals(V3WaterPhaseQualification.Grade.WET_EQUILIBRIUM, water.grade(), water::toString);
        assertTrue(profile.get().wetTrays()[1] && profile.get().freeWater()[1] > 0.0, "tray 1 carries its water over");
        V3AcceptanceAudit.Check dewPoint = audit.checks().stream()
                .filter(check -> check.family().equals("WATER_DEW_POINT")).findFirst().orElseThrow();
        assertTrue(dewPoint.passed() && !dewPoint.detail().startsWith("warning: "), dewPoint.detail());
        assertTrue(audit.advisoryEvidence().stream().noneMatch(advisory -> advisory.startsWith("Warning: ")),
                () -> String.join(" | ", audit.advisoryEvidence()));
        assertTrue(audit.advisoryEvidence().stream().anyMatch(advisory -> advisory.startsWith("wet trays: [1]")),
                () -> String.join(" | ", audit.advisoryEvidence()));
        V3AcceptanceAudit.Check carryOver = audit.checks().stream()
                .filter(check -> check.family().equals("CARRY_OVER")).findFirst().orElseThrow();
        assertTrue(carryOver.passed() && carryOver.detail().endsWith("no entrainment limit without a column diameter"),
                carryOver.detail());
        for (String family : new String[] {"WATER_BALANCE", "CONDENSER_ENERGY_BALANCE", "GLOBAL_ENERGY_BALANCE"}) {
            assertTrue(audit.checks().stream().anyMatch(check -> check.family().equals(family) && check.passed()), family);
        }
        System.out.println("E31 literature preset: " + carryOver.detail() + "; condenser duty "
                + success.result().dutyLedger().map(V3ColumnDutyLedger::condenserWatts).orElse(Double.NaN) + " W");
        // Every mole of the 1,200 kmol/h of stripping steam still decants in the drum at 59 C.
        V3ColumnStreamProperties freeWater = success.result().streams().stream()
                .filter(stream -> stream.streamId().equals("free_water")).findFirst().orElseThrow();
        assertEquals(1_200.0 / 3.6, freeWater.molarFlowMolPerSecond(), 1.0e-9);
        // Light naphtha follows from the specified reflux and condenser temperature; the source fixes it at 833.
        V3ColumnStreamProperties distillate = success.result().streams().stream()
                .filter(stream -> stream.streamId().equals("distillate_liquid")).findFirst().orElseThrow();
        // E31: 697.72 kmol/h before carry-over, when tray 1 published dry below its dew point; it now carries 88.5 kmol/h
        // of free water over to the drum.
        assertEquals(708.4723928221498, distillate.molarFlowMolPerSecond() * 3.6, 1.0);
    }

    /** With the top cooler halved the same column clears the dew point and carries no warning. */
    @Test
    void aHalvedTopCoolerClearsTheDewPointWithoutAWarning() {
        V3ColumnInput preset = V3TestInputs.literatureCduInput();
        V3ColumnInput halved = new V3ColumnInput(preset.schemaVersion(), preset.packageId(), preset.assayId(),
                preset.componentBasis(), preset.feedComponentMolarFlowsMolPerSecond(), preset.feedTemperatureKelvin(),
                preset.stageCount(), preset.feedStageNumber(), preset.topPressurePascal(), preset.stagePressureDropPascal(),
                preset.specifications(), preset.sideDraws(), preset.steamFeeds(), List.of(
                        new V3PumparoundSpec(8, 10, -6.42e6, V3PumparoundSpec.Split.UNIFORM),
                        preset.pumparounds().get(1), preset.pumparounds().get(2)), V3ColumnInput.PRESCRIBED_DROP_DIAMETER);
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(halved, () -> {}, 0.0, 0.0, V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO);
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);
        V3AcceptanceAudit.Check dewPoint = success.diagnostics().acceptanceAudit().checks().stream()
                .filter(check -> check.family().equals("WATER_DEW_POINT")).findFirst().orElseThrow();
        assertTrue(dewPoint.passed() && dewPoint.value() < 1.0, dewPoint.detail());
        assertTrue(success.diagnostics().acceptanceAudit().advisoryEvidence().stream()
                .noneMatch(advisory -> advisory.startsWith("Warning: ")));
    }
}
