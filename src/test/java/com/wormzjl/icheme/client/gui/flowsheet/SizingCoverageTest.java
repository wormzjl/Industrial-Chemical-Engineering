package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.MachineDuty;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.flowsheet.sizing.AirCoolerSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.ColumnSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.CompressorSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.DrumSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.EjectorSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.ExchangerSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.FlareSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.HeaterSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.PumpSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.SizingCoverage;
import com.wormzjl.icheme.science.flowsheet.sizing.StillSizing;
import com.wormzjl.icheme.science.equipment.BatchStillOptions;
import com.wormzjl.icheme.science.flowsheet.sizing.TankSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.TurbineSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.UnitSize;
import com.wormzjl.icheme.science.flowsheet.sizing.UnitType;
import com.wormzjl.icheme.science.flowsheet.sizing.ValveSizing;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S5's coverage gate (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} work package S5): every kind of equipment declares its
 * sizing or why it has none ({@link SizingCoverage}), a kind the simulator runs is sized, and each size class builds the kinds that name it,
 * so Send to block stores the real equipment. The switch fails the compile for a new kind; this test fails for a wrong answer.
 */
public class SizingCoverageTest {
    /** A size of every type the stage proposes, each on the constructor's grid, and a valve, which no kind builds. */
    public static List<UnitSize> samples() {
        var out = new ArrayList<UnitSize>();
        out.add(new PumpSizing.Size(UnitSize.grid(EquipmentShape.Kind.PUMP)[2]));
        double shell = UnitSize.grid(EquipmentShape.Kind.EXCHANGER)[2];
        out.add(new ExchangerSizing.Size(false, 3, shell, ProcessUnit.defaultLength(EquipmentShape.Kind.EXCHANGER, shell)));
        double kettle = UnitSize.grid(EquipmentShape.Kind.KETTLE)[2];
        out.add(new ExchangerSizing.Size(true, 1, kettle, ProcessUnit.defaultLength(EquipmentShape.Kind.KETTLE, kettle)));
        double bay = UnitSize.grid(EquipmentShape.Kind.AIR_COOLER)[0];
        out.add(new AirCoolerSizing.Size(bay, ProcessUnit.defaultLength(EquipmentShape.Kind.AIR_COOLER, bay), new ProcessUnit.Bank(3, 2), 2));
        for (var type : List.of(UnitType.VERTICAL_HEATER, UnitType.CABIN_HEATER)) {
            double d = UnitSize.grid(type.kind())[1];
            out.add(new HeaterSizing.Size(type, 1, d, ProcessUnit.defaultLength(type.kind(), d)));
        }
        for (var type : List.of(UnitType.PISTON, UnitType.TURBO)) {
            double d = UnitSize.grid(type.kind())[0];
            out.add(new CompressorSizing.Size(type, d, ProcessUnit.defaultLength(type.kind(), d)));
        }
        for (var type : List.of(UnitType.FLOATING_ROOF, UnitType.SPHERE)) {
            double d = UnitSize.grid(type.kind())[1];
            out.add(new TankSizing.Size(type, 2, d, ProcessUnit.defaultLength(type.kind(), d)));
        }
        out.add(new DrumSizing.Size(UnitType.STANDING_DRUM, 1.5, 4, false));
        out.add(new DrumSizing.Size(UnitType.LYING_DRUM, 2.5, 8, true));
        var components = new ColumnComponents(ColumnComponents.Condenser.EXCHANGER, ColumnComponents.Pump.STANDARD, List.of(ColumnComponents.Pump.STANDARD), false, true,
                new ColumnComponents.Units(3, 2, List.of(2)));
        out.add(new ColumnSizing.Size(4, new ColumnTrays(30, 20, List.of(10), List.of(31), List.of(new ColumnTrays.Pumparound(10, 8)), components)));
        out.add(new EjectorSizing.Size(UnitSize.grid(EquipmentShape.Kind.EJECTOR)[3]));
        out.add(new TurbineSizing.Size(1250, MachineDuty.Steam.HP, 350));
        out.add(new FlareSizing.Size(UnitSize.grid(EquipmentShape.Kind.FLARE_STACK)[4]));
        out.add(new com.wormzjl.icheme.science.flowsheet.sizing.ChimneySizing.Size(EquipmentShape.Kind.STEEL_STACK, 1, 30));
        out.add(new com.wormzjl.icheme.science.flowsheet.sizing.ChimneySizing.Size(EquipmentShape.Kind.BRICK_CHIMNEY, 2.5, 60));
        out.add(new StillSizing.Size(StillSizing.pots().get(4), new BatchStillOptions(BatchStillOptions.Heater.LIQUID, BatchStillOptions.Condenser.BUILT_IN, 4)));
        out.add(new ValveSizing.Size(.3));
        return out;
    }

    @Test
    void everyKindDeclaresItsSizingOrWhyItHasNone() {
        for (var kind : EquipmentShape.Kind.values()) {
            var entry = SizingCoverage.of(kind);
            assertNotNull(entry, kind.name());
            if (entry.size() == null) assertFalse(entry.reason().isBlank(), kind.name());
        }
    }

    /** A kind the simulator runs (a lit tile) has a size class: a run gives its numbers, so the tab rates it and Send to block stores it. */
    @Test
    void aKindTheSimulatorRunsIsSized() {
        for (var kind : EquipmentShape.Kind.values())
            if (FlowsheetCatalog.of(kind).coverage() == FlowsheetCatalog.Coverage.MODELLED) assertNotNull(SizingCoverage.of(kind).size(), kind + " runs but nothing sizes it");
    }

    /** Each size class builds only kinds that name it, every class the stage has is named by a kind or builds none, and every named class is sampled. */
    @Test
    void eachSizeClassBuildsTheKindsThatNameIt() {
        var built = EnumSet.noneOf(EquipmentShape.Kind.class);
        var sampled = new HashSet<Class<?>>();
        for (var size : samples()) {
            sampled.add(size.getClass());
            var shape = size.shape();
            if (size.getClass() == SizingCoverage.NO_KIND) {
                assertNull(shape, "a valve is laid with the pipe tool");
                continue;
            }
            assertNotNull(shape, size.toString());
            assertEquals(size.getClass(), SizingCoverage.of(shape.kind()).size(), size + " builds a " + shape.kind());
            built.add(shape.kind());
        }
        for (var kind : EquipmentShape.Kind.values())
            if (SizingCoverage.of(kind).size() != null) assertTrue(built.contains(kind), kind + " is named by a size class no sample builds");
        for (var permitted : UnitSize.class.getPermittedSubclasses()) assertTrue(sampled.contains(permitted), permitted.getSimpleName() + " has no sample");
    }
}
