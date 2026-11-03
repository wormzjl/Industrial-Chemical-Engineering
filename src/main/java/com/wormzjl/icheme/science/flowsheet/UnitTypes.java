package com.wormzjl.icheme.science.flowsheet;

import com.wormzjl.icheme.science.flowsheet.unit.AirCoolerUnit;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnUnit;
import com.wormzjl.icheme.science.flowsheet.unit.EjectorUnit;
import com.wormzjl.icheme.science.flowsheet.unit.ExchangerUnit;
import com.wormzjl.icheme.science.flowsheet.unit.FiredHeaterUnit;
import com.wormzjl.icheme.science.flowsheet.unit.FilterUnit;
import com.wormzjl.icheme.science.flowsheet.unit.MixerUnit;
import com.wormzjl.icheme.science.flowsheet.unit.MoverUnit;
import com.wormzjl.icheme.science.flowsheet.unit.SplitterUnit;
import com.wormzjl.icheme.science.flowsheet.unit.ValveUnit;
import com.wormzjl.icheme.science.flowsheet.unit.VesselUnit;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * The tiles that run, by the stable type strings a flowsheet file holds (batch 2026-11-02-process-simulator, plan section 3.2).
 * One tile for each function: a drum's or a heater's kind is the sizing stage's, and the run needs none. A renamed type or port gets
 * an alias; a removed one reads as a placeholder.
 */
public final class UnitTypes {
    /**
     * What a type or a port was called. A file is read through this, so a sheet holds today's names.
     *
     * @param types today's type by what it was called
     * @param ports by today's type, today's port by what it was called
     */
    public record Aliases(Map<String, String> types, Map<String, Map<String, String>> ports) {
        String type(String type) {
            return types.getOrDefault(type, type);
        }

        String port(String type, String port) {
            return ports.getOrDefault(type, Map.of()).getOrDefault(port, port);
        }
    }

    /** No type or port has changed its name yet. */
    public static final Aliases ALIASES = new Aliases(Map.of(), Map.of());

    private static final Map<String, UnitModel> MODELS = Map.ofEntries(
            Map.entry("valve", new ValveUnit()),
            Map.entry("mixer", new MixerUnit()),
            Map.entry("splitter", new SplitterUnit()),
            Map.entry("filter", new FilterUnit()),
            Map.entry("drum", new VesselUnit(false)),
            Map.entry("storage_tank", new VesselUnit(true)),
            Map.entry("exchanger", new ExchangerUnit(false)),
            Map.entry("kettle", new ExchangerUnit(true)),
            Map.entry("air_cooler", new AirCoolerUnit()),
            Map.entry("fired_heater", new FiredHeaterUnit()),
            Map.entry("column", new ColumnUnit()),
            Map.entry("pump", new MoverUnit(new FlowControl.Pump(1, 1))),
            Map.entry("compressor", new MoverUnit(new FlowControl.Compressor(1, 2))),
            Map.entry("ejector", new EjectorUnit()));

    private UnitTypes() {
    }

    /** The model of {@code type}, null if this version has none. */
    public static @Nullable UnitModel model(String type) {
        return MODELS.get(type);
    }
}
