package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The two grade presets whose top tray sits below its water dew point reach a wet equilibrium by carry-over (E31 of
 * batch 2026-11-04-open-thermo-data).
 *
 * <p>Heavy crude and oil-sand dilbit on {@code icheme:tjl20_methane}, an 8 m column, 250 kPa top and a 59 C drum.
 * Solved on the classical start before E31, tray one published dry and supersaturated (saturation ratios about 1.15
 * and 1.13) with the WATER_DEW_POINT warning, in 0.56 M and 1.84 M control polls. With carry-over tray one is wet,
 * its carried water reaches the drum, and the column grades WET_EQUILIBRIUM. The presets are O3's drafts as the
 * neural retrain reads them ({@code tools/neural-retrain/presets/} on its branch), copied under
 * {@code science/column/v3/presets/}: the game does not ship them. The readings each run prints (carried water,
 * entrainment limit, flood fraction, polls) are the numbers of {@code CONDENSER_CARRYOVER_REVIEW.md}.</p>
 */
class V3GradePresetCarryOverTest {
    /** The retrain's cap of one classical solve; the slowest of these took 1.84 M before E31. */
    private static final long POLL_CAP = 4_000_000L;

    @ParameterizedTest
    @ValueSource(strings = {"heavy_crude", "oil_sand_dilbit"})
    void theGradePresetsTopTrayCarriesItsWaterOverAndTheColumnGradesWet(String grade) {
        var resources = new LinkedHashMap<>(MaterialCatalog.bundled().resources());
        resources.put("data/icheme/materials/presets/column_" + grade + ".json", fixture(grade));
        MaterialCatalog catalog = MaterialCatalog.parse(resources);
        V3ColumnInput input = catalog.presets().column(grade).input(catalog);
        AtomicLong polls = new AtomicLong();
        AtomicReference<V3NeuralSeed> profile = new AtomicReference<>();
        V3SolveControl control = () -> {
            if (polls.incrementAndGet() > POLL_CAP) throw new CancellationException("poll cap");
        };
        V3ColumnOutcome outcome = MaterialRuntime.with(catalog, input.packageId(),
                () -> V3ColumnCalculator.calculateWithAcceptedProfile(input, control, profile::set));

        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);
        assertNotNull(profile.get(), "the accepted profile is exported");
        V3WaterPhaseQualification.Assessment water = MaterialRuntime.with(catalog, input.packageId(),
                () -> V3WaterPhaseQualification.assess(profile.get()));
        assertEquals(V3WaterPhaseQualification.Grade.WET_EQUILIBRIUM, water.grade(), water::toString);
        assertTrue(profile.get().wetTrays()[1], "tray one is the wet tray");
        assertTrue(profile.get().freeWater()[1] > 0.0);

        V3AcceptanceAudit audit = success.diagnostics().acceptanceAudit();
        assertTrue(audit.accepted(), () -> audit.checks().toString());
        V3AcceptanceAudit.Check dewPoint = check(audit, "WATER_DEW_POINT");
        assertTrue(dewPoint.passed() && !dewPoint.detail().startsWith("warning: "), dewPoint::detail);
        V3AcceptanceAudit.Check carryOver = check(audit, "CARRY_OVER");
        for (String family : new String[] {"WATER_BALANCE", "CONDENSER_ENERGY_BALANCE", "GLOBAL_ENERGY_BALANCE"}) {
            assertTrue(check(audit, family).passed(), family);
        }
        System.out.println(String.format(Locale.ROOT,
                "E31 grade %s: %d polls, carried %.1f kmol/h, wet trays %d; %s", grade, polls.get(),
                profile.get().freeWater()[1] * 3.6, water.wetTrayCount(), carryOver.detail()));
    }

    private static V3AcceptanceAudit.Check check(V3AcceptanceAudit audit, String family) {
        return audit.checks().stream().filter(candidate -> candidate.family().equals(family)).findFirst()
                .orElseThrow(() -> new AssertionError(family + " missing from " + audit.checks()));
    }

    private static String fixture(String grade) {
        String path = "/science/column/v3/presets/column_" + grade + ".json";
        try (InputStream stream = V3GradePresetCarryOverTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("missing fixture " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
