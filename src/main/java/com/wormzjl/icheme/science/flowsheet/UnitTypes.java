package com.wormzjl.icheme.science.flowsheet;

import com.wormzjl.icheme.science.flowsheet.unit.BatchStillUnit;
import com.wormzjl.icheme.science.flowsheet.unit.CoolerUnit;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnUnit;
import com.wormzjl.icheme.science.flowsheet.unit.EjectorUnit;
import com.wormzjl.icheme.science.flowsheet.unit.ExchangerUnit;
import com.wormzjl.icheme.science.flowsheet.unit.FiredHeaterUnit;
import com.wormzjl.icheme.science.flowsheet.unit.FilterUnit;
import com.wormzjl.icheme.science.flowsheet.unit.FlareUnit;
import com.wormzjl.icheme.science.flowsheet.unit.MixerUnit;
import com.wormzjl.icheme.science.flowsheet.unit.MoverUnit;
import com.wormzjl.icheme.science.flowsheet.unit.SplitterUnit;
import com.wormzjl.icheme.science.flowsheet.unit.TurbineUnit;
import com.wormzjl.icheme.science.flowsheet.unit.ValveUnit;
import com.wormzjl.icheme.science.flowsheet.unit.VesselUnit;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * The tiles that run, by the stable type strings a flowsheet file holds (batch 2026-11-02-process-simulator, plan section 3.2).
 * One tile for each function: a drum's or a heater's kind is the sizing stage's, and the run needs none. A type this list does not
 * hold reads as a placeholder. Sheet files have no compatibility (owner, 2026-11-04): a renamed type or port gets no alias.
 */
public final class UnitTypes {
    private static final Map<String, UnitModel> MODELS = Map.ofEntries(
            Map.entry("valve", new ValveUnit()),
            Map.entry("mixer", new MixerUnit()),
            Map.entry("splitter", new SplitterUnit()),
            Map.entry("filter", new FilterUnit()),
            Map.entry("drum", new VesselUnit(false)),
            Map.entry("storage_tank", new VesselUnit(true)),
            Map.entry("exchanger", new ExchangerUnit(false)),
            Map.entry("kettle", new ExchangerUnit(true)),
            Map.entry("cooler", new CoolerUnit()),
            Map.entry("fired_heater", new FiredHeaterUnit()),
            Map.entry("flare", new FlareUnit()),
            Map.entry("batch_still", new BatchStillUnit()),
            Map.entry("column", new ColumnUnit()),
            Map.entry("pump", new MoverUnit(new FlowControl.Pump(1, 1))),
            Map.entry("compressor", new MoverUnit(new FlowControl.Compressor(1, 2))),
            Map.entry("ejector", new EjectorUnit()),
            Map.entry("steam_turbine", new TurbineUnit()));

    private UnitTypes() {
    }

    /** The model of {@code type}, null if this version has none. */
    public static @Nullable UnitModel model(String type) {
        return MODELS.get(type);
    }
}
