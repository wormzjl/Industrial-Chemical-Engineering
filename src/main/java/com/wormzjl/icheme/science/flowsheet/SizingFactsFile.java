package com.wormzjl.icheme.science.flowsheet;

import com.google.gson.JsonObject;
import com.wormzjl.icheme.science.equipment.MachineDuty;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.flowsheet.sizing.AirCoolerSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.CompressorSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.DesignBasis;
import com.wormzjl.icheme.science.flowsheet.sizing.DrumSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.EjectorSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.FlareSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.StillSizing;
import com.wormzjl.icheme.science.equipment.BatchStillOptions;
import com.wormzjl.icheme.science.flowsheet.sizing.ExchangerSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.HeaterSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.LineSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.PumpSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.Service;
import com.wormzjl.icheme.science.flowsheet.sizing.SizingFacts;
import com.wormzjl.icheme.science.flowsheet.sizing.TankSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.TurbineSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.UnitSize;
import com.wormzjl.icheme.science.flowsheet.sizing.UnitType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Sizing's facts in the flowsheet file ({@link FlowsheetFile}; sizing plan section 3.9): absent on a sheet never sized, which reads
 * as every item on Auto, no size. Each row keeps what a later version put beside its fields.
 */
final class SizingFactsFile {
    private SizingFactsFile() {
    }

    /** The facts in {@code fields}, every item on Auto where the sheet holds none. */
    static SizingFacts read(@Nullable JsonFields fields) {
        if (fields == null) return SizingFacts.NONE;
        var basis = DesignBasis.DEFAULT;
        var basisRest = new JsonObject();
        var given = fields.optionalObject("basis");
        if (given != null) {
            try {
                basis = new DesignBasis(given.number("design_flow", basis.designFlow()), given.number("line_length", basis.lineLength()), given.number("pa_fall", basis.paFall()));
            } catch (IllegalArgumentException invalid) {
                throw JsonFields.damaged(given.path);
            }
            basisRest = given.rest;
        }
        var units = new LinkedHashMap<String, SizingFacts.UnitFacts>();
        var unitRows = fields.optionalObject("units");
        if (unitRows != null) for (var id : new ArrayList<>(unitRows.rest.keySet())) units.put(id, unitFacts(unitRows.object(id)));
        var lines = new LinkedHashMap<String, SizingFacts.LineFacts>();
        var lineRows = fields.optionalObject("lines");
        if (lineRows != null) for (var id : new ArrayList<>(lineRows.rest.keySet())) lines.put(id, lineFacts(lineRows.object(id)));
        return new SizingFacts(basis, basisRest, units, lines, fields.rest);
    }

    private static SizingFacts.UnitFacts unitFacts(JsonFields fields) {
        var mode = fields.name("mode", SizingFacts.Mode.class, SizingFacts.Mode.AUTO);
        var type = fields.name("type", UnitType.class, null);
        var hold = fields.optionalAmount("hold");
        var size = fields.optionalObject("size");
        return new SizingFacts.UnitFacts(mode, type, size == null ? null : unitSize(size), hold, size == null ? new JsonObject() : size.rest, fields.rest);
    }

    private static SizingFacts.LineFacts lineFacts(JsonFields fields) {
        var mode = fields.name("mode", SizingFacts.Mode.class, SizingFacts.Mode.AUTO);
        var service = fields.name("service", Service.class, null);
        var length = fields.optionalAmount("length");
        var size = fields.optionalObject("size");
        LineSizing.Size line = null;
        if (size != null) {
            try {
                line = new LineSizing.Size(size.number("bore"), size.whole("runs"));
            } catch (IllegalArgumentException invalid) {
                throw JsonFields.damaged(size.path);
            }
        }
        return new SizingFacts.LineFacts(mode, service, length, line, size == null ? new JsonObject() : size.rest, fields.rest);
    }

    /** A size by its kind's name; one its record refuses is damage. One the constructor cannot build is a red lamp, not damage. */
    private static UnitSize unitSize(JsonFields fields) {
        String kind = fields.id("kind");
        try {
            return switch (kind) {
                case "PUMP" -> new PumpSizing.Size(fields.number("discharge"));
                case "EXCHANGER" -> new ExchangerSizing.Size(fields.bool("kettle"), fields.whole("shells"), fields.number("shell"), fields.whole("tubes"),fields.name("type",com.wormzjl.icheme.science.equipment.ExchangerType.class,com.wormzjl.icheme.science.equipment.ExchangerType.STANDARD));
                case "AIR_COOLER" -> new AirCoolerSizing.Size(fields.number("width"), fields.whole("length"), new ProcessUnit.Bank(fields.whole("parallel"), fields.whole("series")), fields.whole("banks", 1));
                case "HEATER" -> new HeaterSizing.Size(fields.name("type", UnitType.class), fields.whole("count"), fields.number("diameter"), fields.whole("length"));
                case "COMPRESSOR" -> new CompressorSizing.Size(fields.name("type", UnitType.class), fields.number("size"), fields.whole("length"));
                case "DRUM" -> new DrumSizing.Size(fields.name("type", UnitType.class), fields.number("diameter"), fields.whole("length"), fields.bool("boot"));
                case "TANK" -> new TankSizing.Size(fields.name("type", UnitType.class), fields.whole("count"), fields.number("diameter"), fields.whole("shell"));
                case "EJECTOR" -> new EjectorSizing.Size(fields.number("suction"));
                case "VACUUM_PUMP" -> new com.wormzjl.icheme.science.flowsheet.sizing.VacuumPumpSizing.Size(fields.name("type", com.wormzjl.icheme.science.machine.VacuumPump.Type.class), fields.whole("model"));
                case "FLARE" -> new FlareSizing.Size(fields.number("tip"));
                case "BATCH_STILL" -> new StillSizing.Size(fields.number("pot"), new BatchStillOptions(fields.name("heater", BatchStillOptions.Heater.class),
                        fields.name("condenser", BatchStillOptions.Condenser.class), fields.whole("stages")));
                case "STEAM_TURBINE" -> new TurbineSizing.Size(fields.number("power"), fields.name("steam", MachineDuty.Steam.class), fields.number("exhaust"));
                default -> throw JsonFields.damaged(fields.path + ".kind");
            };
        } catch (IllegalArgumentException invalid) {
            throw JsonFields.damaged(fields.path);
        }
    }

    static JsonObject write(SizingFacts facts) {
        var json = new JsonObject();
        var basis = new JsonObject();
        basis.addProperty("design_flow", facts.basis().designFlow());
        basis.addProperty("line_length", facts.basis().lineLength());
        basis.addProperty("pa_fall", facts.basis().paFall());
        json.add("basis", JsonFields.beside(basis, facts.basisUnknown()));
        var units = new JsonObject();
        facts.units().forEach((id, unit) -> {
            var row = new JsonObject();
            row.addProperty("mode", unit.mode().name());
            if (unit.type() != null) row.addProperty("type", unit.type().name());
            if (unit.hold() != null) row.addProperty("hold", unit.hold());
            if (unit.size() != null) row.add("size", JsonFields.beside(unitSize(unit.size()), unit.sizeUnknown()));
            units.add(id, JsonFields.beside(row, unit.unknown()));
        });
        json.add("units", units);
        var lines = new JsonObject();
        facts.lines().forEach((id, line) -> {
            var row = new JsonObject();
            row.addProperty("mode", line.mode().name());
            if (line.service() != null) row.addProperty("service", line.service().name());
            if (line.length() != null) row.addProperty("length", line.length());
            if (line.size() != null) {
                var size = new JsonObject();
                size.addProperty("bore", line.size().bore());
                size.addProperty("runs", line.size().runs());
                row.add("size", JsonFields.beside(size, line.sizeUnknown()));
            }
            lines.add(id, JsonFields.beside(row, line.unknown()));
        });
        json.add("lines", lines);
        return JsonFields.beside(json, facts.unknown());
    }

    private static JsonObject unitSize(UnitSize size) {
        var json = new JsonObject();
        switch (size) {
            case PumpSizing.Size pump -> {
                json.addProperty("kind", "PUMP");
                json.addProperty("discharge", pump.discharge());
            }
            case ExchangerSizing.Size exchanger -> {
                json.addProperty("kind", "EXCHANGER");
                json.addProperty("kettle", exchanger.kettle());
                json.addProperty("shells", exchanger.shells());
                json.addProperty("shell", exchanger.shell());
                json.addProperty("tubes", exchanger.tubes());
                if(exchanger.type()!=com.wormzjl.icheme.science.equipment.ExchangerType.STANDARD)json.addProperty("type", exchanger.type().name());
            }
            case AirCoolerSizing.Size cooler -> {
                json.addProperty("kind", "AIR_COOLER");
                json.addProperty("width", cooler.width());
                json.addProperty("length", cooler.length());
                json.addProperty("parallel", cooler.bank().parallel());
                json.addProperty("series", cooler.bank().series());
                // Identical banks in parallel (S3b): one where absent.
                if (cooler.banks() > 1) json.addProperty("banks", cooler.banks());
            }
            case HeaterSizing.Size heater -> {
                json.addProperty("kind", "HEATER");
                json.addProperty("type", heater.type().name());
                json.addProperty("count", heater.count());
                json.addProperty("diameter", heater.diameter());
                json.addProperty("length", heater.length());
            }
            case CompressorSizing.Size compressor -> {
                json.addProperty("kind", "COMPRESSOR");
                json.addProperty("type", compressor.type().name());
                json.addProperty("size", compressor.size());
                json.addProperty("length", compressor.length());
            }
            case DrumSizing.Size drum -> {
                json.addProperty("kind", "DRUM");
                json.addProperty("type", drum.type().name());
                json.addProperty("diameter", drum.diameter());
                json.addProperty("length", drum.length());
                json.addProperty("boot", drum.boot());
            }
            case TankSizing.Size tank -> {
                json.addProperty("kind", "TANK");
                json.addProperty("type", tank.type().name());
                json.addProperty("count", tank.count());
                json.addProperty("diameter", tank.diameter());
                json.addProperty("shell", tank.shell());
            }
            case com.wormzjl.icheme.science.flowsheet.sizing.VacuumPumpSizing.Size vacuum -> {
                json.addProperty("kind", "VACUUM_PUMP");
                json.addProperty("type", vacuum.type().name());
                json.addProperty("model", vacuum.model());
            }
            case EjectorSizing.Size ejector -> {
                json.addProperty("kind", "EJECTOR");
                json.addProperty("suction", ejector.suction());
            }
            case FlareSizing.Size flare -> {
                json.addProperty("kind", "FLARE");
                json.addProperty("tip", flare.tip());
            }
            case StillSizing.Size still -> {
                json.addProperty("kind", "BATCH_STILL");
                json.addProperty("pot", still.pot());
                json.addProperty("heater", still.options().heater().name());
                json.addProperty("condenser", still.options().condenser().name());
                json.addProperty("stages", still.options().stages());
            }
            case TurbineSizing.Size turbine -> {
                json.addProperty("kind", "STEAM_TURBINE");
                json.addProperty("power", turbine.power());
                json.addProperty("steam", turbine.steam().name());
                json.addProperty("exhaust", turbine.exhaust());
            }
            default -> throw new IllegalArgumentException("A " + size.getClass().getSimpleName() + " is no fact: it follows from the sheet");
        }
        return json;
    }
}
