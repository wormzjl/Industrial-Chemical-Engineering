import com.wormzjl.icheme.client.gui.common.AssemblyColumn;
import com.wormzjl.icheme.client.gui.common.TitleStrip;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram;
import com.wormzjl.icheme.client.gui.flowsheet.AssemblyBlock;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn;
import com.wormzjl.icheme.client.gui.common.ConstructorColumn;
import com.wormzjl.icheme.client.gui.common.DeviceColumn;
import com.wormzjl.icheme.client.gui.common.ControlColumns;
import com.wormzjl.icheme.client.gui.common.DialogColumns;
import com.wormzjl.icheme.client.gui.common.HeatControlColumn;
import com.wormzjl.icheme.client.gui.common.MachineColumnSamples;
import com.wormzjl.icheme.client.gui.common.MachineColumn;
import com.wormzjl.icheme.client.gui.common.PumpCurve;
import com.wormzjl.icheme.client.gui.common.StillDoor;
import com.wormzjl.icheme.client.gui.common.ConverterDoor;
import com.wormzjl.icheme.client.gui.common.VesselSection;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Format;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.PanelSkin;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.ScrollView;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.common.FluidGuiLayout;
import com.wormzjl.icheme.science.assembly.AssemblyReport;
import com.wormzjl.icheme.science.assembly.AssemblyView;
import com.wormzjl.icheme.science.assembly.Blueprint.Slot;
import com.wormzjl.icheme.science.assembly.ColumnBlueprint;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.MachineDuty;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.science.equipment.VesselPlacement;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Prototypes of the other screens under the Control tab's rules (batch 2026-10-28-gui-toolkit, GUI_REVIEW.md; owner:
 * "Review other GUI based on above rule, prototype first"): function groups in frames under one- or two-word plates, the
 * sentences in hovers, Off and On then a target where a device runs, latched selectors, no cut text, the light and dark
 * doors. Drawn through the kit (`Column`, `PanelSkin`) in the game font, like {@code HeatDiagramRender}; the screens'
 * data are illustrative. Run from the worktree root (JDK 22+ runs both files of the folder):
 * <pre>java -Duser.language=en -Djava.awt.headless=true -cp build/classes/java/main tools/heat-gui-renders/GuiPrototypes.java
 *     src/main/resources/assets/icheme/lang/en_us.json tmp/gui-prototypes [minecraft resources jar]</pre>
 */
public final class GuiPrototypes {
    static final int SCALE = HeatDiagramRender.SCALE;

    /** A prototype page: its file name, its size in GUI px, what it draws on a door, and whether it shows scrolled to the end too. */
    record Prototype(String file, int width, int height, BiConsumer<Door, Skin> body, boolean scrolls) {
        Prototype(String file, int width, int height, BiConsumer<Door, Skin> body) {
            this(file, width, height, body, false);
        }
    }

    public static void main(String[] args) throws Exception {
        HeatDiagramRender.lang = HeatDiagramRender.lang(Path.of(args[0]));
        var out = Path.of(args[1]);
        Files.createDirectories(out);
        var jar = Path.of(args.length > 2 ? args[2] : "build/moddev/artifacts/neoforge-21.1.219-client-extra-aka-minecraft-resources.jar");
        HeatDiagramRender.font = Files.exists(jar) ? HeatDiagramRender.GameFont.load(jar) : null;
        // The valve's Sensor option sheets alone (they need no material catalog).
        if (System.getProperty("only", "").startsWith("valve-sensor")) {
            valveSensorSheets(out);
            return;
        }
        // The pollution map sheets alone need no material catalog either.
        var all = System.getProperty("only", "").startsWith("pollution-map") ? pollutionMap() : prototypes();
        for (var p : all) {
            if (!p.file().startsWith(System.getProperty("only", ""))) continue;
            var titles = new ArrayList<>(List.of("Light panel (3a)", "Dark panel (3c)"));
            var pages = new ArrayList<>(List.of(render(p, PanelSkin.LIGHT, false), render(p, PanelSkin.DARK, false)));
            if (p.scrolls()) {
                titles.addAll(List.of("Light, the column scrolled to its end", "Dark, the column scrolled to its end"));
                pages.addAll(List.of(render(p, PanelSkin.LIGHT, true), render(p, PanelSkin.DARK, true)));
            }
            HeatDiagramRender.grid(out.resolve(p.file() + ".png"), titles, pages);
        }
    }

    static List<Prototype> prototypes() {
        var out = new ArrayList<>(List.of(pump(), compressor(), generator(), tank(), pipe()));
        out.addAll(valve());
        out.addAll(calculator());
        out.addAll(assembly());
        out.addAll(constructor());
        out.addAll(List.of(pipelineTool(), gauge(), thermometer(), vessel(), unit()));
        out.addAll(alarmBeacons());
        out.addAll(OperationPrototypes.operation());
        out.add(titleStrip());
        out.addAll(machines());
        out.addAll(machinePages());
        out.add(exchangerTypes());
        out.addAll(pumpReadings());
        out.addAll(dense());
        out.addAll(plunger());
        out.addAll(createCoupling());
        out.addAll(controlPanels());
        out.addAll(firedHeater());
        out.addAll(separator());
        out.addAll(separation());
        out.addAll(flareStack());
        out.addAll(batchStill());
        out.addAll(fluidConverter());
        out.addAll(blanketValve());
        out.addAll(reliefValve());
        out.addAll(integrity());
        out.addAll(pollutionMap());
        return out;
    }

    /**
     * The pollution map's layers (batch 2026-11-18-ground-pollution, G3, option A): a 7 by 5 chunk patch of the FTB Chunks
     * large map with an air plume to the east and polluted ground to the west, its squares in each layer
     * ({@link com.wormzjl.icheme.client.pollution.MapLayer#argb}: the air's worst colour, the ground's brown, or the higher of the two),
     * and the tooltip of the middle chunk ({@link com.wormzjl.icheme.client.pollution.MapLayer#tooltip}) in the game's colours.
     * The map and the tooltip are FTB's and vanilla's, not the panel's: the light and dark doors only frame them.
     */
    static List<Prototype> pollutionMap() {
        var out = new ArrayList<Prototype>();
        for (var layer : com.wormzjl.icheme.client.pollution.MapLayer.values()) {
            out.add(new Prototype("pollution-map-" + layer.name().toLowerCase(java.util.Locale.ROOT), 464, 254, (d, s) -> {
                int cell = 30, ox = 10, oy = 34;
                d.skin.title(d.c, 10, 10, "Pollution map, layer " + layer.name());
                for (int cz = 0; cz < 5; cz++) for (int cx = 0; cx < 7; cx++) {
                    int x = ox + cx * cell, y = oy + cz * cell;
                    d.c.fill(x, y, x + cell, y + cell, ((cx + cz) & 1) == 0 ? 0xFF5E8C3A : 0xFF6A9442);
                    int air = mapAir(cx, cz), ground = mapGround(cx, cz);
                    int argb = layer.argb(air, ground);
                    if (argb != 0) d.c.fill(x, y, x + cell, y + cell, argb);
                }
                d.c.outline(ox + 3 * cell, oy + 2 * cell, cell, cell, 0xFFFFFFFF);
                var lines = layer.tooltip(com.wormzjl.icheme.science.pollution.MapColumns.column(3, 2), mapAir(3, 2), mapGround(3, 2));
                var texts = new ArrayList<String>();
                var colours = new ArrayList<Integer>();
                for (var line : lines) {
                    var args = new Object[line.args().size()];
                    for (int i = 0; i < args.length; i++) args[i] = line.args().get(i) instanceof com.wormzjl.icheme.client.pollution.MapLayer.Key k ? Door.tr(k.key()) : line.args().get(i);
                    texts.add(Door.tr(line.key(), args));
                    colours.add(line.word() == null ? (line.args().isEmpty() ? 0xFF55FFFF : 0xFFFFFFFF) : switch (line.word()) {
                        case SAFE -> 0xFF55FF55;
                        case WARNING -> 0xFFFFFF55;
                        case DANGER -> 0xFFFFAA00;
                        case LETHAL, FLAMMABLE -> 0xFFFF5555;
                    });
                }
                int tw = 0;
                for (var t : texts) tw = Math.max(tw, d.m.width(t));
                int tx = ox + 7 * cell + 10, ty = oy + 4, th = texts.size() * 10 + 6;
                d.c.fill(tx - 3, ty - 4, tx + tw + 3, ty + th - 2, 0xF0100010);
                d.c.outline(tx - 3, ty - 4, tw + 6, th + 2, 0xFF5000FF);
                for (int i = 0; i < texts.size(); i++) d.c.text(texts.get(i), tx, ty + i * 10, colours.get(i));
            }));
        }
        return out;
    }

    /** The air plume of the map sheets: east of the middle, toxic, falling off with distance. */
    static int mapAir(int cx, int cz) {
        double d = Math.hypot(cx - 4.5, cz - 2), index = 1.4 / (1 + d * d);
        return com.wormzjl.icheme.science.pollution.MapColumns.quantise(index < 0.1 ? 0 : index) << 8 * com.wormzjl.icheme.science.pollution.Channel.TOXIC.ordinal();
    }

    /** The polluted ground of the map sheets: west of the middle, one byte. */
    static int mapGround(int cx, int cz) {
        double d = Math.hypot(cx - 1.5, cz - 2), index = 2.0 / (1 + d * d);
        return index < 0.1 ? 0 : com.wormzjl.icheme.science.pollution.MapColumns.quantise(index);
    }

    /**
     * A batch still (batch 2026-11-09-batch-distiller, W6): its pot's page distilling on a solid fire with a column, its
     * damper held by a controller; a liquid-fired pot still heating; an electric still boiled dry; and the constructor's
     * still options.
     */
    /**
     * A fluid converter (batch 2026-11-12-fluid-conversion, W5): its process tank's page idle (Off, no rule, the Forge tank
     * empty), converting to the Forge tank on a water rule, and a closed gas converter in Balance with an alarm (its Forge
     * tank over its high level).
     */
    static List<Prototype> fluidConverter() {
        var out = new ArrayList<Prototype>();
        record Page(String file, String title, ConverterDoor.Input in, String status, int colour, Map<String, String> drafts) {}
        var none = List.of("—", "—", "—", "—");
        var water = List.of(new ConverterDoor.Range("Water", "0.998", "at least 0.99", "mol frac", false), new ConverterDoor.Range("Others", "0.002", "at most 0.01", "mol frac", false),
                new ConverterDoor.Range("Temperature", "25.0", "5.0 to 60.0", "°C", false), new ConverterDoor.Range("Pressure", "1.013", "0.900 to 1.500", "bar", false));
        var gas = List.of(new ConverterDoor.Range("Methane", "0.951", "gives", "mol frac", false), new ConverterDoor.Range("Ethane", "0.049", "gives", "mol frac", false),
                new ConverterDoor.Range("Temperature", "25.0", "gives", "°C", false), new ConverterDoor.Range("Pressure", "1.013", "gives", "bar", false));
        var pages = List.of(
                new Page("fluid-converter-idle", "Fluid converter", new ConverterDoor.Input(true, ConverterDoor.Mode.OFF, "L/h", "5,655", "5,655", "5", none,
                        new ConverterDoor.Tank(0, "0.0", "0 of 1,571 mB"), null, new ConverterDoor.Tank(0, "0.0", "0 L"), null, false, List.of(), "0.0", "none"),
                        "OFF", Palette.MUTED, Map.of("converterRate", "5,655", "converterDeadband", "5")),
                new Page("fluid-converter-converting", "Fluid converter", new ConverterDoor.Input(true, ConverterDoor.Mode.TO_FORGE, "L/h", "5,655", "2,880", "5", List.of("90", "—", "95", "—"),
                        new ConverterDoor.Tank(.41, "41.0", "644 of 1,571 mB"), "minecraft:water", new ConverterDoor.Tank(.52, "52.0", "817 L"), "icheme:water_to_forge", true, water, "-2,880", "to_forge"),
                        "CONVERTING", Palette.ACCENT, Map.of("converterRate", "2,880", "converterIchemeHigh", "90", "converterForgeHigh", "95")),
                new Page("fluid-converter-gas-alarm", "Gas converter", new ConverterDoor.Input(true, ConverterDoor.Mode.BALANCE, "L/h", "50,894", "50,894", "5", List.of("—", "—", "80", "—"),
                        new ConverterDoor.Tank(.86, "86.0", "12,159 of 14,137 mB"), "examplemod:natural_gas", new ConverterDoor.Tank(.31, "31.0", "0.41 kmol, 7.0 kg"), "examplemod:natural_gas_in", false, gas, "14,400", "to_icheme"),
                        "CONVERTING / LEVEL HIGH", Palette.RED, Map.of("converterRate", "50,894", "converterDeadband", "5", "converterForgeHigh", "80")));
        for (var p : pages) {
            var door = ConverterDoor.rows(p.in(), TEXTS);
            var input = new DeviceColumn.Input(Kind.RESERVOIR, true, false, true, "bar", "°C", "/h", DeviceColumn.Valve.NONE, null, false,
                    null, null, door, phases("volume", p.file().contains("gas") ? 0 : 52, 0, p.file().contains("gas") ? 100 : 48, 0), List.of(meter("temperature", "25.0", "°C"), meter("pressure", "1.013", "bar")), null, null);
            out.add(devicePage(p.file(), WINDOW[0], WINDOW[1], input, (d, k) -> {
                d.title(p.title());
                d.name("FC-1", false);
                d.tabs(0, "Overview", "Outlets", "Datasheet");
                d.status(p.status(), p.colour());
                var box = device(d, input, DeviceColumn.rows(input, TEXTS), p.drafts());
                contents(d, box, p.file().contains("gas") ? 2 : 0, p.file().contains("gas") ? List.of(new String[]{"Component", "Mole %", "kmol"}, new String[]{"Methane", "95.1", "0.39"}, new String[]{"Ethane", "4.9", "0.02"})
                        : List.of(new String[]{"Component", "Mole %", "kmol"}, new String[]{"Water", "99.8", "45.3"}));
                d.footer(2, "Close", "Mole", "°C", "bar", "/h");
            }));
        }
        return out;
    }

    static List<Prototype> batchStill() {
        var out = new ArrayList<Prototype>();
        record Still(String file, StillDoor.Heater heater, boolean column, String held, StillDoor.Fuel fuel, String status, int colour, List<DeviceColumn.Meter> meters,
                     DeviceColumn.Power power) {}
        var flow = "kg/h";
        var stills = List.of(
                new Still("batch-still-solid-column", StillDoor.Heater.SOLID, true, "63.0", new StillDoor.Fuel("Coal", "12", "1630", "27.7"), "DISTILLING", Palette.ACCENT,
                        List.of(meter("still_pot_temperature", "118.4", "°C"), new DeviceColumn.Meter("still_head_temperature", "86.2", "°C", TEXTS.get("still_head_temperature.hint")),
                                new DeviceColumn.Meter("still_level", "0.52", "m", TEXTS.get("still_level.hint", "61.0")), new DeviceColumn.Meter("still_heat", "20.2", "kW", TEXTS.get("still_heat.hint", "32.0", "63.0")),
                                new DeviceColumn.Meter("still_distillate", "61.4", flow, TEXTS.get("still_distillate.hint")), new DeviceColumn.Meter("still_vent", "0.0", flow, TEXTS.get("still_vent.hint")),
                                new DeviceColumn.Meter("still_condensed", "100.0", "%", TEXTS.get("still_condensed.hint")), new DeviceColumn.Meter("still_reflux_run", "2.0", "", TEXTS.get("still_reflux_run.hint"))), null),
                new Still("batch-still-liquid", StillDoor.Heater.LIQUID, false, null, null, "HEATING", Palette.MUTED,
                        List.of(meter("still_pot_temperature", "64.9", "°C"), new DeviceColumn.Meter("still_head_temperature", "—", "°C", TEXTS.get("still_head_temperature.hint")),
                                new DeviceColumn.Meter("still_level", "0.61", "m", TEXTS.get("still_level.hint", "70.0")), new DeviceColumn.Meter("still_heat", "32.0", "kW", TEXTS.get("still_heat.hint", "32.0", "100.0")),
                                new DeviceColumn.Meter("still_distillate", "0.0", flow, TEXTS.get("still_distillate.hint")), new DeviceColumn.Meter("still_vent", "0.0", flow, TEXTS.get("still_vent.hint")),
                                new DeviceColumn.Meter("still_condensed", "100.0", "%", TEXTS.get("still_condensed.hint"))), null),
                new Still("batch-still-electric-dry", StillDoor.Heater.ELECTRIC, false, null, null, "BOIL DRY", Palette.RED,
                        List.of(meter("still_pot_temperature", "236.0", "°C"), new DeviceColumn.Meter("still_head_temperature", "198.3", "°C", TEXTS.get("still_head_temperature.hint")),
                                new DeviceColumn.Meter("still_level", "0.03", "m", TEXTS.get("still_level.hint", "4.0")), new DeviceColumn.Meter("still_heat", "0.0", "kW", TEXTS.get("still_heat.hint", "32.0", "100.0"))),
                        new DeviceColumn.Power("0.0", "0", "100")));
        for (var s : stills) {
            var door = StillDoor.rows(new StillDoor.Input(s.heater(), true, true, s.column(), true, s.held(), null, s.fuel()), TEXTS);
            var input = new DeviceColumn.Input(Kind.RESERVOIR, true, false, true, "bar", "°C", "/h", DeviceColumn.Valve.NONE, null, false,
                    new DeviceColumn.Level(0.61, "305.0", "500.0"), null, door, phases("volume", 61, 0, 39, 0), s.meters(), null, s.power());
            out.add(devicePage(s.file(), WINDOW[0], WINDOW[1], input, (d, k) -> {
                d.title("Batch still");
                d.name("ST-1", true);
                d.tabs(0, "Overview", "Outlets", "Datasheet");
                d.status(s.status(), s.colour());
                var box = device(d, input, DeviceColumn.rows(input, TEXTS), Map.of("stillFiring", "100", "stillReflux", "2"));
                contents(d, box, 1, List.of(new String[]{"Component", "Mole %", "kmol"}, new String[]{"n-Hexane", "38.2", "0.71"}, new String[]{"Toluene", "61.8", "1.15"}));
                d.footer(2, "Close", "Mole", "°C", "bar", "/h");
            }));
        }
        var outlet = new ConstructorColumn.Outlet(1, 0, STANDING);
        for (var options : List.of(new com.wormzjl.icheme.science.equipment.BatchStillOptions(com.wormzjl.icheme.science.equipment.BatchStillOptions.Heater.SOLID,
                        com.wormzjl.icheme.science.equipment.BatchStillOptions.Condenser.BUILT_IN, 4),
                new com.wormzjl.icheme.science.equipment.BatchStillOptions(com.wormzjl.icheme.science.equipment.BatchStillOptions.Heater.ELECTRIC,
                        com.wormzjl.icheme.science.equipment.BatchStillOptions.Condenser.NONE, 0))) {
            var page = ConstructorColumn.design(new ConstructorColumn.Design(VesselPlacement.Form.BATCH_STILL, 500, 1, 0, 1, 0, 0, new ProcessUnit.Bank(1, 1), null, outlet, 16,
                    com.wormzjl.icheme.science.equipment.TowerBank.ONE, MachineDuty.NONE, com.wormzjl.icheme.science.equipment.ExchangerType.STANDARD, options), DIALOG);
            out.add(new Prototype("equipment-constructor-still-" + options.heater().key(), 464, 254, (d, k) -> constructorDoor(d, page, "Batch still", "change", "", 0, Map.of()), true));
        }
        return out;
    }

    /**
     * A flare stack (batch 2026-11-09-flare-stack): its page flaring and with its flame out, its constructor design page, and
     * the heat equipment's choice with the flare's tile.
     */
    static List<Prototype> flareStack() {
        var out = new ArrayList<Prototype>();
        for (boolean lit : new boolean[]{true, false}) {
            var input = new DeviceColumn.Input(Kind.VOID, false, false, true, "bar", "°C", "/h", DeviceColumn.Valve.NONE, null, false, null, null, List.of(),
                    phases("volume", 0, 0, 1, 0), List.of(meter("pressure", "1.013", "bar"), new DeviceColumn.Meter("throughput", lit ? "6120.0" : "5040.0", "kg/h", TEXTS.get("throughput.hint")),
                    new DeviceColumn.Meter("flare_heat", lit ? "75.40" : "0.00", "MW", TEXTS.get("flare_heat.hint", "212.4 MW")),
                    new DeviceColumn.Meter("flare_heating_value", lit ? "33.4" : "3.7", "MJ/m³", TEXTS.get("flare_heating_value.hint")),
                    new DeviceColumn.Meter("flare_flame", lit ? "1958.9" : "1352.4", "°C", TEXTS.get("flare_flame.hint", "1426.9 °C")),
                    new DeviceColumn.Meter("flare_limit", lit ? "15282.0" : "5040.0", "kg/h", TEXTS.get("flare_limit.hint", "0.3 m"))));
            out.add(devicePage(lit ? "flare-stack-flaring" : "flare-stack-flame-out", WINDOW[0], WINDOW[1], input, (d, s) -> {
                d.title("Flare stack");
                d.name("FL-101", true);
                d.tabs(0, "Overview", "Control", "Datasheet");
                d.status(lit ? "FLARING" : "FLAME OUT", lit ? Palette.ACCENT : Palette.RED);
                var box = device(d, input, DeviceColumn.rows(input, TEXTS), Map.of());
                contents(d, box, 1, lit ? List.of(new String[]{"Component", "Mole %", "kg/h"}, new String[]{"Methane", "100.0", "6120"})
                        : List.of(new String[]{"Component", "Mole %", "kg/h"}, new String[]{"Methane", "11.1", "254"}, new String[]{"Nitrogen", "88.9", "4786"}));
                d.footer(2, "Close", "Mass", "°C", "bar", "/h");
            }));
        }
        var outlet = new ConstructorColumn.Outlet(1, 0, STANDING);
        var flare = ConstructorColumn.design(new ConstructorColumn.Design(VesselPlacement.Form.FLARE_STACK, .3, 1, 0, 0, 0, 0, new ProcessUnit.Bank(1, 1), null, outlet, 16, com.wormzjl.icheme.science.equipment.TowerBank.ONE), DIALOG);
        out.add(new Prototype("equipment-constructor-flare", 464, 254, (d, s) -> constructorDoor(d, flare, "Flare stack", "change", DIALOG.get(CONSTRUCTOR + "flare_rating", "212.4", "32"), Palette.ACCENT, Map.of()), true));
        out.add(new Prototype("equipment-constructor-choose-heat", 464, 254, (d, s) -> constructorDoor(d, ConstructorColumn.choose(ConstructorColumn.Category.HEAT,
                ConstructorColumn.Equipment.INDUCED_DRAFT_COOLING_TOWER, 16, DIALOG), "Constructor", "back", null, 0, Map.of()), true));
        return out;
    }

    /**
     * A fired heater's radiant coil page on a short fuel supply (batch 2026-11-09-furnace-fuel-power): its coil rows, then
     * the burners' fuel flow, the heat fired against the heat asked for, and the combustion-air fans' draw.
     */
    static List<Prototype> firedHeater() {
        var input = new DeviceColumn.Input(Kind.HEAT_SIDE, true, true, true, "bar", "°C", "/h", DeviceColumn.Valve.NONE, null, false, null, null, List.of(),
                phases("volume", 0, 1, 0, 0), List.of(meter("temperature", "50.0", "°C"), meter("throughput", "1995.0", "kmol/h"),
                meter("outlet_temperature", "112.4", "°C"), new DeviceColumn.Meter("duty", "1598.0", "kW", TEXTS.get("duty.fired")),
                meter("set_temperature", "170.0", "°C"), new DeviceColumn.Meter("duty_available", "3500.0", "kW", TEXTS.get("duty_available.hint")),
                new DeviceColumn.Meter("fuel_flow", "204.1", "kg/h", TEXTS.get("fuel_flow.hint", "50.0 MJ/kg")),
                new DeviceColumn.Meter("fuel_fired", "2836.0", "kW", TEXTS.get("fuel_fired.hint", "6213.0 kW")),
                new DeviceColumn.Meter("burner_fans", "2.3", "kW", TEXTS.get("burner_fans.hint"))));
        return List.of(devicePage("fired-heater-low-fuel", WINDOW[0], WINDOW[1], input, (d, s) -> {
            d.title("Fired heater, radiant coil");
            d.name("H-101", true);
            d.tabs(0, "Overview", "Control", "Datasheet");
            d.status(Door.tr("gui.icheme.fluid.flowing") + " · LOW FUEL", Palette.RED);
            var box = device(d, input, DeviceColumn.rows(input, TEXTS), Map.of());
            contents(d, box, 1, List.of(new String[]{"Component", "Mole %", "kmol/h"}, new String[]{"Water", "100.0", "1995"}));
            d.footer(2, "Close", "Apply", "Mole", "°C", "bar", "/h");
        }));
    }

    /** Two raised alarms (batch 2026-11-09-furnace-fuel-power): the alarms group is last on a DCS's controller list and a controller box's loop page. */
    static final List<ControlColumns.Alarm> ALARMS=List.of(new ControlColumns.Alarm("EQ-0007","NO_FUEL","Fired heater, 120, 64, -40"),new ControlColumns.Alarm("EQ-0012","LOW_FUEL","Fired heater, 96, 64, -12"));
    static <T> List<T> plus(List<T> a,List<T> b){var out=new ArrayList<T>(a);out.addAll(b);return out;}
    static List<Prototype> controlPanels(){
        var out=new ArrayList<Prototype>();
        for(boolean dcs:new boolean[]{false,true})for(boolean compact:new boolean[]{false,true}){
            var layout=ControlColumns.layout(compact?320:640,compact?240:480,dcs);
            out.add(new Prototype((dcs?"control-dcs":"control-local")+(compact?"-compact":"-wide"),layout.width(),layout.height(),(d,s)->{
            d.skin.title(d.c,10,10,dcs?"DCS":"Local controller");
            var view=new ControlColumns.View(true,"m","%",true,"LT-0012 / LEVEL","37.0","CV-0021","CONTROLLING",true,ControlColumns.Variable.LEVEL);
            // KitDoor shares the long loop column's scroll offset; this one-row controller list is above the end view.
            if(layout.split()&&!d.end)doorColumn(d,plus(ControlColumns.controllers(List.of(new ControlColumns.Choice("controller:1","Drum level",true)),DIALOG::get),ControlColumns.alarms(ALARMS,DIALOG::get)),layout.areas().getFirst(),Map.of(),"DCS controller list");
            if(layout.chooser()!=null)d.skin.button(d.c,layout.chooser(),DIALOG.get("control.controllers"),Skin.Press.REST,false);
            doorColumn(d,dcs?ControlColumns.loop(view,DIALOG::get):plus(ControlColumns.loop(view,DIALOG::get),ControlColumns.alarms(ALARMS,DIALOG::get)),layout.areas().getLast(),Map.of("name","Drum level","target","25"),"Level control");
            dialogFooter(d,DIALOG.get("control.close"),DIALOG.get("control.apply"));
            },true));
        }
        var compact=ControlColumns.layout(320,240,true);
        out.add(new Prototype("control-dcs-controller-list",compact.width(),compact.height(),(d,s)->{
            d.skin.title(d.c,10,10,"DCS");
            d.skin.button(d.c,compact.chooser(),DIALOG.get("control.back"),Skin.Press.REST,false);
            doorColumn(d,plus(ControlColumns.controllers(List.of(new ControlColumns.Choice("controller:1","Drum level",true),new ControlColumns.Choice("controller:2","Feed flow",false)),DIALOG::get),ControlColumns.alarms(ALARMS,DIALOG::get)),compact.areas().getFirst(),Map.of(),"Compact controller list");
            dialogFooter(d,DIALOG.get("control.close"));
        }));
        out.add(new Prototype("control-instrument-ids",246,254,(d,s)->{
            d.skin.title(d.c,10,10,"Drum instruments");
            var rows=new ArrayList<Column.Row>();
            rows.add(new Column.Group("Readings",null,List.of(new Column.Reading("level","Liquid level","25","%",null))));
            rows.addAll(DeviceColumn.instrumentIds(List.of("LT-0012 / Liquid level","PT-0013 / Pressure probe"),TEXTS));
            doorColumn(d,rows,new Rect(10,30,226,184),Map.of(),"Instrument IDs");
            dialogFooter(d,"Close");
        }));
        return out;
    }

    static Prototype exchangerTypes() {
        return new Prototype("exchanger-types",464,254,(d,s)-> {
            var page=ConstructorColumn.design(new ConstructorColumn.Design(VesselPlacement.Form.EXCHANGER,2.25,7,2,3,0,0,ProcessUnit.Bank.ONE,null,
                    new ConstructorColumn.Outlet(1,0,STANDING),16,com.wormzjl.icheme.science.equipment.TowerBank.ONE,MachineDuty.NONE,
                    com.wormzjl.icheme.science.equipment.ExchangerType.CROSSFLOW),DIALOG);
            constructorDoor(d,page,"Heat exchanger","change",DIALOG.get(CONSTRUCTOR+"surface","2,658"),Palette.ACCENT,Map.of());
            var b=d.skin.well(d.c,new Rect(203,30,251,170));
            d.c.text("Two tube passes",b.x()+8,b.y()+10,Palette.ACCENT);
            d.c.text("Upper internal space",b.x()+8,b.y()+35,Palette.TEXT);
            d.c.fill(b.x()+20,b.y()+55,b.x()+220,b.y()+65,Palette.LINE);
            d.c.outline(b.x()+20,b.y()+50,200,80,Palette.ACCENT);
            d.c.fill(b.x()+20,b.y()+116,b.x()+220,b.y()+126,Palette.LINE);
            d.c.text("Lower internal space",b.x()+8,b.y()+139,Palette.TEXT);
        },true);
    }

    // ---- One dense fluid (batch 2026-11-15-supercritical-fluids, S5) ----

    /**
     * A closed tank of carbon dioxide at 310 K at reduced density 0.4, 0.8, 1.0005 and 1.6, and a casing pump drawing it at 0.6
     * and 0.8: the shipped DeviceMeters on a view formed from the network model's own flash, as the screen reads it, with the
     * client's dense label ({@code DenseLabel}) evaluated on that view. The slot rule moves the fluid between the vapour and the
     * oil row at 1; the tab and legend of the row that holds it read Dense above 0.5, the level line No interface, the pump's
     * gas meter its head share.
     */
    static List<Prototype> dense() {
        var catalog = com.wormzjl.icheme.science.material.MaterialCatalog.bundled();
        var model = com.wormzjl.icheme.client.gui.common.DenseLabel.model(catalog);
        double[] co2 = new double[model.componentCount()];
        co2[model.components().indexOf("CarbonDioxide")] = 1;
        var weights = new ArrayList<Double>();
        for (int i = 0; i < model.componentCount(); i++) weights.add(model.molecularWeight(i));
        var out = new ArrayList<Prototype>();
        for (double delta : new double[]{.4, .8, 1.0005, 1.6, .6}) {
            double lo = 1e5, hi = 8e7;
            for (int i = 0; i < 200 && hi - lo > 1e-13 * hi; i++) {
                double mid = (lo + hi) / 2;
                if (model.flashTP(310, mid, co2, () -> {
                }).reducedDensity() < delta) lo = mid;
                else hi = mid;
            }
            var s = model.flashTP(310, (lo + hi) / 2, co2, () -> {
            });
            var state = new com.wormzjl.icheme.runtime.fluid.FluidView.State(s.pressure(), s.temperature(), s.volume(), s.mass(),
                    new double[]{s.labelVolume(com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.OIL), s.labelVolume(com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.AQUEOUS), s.vaporVolume()},
                    new double[][]{s.labelAmountsView(com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.OIL).clone(), s.labelAmountsView(com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.AQUEOUS).clone(), s.vaporAmounts()},
                    com.wormzjl.icheme.science.fluid.state.SolidInventory.EMPTY, s.volume());
            String tag = String.valueOf(delta).replace('.', '_');
            String said = "reduced density " + delta + ", " + (s.oneFluidSlot() == com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.State.VAPOR ? "vapour slot" : "liquid slot");
            boolean pump = delta == .6 || delta == .8;
            if (delta != .6) {
                var tank = new com.wormzjl.icheme.runtime.fluid.FluidView.Tank(2, false, 0, 0, s.volume(), s.pressure(), s.pressure());
                var view = new com.wormzjl.icheme.runtime.fluid.FluidView(2, 1, 0, 0, "FULL", state, 0, List.of(), null, 1, List.of(), null, null, List.of(), tank);
                var meters = new com.wormzjl.icheme.client.gui.common.DeviceMeters(new com.wormzjl.icheme.client.gui.common.DeviceMeters.Data(Kind.RESERVOIR, view, model.components(), weights, true, null, false, List.of()),
                        null, com.wormzjl.icheme.client.gui.common.DeviceMeters.PhaseRatioBasis.VOLUME, com.wormzjl.icheme.science.display.DisplayUnit.TemperatureUnit.CELSIUS,
                        com.wormzjl.icheme.science.display.DisplayUnit.PressureUnit.BAR, com.wormzjl.icheme.science.display.DisplayUnit.FlowRateUnit.PER_SECOND, false, TEXTS);
                var m = meters.withDense(meters.dense(model));
                var in = new DeviceColumn.Input(Kind.RESERVOIR, true, false, true, "bar", "°C", "/s", DeviceColumn.Valve.NONE, null, false, m.level(), null, List.of(), m.phases(), m.meters());
                int row = m.dense() >= 0 ? m.dense() : s.oneFluidSlot() == com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.State.VAPOR ? 2 : 0;
                out.add(devicePage("dense-tank-" + tag, WINDOW[0], WINDOW[1], in, (d, k) -> {
                    d.title("CO2 tank at 310 K, " + said);
                    d.tabs(0, "Overview", "Outlets", "Datasheet");
                    d.status(Door.tr("gui.icheme.fluid.flowing"), Palette.MUTED);
                    var box = device(d, in, Map.of());
                    denseContents(d, box, row, m.dense(), List.of(new String[]{"Component", "Volume %", "kmol"}, new String[]{"CarbonDioxide", "100.0", com.wormzjl.icheme.client.gui.common.kit.Format.number(s.mass() / .04401 / 1000)}));
                    d.footer(1, "Close", "Volume", "°C", "bar", "/s");
                }));
            }
            if (pump) {
                double alpha = 1 - com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.pumpLiquidLikeWeight(s);
                var view = new com.wormzjl.icheme.runtime.fluid.FluidView(3, 1, 0, 0, "STEADY: flowing", state, 5.0, List.of(), 2e5, 1, List.of(), null, null, List.of(), null, null, null, null, null,
                        new com.wormzjl.icheme.runtime.fluid.FluidView.Pump(null, null, alpha, 1, null, null), null, null, null, null);
                var meters = new com.wormzjl.icheme.client.gui.common.DeviceMeters(new com.wormzjl.icheme.client.gui.common.DeviceMeters.Data(Kind.PUMP, view, model.components(), weights, true, null, false, List.of()),
                        null, com.wormzjl.icheme.client.gui.common.DeviceMeters.PhaseRatioBasis.VOLUME, com.wormzjl.icheme.science.display.DisplayUnit.TemperatureUnit.CELSIUS,
                        com.wormzjl.icheme.science.display.DisplayUnit.PressureUnit.BAR, com.wormzjl.icheme.science.display.DisplayUnit.FlowRateUnit.PER_HOUR, false, TEXTS);
                var m = meters.withDense(meters.dense(model));
                var in = casingInput().withSuction(m.suction());
                // The Suction and Health groups alone, as the page shows them under the drive.
                List<Column.Row> rows = DeviceColumn.suction(m.suction(), TEXTS);
                out.add(devicePage("dense-pump-" + tag, WINDOW[0], WINDOW[1], rows, (d, k) -> {
                    d.title("Centrifugal pump, CO2 at 310 K, " + said);
                    d.name("P-101", true);
                    d.tabs(0, "Overview", "Datasheet");
                    d.status(Door.tr("gui.icheme.fluid.flowing"), Palette.MUTED);
                    device(d, in, rows, Map.of("volumeFlow", "100.0", "maximumAddedPressure", "10.0"));
                    d.footer(2, "Close", "Apply", "Volume", "°C", "bar", "/h");
                }));
            }
        }
        // A centrifugal pump on a two-phase supply (P2 of batch 2026-11-16-supercritical-followups, item 10): water with 3 % of
        // its suction volume nitrogen at 2 bar, its curve present: the head share beside the gas.
        double[] wet = new double[model.componentCount()];
        wet[model.components().indexOf("Nitrogen")] = .02;
        wet[wet.length - 1] = .98;
        var s = model.flashTP(300, 2e5, wet, () -> {
        });
        var state = new com.wormzjl.icheme.runtime.fluid.FluidView.State(s.pressure(), s.temperature(), s.volume(), s.mass(),
                new double[]{s.labelVolume(com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.OIL), s.labelVolume(com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.AQUEOUS), s.vaporVolume()},
                new double[][]{s.labelAmountsView(com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.OIL).clone(), s.labelAmountsView(com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.AQUEOUS).clone(), s.vaporAmounts()},
                com.wormzjl.icheme.science.fluid.state.SolidInventory.EMPTY, s.volume());
        var view = new com.wormzjl.icheme.runtime.fluid.FluidView(3, 1, 0, 0, "STEADY: flowing", state, 5.0, List.of(), 2e5, 1, List.of(), null, null, List.of(), null, null, null, null, null,
                new com.wormzjl.icheme.runtime.fluid.FluidView.Pump(6.0, 2.0, .03, 1, new com.wormzjl.icheme.runtime.fluid.FluidView.Curve(100 / 3600.0, 80, .70, 2950), null), null, null, null, null);
        var meters = new com.wormzjl.icheme.client.gui.common.DeviceMeters(new com.wormzjl.icheme.client.gui.common.DeviceMeters.Data(Kind.PUMP, view, model.components(), weights, true, null, false, List.of()),
                null, com.wormzjl.icheme.client.gui.common.DeviceMeters.PhaseRatioBasis.VOLUME, com.wormzjl.icheme.science.display.DisplayUnit.TemperatureUnit.CELSIUS,
                com.wormzjl.icheme.science.display.DisplayUnit.PressureUnit.BAR, com.wormzjl.icheme.science.display.DisplayUnit.FlowRateUnit.PER_HOUR, false, TEXTS);
        var m = meters.withDense(meters.dense(model));
        var in = casingInput().withSuction(m.suction());
        List<Column.Row> rows = DeviceColumn.suction(m.suction(), TEXTS);
        out.add(devicePage("dense-two-phase-pump", WINDOW[0], WINDOW[1], rows, (d, k) -> {
            d.title("Centrifugal pump, water with 3 % gas at 2 bar");
            d.name("P-101", true);
            d.tabs(0, "Overview", "Datasheet");
            d.status(Door.tr("gui.icheme.fluid.flowing"), Palette.MUTED);
            device(d, in, rows, Map.of("volumeFlow", "100.0", "maximumAddedPressure", "10.0"));
            d.footer(2, "Close", "Apply", "Volume", "°C", "bar", "/h");
        }));
        return out;
    }

    /** The contents' phase tabs as the device screen names them ({@code DeviceColumn.phaseKey}, the dense row's "Dense"), then the table. */
    static void denseContents(Door d, Rect box, int phase, int dense, List<String[]> table) {
        int n = 4, tw = (box.w() - 3 * (n - 1)) / n;
        for (int i = 0; i < n; i++) d.skin.button(d.c, new Rect(box.x() + i * (tw + 3), box.y(), tw, 16), TEXTS.get("phase_tab." + DeviceColumn.phaseKey(i, false, dense)), i == phase ? Skin.Press.CURRENT : Skin.Press.REST, false);
        d.table(new Rect(box.x(), box.y() + 20, box.w(), box.h() - 20), new int[]{box.w() - 120, 60, 60}, table);
    }

    // ---- The plunger pump (batch 2026-11-15-supercritical-fluids, S8; -Donly=plunger) ----

    /**
     * A triplex plunger pump PP-30 on carbon dioxide at 310 K, reduced density 0.7, at 20 m³/h and a rise of 10 MPa, on a 4-pole
     * motor through its gearbox: the shipped DeviceMeters on a view formed from the network model's own flash, as the screen reads
     * it (its drive's shaft power the plungers' work over 0.88, no NPSH3 of its own, the suction meter the server's share).
     */
    static List<Prototype> plunger() {
        var catalog = com.wormzjl.icheme.science.material.MaterialCatalog.bundled();
        var model = com.wormzjl.icheme.client.gui.common.DenseLabel.model(catalog);
        double[] co2 = new double[model.componentCount()];
        co2[model.components().indexOf("CarbonDioxide")] = 1;
        var weights = new ArrayList<Double>();
        for (int i = 0; i < model.componentCount(); i++) weights.add(model.molecularWeight(i));
        double delta = .7, lo = 1e5, hi = 8e7;
        for (int i = 0; i < 200 && hi - lo > 1e-13 * hi; i++) {
            double mid = (lo + hi) / 2;
            if (model.flashTP(310, mid, co2, () -> {
            }).reducedDensity() < delta) lo = mid;
            else hi = mid;
        }
        var s = model.flashTP(310, (lo + hi) / 2, co2, () -> {
        });
        var state = new com.wormzjl.icheme.runtime.fluid.FluidView.State(s.pressure(), s.temperature(), s.volume(), s.mass(),
                new double[]{s.labelVolume(com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.OIL), s.labelVolume(com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.AQUEOUS), s.vaporVolume()},
                new double[][]{s.labelAmountsView(com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.OIL).clone(), s.labelAmountsView(com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.AQUEOUS).clone(), s.vaporAmounts()},
                com.wormzjl.icheme.science.fluid.state.SolidInventory.EMPTY, s.volume());
        var law = com.wormzjl.icheme.science.machine.PlungerPump.of(com.wormzjl.icheme.science.machine.PlungerPump.model(3));
        double flow = 20 / 3600.0, rise = 10e6, density = s.mass() / s.volume(), shaft = flow * rise / com.wormzjl.icheme.science.machine.PlungerPump.MECHANICAL_EFFICIENCY;
        double motor = com.wormzjl.icheme.science.machine.Motor.ratingFor(law.maximumShaftPower());
        double alpha = 1 - com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.pumpLiquidLikeWeight(s);
        var view = new com.wormzjl.icheme.runtime.fluid.FluidView(3, 1, 0, 0, "STEADY: flowing", state, flow * density, List.of(), rise, 1, List.of(), null, null, List.of(), null, null, null,
                new com.wormzjl.icheme.runtime.fluid.FluidView.Drive("MOTOR", motor, 4, "RUNNING", 1500, false, flow / law.displacement(), shaft),
                null, new com.wormzjl.icheme.runtime.fluid.FluidView.Pump(null, null, alpha, 1, null, null), null, null, null, null);
        var meters = new com.wormzjl.icheme.client.gui.common.DeviceMeters(new com.wormzjl.icheme.client.gui.common.DeviceMeters.Data(Kind.PUMP, view, model.components(), weights, true, null, false, List.of()),
                null, com.wormzjl.icheme.client.gui.common.DeviceMeters.PhaseRatioBasis.VOLUME, com.wormzjl.icheme.science.display.DisplayUnit.TemperatureUnit.CELSIUS,
                com.wormzjl.icheme.science.display.DisplayUnit.PressureUnit.BAR, com.wormzjl.icheme.science.display.DisplayUnit.FlowRateUnit.PER_HOUR, false, TEXTS);
        var m = meters.withDense(meters.dense(model));
        var in = new DeviceColumn.Input(Kind.PUMP, true, true, true, "bar", "°C", "/h", DeviceColumn.Valve.NONE, null, false, null, null, List.of(),
                m.phases(), m.meters(), m.drive(), null, m.suction());
        System.out.printf(java.util.Locale.ROOT, "plunger: %.6g Pa, %.1f kg/m3, shaft %.2f kW on a %.0f kW motor, alpha %.4f%n", s.pressure(), density, shaft / 1000, motor / 1000, alpha);
        var out = new ArrayList<Prototype>();
        out.add(devicePage("plunger-pump-0_7", WINDOW[0], WINDOW[1], in, (d, k) -> {
                d.title("Plunger pump PP-30, CO2 at 310 K, reduced density 0.7");
                d.name("P-101", true);
                d.tabs(0, "Overview", "Datasheet");
                d.status(Door.tr("gui.icheme.fluid.flowing"), Palette.MUTED);
                device(d, in, DeviceColumn.rows(in, TEXTS), Map.of("volumeFlow", "20.0", "maximumAddedPressure", "100.0"));
                d.footer(2, "Close", "Apply", "Volume", "°C", "bar", "/h");
            }));
        return out;
    }

    // ---- W10d's pump readings and curve (batch 2026-11-03-rotating-machinery, decision D37; W10D_BUILD_PLAN.md) ----

    /**
     * The constructed pump P-101 (as machine-casing) with the shipped Suction and Health groups (DeviceColumn) and the shipped
     * curve on the glass (PumpCurve), cavitating: NPSH available 6.8 m between NPSH3 3.3 m and 4 x NPSH3 13.2 m (σ about 2);
     * the same pump at 70 % speed running quiet; and an in-line pump with the readings only and its composition table.
     */
    static List<Prototype> pumpReadings() {
        double rated = 100 / 3600.0;
        var pump = new com.wormzjl.icheme.science.machine.CentrifugalPump(rated, 80, 0.70, 2950);
        String required = com.wormzjl.icheme.client.gui.common.kit.Format.number(pump.npsh(1).required(rated));
        var casing = casingInput().withSuction(new DeviceColumn.Suction("6.8", required, "0.4", "73.0", "87.6"));
        var casingRows = DeviceColumn.rows(casing, TEXTS);
        double slow = .7, flow = .7 * rated, head = pump.head(flow, slow);
        String slowRequired = com.wormzjl.icheme.client.gui.common.kit.Format.number(pump.npsh(slow).required(flow));
        var quiet = casingInput().withSuction(new DeviceColumn.Suction("12.0", slowRequired, "0.0", "73.0"));
        var inline = new DeviceColumn.Input(Kind.PUMP, true, true, true, "bar", "°C", "/h", DeviceColumn.Valve.NONE, null, false, null, null, List.of(),
                phases("volume", 0, 1, 0, 0), List.of(meter("temperature", "25.0", "°C"), meter("suction_pressure", "1.10", "bar"),
                meter("throughput", "1995.0", "kmol/h"), meter("pressure_change", "2.40", "bar"))).withSuction(new DeviceColumn.Suction("9.5", "1.7", "0.0", "100.0"));
        var inlineRows = DeviceColumn.rows(inline, TEXTS);
        return List.of(
                devicePage("pump-readings-casing", WINDOW[0], WINDOW[1], casing, (d, s) -> {
                    d.title("Centrifugal pump");
                    d.name("P-101", true);
                    d.tabs(0, "Overview", "Datasheet");
                    d.status(Door.tr("gui.icheme.fluid.flowing") + " · CAVITATION", Palette.MUTED);
                    var box = device(d, casing, casingRows, Map.of("volumeFlow", "100.0", "maximumAddedPressure", "10.0"));
                    d.c.clip(box);
                    PumpCurve.draw(d.c, box, new PumpCurve.Input(pump, 1.0, rated, 80, 6.8, 3600, "m³/h"), TEXTS);
                    d.c.unclip();
                    d.footer(2, "Close", "Apply", "Mole", "°C", "bar", "/h");
                }),
                devicePage("pump-readings-quiet", WINDOW[0], WINDOW[1], quiet, (d, s) -> {
                    d.title("Centrifugal pump");
                    d.name("P-101", true);
                    d.tabs(0, "Overview", "Datasheet");
                    d.status(Door.tr("gui.icheme.fluid.flowing"), Palette.MUTED);
                    var box = device(d, quiet, DeviceColumn.rows(quiet, TEXTS), Map.of("volumeFlow", "70.0", "maximumAddedPressure", "10.0"));
                    d.c.clip(box);
                    PumpCurve.draw(d.c, box, new PumpCurve.Input(pump, slow, flow, head, 12.0, 3600, "m³/h"), TEXTS);
                    d.c.unclip();
                    d.footer(2, "Close", "Apply", "Mole", "°C", "bar", "/h");
                }),
                devicePage("pump-readings-inline", WINDOW[0], WINDOW[1], inline, (d, s) -> {
                    d.title("In-line pump");
                    d.name("P-102", true);
                    d.tabs(0, "Overview", "Datasheet");
                    d.status(Door.tr("gui.icheme.fluid.flowing"), Palette.MUTED);
                    var box = device(d, inline, inlineRows, Map.of("volumeFlow", "36.0", "maximumAddedPressure", "5.00"));
                    contents(d, box, 1, List.of(new String[]{"Component", "Mole %", "kmol/h"}, new String[]{"Water", "100.0", "1995"}));
                    d.footer(2, "Close", "Apply", "Mole", "°C", "bar", "/h");
                }));
    }

    // ---- W7's machine pages (batch 2026-11-03-rotating-machinery, owner decision D31: option A, each machine its own page) ----

    static final String MACHINE = MachineColumn.KEY;

    /** A machine text under {@code gui.icheme.machine.}. */
    static String mt(String key, Object... args) {
        return DIALOG.get(MACHINE + key, args);
    }

    /**
     * A pump casing's page with the Drive group (the shipped DeviceColumn): a 30 kW 2-pole motor on a variable drive at 2950
     * rpm driving P-101, 100 m³/h at 80 m, efficiency 70 %, speed 100 %.
     */
    static DeviceColumn.Input casingInput() {
        return new DeviceColumn.Input(Kind.PUMP, true, true, true, "bar", "°C", "/h", DeviceColumn.Valve.NONE, null, false, null, null, List.of(),
                phases("volume", 0, 1, 0, 0), List.of(meter("temperature", "25.0", "°C"), meter("suction_pressure", "1.20", "bar"), meter("throughput", "5530.0", "kmol/h"),
                meter("pressure_change", "7.83", "bar"), new DeviceColumn.Meter("head", "80.0", "m", TEXTS.get("head.hint")), meter("speed_share", "100.0", "%"),
                meter("shaft_power", "24.9", "kW"), new DeviceColumn.Meter("efficiency", "70.0", "%", TEXTS.get("efficiency.hint"))),
                new DeviceColumn.Drive(TEXTS.get("drive_line_poles", TEXTS.get("driver.motor"), "30.0", 2), TEXTS.get("drive_state", "RUNNING", "2950"), true, false), null);
    }

    /** A device page's frame around {@code rows} instead of a DeviceColumn.Input's (as {@link #devicePage}). */
    static Prototype devicePage(String file, int fw, int fh, List<Column.Row> rows, BiConsumer<Door, Skin> body) {
        var m = new HeatDiagramRender.Java2dCanvas(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics()).measure();
        java.util.function.BiPredicate<Integer,Integer> fit = (w, h) -> FluidGuiLayout.fits(w, h, iw -> Column.place(rows, 10, FluidGuiLayout.DOOR_TOP, FluidGuiLayout.side(iw) - 10, m, Column.Metrics.COMPACT).bounds().h());
        int s = Math.max(1, Integer.getInteger("guiScale", 3)), gw = -Math.floorDiv(-fw, s), gh = -Math.floorDiv(-fh, s);
        System.out.printf(java.util.Locale.ROOT, "%-24s %d by %d window: player scale %d, %d by %d GUI px%n", file, fw, fh, s, gw, gh);
        return new Prototype(file, FluidGuiLayout.imageWidth(gw), FluidGuiLayout.imageHeight(gh), body, !fit.test(gw, gh));
    }

    /**
     * {@code rows} in a door column's view {@code area} (narrowed for its bar where taller), scrolled to its end where the door
     * asks, with their widgets; fields show {@code drafts}. Prints the column's height against the view as {@code what}.
     */
    static void doorColumn(Door d, List<Column.Row> rows, Rect area, Map<String, String> drafts, String what) {
        int tall = Column.place(rows, area.x(), area.y(), area.w(), d.m, Column.Metrics.COMPACT).bounds().h();
        int width = tall > area.h() ? area.w() - ScrollView.BAR - 4 : area.w();
        var column = Column.place(rows, area.x(), area.y(), width, d.m, Column.Metrics.COMPACT);
        var view = new ScrollView();
        view.layout(new Rect(area.x() - 2, area.y(), width + 4, area.h()), area.x() + width + 3, column.bounds().h());
        while (d.end && view.offset() < view.max()) view.wheel(view.viewport().x() + 2, view.viewport().y() + 2, -1);
        d.c.clip(view.viewport());
        column.draw(d.c, -view.offset(), d.skin);
        for (var p : column.rows()) {
            if (p.row() instanceof Column.Field f) {
                var r = p.slots().get(f.key());
                if (view.visible(r)) d.skin.field(d.c, view.onScreen(r), drafts.getOrDefault(f.key(), ""), "", false);
            } else if (p.row() instanceof Column.Wide wide) {
                var r=p.slots().get(wide.key());
                if(view.visible(r))d.skin.field(d.c,view.onScreen(r),drafts.getOrDefault(wide.key(),""),"",false);
            } else if (p.row() instanceof Column.Buttons b)
                for (var spec : b.buttons()) {
                    var r = p.slots().get(spec.key());
                    if (!view.visible(r)) continue;
                    if (spec.soft()) d.skin.softKey(d.c, view.onScreen(r), spec.text(), spec.latched());
                    else d.skin.button(d.c, view.onScreen(r), spec.text(), spec.latched() ? Skin.Press.CURRENT : !spec.active() ? Skin.Press.OFF : Skin.Press.REST, false);
                }
            else if (p.row() instanceof Column.Setting st && st.steps())
                for (var spec : List.of(st.minus(), st.plus())) {
                    var r = p.slots().get(spec.key());
                    if (view.visible(r)) d.skin.button(d.c, view.onScreen(r), spec.text(), Skin.Press.REST, false);
                }
        }
        d.c.unclip();
        view.drawBar(d.c, d.skin);
        if (!d.end && d.skin == PanelSkin.LIGHT) System.out.printf(java.util.Locale.ROOT, "%-24s column %3d px in a %d px view%n", what, column.bounds().h(), area.h());
    }

    /** A device page's door column (x 10 to {@code FluidGuiLayout.side}), its divider and the glass; returns the glass's inner box. */
    static Rect deviceDoor(Door d, List<Column.Row> rows, Map<String, String> drafts, String what) {
        int side = FluidGuiLayout.side(d.w), top = FluidGuiLayout.DOOR_TOP, bottom = d.h - FluidGuiLayout.DOOR_FOOT;
        doorColumn(d, rows, new Rect(10, top, side - 10, bottom - top), drafts, what);
        d.skin.vDivider(d.c, side + 13, top, bottom);
        return d.well(new Rect(side + 15, 72, d.w - side - 20, d.h - 50 - 72));
    }

    /** Push buttons at the footer's left under its divider, as MachineScreen places them: Close 50 px, the others 60, {@code primary} the confirm one (-1: none). */
    static void pushFooter(Door d, int primary, String... texts) {
        d.skin.hDivider(d.c, 8, d.w - 8, d.h - 35);
        int x = 10;
        for (int i = 0; i < texts.length; i++) {
            int w = i == 0 ? 50 : 60;
            d.skin.button(d.c, new Rect(x, d.h - 27, w, 18), texts[i], i == primary ? Skin.Press.PRIMARY : Skin.Press.REST, false);
            x += w + 6;
        }
    }

    /** The nameplate on the glass, as MachineScreen draws it. */
    static void nameplate(Door d, Rect box, List<String[]> lines) {
        int x = box.x() + 6, y = box.y() + 6, w = Math.min(box.w() - 12, 240);
        var all = new ArrayList<String[]>();
        all.add(new String[]{mt("nameplate"), ""});
        all.addAll(lines);
        for (int i = 0; i < all.size() && y + 10 <= box.bottom(); i++, y += 12) {
            d.c.text(d.m.cut(all.get(i)[0], w - 80), x, y, i == 0 ? Palette.MUTED : Palette.TEXT);
            d.c.text(all.get(i)[1], x + w - d.m.width(all.get(i)[1]), y, Palette.ACCENT);
            if (i == 0) d.c.hLine(x - 2, x + w + 2, y + 10, Palette.LINE);
        }
    }

    /** One machine's page from the shipped MachineColumn: its title strip, the one tab, the status once, the door, the nameplate, the footer. */
    static Prototype machinePage(String file, MachineColumn.Input in, String name, String status, Map<String, String> drafts, List<String[]> plate, boolean datasheet) {
        var rows = MachineColumn.rows(in, DIALOG);
        return devicePage(file, WINDOW[0], WINDOW[1], rows, (d, s) -> {
            d.title(mt("kind." + in.kind().name().toLowerCase(java.util.Locale.ROOT)));
            if (!name.isEmpty()) d.name(name, in.canEdit());
            d.tabs(0, TEXTS.get("overview"));
            d.status(status, MachineColumn.warns(status) ? Palette.WARN : Palette.MUTED);
            var box = deviceDoor(d, rows, drafts, file);
            nameplate(d, box, plate);
            boolean apply = in.canEdit() && in.kind().settable();
            if (datasheet) pushFooter(d, apply ? 2 : -1, apply ? new String[]{TEXTS.get("close"), TEXTS.get("datasheet"), TEXTS.get("apply")} : new String[]{TEXTS.get("close"), TEXTS.get("datasheet")});
            else pushFooter(d, apply ? 1 : -1, apply ? new String[]{TEXTS.get("close"), TEXTS.get("apply")} : new String[]{TEXTS.get("close")});
        });
    }

    /** Each machine's page (motor, motor tripped, steam turbine, generator, gearbox, creative motor, a reader) and the casing with its Drive group. */
    static List<Prototype> machinePages() {
        var t = DIALOG;
        var motorPlate = List.of(new String[]{mt("rating"), "30.0 kW"}, new String[]{mt("poles"), "2"}, new String[]{mt("drive"), mt("drive.variable")},
                new String[]{mt("rated_speed"), "2950 rpm"});
        var casing = casingInput();
        return List.of(
                machinePage("machine-motor", MachineColumnSamples.sample(MachineColumn.Kind.MOTOR, true, true, MachineColumn.Mode.SPEED, false, t), "M-101", "RUNNING",
                        Map.of("speed", "100"), motorPlate, true),
                machinePage("machine-motor-tripped", MachineColumnSamples.sample(MachineColumn.Kind.MOTOR, true, false, MachineColumn.Mode.SPEED, true, t), "M-102", "TRIPPED: OVERLOAD",
                        Map.of(), List.of(new String[]{mt("rating"), "30.0 kW"}, new String[]{mt("poles"), "2"}, new String[]{mt("drive"), mt("drive.fixed")},
                                new String[]{mt("rated_speed"), "2950 rpm"}), true),
                machinePage("machine-turbine", MachineColumnSamples.sample(MachineColumn.Kind.STEAM_TURBINE, true, true, MachineColumn.Mode.FLOW, false, t), "KT-101", "RUNNING",
                        Map.of(), List.of(new String[]{mt("rating"), "2500.0 kW"}, new String[]{mt("rated_speed"), "4200 rpm"}), true),
                machinePage("machine-turbine-tripped", MachineColumnSamples.sample(MachineColumn.Kind.STEAM_TURBINE, true, true, MachineColumn.Mode.SPEED, true, t), "KT-102", "TRIPPED: OVERSPEED",
                        Map.of("speed", "4200"), List.of(new String[]{mt("rating"), "2500.0 kW"}, new String[]{mt("rated_speed"), "4200 rpm"}), true),
                machinePage("machine-generator", MachineColumnSamples.sample(MachineColumn.Kind.GENERATOR, true, true, MachineColumn.Mode.SPEED, false, t), "G-101", "RUNNING",
                        Map.of("powerLimit", ""), List.of(new String[]{mt("rating"), "30.0 kW"}, new String[]{mt("poles"), "2"}, new String[]{mt("rated_speed"), "2950 rpm"}), true),
                machinePage("machine-gearbox", MachineColumnSamples.sample(MachineColumn.Kind.GEARBOX, true, true, MachineColumn.Mode.SPEED, false, t), "GB-101", "RUNNING",
                        Map.of("ratio", "0"), List.<String[]>of(new String[]{mt("rated_speed"), "12600 rpm"}), true),
                machinePage("machine-creative-motor", MachineColumnSamples.sample(MachineColumn.Kind.CREATIVE_MOTOR, true, true, MachineColumn.Mode.SPEED, false, t), "", "RUNNING",
                        Map.of("speed", "2950", "powerLimit", ""), List.<String[]>of(new String[]{mt("rated_speed"), "2950 rpm"}), false),
                machinePage("machine-motor-reader", MachineColumnSamples.sample(MachineColumn.Kind.MOTOR, false, true, MachineColumn.Mode.SPEED, false, t), "M-101", "RUNNING",
                        Map.of(), motorPlate, true),
                devicePage("machine-casing", WINDOW[0], WINDOW[1], casing, (d, s) -> {
                    d.title("Centrifugal pump");
                    d.name("P-101", true);
                    d.tabs(0, "Overview", "Datasheet");
                    d.status(Door.tr("gui.icheme.fluid.flowing"), Palette.MUTED);
                    var box = device(d, casing, Map.of("volumeFlow", "100.0", "maximumAddedPressure", "10.0"));
                    contents(d, box, 1, List.of(new String[]{"Component", "Mole %", "kmol/h"}, new String[]{"Water", "100.0", "5530"}));
                    d.footer(2, "Close", "Apply", "Mole", "°C", "bar", "/h");
                }));
    }

    // ---- W6b's Create coupling page (batch 2026-11-03-rotating-machinery, form A, decision D40; -Donly=create-coupling) ----

    /**
     * The Create coupling's page as the game draws it (D41): a published MachineView through MachineColumn.input and its
     * nameplate, no switches, no Apply: the 30 kW pump train of W6B_BUILD_PLAN
     * section 1 behind an auto gearbox (11.52), 24.9 kW at the pump, 25.4 kW at the coupling over the gear's 98 %, so 17,345 su at
     * 1.465 W per su. RUNNING on 40 large water wheels (20,480 su, 3,135 spare); POWER LIMITED on 24 (12,288 su, all drawn: the
     * train slows to 2629 rpm, 18.0 kW by the cube law); NO ROTATION with Create at rest.
     */
    static List<Prototype> createCoupling() {
        var t = DIALOG;
        return List.of(
                coupling("create-coupling-running", MachineColumnSamples.couplingSample("RUNNING", 256, 2950, 17345, 20480, 25.41e3), t),
                coupling("create-coupling-limited", MachineColumnSamples.couplingSample("POWER LIMITED", 256, 2629, 12287, 12288, 18.0e3), t),
                coupling("create-coupling-no-rotation", MachineColumnSamples.couplingSample("NO ROTATION", 0, 0, 0, 0, 0), t));
    }

    /** One coupling sheet from view {@code v}, for a player who may build (as MachineScreen: the coupling's rows, its nameplate, Close only). */
    static Prototype coupling(String file, com.wormzjl.icheme.runtime.fluid.MachineView v, HeatControlColumn.Texts t) {
        return machinePage(file, MachineColumn.input(v, true, true, MachineColumn.Mode.SPEED, t), "", v.status(), Map.of(), MachineColumn.nameplate(v, t), false);
    }

    // ---- N1's title strip (the owner 2026-11-03: "You can put the equipment name here") ----

    /** A 32-character name of the widest letters, and a 32-character name as a plant would write it. */
    static final String WIDEST = "W".repeat(32), LONG = "CDU-1 crude charge pump P-101A/B";

    /** One strip a row: the screen, its width, its theme key, the kind, the name, an editor's entry or a reader's text. */
    record StripCase(String screen, int width, int key, String kind, String name, boolean editor) {
    }

    static final List<StripCase> STRIPS = List.of(
            new StripCase("device", 448, 18, "Centrifugal pump", "P-101", true),
            new StripCase("device", 448, 18, "Centrifugal pump", "", true),
            new StripCase("device", 448, 18, "Centrifugal pump", WIDEST, true),
            new StripCase("device", 448, 18, "Centrifugal pump", LONG, false),
            new StripCase("device", 448, 18, "Circulating fluidized-bed boiler", LONG, true),
            new StripCase("device", 790, 18, "Natural-draft cooling tower", LONG, true),
            new StripCase("assembly", 448, 18, "Distillation column", "T-101", true),
            new StripCase("datasheet", 400, 0, "Circulating fluidized-bed boiler", WIDEST, true),
            new StripCase("datasheet", 400, 0, "Drum", "", false));

    /** Each case on its own band of door, its screen and width beside it; prints each name place's width (STRIP). */
    static Prototype titleStrip() {
        int band = 30;
        return new Prototype("title-strip", 1000, STRIPS.size() * (band + 6) + 4 + band + 6, (d, s) -> {
            for (int i = 0; i <= STRIPS.size(); i++) {
                int dy = 4 + i * (band + 6);
                if (i == STRIPS.size()) {
                    // The constructor's header as built: its 200 px panel holds the kind and Change, no room for the entry.
                    d.skin.page(d.c, new Rect(0, dy, ConstructorColumn.PANEL, band));
                    String title = ConstructorColumn.title(LONG, "Horizontal drum", "Drum", d.m, (nm, k) -> nm + " · " + k);
                    d.skin.title(d.c, 10, dy + 10, title);
                    d.c.text("constructor 200 px, as built (no room for a " + TitleStrip.NAME + " px entry)", 810, dy + 10, Palette.MUTED);
                    continue;
                }
                var k = STRIPS.get(i);
                d.skin.page(d.c, new Rect(0, dy, k.width(), band));
                var strip = TitleStrip.place(k.width(), k.key(), k.kind(), d.m);
                d.skin.title(d.c, TitleStrip.TITLE_X, TitleStrip.TITLE_Y + dy, strip.kind());
                if (k.key() > 0) d.skin.iconKey(d.c, new Rect(k.width() - 10 - k.key(), 5 + dy, k.key(), 18), Skin.Icon.THEME, false);
                var place = strip.name() == null ? null : strip.name().moved(0, dy);
                if (place != null && k.editor()) d.skin.field(d.c, place, k.name(), TEXTS.get("name"), false);
                else if (place != null) {
                    int[] at = TitleStrip.textAt(place);
                    d.skin.text(d.c, TitleStrip.text(k.name(), place, d.m), at[0], at[1], Palette.TEXT);
                }
                d.c.text(k.screen() + " " + k.width() + " px, " + (k.editor() ? "editor" : "reader"), 810, dy + 10, Palette.MUTED);
                if (d.skin == PanelSkin.LIGHT) System.out.printf(java.util.Locale.ROOT, "STRIP %-9s %3d px %-34s name place %s px%n", k.screen(), k.width(), k.kind(),
                        strip.name() == null ? "none" : Integer.toString(strip.name().w()));
            }
        });
    }

    // ---- A machine's design point (batch 2026-11-03-rotating-machinery, option A, owner pick 2026-11-04) ----

    /** The shipped design page of a pump, a motor and a steam turbine: Duty, then Speed and Drive or Inlet steam and Exhaust, then Place. */
    static List<Prototype> machines() {
        var outlet = new ConstructorColumn.Outlet(1, 0, STANDING);
        var one = com.wormzjl.icheme.science.equipment.TowerBank.ONE;
        var bank = new ProcessUnit.Bank(1, 1);
        var pump = ConstructorColumn.design(new ConstructorColumn.Design(VesselPlacement.Form.PUMP, UnitDesign.of(com.wormzjl.icheme.science.equipment.EquipmentShape.Kind.PUMP).size(MachineDuty.pump(200, 100, 2)), 1, 0, 3, 0, 0, bank, null, outlet, 16, one,
                MachineDuty.pump(200, 100, 2)), DIALOG);
        var motor = ConstructorColumn.design(new ConstructorColumn.Design(VesselPlacement.Form.MOTOR, 250, 1, 2, 3, 0, 0, bank, null, outlet, 16, one,
                MachineDuty.motor(250, 4, MachineDuty.Drive.FIXED)), DIALOG);
        var turbine = ConstructorColumn.design(new ConstructorColumn.Design(VesselPlacement.Form.STEAM_TURBINE, UnitDesign.of(com.wormzjl.icheme.science.equipment.EquipmentShape.Kind.STEAM_TURBINE).size(MachineDuty.turbine(2500, MachineDuty.Steam.HP, 450)), 1, 2, 3, 0, 0, bank, null, outlet, 16, one,
                MachineDuty.turbine(2500, MachineDuty.Steam.HP, 450)), DIALOG);
        return List.of(
                new Prototype("equipment-constructor-pump", 464, 254, (d, s) -> constructorDoor(d, pump, "Pump", "change",
                        DIALOG.get(CONSTRUCTOR + "pump_flow", "200", "100", "78"), Palette.ACCENT, Map.of()), true),
                new Prototype("equipment-constructor-motor", 464, 254, (d, s) -> constructorDoor(d, motor, "Motor", "change",
                        DIALOG.get(CONSTRUCTOR + "motor.rating", "250", "1,500", "0.75"), Palette.ACCENT, Map.of()), true),
                new Prototype("equipment-constructor-turbine", 464, 254, (d, s) -> constructorDoor(d, turbine, "Steam turbine", "change",
                        DIALOG.get(CONSTRUCTOR + "steam_turbine.power", "2,500", "4,200", "450"), Palette.ACCENT, Map.of()), true));
    }

    // ---- The column calculator: the shipped CalculatorColumn on its door (GUI_REVIEW.md order 5) ----

    /** The Column page with draw D1 selected and the Results page's profiles, at the smallest split door, a one-column door and a wide one. */
    static List<Prototype> calculator() {
        return List.of(new Prototype("column-calculator", 464, 254, (d, s) -> calculatorDoor(d, CalculatorColumn.Page.COLUMN, CalculatorColumn.Metric.TEMPERATURE), true),
                new Prototype("column-calculator-narrow", 380, 254, (d, s) -> calculatorDoor(d, CalculatorColumn.Page.COLUMN, CalculatorColumn.Metric.TEMPERATURE), true),
                new Prototype("column-calculator-wide", 533, 280, (d, s) -> calculatorDoor(d, CalculatorColumn.Page.COLUMN, CalculatorColumn.Metric.TEMPERATURE)),
                new Prototype("column-calculator-results", 464, 254, (d, s) -> calculatorDoor(d, CalculatorColumn.Page.RESULTS, CalculatorColumn.Metric.TEMPERATURE)),
                new Prototype("column-calculator-results-wide", 533, 280, (d, s) -> calculatorDoor(d, CalculatorColumn.Page.RESULTS, CalculatorColumn.Metric.TRAFFIC)),
                // C1 (COOLER_MODEL_PLAN.md section 6): the condenser's third choice, air then water, with its banks and its trim's shells.
                new Prototype("column-calculator-air-then-water", 464, 254, (d, s) -> calculatorDoor(d, CalculatorColumn.Page.EQUIPMENT, CalculatorColumn.Metric.TEMPERATURE), true),
                new Prototype("column-calculator-air-then-water-narrow", 380, 254, (d, s) -> calculatorDoor(d, CalculatorColumn.Page.EQUIPMENT, CalculatorColumn.Metric.TEMPERATURE), true),
                // W8 (rotating machinery, D43, option B as built): the pump keys gone, the pumps' duties as one table on the glass, rated and before a rating.
                new Prototype("pump-duty-table", 464, 254, (d, s) -> calculatorDoor(d, CalculatorColumn.Page.EQUIPMENT, CalculatorColumn.Metric.TEMPERATURE, twoPumparounds(), GuiPrototypes::dutyTable), true),
                new Prototype("pump-duty-table-narrow", 380, 254, (d, s) -> calculatorDoor(d, CalculatorColumn.Page.EQUIPMENT, CalculatorColumn.Metric.TEMPERATURE, twoPumparounds(), GuiPrototypes::dutyTable), true),
                new Prototype("pump-duty-table-unrated", 464, 254, (d, s) -> calculatorDoor(d, CalculatorColumn.Page.EQUIPMENT, CalculatorColumn.Metric.TEMPERATURE, twoPumparounds(), GuiPrototypes::unratedTable)));
    }

    /** The draft's entries as the door shows them. */
    static final Map<String, String> CALCULATOR = Map.ofEntries(Map.entry("s2", "20"), Map.entry("s9", "8.0"), Map.entry("s7", "1.20"), Map.entry("s0", "420"),
            Map.entry("s1", "180.0"), Map.entry("s3", "8"), Map.entry("s4", "45.0"), Map.entry("s6", "2.50"), Map.entry("s5", "12.0"), Map.entry("d0rate", "60.0"),
            Map.entry("d0stage", "14"));

    /** A made-up accepted result down the 22 nodes: temperature, and the four phases' flows (water vapour only in the top half, no aqueous phase below tray 6). */
    static double[][] calculatorProfile(CalculatorColumn.Metric metric) {
        int nodes = 22;
        if (metric == CalculatorColumn.Metric.TEMPERATURE) {
            var t = new double[1][nodes];
            for (int i = 0; i < nodes; i++) t[0][i] = 45 + 190 * Math.pow(i / 21.0, 0.8);
            return t;
        }
        var f = new double[4][nodes];
        for (int i = 0; i < nodes; i++) {
            f[0][i] = i == 0 ? 1050 : 900 + 30 * Math.sin(i / 3.0) + (i >= 8 ? 420 : 0);
            f[1][i] = i == nodes - 1 ? Double.NaN : 1300 - 25 * i;
            f[2][i] = i < 11 ? 180 - 12 * i : Double.NaN;
            f[3][i] = i < 6 ? 60 - 9 * i : Double.NaN;
        }
        return f;
    }

    /**
     * The calculator's door as ColumnCalculatorV3Screen draws it: the title and the theme key; the tabs, the page shown
     * latched; the status once; the footer's divider and the display keys' tray; the glass (the drawing with D1
     * selected, or a profile) left of the door columns, one view over them, scrolled to its end where the door asks;
     * Templates, Solve (primary) and Save; the basis and temperature keys.
     */
    static void calculatorDoor(Door d, CalculatorColumn.Page page, CalculatorColumn.Metric metric) {
        calculatorDoor(d, page, metric, null);
    }

    /** The shipped Equipment page of a crude column with two pumparounds, an air-then-water condenser and a kettle. */
    static CalculatorColumn.Rows twoPumparounds() {
        return CalculatorColumn.equipment(new CalculatorColumn.EquipmentInput(false, ColumnComponents.Condenser.AIR_THEN_WATER, 2, true, new ColumnComponents.Units(2, 1, List.of(1, 2), 3), true), DIALOG);
    }

    /**
     * W8 (batch 2026-11-03-rotating-machinery, D43): the pumps' duties on the glass through the shipped builder
     * ({@link CalculatorColumn#pumpDuties}), at sample duties: reflux 60 m³/h and 55 m of naphtha (700 kg/m³); PA 1 250 m³/h and 45 m of
     * kerosene (760); PA 2 350 m³/h and 50 m of gas oil (820), each through {@link com.wormzjl.icheme.science.flowsheet.sizing.PumpSizing#duty}.
     */
    static void dutyTable(Door d, Rect box) {
        var t = DIALOG;
        var pumps = List.of(sampleDuty(t.get("column.component_reflux_pump"), 60, 55, 700), sampleDuty(t.get("column.component_pa", 1), 250, 45, 760),
                sampleDuty(t.get("column.component_pa", 2), 350, 50, 820));
        drawDuties(d, box, pumps, "pump duty table");
    }

    /** The same table before the sheet has a rating: each number a dash, and the note under it. */
    static void unratedTable(Door d, Rect box) {
        var t = DIALOG;
        drawDuties(d, box, List.of(CalculatorColumn.PumpDuty.unknown(t.get("column.component_reflux_pump")), CalculatorColumn.PumpDuty.unknown(t.get("column.component_pa", 1)),
                CalculatorColumn.PumpDuty.unknown(t.get("column.component_pa", 2))), "pump duty table, unrated");
    }

    static void drawDuties(Door d, Rect box, List<CalculatorColumn.PumpDuty> pumps, String what) {
        var sheet = CalculatorColumn.pumpDuties(pumps, box.w(), d.m, DIALOG);
        CalculatorColumn.draw(d.c, sheet, box, 0);
        if (d.skin == PanelSkin.LIGHT && !d.end)
            System.out.printf(java.util.Locale.ROOT, "%-28s glass %d by %d: table %d px tall, %d lines%n", what, box.w(), box.h(), sheet.height(), sheet.lines().size());
    }

    static CalculatorColumn.PumpDuty sampleDuty(String name, double flow, double head, double density) {
        var load = new com.wormzjl.icheme.science.flowsheet.sizing.PumpSizing.Load(flow, head, 0, null, density);
        // The duty at the size the calculator picks for it (PumpSizing.duty takes the size since the sized pump speed).
        var duty = com.wormzjl.icheme.science.flowsheet.sizing.PumpSizing.duty(load, com.wormzjl.icheme.science.flowsheet.sizing.PumpSizing.auto(load).size());
        return new CalculatorColumn.PumpDuty(name, duty.flow(), duty.head(), duty.shaft(), duty.npsh(), duty.motor());
    }

    static void calculatorDoor(Door d, CalculatorColumn.Page page, CalculatorColumn.Metric metric, CalculatorColumn.Rows override) {
        calculatorDoor(d, page, metric, override, null);
    }

    /** The calculator door with {@code override}'s rows, and {@code glass} painting the Equipment page's glass (null: left empty, as the screen leaves it). */
    static void calculatorDoor(Door d, CalculatorColumn.Page page, CalculatorColumn.Metric metric, CalculatorColumn.Rows override, BiConsumer<Door, Rect> glass) {
        var t = DIALOG;
        d.skin.title(d.c, 10, 10, d.m.cut(HeatDiagramRender.tr("block.icheme.process_simulator"), CalculatorColumn.title(d.w)));
        d.skin.iconKey(d.c, CalculatorColumn.theme(d.w), Skin.Icon.THEME, false);
        var tabs = CalculatorColumn.tabs(page, t);
        var tabSlots = CalculatorColumn.tabSlots(d.w);
        for (int i = 0; i < tabs.size(); i++) {
            var spec = tabs.get(i);
            d.skin.button(d.c, tabSlots.get(i), spec.text(), spec.latched() ? Skin.Press.CURRENT : !spec.active() ? Skin.Press.OFF : Skin.Press.REST, false);
        }
        var status = CalculatorColumn.status(new CalculatorColumn.StatusInput(false, false, "", "", false, false, false, null, true, false, null), t);
        d.skin.status(d.c, CalculatorColumn.display(d.w), status.text(), status.palette());
        var rows = override != null ? override : page == CalculatorColumn.Page.COLUMN
                ? CalculatorColumn.column(new CalculatorColumn.ColumnInput(false, true, "°C", "bar", "kmol/h", CalculatorColumn.Connection.DRAW, 0, true, true, true, true), t)
                : page == CalculatorColumn.Page.EQUIPMENT
                ? CalculatorColumn.equipment(new CalculatorColumn.EquipmentInput(false, ColumnComponents.Condenser.AIR_THEN_WATER, 1, true, new ColumnComponents.Units(2, 1, List.of(1), 3), true), t)
                : CalculatorColumn.results(new CalculatorColumn.ResultsInput(false, CalculatorColumn.View.PROFILES, metric, List.of(), 0, 13, 20), t);
        boolean two = rows.two();
        d.skin.hDivider(d.c, 8, d.w - 8, d.h - CalculatorColumn.DIVIDER);
        d.skin.softKeys(d.c, CalculatorColumn.keys(d.w, d.h));
        for (int x : CalculatorColumn.dividers(d.w, d.h, two)) d.skin.vDivider(d.c, x, CalculatorColumn.TOP, d.h - CalculatorColumn.FOOT);
        var box = d.well(CalculatorColumn.glass(d.w, d.h, two));
        d.c.clip(box);
        if (page == CalculatorColumn.Page.COLUMN) {
            var shares = new double[22];
            for (int i = 0; i < shares.length; i++) shares[i] = Math.pow(i / 21.0, 0.8);
            var in = new CalculatorColumn.DrawingInput(20, 7, List.of(new CalculatorColumn.Mark(0, 13, 0)), List.of(new CalculatorColumn.Mark(0, 10, 8)),
                    List.of(new CalculatorColumn.Mark(0, 21, 0)), 13, CalculatorColumn.Connection.DRAW, 0, shares);
            CalculatorColumn.draw(d.c, CalculatorColumn.drawing(in, box, 0, d.m, t, node -> ""), t);
        } else if (page == CalculatorColumn.Page.EQUIPMENT) {
            // The derived components' glass is the screen's own; the sheet shows the door (or a prototype's glass).
            if (glass != null) glass.accept(d, box);
        } else {
            var legend = metric == CalculatorColumn.Metric.TRAFFIC ? List.of(new CalculatorColumn.Legend(t.get("column.traffic.organic"), CalculatorColumn.TRAFFIC[0]),
                    new CalculatorColumn.Legend(t.get("column.traffic.vapor"), CalculatorColumn.TRAFFIC[1]), new CalculatorColumn.Legend(t.get("column.traffic.water"), CalculatorColumn.TRAFFIC[2]),
                    new CalculatorColumn.Legend(t.get("column.traffic.aqueous"), CalculatorColumn.TRAFFIC[3])) : List.<CalculatorColumn.Legend>of();
            String title = metric == CalculatorColumn.Metric.TRAFFIC ? t.get("column.traffic") : t.get("column.temperature", "°C");
            int head = CalculatorColumn.heading(d.c, box, title, legend, d.m);
            CalculatorColumn.profile(d.c, CalculatorColumn.plot(box, head), calculatorProfile(metric),
                    metric == CalculatorColumn.Metric.TRAFFIC ? CalculatorColumn.TRAFFIC : new int[]{Palette.ACCENT});
        }
        d.c.unclip();
        // The door columns in one view (KitDoor): each in its area, the bar right of the last.
        var areas = CalculatorColumn.door(d.w, d.h, two);
        var lists = rows.at(d.w);
        var columns = new ArrayList<Column>();
        int tall = 0;
        for (int i = 0; i < areas.size(); i++) {
            var a = areas.get(i);
            columns.add(Column.place(lists.get(i), a.x(), a.y(), a.w(), d.m, Column.Metrics.COMPACT));
            tall = Math.max(tall, columns.getLast().bounds().h());
        }
        var first = areas.getFirst();
        var view = new ScrollView();
        view.layout(new Rect(first.x() - 2, first.y(), areas.getLast().right() - first.x() + 4, first.h()), CalculatorColumn.bar(d.w), tall);
        while (d.end && view.offset() < view.max()) view.wheel(view.viewport().x() + 2, view.viewport().y() + 2, -1);
        d.c.clip(view.viewport());
        for (var column : columns) {
            column.draw(d.c, -view.offset(), d.skin);
            for (var p : column.rows()) {
                List<Column.Button> specs = p.row() instanceof Column.Buttons b ? b.buttons() : p.row() instanceof Column.Setting st && st.steps() ? List.of(st.minus(), st.plus()) : List.of();
                for (var spec : specs) {
                    var r = p.slots().get(spec.key());
                    if (view.visible(r)) d.skin.button(d.c, view.onScreen(r), spec.text(), spec.latched() ? Skin.Press.CURRENT : !spec.active() ? Skin.Press.OFF : Skin.Press.REST, false);
                }
                if (p.row() instanceof Column.Field f && view.visible(p.slots().get(f.key())))
                    d.skin.field(d.c, view.onScreen(p.slots().get(f.key())), CALCULATOR.getOrDefault(f.key(), ""), "", false);
            }
        }
        d.c.unclip();
        view.drawBar(d.c, d.skin);
        var footer = CalculatorColumn.footer(d.w, d.h);
        var keys = CalculatorColumn.footerButtons(new CalculatorColumn.Footer(false, false, true, false, "°C"), t);
        for (int i = 0; i < keys.size(); i++) {
            var spec = keys.get(i);
            if (spec.soft()) d.skin.softKey(d.c, footer.get(i), spec.text(), false);
            else d.skin.button(d.c, footer.get(i), spec.text(), spec.key().equals("solve") ? Skin.Press.PRIMARY : Skin.Press.REST, false);
        }
        if (!d.end && d.skin == PanelSkin.LIGHT)
            System.out.printf(java.util.Locale.ROOT, "%-24s %d by %d: %d door columns %d px wide, %d px tall in a %d px view, glass %d px%n", "calculator " + page.key(), d.w, d.h,
                    areas.size(), first.w(), tall, first.h(), box.w());
    }

    /** A column on the glass as an illustration (the constructor's schematic): the shell, its trays, the condenser and reboiler, the feed and one draw. */
    static void columnDrawing(Door d, Rect box) {
        var c = d.c;
        int cx = box.x() + box.w() / 2, x0 = cx - 18, x1 = cx + 18, y0 = box.y() + 30, y1 = box.bottom() - 34;
        c.fill(x0, y0, x1, y1, 0xFF1C2630);
        c.outline(x0, y0, x1 - x0, y1 - y0, Palette.TEAL);
        for (int i = 1; i < 22; i++) c.hLine(x0 + 2, x1 - 2, y0 + i * (y1 - y0) / 22, 0xFF3C5A66);
        c.outline(cx + 30, box.y() + 6, 36, 16, Palette.TEAL);
        c.text("Cond.", cx + 34, box.y() + 10, Palette.MUTED);
        c.hLine(cx, cx + 30, box.y() + 14, Palette.TEAL);
        c.vLine(cx, box.y() + 14, y0, Palette.TEAL);
        c.outline(cx + 30, y1 + 8, 36, 16, Palette.TEAL);
        c.text("Reb.", cx + 36, y1 + 12, Palette.MUTED);
        c.hLine(cx, cx + 30, y1 + 16, Palette.TEAL);
        c.vLine(cx, y1, y1 + 16, Palette.TEAL);
        int fy = y0 + 8 * (y1 - y0) / 22, dy = y0 + 14 * (y1 - y0) / 22;
        c.hLine(box.x() + 8, x0, fy, Palette.ACCENT);
        c.text("Feed", box.x() + 8, fy - 10, Palette.ACCENT);
        c.hLine(x1, box.right() - 30, dy, Palette.YELLOW);
        c.outline(box.right() - 30, dy - 6, 20, 12, Palette.YELLOW);
        c.text("D1", box.right() - 26, dy - 3, Palette.YELLOW);
        c.outline(x0 - 2, dy - 4, x1 - x0 + 4, 8, 0xFFFFFFFF);
        c.text("Tray 14", box.x() + 8, dy + 4, Palette.TEXT);
    }

    // ---- The assembly check: the shipped AssemblyColumn and AssemblyBlock on the door (GUI_REVIEW.md order 4) ----

    /** A column with a kettle, one side draw and one pumparound. */
    static final ColumnTrays ASSEMBLY = new ColumnTrays(20, 10, List.of(6), List.of(), List.of(new ColumnTrays.Pumparound(8, 6)),
            new ColumnComponents(ColumnComponents.Condenser.EXCHANGER, ColumnComponents.Pump.STANDARD, List.of(ColumnComponents.Pump.STANDARD), false, true));

    /**
     * S3b: the same column asking for three condenser shells, two kettles and two shells on its PA cooler. Two condenser shells
     * stand in parallel (a count fault), the kettles are in place, and one PA cooler shell is another size.
     */
    static final ColumnTrays COUNTED = new ColumnTrays(20, 10, List.of(6), List.of(), List.of(new ColumnTrays.Pumparound(8, 6)),
            new ColumnComponents(ColumnComponents.Condenser.EXCHANGER, ColumnComponents.Pump.STANDARD, List.of(ColumnComponents.Pump.STANDARD), false, true,
                    new ColumnComponents.Units(3, 2, List.of(2))));

    /** A check of {@link #ASSEMBLY}: an in-line pump where the reflux pump goes (misplaced), no feed valve, the PA cooler's line open; the rest in place. */
    static AssemblyReport assemblyReport() {
        return assemblyReport(ASSEMBLY, Map.of(ColumnBlueprint.REFLUX_PUMP, new AssemblyReport.SlotReport(ColumnBlueprint.REFLUX_PUMP, AssemblyReport.State.WRONG_KIND, 1003, AssemblyReport.NONE, "INLINE_PUMP"),
                ColumnBlueprint.FEED_VALVE, new AssemblyReport.SlotReport(ColumnBlueprint.FEED_VALVE, AssemblyReport.State.MISSING, AssemblyReport.NONE, AssemblyReport.NONE, ""),
                ColumnBlueprint.pumparoundCooler(1), new AssemblyReport.SlotReport(ColumnBlueprint.pumparoundCooler(1), AssemblyReport.State.OPEN_END, 1012, AssemblyReport.NONE, "unconnected")));
    }

    /** A check of {@link #COUNTED}: two condenser shells where the design asks for three, the kettles in parallel, a PA cooler shell of another size. */
    static AssemblyReport countedReport() {
        return assemblyReport(COUNTED, Map.of(ColumnBlueprint.CONDENSER, new AssemblyReport.SlotReport(ColumnBlueprint.CONDENSER, AssemblyReport.State.UNIT_COUNT, 1001, AssemblyReport.NONE, "2:3", List.of(1101L)),
                ColumnBlueprint.REBOILER, new AssemblyReport.SlotReport(ColumnBlueprint.REBOILER, AssemblyReport.State.OK, 1002, AssemblyReport.NONE, "", List.of(1102L)),
                ColumnBlueprint.pumparoundCooler(1), new AssemblyReport.SlotReport(ColumnBlueprint.pumparoundCooler(1), AssemblyReport.State.UNIT_SHAPES, 1012, 1112, "", List.of(1112L))));
    }

    /** A check of {@code trays}: {@code faults} where they are, every other slot in place. */
    static AssemblyReport assemblyReport(ColumnTrays trays, Map<Slot, AssemblyReport.SlotReport> faults) {
        var slots = ColumnBlueprint.of(trays).slots();
        var reports = new ArrayList<AssemblyReport.SlotReport>();
        var members = new ArrayList<Long>();
        for (int i = 0; i < slots.size(); i++) {
            var slot = slots.get(i);
            var r = faults.getOrDefault(slot, new AssemblyReport.SlotReport(slot, AssemblyReport.State.OK, 1000L + i, AssemblyReport.NONE, ""));
            reports.add(r);
            if (r.member() != AssemblyReport.NONE) members.add(r.member());
        }
        // Each drawn port of a unit in place is connected; a faulted unit's still need their pipes.
        var ports = new ArrayList<AssemblyReport.PortReport>();
        for (var m : AssemblyDiagram.of(trays).ports())
            for (var r : reports)
                if (r.slot().equals(m.slot()) && r.member() != AssemblyReport.NONE)
                    for (long unit : r.units())
                        ports.add(new AssemblyReport.PortReport(unit, m.role(), Math.max(0, m.ordinal()), r.state() == AssemblyReport.State.OK ? AssemblyReport.PortState.CONNECTED : AssemblyReport.PortState.NEEDED));
        return new AssemblyReport(reports, ports, java.util.Set.of(), members);
    }

    /** Nothing selected after a check; then the reflux pump selected while the next check is queued; then S3b's members in parallel, the condenser selected. */
    static List<Prototype> assembly() {
        return List.of(new Prototype("assembly", 464, 254, (d, s) -> assemblyDoor(d, null, false)),
                new Prototype("assembly-selected", 464, 254, (d, s) -> assemblyDoor(d, ColumnBlueprint.REFLUX_PUMP, true)),
                // At a 1600 by 900 window's size, where the block's labels draw (scale 0.5 and up).
                new Prototype("assembly-wide", 760, 400, (d, s) -> assemblyDoor(d, ASSEMBLY, assemblyReport(), ColumnBlueprint.REFLUX_PUMP, false)),
                new Prototype("assembly-member-counts", 760, 400, (d, s) -> assemblyDoor(d, COUNTED, countedReport(), ColumnBlueprint.CONDENSER, false)),
                new Prototype("assembly-air-then-water", 760, 400, (d, s) -> assemblyDoor(d, TRIMMED, assemblyReport(TRIMMED, Map.of()), ColumnBlueprint.TRIM_CONDENSER, false)),
                // The drawing alone, with an ejector, the trim selected, to read the pair's labels and lines.
                new Prototype("assembly-air-then-water-drawing", 360, 420, (d, s) -> {
                    var box = d.well(new Rect(8, 8, 344, 404));
                    var vacuum = new ColumnTrays(TRIMMED.trays(), TRIMMED.feed(), TRIMMED.draws(), TRIMMED.steam(), TRIMMED.pumparounds(), new ColumnComponents(ColumnComponents.Condenser.AIR_THEN_WATER, ColumnComponents.Pump.STANDARD, List.of(ColumnComponents.Pump.STANDARD), true, true, TRIMMED.components().units()));
                    var block = new AssemblyBlock(vacuum);
                    block.fit(box);
                    block.draw(d.c, box, assemblyReport(vacuum, Map.of()), ColumnBlueprint.TRIM_CONDENSER, "T-101", DIALOG);
                }));
    }

    /** C1 (COOLER_MODEL_PLAN.md section 6): the same column with an air-then-water condenser, two air cooler banks and three trim shells, all in place. */
    static final ColumnTrays TRIMMED = new ColumnTrays(20, 10, List.of(6), List.of(), List.of(new ColumnTrays.Pumparound(8, 6)),
            new ColumnComponents(ColumnComponents.Condenser.AIR_THEN_WATER, ColumnComponents.Pump.STANDARD, List.of(ColumnComponents.Pump.STANDARD), false, true,
                    new ColumnComponents.Units(2, 1, List.of(1), 3)));

    static void assemblyDoor(Door d, Slot selected, boolean queued) {
        assemblyDoor(d, ASSEMBLY, assemblyReport(), selected, queued);
    }

    /**
     * The assembly screen's door as AssemblyScreen draws it: the title and the theme key, the tabs (Structure latched,
     * Operation off until the structure is complete), the status once, the glass with the drawing and the parts list, the
     * door column, the footer under its divider.
     */
    static void assemblyDoor(Door d, ColumnTrays trays, AssemblyReport report, Slot selected, boolean queued) {
        var t = DIALOG;
        var diagram = AssemblyDiagram.of(trays);
        var slots = ColumnBlueprint.of(trays).slots();
        d.title(t.get(AssemblyColumn.KEY + "title"));
        d.name("T-101", true);
        var tabs = AssemblyColumn.tabs(false, false, t);
        var tabSlots = AssemblyColumn.tabSlots(d.w);
        for (int i = 0; i < tabs.size(); i++) {
            var spec = tabs.get(i);
            d.skin.button(d.c, tabSlots.get(i), spec.text(), spec.latched() ? Skin.Press.CURRENT : !spec.active() ? Skin.Press.OFF : Skin.Press.REST, false);
        }
        var status = AssemblyColumn.status(new AssemblyView(AssemblyView.Status.CHECKED, report, AssemblyReport.NONE), at -> "?", t);
        d.skin.status(d.c, AssemblyColumn.display(d.w), status.text(), status.palette());
        d.skin.hDivider(d.c, 8, d.w - 8, d.h - AssemblyColumn.DIVIDER);
        var box = d.well(AssemblyColumn.glass(d.w, d.h, true));
        var split = AssemblyColumn.split(box);
        // The drawing as the process simulator draws a column (owner's option A, 2026-11-03), the parts list beside it.
        var block = new AssemblyBlock(trays);
        block.fit(split.drawing());
        d.c.clip(split.drawing());
        block.draw(d.c, split.drawing(), report, selected, "T-101", t);
        d.c.unclip();
        var area = split.list();
        d.c.vLine(area.x() - AssemblyColumn.GAP / 2, box.y() - 1, box.bottom(), Palette.LINE);
        var lines = new Rect(area.x(), area.y(), area.w() - ScrollView.BAR - 2, area.h());
        var parts = AssemblyColumn.parts(AssemblyColumn.parts(slots, diagram, report, t), AssemblyColumn.words(t), lines.w(), d.m);
        var listView = new ScrollView();
        listView.layout(area, area.right() - ScrollView.BAR, parts.height());
        d.c.clip(area);
        AssemblyColumn.draw(d.c, parts, lines, 0, selected == null ? -1 : slots.indexOf(selected));
        d.c.unclip();
        listView.drawBar(d.c, d.skin);
        if (d.skin == PanelSkin.LIGHT) System.out.printf(java.util.Locale.ROOT, "%-24s block at scale %.2f in %d by %d px%n", "assembly " + d.w + "x" + d.h, block.scale(), split.drawing().w(), split.drawing().h());
        // The door column, narrowed for its bar where it is taller than its area (KitDoor).
        String name = selected == null ? null : AssemblyColumn.text(diagram.part(selected).name(), t);
        // N1 (the owner 2026-11-03): a player who may build names the column in the title strip, sent with the footer's Apply.
        var rows = AssemblyColumn.rows(AssemblyColumn.input(slots.size(), report, queued, name, selected != null), t);
        var door = AssemblyColumn.area(d.w, d.h);
        int tall = Column.place(rows, door.x(), door.y(), door.w(), d.m, Column.Metrics.COMPACT).bounds().h();
        d.column(rows, door.x(), door.y(), tall > door.h() ? door.w() - ScrollView.BAR - 4 : door.w(), Map.of());
        var footer = AssemblyColumn.footer(d.w, d.h, 3);
        var keys = AssemblyColumn.footerButtons(true, t);
        for (int i = 0; i < keys.size(); i++) d.skin.button(d.c, footer.get(i), keys.get(i).text(), keys.get(i).key().equals("apply") ? Skin.Press.PRIMARY : Skin.Press.REST, false);
        if (d.skin == PanelSkin.LIGHT)
            System.out.printf(java.util.Locale.ROOT, "%-24s column %3d px in a %d px view%n", "assembly" + (selected == null ? "" : " selected"), tall, door.h());
    }

    // ---- The equipment constructor: the shipped ConstructorColumn on its door left of the world (GUI_REVIEW.md order 3) ----

    /** A standing drum's sides as seen from the constructor ({@code EquipmentShape.side} 0 to 3): front, left, back, right. */
    static final List<String> STANDING = List.of("front", "left", "back", "right");
    static final String CONSTRUCTOR = ConstructorColumn.KEY;

    static List<Prototype> constructor() {
        var outlet = new ConstructorColumn.Outlet(1, 0, STANDING);
        var drum = ConstructorColumn.design(new ConstructorColumn.Design(VesselPlacement.Form.VERTICAL, 2, 6, 3, 3, 0, 0, new ProcessUnit.Bank(1, 1), null, outlet, 16, com.wormzjl.icheme.science.equipment.TowerBank.ONE), DIALOG);
        var lying = ConstructorColumn.design(new ConstructorColumn.Design(VesselPlacement.Form.CROSSWISE, 2.5, 8, 2, 4, -2, 0, new ProcessUnit.Bank(1, 1), null, outlet, 16, com.wormzjl.icheme.science.equipment.TowerBank.ONE), DIALOG);
        var connections = ConstructorColumn.connections(new ConstructorColumn.Connections(2, 1, 1, STANDING, "4.5", false, -1, "back", true, false, false,
                new ConstructorColumn.Outlet(0b1011, 0, STANDING)), DIALOG);
        // The Demister group with a pad (batch 2026-11-17-three-phase-separator; -Donly=equipment-constructor-nozzles): a standing
        // drum, and a lying drum with its boot.
        var padded = ConstructorColumn.connections(new ConstructorColumn.Connections(2, 1, 1, STANDING, "4.5", false, -1, "back", true, false, false,
                new ConstructorColumn.Outlet(0b1011, 0, STANDING), true), DIALOG);
        var paddedLying = ConstructorColumn.connections(new ConstructorColumn.Connections(1, 0, 1, STANDING, "6.5", true, 0, null, true, true, true,
                new ConstructorColumn.Outlet(1, 0, STANDING), true), DIALOG);
        var nozzles = ConstructorColumn.nozzles(new ConstructorColumn.Nozzles(7, 2, new ConstructorColumn.Nozzle("pumparound_draw", 1, true, "18.5",
                new UnitDesign.Line("nozzle.under_out", 12), false, 3, 1), STANDING, 0), DIALOG);
        String volume = DIALOG.get(CONSTRUCTOR + "volume", "20.9", "46.4");
        // A column's design page with Import and Templates, and its Templates page: the bundled templates, the pot off, one of the player's.
        var columnDesign = ConstructorColumn.design(new ConstructorColumn.Design(VesselPlacement.Form.COLUMN, 8, 6, 10, 3, 0, 0, new ProcessUnit.Bank(1, 1),
                new ConstructorColumn.Imported(8, 42, 38), outlet, 16, com.wormzjl.icheme.science.equipment.TowerBank.ONE), DIALOG);
        var templates = new ArrayList<ConstructorColumn.Template>();
        for (var name : List.of("Bonga", "Cold Lake Blend", "Dalia", "Tia Juana Light (default)", "Upper Zakum", "WTI Light - Export"))
            templates.add(new ConstructorColumn.Template(name, true, DIALOG.get(CONSTRUCTOR + "template.bundled", name)));
        templates.add(new ConstructorColumn.Template("Two-tray pot", false, DIALOG.get(CONSTRUCTOR + "template.unusable", "Two-tray pot", "a column needs a tray")));
        templates.add(new ConstructorColumn.Template("My vacuum column", true, DIALOG.get(CONSTRUCTOR + "template.own", "My vacuum column")));
        var templatePage = ConstructorColumn.templates(templates, 0, DIALOG);
        var removal = ConstructorColumn.withRemoval(drum, new ConstructorColumn.Removal("V-101 · Vertical drum", "12, 64, -8", 382, false, null, false, .93, "WEEPING"), DIALOG);
        var refused = ConstructorColumn.withRemoval(drum, new ConstructorColumn.Removal("V-101 · Vertical drum", "12, 64, -8", 382, false,
                "Drain and depressure its vessels first.", true, 1, "LEAKING"), DIALOG);
        return List.of(
                new Prototype("equipment-constructor-removal", 464, 254, (d, theme) -> constructorDoor(d, removal, "Vertical drum", "change", volume, Palette.ACCENT, Map.of()), true),
                new Prototype("equipment-constructor-repair-refused", 464, 254, (d, theme) -> constructorDoor(d, refused, "Vertical drum", "change", volume, Palette.ACCENT, Map.of()), true),
                new Prototype("equipment-constructor-column-design", 464, 254, (d, s) -> {
                    constructorDoor(d, columnDesign, "Distillation column", "change", DIALOG.get(CONSTRUCTOR + "volume", "1696.5", "792.7"), Palette.ACCENT, Map.of());
                    columnDrawing(d, d.well(new Rect(d.w - 16 - 140, 16, 152, d.h - 32)));
                }, true),
                new Prototype("equipment-constructor-templates", 464, 254, (d, s) -> constructorDoor(d, templatePage, "Distillation column", "back", null, 0, Map.of()), true),
                new Prototype("equipment-constructor-choose-separation", 464, 254, (d, s) -> constructorDoor(d, ConstructorColumn.choose(ConstructorColumn.Category.SEPARATION,
                        ConstructorColumn.Equipment.COLUMN, 16, DIALOG), "Constructor", "back", null, 0, Map.of()), true),
                new Prototype("equipment-constructor-choose-rotating", 320, 240, (d, s) -> constructorDoor(d, ConstructorColumn.choose(ConstructorColumn.Category.ROTATING,
                        ConstructorColumn.Equipment.PUMP, 16, DIALOG), "Constructor", "back", null, 0, Map.of()), true),
                new Prototype("equipment-constructor-choose", 464, 254, (d, s) -> constructorDoor(d, ConstructorColumn.choose(ConstructorColumn.Category.VESSELS,
                        ConstructorColumn.Equipment.VERTICAL_DRUM, 16, DIALOG), "Equipment constructor", "back", null, 0, Map.of()), true),
                new Prototype("equipment-constructor", 464, 254, (d, s) -> constructorDoor(d, drum, "Vertical drum", "change", volume, Palette.ACCENT, Map.of()), true),
                new Prototype("equipment-constructor-lying", 464, 254, (d, s) -> constructorDoor(d, lying, "Horizontal drum", "change",
                        DIALOG.get(CONSTRUCTOR + "blocked", 3), Palette.WARN, Map.of()), true),
                new Prototype("equipment-constructor-nozzles", 464, 254, (d, s) -> constructorDoor(d, connections, "Vertical drum", null,
                        DIALOG.get(CONSTRUCTOR + "clash.liquid", 2), Palette.WARN, Map.of()), true),
                new Prototype("equipment-constructor-nozzles-demister", 464, 254, (d, s) -> constructorDoor(d, padded, "Vertical drum", null, volume, Palette.ACCENT, Map.of()), true),
                new Prototype("equipment-constructor-nozzles-lying-demister", 464, 254, (d, s) -> constructorDoor(d, paddedLying, "Horizontal drum", null, volume, Palette.ACCENT, Map.of()), true),
                new Prototype("equipment-constructor-column", 464, 254, (d, s) -> {
                    constructorDoor(d, nozzles, "Distillation column", null, DIALOG.get(CONSTRUCTOR + "volume", "1696.5", "792.7"), Palette.ACCENT, Map.of());
                    // The schematic on its glass beside the world (the screen draws the calculator's; here an illustration).
                    columnDrawing(d, d.well(new Rect(d.w - 16 - 140, 16, 152, d.h - 32)));
                }, true),
                new Prototype("equipment-constructor-paint", 464, 254, (d, s) -> {
                    var column = constructorDoor(d, ConstructorColumn.paint(0, DIALOG), "Vertical drum", null, volume, Palette.ACCENT, Map.of("custom", "A1A1A0"));
                    swatches(d, DialogColumns.palette(column, Paint.PALETTE.size()), 0);
                }, true));
    }

    /** The palette as PaintSwatch draws it: the edited colour ({@code chosen}) outlined in white. */
    static void swatches(Door d, List<Rect> swatches, int chosen) {
        for (int i = 0; i < swatches.size(); i++) {
            var r = swatches.get(i);
            boolean edited = i == chosen;
            d.c.fill(r.x(), r.y(), r.right(), r.bottom(), edited ? 0xFFFFFFFF : Palette.LINE);
            if (edited) d.c.fill(r.x() + 1, r.y() + 1, r.right() - 1, r.bottom() - 1, 0xFF000000);
            int inset = edited ? 2 : 1;
            d.c.fill(r.x() + inset, r.y() + inset, r.right() - inset, r.bottom() - inset, 0xFF000000 | Paint.PALETTE.get(i).rgb());
        }
    }

    /**
     * The constructor's door as EquipmentConstructorScreen draws it: the world beside it; the title and its key
     * ({@code key}: Change or Back, or none); the page's column in its view, narrowed for its bar where it is taller,
     * scrolled to its end where the door asks; the message display; the footer, Build the primary key. Fields show
     * {@code values}. Returns the column as placed, unscrolled (where the column scrolls, the palette is not drawn).
     */
    static Column constructorDoor(Door d, ConstructorColumn.Page page, String title, String key, String status, int tone, Map<String, String> values) {
        int panel = page.panelWidth();
        d.c.fill(panel, 0, d.w, d.h, 0xFF4A6B3A);
        if (panel == ConstructorColumn.PANEL) d.c.text("(the world and the ghost)", panel + 30, d.h / 2, 0xFFE0E0E0);
        d.skin.page(d.c, new Rect(0, 0, panel, d.h));
        d.skin.title(d.c, 10, 10, title);
        if (key != null) d.skin.button(d.c, ConstructorColumn.TITLE_KEY, DIALOG.get(CONSTRUCTOR + key), Skin.Press.REST, false);
        var area = page.area(d.h);
        int tall = Column.place(page.rows(), area.x(), area.y(), area.w(), d.m, Column.Metrics.COMPACT).bounds().h();
        int width = tall > area.h() ? area.w() - ScrollView.BAR - 4 : area.w();
        var column = Column.place(page.rows(), area.x(), area.y(), width, d.m, Column.Metrics.COMPACT);
        var view = new ScrollView();
        view.layout(new Rect(area.x() - 2, area.y(), width + 4, area.h()), area.x() + width + 3, column.bounds().h());
        while (d.end && view.offset() < view.max()) view.wheel(view.viewport().x() + 2, view.viewport().y() + 2, -1);
        d.c.clip(view.viewport());
        column.draw(d.c, -view.offset(), d.skin);
        for (var p : column.rows()) {
            List<Column.Button> specs = p.row() instanceof Column.Buttons b ? b.buttons() : p.row() instanceof Column.Setting st && st.steps() ? List.of(st.minus(), st.plus()) : List.of();
            for (var spec : specs) {
                var r = p.slots().get(spec.key());
                if (view.visible(r)) d.skin.button(d.c, view.onScreen(r), spec.text(), spec.latched() ? Skin.Press.CURRENT : !spec.active() ? Skin.Press.OFF : Skin.Press.REST, false);
            }
            if (p.row() instanceof Column.Field f && view.visible(p.slots().get(f.key())))
                d.skin.field(d.c, view.onScreen(p.slots().get(f.key())), values.getOrDefault(f.key(), ""), "", false);
        }
        d.c.unclip();
        view.drawBar(d.c, d.skin);
        if (page.display()) d.skin.status(d.c, ConstructorColumn.display(d.h), status, tone);
        var slots = ConstructorColumn.footer(d.h, page.footer().size());
        for (int i = 0; i < page.footer().size(); i++) {
            var spec = page.footer().get(i);
            d.skin.button(d.c, slots.get(i), spec.text(), spec.key().equals("build") ? Skin.Press.PRIMARY : spec.active() ? Skin.Press.REST : Skin.Press.OFF, false);
        }
        if (!d.end && d.skin == PanelSkin.LIGHT) System.out.printf(java.util.Locale.ROOT, "%-24s column %3d px in a %d px view%n", title, column.bounds().h(), area.h());
        return view.offset() == 0 ? column : Column.place(List.of(), area.x(), area.y(), width, d.m, Column.Metrics.COMPACT);
    }

    // ---- Small dialogs: the shipped DialogColumns on the door (the gauge, the pipeline tool, the vessel's datasheet) ----

    static final HeatControlColumn.Texts DIALOG = (key, a) -> HeatDiagramRender.tr("gui.icheme." + key, a);

    /** A dialog's height as its screen sizes it: its column with every line it may show, then the footer. */
    static int dialogHeight(List<Column.Row> tallest, int width) {
        return DialogColumns.TOP + Column.place(tallest, 10, DialogColumns.TOP, width - 20, new HeatDiagramRender.Java2dCanvas(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics()).measure(), Column.Metrics.COMPACT).bounds().h()
                + DialogColumns.FOOTER;
    }

    /** The dialog's footer push buttons, as DialogColumns places them; the last of two is the confirm one. */
    static void dialogFooter(Door d, String... texts) {
        var slots = DialogColumns.footer(d.w, d.h, texts.length);
        for (int i = 0; i < texts.length; i++)
            d.skin.button(d.c, slots.get(i), texts[i], texts.length == 2 && i == 1 ? Skin.Press.PRIMARY : Skin.Press.REST, false);
    }

    static Prototype pipelineTool() {
        var tallest = DialogColumns.pipelineTool(new DialogColumns.PipelineTool(true, 0, false, false), DIALOG);
        return new Prototype("pipeline-tool", 230, dialogHeight(tallest, 230), (d, s) -> {
            d.skin.title(d.c, 10, 10, "Pipe laying tool");
            var column = d.column(DialogColumns.pipelineTool(new DialogColumns.PipelineTool(true, 0, false, true), DIALOG), 10, DialogColumns.TOP, d.w - 20,
                    Map.of("bore", "0.150", "custom", "A1A1A0"));
            // The palette as PaintSwatch draws it: the edited colour outlined in white.
            var swatches = DialogColumns.palette(column, Paint.PALETTE.size());
            for (int i = 0; i < swatches.size(); i++) {
                var r = swatches.get(i);
                boolean chosen = i == 0;
                d.c.fill(r.x(), r.y(), r.right(), r.bottom(), chosen ? 0xFFFFFFFF : Palette.LINE);
                if (chosen) d.c.fill(r.x() + 1, r.y() + 1, r.right() - 1, r.bottom() - 1, 0xFF000000);
                int inset = chosen ? 2 : 1;
                d.c.fill(r.x() + inset, r.y() + inset, r.right() - inset, r.bottom() - inset, 0xFF000000 | Paint.PALETTE.get(i).rgb());
            }
            dialogFooter(d, "Cancel", "Save");
        });
    }

    // ---- The alarm beacon (batch 2026-11-15-fluid-interfaces, W9): the shipped AlarmColumns on its door ----

    static final java.util.function.BiFunction<String, Object[], String> ALARM_TEXT = (key, a) -> HeatDiagramRender.tr("gui.icheme." + key, a);

    /** A beacon's settings, {@code view}, on its 246 px door, Apply alone at the right. */
    static Prototype alarmBeacon(String name, com.wormzjl.icheme.client.gui.common.AlarmColumns.View view, Map<String, String> entries) {
        var rows = com.wormzjl.icheme.client.gui.common.AlarmColumns.rows(view, ALARM_TEXT);
        return new Prototype(name, 246, dialogHeight(rows, 246), (d, s) -> {
            d.skin.title(d.c, 10, 10, "Alarm beacon AL-0A1B2C3D");
            d.column(rows, 10, DialogColumns.TOP, d.w - 20, entries);
            d.skin.button(d.c, DialogColumns.footer(d.w, d.h, 1).get(0), "Apply", Skin.Press.PRIMARY, false);
        });
    }

    static List<Prototype> alarmBeacons() {
        return List.of(
                alarmBeacon("alarm-beacon", new com.wormzjl.icheme.client.gui.common.AlarmColumns.View(true, false, "LT-0012 / LEVEL", false, true, "%", "ACTIVE", "84.2", "%", true, ""),
                        Map.of("name", "DRUM HIGH LEVEL", "threshold", "80")),
                alarmBeacon("alarm-beacon-any", new com.wormzjl.icheme.client.gui.common.AlarmColumns.View(true, true, "", false, true, "m", "CLEAR", "", "", true, ""),
                        Map.of("name", "HEATER TRIP")));
    }

    static Prototype gauge() {
        var units = List.of("Pa", "kPa", "bar", "MPa");
        var tallest = DialogColumns.gauge(new DialogColumns.Gauge(true, units, 2, true, false), DIALOG);
        return new Prototype("gauge", 246, dialogHeight(tallest, 246), (d, s) -> {
            d.skin.title(d.c, 10, 10, "Pressure gauge");
            d.column(DialogColumns.gauge(new DialogColumns.Gauge(true, units, 2, true, true), DIALOG), 10, DialogColumns.TOP, d.w - 20, Map.of("minimum", "0.00", "maximum", "10.00"));
            dialogFooter(d, "Cancel", "Save");
        });
    }

    /** A thermometer whose drafts form no range: the warning line under the groups, Save waiting. */
    static Prototype thermometer() {
        var tallest = DialogColumns.gauge(new DialogColumns.Gauge(false, List.of("°C", "K"), 0, false, false), DIALOG);
        return new Prototype("gauge-thermometer", 246, dialogHeight(tallest, 246), (d, s) -> {
            d.skin.title(d.c, 10, 10, "Thermometer");
            d.column(tallest, 10, DialogColumns.TOP, d.w - 20, Map.of("minimum", "200", "maximum", "20"));
            var slots = DialogColumns.footer(d.w, d.h, 2);
            d.skin.button(d.c, slots.get(0), "Cancel", Skin.Press.REST, false);
            d.skin.button(d.c, slots.get(1), "Save", Skin.Press.OFF, false);
        });
    }

    static DialogColumns.Size size(String key, String value, String unit, boolean chosen) {
        return new DialogColumns.Size(key, value, unit, chosen);
    }

    /** A datasheet: the door's sizes and switch, the glass's notes and nozzles, as VesselScreen draws them. */
    static void datasheet(Door d, String title, DialogColumns.Sheet sheet, boolean moving) {
        // N1 (the owner 2026-11-03): the name in the title strip, sent with Apply beside Done; the datasheet has no theme key.
        d.title(title, 0);
        d.name(moving ? "AC-101" : "D-101", true);
        var values = new java.util.HashMap<String, String>();
        for (var size : sheet.sizes()) values.put(size.key(), size.value());
        var rows = DialogColumns.vessel(sheet.sizes(), moving, true, DIALOG);
        d.column(rows, 10, DialogColumns.TOP, 150, values);
        if (d.skin == PanelSkin.LIGHT) System.out.printf(java.util.Locale.ROOT, "%-24s column %3d px in a %d px view%n", "datasheet" + (moving ? " unit" : ""),
                Column.place(rows, 10, DialogColumns.TOP, 150, d.m, Column.Metrics.COMPACT).bounds().h(), d.h - DialogColumns.TOP - DialogColumns.FOOTER);
        var box = d.well(new Rect(166, 25, d.w - 171, d.h - 60));
        var glass = DialogColumns.glass(sheet, false, box.w() - ScrollView.BAR - 2, d.m, DIALOG);
        d.c.clip(box);
        DialogColumns.draw(d.c, glass, new Rect(box.x(), box.y(), box.w() - ScrollView.BAR - 2, box.h()), 0);
        d.c.unclip();
        dialogFooter(d, "Done", TEXTS.get("apply"));
    }

    static Prototype vessel() {
        var sheet = new DialogColumns.Sheet(List.of(size("diameter", "2.00", "m", true), size("length", "6.00", "m", true), size("overall", "7.10", "m", false),
                size("top", "10.1", "m", false), size("volume", "20.9", "m³", false), size("area", "46.4", "m²", false)),
                List.of(DIALOG.get("vessel.kind.skirt"), DIALOG.get("vessel.skirt", 3), DIALOG.get("vessel.cells", 112, 36), DIALOG.get("vessel.controller", "120, 64, -40")),
                null, List.of(new DialogColumns.Nozzle("Feed", "Bulk", "Left"), new DialogColumns.Nozzle("Vapour outlet", "Vapour", "Top"),
                new DialogColumns.Nozzle("Liquid outlet", "Liquid", "Front"), new DialogColumns.Nozzle("Side connection", "Bulk", "Right")));
        return new Prototype("vessel-datasheet", 400, 250, (d, s) -> datasheet(d, "Drum", sheet, false));
    }

    /** A unit with moving parts and long nozzle names: the roles wrap in their column; a blocked count in the warning tone. */
    static Prototype unit() {
        var sheet = new DialogColumns.Sheet(List.of(size("overall", "14.0", "m", false), size("top", "5.20", "m", false)),
                List.of(DIALOG.get("vessel.kind.air_cooler"), DIALOG.get("vessel.bay", "3.0", "9.0", "24.3"), DIALOG.get("vessel.bank", 2, 1),
                        DIALOG.get("vessel.fans", 2, "2.4", 45), DIALOG.get("vessel.air_side", "1210", "60"), DIALOG.get("vessel.unit_support.air_cooler", 4),
                        DIALOG.get("vessel.cells", 640, 210), DIALOG.get("vessel.controller", "120, 64, -40")),
                DIALOG.get("vessel.blocked", 3), List.of(new DialogColumns.Nozzle("Process inlet", "Bulk", "Front"), new DialogColumns.Nozzle("Process outlet", "Bulk", "Back"),
                new DialogColumns.Nozzle("Regeneration gas outlet", "Vapour", "Bottom")));
        return new Prototype("vessel-datasheet-unit", 400, 250, (d, s) -> datasheet(d, "Air cooler", sheet, true));
    }

    // ---- FluidDeviceScreen's device pages: the shipped DeviceColumn on the door, the tables on the HMI glass ----

    static final HeatControlColumn.Texts TEXTS = (key, a) -> HeatDiagramRender.tr("gui.icheme.fluid." + key, a);

    /** A phase split, its four amounts on a basis; mole leaves custom solids out where some are held. */
    static DeviceColumn.Phases phases(String basis, double... amounts) {
        return new DeviceColumn.Phases(basis, amounts, false, TEXTS.get("phase_ratio.hint"));
    }

    /**
     * A device's page as FluidDeviceScreen draws it (GUI_TOOLKIT_PLAN.md section 14): the door column from x 10 to
     * {@code FluidGuiLayout.side}, its view and bar, the divider, the tables' glass; scrolled to the end where the door
     * asks for it. Fields show {@code drafts} by key. Returns the glass's inner box.
     */
    static Rect device(Door d, DeviceColumn.Input in, Map<String, String> drafts) {
        // N1 (the owner 2026-11-03): the name in the title strip, an entry for an editor of a device that takes one (a plain pipe takes none).
        // A tank with contents holds Copy where Apply would stand: its name reads as text (an open item).
        if (in.kind() != Kind.PIPE) d.name(NAMES.getOrDefault(in.kind(), ""), in.canEdit() && in.kind() != Kind.RESERVOIR);
        return device(d, in, DeviceColumn.rows(in, TEXTS), drafts);
    }

    /** As {@link #device(Door, DeviceColumn.Input, Map)} with {@code rows} in place of the input's own (a prototype's added groups); the name is the caller's. */
    static Rect device(Door d, DeviceColumn.Input in, List<Column.Row> rows, Map<String, String> drafts) {
        int side = FluidGuiLayout.side(d.w), top = FluidGuiLayout.DOOR_TOP, bottom = d.h - FluidGuiLayout.DOOR_FOOT;
        var column = Column.place(rows, 10, top, side - 10, d.m, Column.Metrics.COMPACT);
        var view = new ScrollView();
        view.layout(new Rect(8, top, side - 6, bottom - top), side + 3, column.bounds().h());
        while (d.end && view.offset() < view.max()) view.wheel(view.viewport().x() + 2, view.viewport().y() + 2, -1);
        d.c.clip(view.viewport());
        column.draw(d.c, -view.offset(), d.skin);
        for (var p : column.rows()) {
            if (p.row() instanceof Column.Field f) {
                var r = p.slots().get(f.key());
                if (view.visible(r)) d.skin.field(d.c, view.onScreen(r), drafts.getOrDefault(f.key(), ""), "", false);
            } else if (p.row() instanceof Column.Buttons b)
                for (var spec : b.buttons()) {
                    var r = p.slots().get(spec.key());
                    if (!view.visible(r)) continue;
                    if (spec.soft()) d.skin.softKey(d.c, view.onScreen(r), spec.text(), false);
                    else d.skin.button(d.c, view.onScreen(r), spec.text(), spec.latched() ? Skin.Press.CURRENT : !spec.active() ? Skin.Press.OFF : Skin.Press.REST, false);
                }
        }
        d.c.unclip();
        view.drawBar(d.c, d.skin);
        if (!d.end && d.skin == PanelSkin.LIGHT) System.out.printf(java.util.Locale.ROOT, "%-16s column %3d px in a %d px view%n", in.kind(), column.bounds().h(), bottom - top);
        d.skin.vDivider(d.c, side + 13, top, bottom);
        return d.well(new Rect(side + 15, 72, d.w - side - 20, d.h - 50 - 72));
    }

    /** A composition on the glass: the phase tabs latched, the table. */
    static void contents(Door d, Rect box, int phase, List<String[]> table) {
        String[] phases = {"Org", "Aq", "Gas", "Solid"};
        int n = phases.length, tw = (box.w() - 3 * (n - 1)) / n;
        for (int i = 0; i < n; i++) d.skin.button(d.c, new Rect(box.x() + i * (tw + 3), box.y(), tw, 16), phases[i], i == phase ? Skin.Press.CURRENT : Skin.Press.REST, false);
        d.table(new Rect(box.x(), box.y() + 20, box.w(), box.h() - 20), new int[]{box.w() - 120, 60, 60}, table);
    }

    /**
     * A drum's Carry-over group (batch 2026-11-17-three-phase-separator, package 4; owner: "B: own group"), from the
     * shipped builder: a lying drum small for its water, whose boot's water leaves with oil in it, and a standing drum
     * whose gas is faster than its vapour space allows (one liquid outlet: the gas's meter alone).
     */
    static List<Prototype> separator() {
        var out = new ArrayList<Prototype>();
        var base = List.of(meter("temperature", "40.0", "°C"), meter("pressure", "2.50", "bar"), new DeviceColumn.Meter("net_flow", "0.0", "kg/h", TEXTS.get("net_flow.hint")));
        var lying = List.of(new DeviceColumn.Meter("carried_water", "0.0", "%", TEXTS.get("carried_water.hint")),
                new DeviceColumn.Meter("carried_oil", "39.5", "%", TEXTS.get("carried_oil.hint")), new DeviceColumn.Meter("carried_liquid", "0.0", "%", TEXTS.get("carried_liquid.hint")));
        var standing = List.of(new DeviceColumn.Meter("carried_liquid", "26.0", "%", TEXTS.get("carried_liquid.hint")));
        for (boolean boot : new boolean[]{true, false}) {
            var input = new DeviceColumn.Input(Kind.RESERVOIR, true, false, true, "bar", "°C", "/h", DeviceColumn.Valve.NONE, null, false,
                    new DeviceColumn.Level(.5, "885.0", "1770.0", false), null, List.of(), phases("volume", 44, 6, 50, 0), base, null, null).withCarried(boot ? lying : standing);
            var rows = DeviceColumn.rows(input, TEXTS);
            out.add(devicePage(boot ? "separator-carry-over-lying-drum" : "separator-carry-over-standing-drum", WINDOW[0], WINDOW[1], rows, (d, k) -> {
                d.title(boot ? "Lying drum" : "Standing drum");
                d.name(boot ? "V-101" : "V-102", false);
                d.tabs(0, "Overview", "Outlets", "Datasheet");
                d.status("RUNNING", Palette.ACCENT);
                var box = device(d, input, rows, Map.of());
                contents(d, box, 0, List.of(new String[]{"Component", "Mole %", "kmol"}, new String[]{"N-pentane", "62.1", "6.76"}, new String[]{"N-hexane", "37.9", "4.12"}));
                d.footer(2, "Close", "Mole", "°C", "bar", "/h");
            }));
        }
        return out;
    }

    /**
     * A drum's level of separation (batch 2026-11-17-dynamic-settling, law L7; owner: "C: bars and section", D6 C), from the
     * shipped builders: the group Separation between Readings and Carry-over, one gauge a phase the vessel holds
     * ({@link DeviceColumn.Input#withSeparation}, {@link Column.Gauge}). Three sheets each on a standing drum half settled
     * and a lying drum without water over its gas limit: A the gauges alone; B an LED meter a phase (the option not
     * taken, drawn from {@link Column.Reading} rows); C, the one built, the gauges and the vessel's section on the glass
     * beside the table ({@link VesselSection}).
     */
    static List<Prototype> separation() {
        record Case(String file, String title, String name, boolean lying, Double[] shares, String carried, DeviceColumn.Phases phases, double level, double oil, double turbid, double water) {}
        var cases = List.of(
                new Case("standing", "Standing drum", "V-102", false, new Double[]{1.0, .55, .40}, "0.0", phases("volume", 30, 20, 50, 0), .5, .45, .35, .20),
                new Case("gas-limit", "Lying drum", "V-103", true, new Double[]{.74, 1.0, null}, "26.0", phases("volume", 50, 0, 50, 0), .5, 1, 0, 0));
        String[] keys = {"separation_gas", "separation_oil", "separation_water"};
        int[] layers = {VesselSection.GAS_LAYER, VesselSection.OIL_LAYER, VesselSection.WATER_LAYER};
        var base = List.of(meter("temperature", "40.0", "°C"), meter("pressure", "2.50", "bar"), new DeviceColumn.Meter("net_flow", "0.0", "kg/h", TEXTS.get("net_flow.hint")));
        var out = new ArrayList<Prototype>();
        for (String option : new String[]{"a-bars", "b-meters", "c-section"})
            for (var k : cases) {
                var separation = new ArrayList<DeviceColumn.Phase>();
                var labels = new ArrayList<VesselSection.Label>();
                for (int i = 0; i < 3; i++) {
                    if (k.shares()[i] == null) continue;
                    String value = Format.number(100 * k.shares()[i]);
                    separation.add(new DeviceColumn.Phase(keys[i], k.shares()[i], value, TEXTS.get(keys[i] + ".hint")));
                    labels.add(new VesselSection.Label(TEXTS.get(keys[i]) + " " + value + " %", layers[i]));
                }
                var input = new DeviceColumn.Input(Kind.RESERVOIR, true, false, true, "bar", "°C", "/h", DeviceColumn.Valve.NONE, null, false,
                        new DeviceColumn.Level(.5, "885.0", "1770.0", false), null, List.of(), k.phases(), base, null, null)
                        .withCarried(List.of(new DeviceColumn.Meter("carried_liquid", k.carried(), "%", TEXTS.get("carried_liquid.hint"))));
                var rows = new ArrayList<>(DeviceColumn.rows(input, TEXTS));
                if (option.startsWith("b")) {
                    // The option not taken: a meter a phase, in the shipped group's place.
                    var meters = new ArrayList<Column.Row>();
                    for (var ph : separation) meters.add(new Column.Reading("separation:" + ph.key(), TEXTS.get(ph.key()), ph.value(), "%", ph.hint()));
                    String carryOver = TEXTS.get("device_group.carry_over");
                    int at = 0;
                    while (at < rows.size() && !(rows.get(at) instanceof Column.Group g && g.title().equals(carryOver))) at++;
                    rows.add(at, new Column.Group(TEXTS.get("device_group.separation"), TEXTS.get("separation.hint"), meters));
                } else {
                    input = input.withSeparation(separation);
                    rows = new ArrayList<>(DeviceColumn.rows(input, TEXTS));
                }
                var shown = input;
                var section = new VesselSection.Layers(k.lying(), k.level(), k.oil(), k.turbid(), k.water(), k.lying(), labels);
                var table = List.of(new String[]{"Component", "Mole %", "kmol"}, new String[]{"N-pentane", "62.1", "6.76"}, new String[]{"N-hexane", "37.9", "4.12"});
                var page = List.copyOf(rows);
                out.add(devicePage("separation-" + option + "-" + k.file(), WINDOW[0], WINDOW[1], page, (d, s) -> {
                    d.title(k.title());
                    d.name(k.name(), false);
                    d.tabs(0, "Overview", "Outlets", "Datasheet");
                    d.status("RUNNING", Palette.ACCENT);
                    var box = device(d, shown, page, Map.of());
                    if (option.startsWith("c")) {
                        int sw = Math.max(110, box.w() * 2 / 5), tw = box.w() - sw - 6;
                        contents(d, new Rect(box.x(), box.y(), tw, box.h()), 0, table);
                        d.c.vLine(box.x() + tw + 3, box.y(), box.bottom(), Palette.LINE);
                        VesselSection.draw(d.c, new Rect(box.right() - sw, box.y(), sw, box.h()), section);
                    } else contents(d, box, 0, table);
                    d.footer(2, "Close", "Mole", "°C", "bar", "/h");
                }));
            }
        return out;
    }

    static DeviceColumn.Meter meter(String key, String value, String unit) {
        return new DeviceColumn.Meter(key, value, unit, null);
    }

    /** The window the device pages are drawn for, in screen px: {@code -Dwindow=1920x1080} (the default). */
    static final int[] WINDOW = java.util.Arrays.stream(System.getProperty("window", "1920x1080").split("x")).mapToInt(Integer::parseInt).toArray();

    /**
     * A device's page as a window {@code fw} by {@code fh} screen px shows it: at the scale FluidDeviceScreen takes
     * (the explicit -DguiScale preview setting), its image 8 px inside the window's GUI size; drawn scrolled to its
     * end too where even that scale leaves the column a bar.
     */
    static Prototype devicePage(String file, int fw, int fh, DeviceColumn.Input in, BiConsumer<Door, Skin> body) {
        var m = new HeatDiagramRender.Java2dCanvas(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics()).measure();
        var rows = DeviceColumn.rows(in, TEXTS);
        java.util.function.BiPredicate<Integer,Integer> fit = (w, h) -> FluidGuiLayout.fits(w, h, iw -> Column.place(rows, 10, FluidGuiLayout.DOOR_TOP, FluidGuiLayout.side(iw) - 10, m, Column.Metrics.COMPACT).bounds().h());
        int s = Math.max(1, Integer.getInteger("guiScale", 3)), gw = -Math.floorDiv(-fw, s), gh = -Math.floorDiv(-fh, s);
        System.out.printf(java.util.Locale.ROOT, "%-24s %d by %d window: player scale %d, %d by %d GUI px%n", file, fw, fh, s, gw, gh);
        return new Prototype(file, FluidGuiLayout.imageWidth(gw), FluidGuiLayout.imageHeight(gh), body, !fit.test(gw, gh));
    }

    static Prototype pump() {
        var in = new DeviceColumn.Input(Kind.PUMP, true, true, true, "bar", "°C", "/h", DeviceColumn.Valve.NONE, null, false, null, null, List.of(),
                phases("volume", 0, 1, 0, 0), List.of(meter("temperature", "25.0", "°C"), meter("suction_pressure", "1.20", "bar"),
                meter("throughput", "554.8", "kmol/h"), meter("pressure_change", "2.40", "bar")));
        return devicePage("fluid-pump", WINDOW[0], WINDOW[1], in, (d, s) -> {
            d.title("Centrifugal pump");
            d.tabs(0, "Overview", "Datasheet");
            d.status(Door.tr("gui.icheme.fluid.flowing"), Palette.MUTED);
            var box = device(d, in, Map.of("volumeFlow", "12.0", "maximumAddedPressure", "5.00"));
            contents(d, box, 1, List.of(new String[]{"Component", "Mole %", "kmol/h"}, new String[]{"Water", "92.1", "511"}, new String[]{"Ethanol", "7.9", "43.8"}));
            d.footer(2, "Close", "Apply", "Mole", "°C", "bar", "/h");
        });
    }

    /** A compressor's page: its readings as {@code meters} give them. */
    static Prototype compressor(String file, int fw, int fh, List<DeviceColumn.Meter> meters) {
        var in = new DeviceColumn.Input(Kind.COMPRESSOR, true, true, true, "bar", "°C", "/h", DeviceColumn.Valve.NONE, null, false, null, null, List.of(),
                phases("volume", 0, 0, 1, 0), meters);
        return devicePage(file, fw, fh, in, (d, s) -> {
            d.title("Centrifugal compressor");
            d.tabs(0, "Overview", "Datasheet");
            d.status(Door.tr("gui.icheme.fluid.flowing"), Palette.MUTED);
            var box = device(d, in, Map.of("volumeFlow", "1200", "maximumPressureRatio", "4.00"));
            contents(d, box, 2, List.of(new String[]{"Component", "Mole %", "kmol/h"}, new String[]{"Methane", "92.0", "46.0"}, new String[]{"Ethane", "8.0", "4.0"}));
            d.footer(2, "Close", "Apply", "Mole", "°C", "bar", "/h");
        });
    }

    /** A compressor's readings: its pressure ratio, the pressure change on that meter's hover (owner 2026-10-28, option A). */
    static List<DeviceColumn.Meter> compressorMeters() {
        return List.of(meter("temperature", "25.0", "°C"), meter("suction_pressure", "1.01", "bar"), meter("throughput", "50.0", "kmol/h"),
                new DeviceColumn.Meter("pressure_ratio", "4.00", "", HeatDiagramRender.tr("gui.icheme.fluid.pressure_ratio.hint", "3.04 bar")));
    }

    static Prototype compressor() {
        return compressor("fluid-compressor", WINDOW[0], WINDOW[1], compressorMeters());
    }

    static Prototype generator() {
        var in = new DeviceColumn.Input(Kind.GENERATOR, true, true, true, "bar", "°C", "/s", DeviceColumn.Valve.NONE, null, false, null, null, List.of(),
                phases("mole", 1, 0, 0.1, 0), List.of(meter("temperature", "25.0", "°C"), meter("pressure", "2.00", "bar"), meter("throughput", "0.2", "kmol/s")));
        return devicePage("fluid-generator", WINDOW[0], WINDOW[1], in, (d, s) -> {
            d.title("Fluid source");
            d.tabs(0, "Overview", "Composition", "Solids");
            d.status(Door.tr("gui.icheme.fluid.flowing"), Palette.MUTED);
            var box = device(d, in, Map.of("pressure", "2.00", "temperature", "25.0"));
            contents(d, box, 0, List.of(new String[]{"Component", "Mole %", "kmol/s"}, new String[]{"n-Hexane", "61.0", "0.1"}, new String[]{"n-Heptane", "39.0", "0.1"}));
            d.footer(2, "Close", "Apply", "Mole", "°C", "bar", "/s");
        });
    }

    /** The names the device pages' title strips show (N1), by kind. */
    static final Map<Kind, String> NAMES = Map.of(Kind.PUMP, "P-101", Kind.COMPRESSOR, "K-101", Kind.VALVE, "FV-101", Kind.GENERATOR, "F-101", Kind.RESERVOIR, "TK-101",
            Kind.VOID, "FL-101", Kind.FILTER, "S-101", Kind.HEAT_SIDE, "E-101");

    /**
     * The walls on the device pages (batch 2026-11-16-fouling-corrosion, WP4): the casing pump P-101 with its Integrity group
     * beside Health (a chrome steel casing at 72 % used, 0.4 mm/y of sulfidation, 1.5 min of play left), and the control valve
     * FV-101 with its Trim group (an eroded trim at 22 %, 10 min of play left).
     */
    static List<Prototype> integrity() {
        var casing = casingInput().withSuction(new DeviceColumn.Suction("6.8", "3.3", "0.4", "73.0", "87.6"))
                .withIntegrity(new DeviceColumn.Wall("28.0", "0.4", "1.5", "Sulfidation", "Chrome steel"), null);
        var valve = new DeviceColumn.Input(Kind.VALVE, true, false, true, "bar", "°C", "/s", DeviceColumn.Valve.MASS_FLOW, new DeviceColumn.Opening(0.63, "Holding the set point"),
                false, null, null, List.of(), phases("volume", 1, 0, 0, 0), List.of(meter("temperature", "80.0", "°C"), meter("inlet_pressure", "3.10", "bar"),
                meter("throughput", "2.5", "kg/s"), meter("pressure_change", "-1.10", "bar"))).withIntegrity(null, new DeviceColumn.Trim("22.0", "10.0"));
        // A drum V-101 as a reader sees it: a stainless shell weeping by sour water, 93 % used.
        var drum = new DeviceColumn.Input(Kind.RESERVOIR, false, false, true, "bar", "°C", "/h", DeviceColumn.Valve.NONE, null, false, null, null, List.of(),
                null, List.of(meter("temperature", "45.0", "°C"), meter("pressure", "1.02", "bar")))
                .withIntegrity(new DeviceColumn.Wall("7.0", "1.2", "0.4", "Sour water", "Stainless steel"), null);
        return List.of(
                devicePage("integrity-vessel", WINDOW[0], WINDOW[1], drum, (d, s) -> {
                    d.title("Vertical drum");
                    d.name("V-101", false);
                    d.tabs(0, "Overview");
                    d.status("WEEPING · " + Door.tr("gui.icheme.fluid.flowing"), Palette.MUTED);
                    device(d, drum, DeviceColumn.rows(drum, TEXTS), Map.of());
                    d.footer(1, "Close", "Mole", "°C", "bar", "/h");
                }),
                devicePage("integrity-pump-casing", WINDOW[0], WINDOW[1], casing, (d, s) -> {
                    d.title("Centrifugal pump");
                    d.name("P-101", true);
                    d.tabs(0, "Overview", "Datasheet");
                    d.status(Door.tr("gui.icheme.fluid.flowing") + " · THIN WALL", Palette.MUTED);
                    device(d, casing, DeviceColumn.rows(casing, TEXTS), Map.of("volumeFlow", "100.0", "maximumAddedPressure", "10.0"));
                    d.footer(2, "Close", "Apply", "Mole", "°C", "bar", "/h");
                }),
                devicePage("integrity-valve-trim", WINDOW[0], WINDOW[1], valve, (d, s) -> {
                    d.title("Control valve");
                    d.tabs(0, "Overview");
                    d.status(Door.tr("gui.icheme.fluid.flowing") + " · ERODED TRIM", Palette.MUTED);
                    var box = device(d, valve, Map.of("massFlow", "2.50"));
                    contents(d, box, 0, List.of(new String[]{"Component", "Mass %", "kg/s"}, new String[]{"Toluene", "100.0", "2.5"}));
                    d.footer(2, "Close", "Apply", "Mass", "°C", "bar", "/s");
                }));
    }

    /** The hover as the game draws it: its text at ({@code x}, {@code y}), its frame round it (renderTooltip's look). */
    static void hover(Door d, String text, int x, int y) {
        var lines = text.split("\n");
        int w = 0;
        for (var line : lines) w = Math.max(w, d.m.width(line));
        int left = x - 3, top = y - 3, h = lines.length * 10 + 4;
        d.c.fill(left, top, left + w + 6, top + h, 0xF0100010);
        d.c.outline(left, top, w + 6, h, 0xFF5000FF);
        for (int i = 0; i < lines.length; i++) d.c.text(lines[i], x, y + i * 10, 0xFFFFFFFF);
    }

    /**
     * The control valve's Sensor group as shipped (batch 2026-11-16-review-plant-sizing, owner's pick A of
     * VALVE_SENSOR_REVIEW.md), from {@code DeviceColumn.sensors}: no sensor linked, three linked and none selected (NO
     * SIGNAL: the valve stands open), FT-0012 selected with its hover beside the door column as FluidDeviceScreen places it,
     * and PT-0007 selected (the set point an upstream pressure); an editor beside a reader, at 464 by 254. Run with
     * {@code -Donly=valve-sensor}.
     */
    static void valveSensorSheets(Path out) throws Exception {
        String[] labels = {"FT-0012 / FLOW", "PT-0007 / PRESSURE", "FT-0015 / FLOW"};
        String[] hints = {"Sensor block: 104, 64, -31", "Sensor block: 98, 66, -31\nEquipment: #0042", "Sensor block: 112, 64, -28"};
        String[] states = {"no sensor linked", "linked, none selected (NO SIGNAL)", "FT-0012 selected, its hover", "PT-0007 selected"};
        int[][] state = {{0, -1}, {3, -1}, {3, 0}, {3, 1}};
        var prototypes = new ArrayList<Prototype>();
        var titles = new ArrayList<String>();
        for (int st = 0; st < states.length; st++)
            for (boolean editor : new boolean[]{true, false}) {
                var choices = new ArrayList<DeviceColumn.SensorChoice>();
                for (int i = 0; i < state[st][0]; i++) choices.add(new DeviceColumn.SensorChoice(labels[i], hints[i], i == state[st][1], DeviceColumn.SENSOR_KEY + i));
                titles.add((editor ? "Editor" : "Reader") + " · " + states[st]);
                prototypes.add(valveSensorPage(choices, state[st][1], editor, st == 2));
            }
        for (var skin : List.of(PanelSkin.LIGHT, PanelSkin.DARK)) {
            var pages = new ArrayList<BufferedImage>();
            for (var p : prototypes) pages.add(render(p, skin, false));
            HeatDiagramRender.grid(out.resolve("valve-sensor-" + (skin == PanelSkin.LIGHT ? "light" : "dark") + ".png"), titles, pages);
        }
    }

    /** One valve page of the Sensor sheet: {@code selected} the index into {@code choices} (-1: none), its hover shown where {@code hover}. */
    static Prototype valveSensorPage(List<DeviceColumn.SensorChoice> choices, int selected, boolean editor, boolean hover) {
        boolean set = selected >= 0, pressure = set && choices.get(selected).label().startsWith("PT");
        var meters = set ? List.of(meter("temperature", "80.0", "°C"), meter("inlet_pressure", "3.10", "bar"), meter("throughput", "2.5", "kg/s"), meter("pressure_change", "-1.10", "bar"))
                : List.of(meter("temperature", "80.0", "°C"), meter("inlet_pressure", "3.10", "bar"), meter("throughput", "4.1", "kg/s"), meter("pressure_change", "-0.35", "bar"));
        var in = new DeviceColumn.Input(Kind.VALVE, editor, false, true, "bar", "°C", "/s", !set ? DeviceColumn.Valve.UNCONFIGURED : pressure ? DeviceColumn.Valve.PRESSURE : DeviceColumn.Valve.MASS_FLOW,
                new DeviceColumn.Opening(set ? 0.63 : 1, set ? "Regulating" : ""), false, null, null, DeviceColumn.sensors(choices, editor, TEXTS), phases("volume", 1, 0, 0, 0), meters);
        var rows = DeviceColumn.rows(in, TEXTS);
        return new Prototype("valve-sensor", 464, 254, (d, k) -> {
            d.title("Control valve");
            d.name("FV-101", editor);
            d.tabs(0, "Overview");
            d.status(set ? Door.tr("gui.icheme.fluid.flowing") : TEXTS.get("valve_no_signal"), set ? Palette.MUTED : Palette.WARN);
            var box = device(d, in, rows, Map.of("massFlow", "2.50", "pressure", "2.00"));
            contents(d, box, 0, List.of(new String[]{"Component", "Mass %", "kg/s"}, new String[]{"Toluene", "100.0", set ? "2.5" : "4.1"}));
            if (editor) d.footer(2, "Close", "Apply", "Mass", "°C", "bar", "/s");
            else d.footer(1, "Close", "Mass", "°C", "bar", "/s");
            if (!hover) return;
            // The selected choice's hover beside the door column, level with its row (FluidDeviceScreen: KitDoor.choice).
            for (var p : Column.place(rows, 10, FluidGuiLayout.DOOR_TOP, FluidGuiLayout.side(d.w) - 10, d.m, Column.Metrics.COMPACT).rows())
                if (p.row() instanceof Column.Choice c && c.selected()) {
                    hover(d, c.hint(), p.rect().right() + 6, p.rect().y());
                    break;
                }
        });
    }

    /** The valve, the tallest page, in a {@link #WINDOW} window; and in a 1366 by 768 one, where scale 2 still leaves it a bar. */
    static List<Prototype> valve() {
        return List.of(valve("fluid-valve", WINDOW[0], WINDOW[1]), valve("fluid-valve-1366x768", 1366, 768));
    }

    static Prototype valve(String file, int fw, int fh) {
        var in = new DeviceColumn.Input(Kind.VALVE, true, false, true, "bar", "°C", "/s", DeviceColumn.Valve.MASS_FLOW, new DeviceColumn.Opening(0.63, "Holding the set point"),
                false, null, null, List.of(), phases("volume", 1, 0, 0, 0), List.of(meter("temperature", "80.0", "°C"), meter("inlet_pressure", "3.10", "bar"),
                meter("throughput", "2.5", "kg/s"), meter("pressure_change", "-1.10", "bar")));
        return devicePage(file, fw, fh, in, (d, s) -> {
            d.title("Control valve");
            d.tabs(0, "Overview");
            d.status(Door.tr("gui.icheme.fluid.flowing"), Palette.MUTED);
            var box = device(d, in, Map.of("massFlow", "2.50"));
            contents(d, box, 0, List.of(new String[]{"Component", "Mass %", "kg/s"}, new String[]{"Toluene", "100.0", "2.5"}));
            d.footer(2, "Close", "Apply", "Mass", "°C", "bar", "/s");
        });
    }

    /**
     * A drum's accessory page (batch 2026-11-11-gas-blanketing): its gas blanketing valve's set pressure as an editor's
     * entry and as a reader's meter, the drum's pressure, and on the glass the pressures at which it pads and vents.
     */
    static List<Prototype> blanketValve() {
        var in = new DeviceColumn.Input(Kind.RESERVOIR, true, false, true, "bar", "°C", "/s", DeviceColumn.Valve.NONE, null, false,
                new DeviceColumn.Level(0.5, "3976.0", "7952.0"), null, List.of(), phases("volume", 0, 4, 4, 0), List.of());
        var out = new ArrayList<Prototype>();
        for (boolean editor : new boolean[]{true, false}) {
            var rows = DeviceColumn.blanketValve(editor, "bar", "1.213", "1.209", TEXTS);
            out.add(devicePage(editor ? "fluid-drum-accessory" : "fluid-drum-accessory-reader", WINDOW[0], WINDOW[1], rows, (d, s) -> {
                d.title("Horizontal drum");
                d.name("V-101", false);
                d.tabs(3, "Overview", "Outlets", "Datasheet", Door.tr("gui.icheme.fluid.accessory"));
                d.status(Door.tr("gui.icheme.fluid.flowing"), Palette.MUTED);
                var box = device(d, in, rows, Map.of("blanket", "1.213"));
                int x = box.x() + 9, w = box.w() - 16;
                d.c.text(d.m.cut(Door.tr("gui.icheme.fluid.accessory.blanket"), w), x, 82, Palette.ACCENT);
                d.c.text(d.m.cut(Door.tr("gui.icheme.fluid.accessory.blanket.pad", "1.213", "bar"), w - 4), x, 100, Palette.TEXT);
                d.c.hLine(x, x + w, 112, Palette.LINE);
                d.c.text(d.m.cut(Door.tr("gui.icheme.fluid.accessory.blanket.vent", "1.274", "bar"), w - 4), x, 118, Palette.TEXT);
                d.c.hLine(x, x + w, 130, Palette.LINE);
                int y = 136;
                for (var line : d.m.wrap(Door.tr("gui.icheme.fluid.accessory.blanket.supply"), w - 4)) {
                    d.c.text(line, x, y, Palette.MUTED);
                    y += 10;
                }
                if (editor) d.footer(2, "Close", "Apply", "Mass", "°C", "bar", "/s");
                else d.footer(1, "Close", "Mass", "°C", "bar", "/s");
            }));
        }
        return out;
    }

    /**
     * A pressure safety valve (same batch): on a drum's accessory page beside its blanketing valve (an editor), alone on a
     * drum (a reader, relieving to the atmosphere), and on a pipe's own valve screen (an editor, piped).
     */
    static List<Prototype> reliefValve() {
        var drum = new DeviceColumn.Input(Kind.RESERVOIR, true, false, true, "bar", "°C", "/s", DeviceColumn.Valve.NONE, null, false,
                new DeviceColumn.Level(0.5, "3976.0", "7952.0"), null, List.of(), phases("volume", 0, 4, 4, 0), List.of());
        var pipe = new DeviceColumn.Input(Kind.PIPE, true, false, true, "bar", "°C", "/s", DeviceColumn.Valve.NONE, null, false,
                null, null, List.of(), phases("volume", 0, 1, 0, 0), List.of());
        var out = new ArrayList<Prototype>();
        // Both valves on one drum, an editor.
        var both = DeviceColumn.accessories(DeviceColumn.blanketValve(true, "bar", "1.213", "4.012", TEXTS),
                DeviceColumn.reliefValve(true, "bar", "4.013", null, "1.111", "kg/s", true, TEXTS));
        out.add(devicePage("fluid-drum-accessory-psv", WINDOW[0], WINDOW[1], both, (d, s) -> {
            d.title("Horizontal drum");
            d.name("V-101", false);
            d.tabs(3, "Overview", "Outlets", "Datasheet", Door.tr("gui.icheme.fluid.accessory"));
            d.status(Door.tr("gui.icheme.fluid.flowing"), Palette.MUTED);
            var box = device(d, drum, both, Map.of("blanket", "1.213", "relief", "4.013"));
            int y = blanketGlass(d, box, 82, "1.213", "1.274");
            int x = box.x() + 9, w = box.w() - 16;
            d.c.hLine(x, x + w, y + 5, Palette.ACCENT);
            d.c.hLine(x, x + w, y + 6, Palette.ACCENT);
            reliefGlass(d, box, y + 14, "4.013", true);
            d.footer(2, "Close", "Apply", "Mass", "°C", "bar", "/s");
        }));
        // The safety valve alone, a reader.
        var alone = DeviceColumn.reliefValve(false, "bar", "4.013", "4.013", "1.111", "kg/s", true, TEXTS);
        out.add(devicePage("fluid-drum-accessory-psv-reader", WINDOW[0], WINDOW[1], alone, (d, s) -> {
            d.title("Vertical drum");
            d.name("V-102", false);
            d.tabs(3, "Overview", "Outlets", "Datasheet", Door.tr("gui.icheme.fluid.accessory"));
            d.status(Door.tr("gui.icheme.fluid.flowing"), Palette.MUTED);
            var box = device(d, drum, alone, Map.of());
            reliefGlass(d, box, 82, "4.013", true);
            d.footer(1, "Close", "Mass", "°C", "bar", "/s");
        }));
        // A pipe's safety valve, its own screen, piped to a flare header.
        var onPipe = DeviceColumn.reliefValve(true, "bar", "3.000", "2.998", "0.000", "kg/s", false, TEXTS);
        out.add(devicePage("fluid-pipe-psv", WINDOW[0], WINDOW[1], onPipe, (d, s) -> {
            d.title("Pipe");
            d.name("", false);
            d.tabs(0, Door.tr("gui.icheme.fluid.safety_valve"));
            d.status(Door.tr("gui.icheme.fluid.flowing"), Palette.MUTED);
            var box = device(d, pipe, onPipe, Map.of("relief", "3.000"));
            reliefGlass(d, box, 82, "3.000", false);
            d.footer(2, "Close", "Apply", "Mass", "°C", "bar", "/s");
        }));
        return out;
    }

    /** The glass of a blanketing valve from {@code y}, as the screen draws it; returns the line below it. */
    static int blanketGlass(Door d, Rect box, int y, String set, String vent) {
        int x = box.x() + 9, w = box.w() - 16;
        d.c.text(d.m.cut(Door.tr("gui.icheme.fluid.accessory.blanket"), w), x, y, Palette.ACCENT);
        d.c.text(d.m.cut(Door.tr("gui.icheme.fluid.accessory.blanket.pad", set, "bar"), w - 4), x, y + 18, Palette.TEXT);
        d.c.hLine(x, x + w, y + 30, Palette.LINE);
        d.c.text(d.m.cut(Door.tr("gui.icheme.fluid.accessory.blanket.vent", vent, "bar"), w - 4), x, y + 36, Palette.TEXT);
        d.c.hLine(x, x + w, y + 48, Palette.LINE);
        y += 54;
        for (var line : d.m.wrap(Door.tr("gui.icheme.fluid.accessory.blanket.supply"), w - 4)) {
            d.c.text(line, x, y, Palette.MUTED);
            y += 10;
        }
        return y;
    }

    /** The glass of a pressure safety valve from {@code y}, as the screen draws it. */
    static void reliefGlass(Door d, Rect box, int y, String set, boolean open) {
        int x = box.x() + 9, w = box.w() - 16;
        d.c.text(d.m.cut(Door.tr("gui.icheme.fluid.accessory.relief"), w), x, y, Palette.ACCENT);
        d.c.text(d.m.cut(Door.tr("gui.icheme.fluid.accessory.relief.opens", set, "bar"), w - 4), x, y + 18, Palette.TEXT);
        d.c.hLine(x, x + w, y + 30, Palette.LINE);
        d.c.text(d.m.cut(Door.tr(open ? "gui.icheme.fluid.accessory.relief.open" : "gui.icheme.fluid.accessory.relief.piped"), w - 4), x, y + 36, Palette.TEXT);
        d.c.hLine(x, x + w, y + 48, Palette.LINE);
        y += 54;
        for (var line : d.m.wrap(Door.tr("gui.icheme.fluid.accessory.relief.note"), w - 4)) {
            d.c.text(line, x, y, Palette.MUTED);
            y += 10;
        }
    }

    static Prototype tank() {
        var in = new DeviceColumn.Input(Kind.RESERVOIR, true, false, true, "bar", "°C", "/s", DeviceColumn.Valve.NONE, null, false,
                new DeviceColumn.Level(0.63, "25200.0", "40000.0"), null, List.of(), phases("volume", 0, 25.2, 14.8, 0),
                List.of(meter("temperature", "20.0", "°C"), meter("top_pressure", "1.01", "bar"), meter("bottom_pressure", "1.25", "bar"), meter("net_flow", "-0.4", "kg/s"),
                        meter("mass", "25150.0", "kg")));
        return devicePage("fluid-tank", WINDOW[0], WINDOW[1], in, (d, s) -> {
            d.title("Atmospheric tank");
            d.tabs(0, "Overview", "Outlets", "Datasheet");
            d.status(Door.tr("gui.icheme.fluid.flowing"), Palette.MUTED);
            var box = device(d, in, Map.of());
            contents(d, box, 1, List.of(new String[]{"Component", "Mole %", "kmol"}, new String[]{"Water", "99.2", "1390"}, new String[]{"Salt", "0.8", "11.2"}));
            d.footer(1, "Close", "Mole", "°C", "bar", "/s");
        });
    }

    static Prototype pipe() {
        var in = new DeviceColumn.Input(Kind.PIPE, true, false, true, "kPa", "°C", "/min", DeviceColumn.Valve.NONE, null, false, null,
                HeatDiagramRender.tr("gui.icheme.fluid.junction", 3), List.of(), phases("volume", 0, 1, 0, 0),
                List.of(meter("temperature", "35.0", "°C"), meter("pressure", "180.0", "kPa"), meter("throughput", "120.0", "kmol/min"),
                        meter("velocity_lowest", "0.8", "m/s"), meter("velocity_highest", "1.6", "m/s"), meter("gradient", "0.4", "kPa/m")));
        return devicePage("fluid-pipe", WINDOW[0], WINDOW[1], in, (d, s) -> {
            d.title("Pipe");
            d.tabs(0, "Overview", "Connections");
            d.status(Door.tr("gui.icheme.fluid.reversed_interval") + " " + Door.tr("gui.icheme.fluid.flowing"), Palette.MUTED);
            var box = device(d, in, Map.of("diameter", "0.10"));
            contents(d, box, 1, List.of(new String[]{"Component", "Mole %", "kmol/min"}, new String[]{"Water", "100.0", "120.0"}));
            d.footer(2, "Close", "Apply", "Mole", "°C", "kPa", "/min");
        });
    }

    static BufferedImage render(Prototype p, Skin skin, boolean end) {
        var image = new BufferedImage((p.width() + 8) * SCALE, (p.height() + 8) * SCALE, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.scale(SCALE, SCALE);
        g.translate(4, 4);
        var canvas = new HeatDiagramRender.Java2dCanvas(g);
        var door = new Door(canvas, skin, p.width(), p.height(), end);
        skin.page(canvas, new Rect(0, 0, p.width(), p.height()));
        p.body().accept(door, skin);
        canvas.count(p.file() + " " + (skin == PanelSkin.LIGHT ? "light" : skin == PanelSkin.DARK ? "dark" : "plain") + (end ? " end" : ""));
        g.dispose();
        return image;
    }

    /** A door being drawn: the screen's frame parts and the kit's rows with their widgets. */
    static final class Door {
        final Canvas c;
        final Skin skin;
        final int w, h;
        final Measure m;
        /** Whether a scrolled column is drawn scrolled to its end. */
        final boolean end;

        Door(Canvas c, Skin skin, int w, int h, boolean end) {
            this.end = end;
            this.c = c;
            this.skin = skin;
            this.w = w;
            this.h = h;
            this.m = c.measure();
        }

        static String tr(String key, Object... args) {
            return HeatDiagramRender.tr(key, args);
        }

        /** The title strip as last placed: the kind's plate and the name's place (N1). */
        TitleStrip.Strip strip;

        /** The title plate and the theme key with its contrast sign, as FluidDeviceScreen draws them. */
        void title(String text) {
            title(text, 18);
        }

        /** The title strip (N1): the kind's plate, the theme key {@code key} px wide at the right (0: none, as the datasheet). */
        void title(String text, int key) {
            strip = TitleStrip.place(w, key, text, m);
            skin.title(c, TitleStrip.TITLE_X, TitleStrip.TITLE_Y, strip.kind());
            if (key > 0) skin.iconKey(c, new Rect(w - 10 - key, 5, key, 18), Skin.Icon.THEME, false);
        }

        /** The name in the title strip (N1): an editor's LCD entry (its hint while empty), a reader's text. */
        void name(String name, boolean editor) {
            if (strip == null || strip.name() == null) return;
            if (editor) skin.field(c, strip.name(), name, TEXTS.get("name"), false);
            else {
                int[] at = TitleStrip.textAt(strip.name());
                skin.text(c, TitleStrip.text(name, strip.name(), m), at[0], at[1], Palette.TEXT);
            }
        }

        /** Tabs side by side at y 29, {@code current} latched. */
        void tabs(int current, String... names) {
            int n = names.length, tw = (w - 20 - 4 * (n - 1)) / n;
            for (int i = 0; i < n; i++)
                skin.button(c, new Rect(10 + i * (tw + 4), 29, tw, 18), names[i], i == current ? Skin.Press.CURRENT : Skin.Press.REST, false);
        }

        void status(String text, int palette) {
            skin.status(c, new Rect(10, 59, w - 20, 9), text, palette);
        }

        /**
         * The footer under a divider: its first {@code actions} texts are push buttons at the left (the second the
         * confirm one), the rest display keys (units, bases) on their strip at the right, as FluidDeviceScreen draws them.
         */
        void footer(int actions, String... texts) {
            skin.hDivider(c, 8, w - 8, h - 35);
            int keys = texts.length - actions, kw = 40, strip = keys * kw + Math.max(0, keys - 1) * 6;
            int bw = (w - 20 - (keys > 0 ? strip + 14 : 0) - 4 * (actions - 1)) / actions;
            for (int i = 0; i < actions; i++)
                skin.button(c, new Rect(10 + i * (bw + 4), h - 27, bw, 18), texts[i], i == 1 ? Skin.Press.PRIMARY : Skin.Press.REST, false);
            if (keys == 0) return;
            int x = w - 10 - strip;
            skin.softKeys(c, new Rect(x - 4, h - 31, strip + 8, 26));
            for (int i = 0; i < keys; i++) skin.softKey(c, new Rect(x + i * (kw + 6), h - 27, kw, 18), texts[actions + i], false);
        }

        /** The HMI screen's glass in {@code r}; returns the box inside it. */
        Rect well(Rect r) {
            return skin.well(c, r);
        }

        /**
         * {@code rows} placed at ({@code x}, {@code y}), {@code width} wide, at compact heights, drawn with their widgets:
         * fields show {@code values} (by key, blank shows the hint "off"), buttons their latched or acting state.
         */
        Column column(List<? extends Column.Row> rows, int x, int y, int width, Map<String, String> values) {
            var column = Column.place(rows, x, y, width, m, Column.Metrics.COMPACT);
            column.draw(c, 0, skin);
            for (var p : column.rows()) {
                if (p.row() instanceof Column.Field f) skin.field(c, p.slots().get(f.key()), values.getOrDefault(f.key(), ""), "off", false);
                else if (p.row() instanceof Column.Wide wide) skin.field(c, p.slots().get(wide.key()), values.getOrDefault(wide.key(), ""), "", false);
                else if (p.row() instanceof Column.Buttons b)
                    for (var spec : b.buttons())
                        skin.button(c, p.slots().get(spec.key()), spec.text(), spec.latched() ? Skin.Press.CURRENT : !spec.active() ? Skin.Press.OFF : Skin.Press.REST, false);
                else if (p.row() instanceof Column.Setting st && st.steps())
                    for (var spec : List.of(st.minus(), st.plus())) skin.button(c, p.slots().get(spec.key()), spec.text(), Skin.Press.REST, false);
            }
            return column;
        }

        /** A table on the HMI glass: a header row, then rows; numbers right-aligned in the last columns. */
        void table(Rect box, int[] widths, List<String[]> rows) {
            int y = box.y() + 2;
            for (int r = 0; r < rows.size() && y + 10 <= box.bottom(); r++, y += 12) {
                int x = box.x() + 3;
                for (int i = 0; i < widths.length; i++) {
                    String cell = m.cut(rows.get(r)[i], widths[i] - 4);
                    int colour = r == 0 ? Palette.MUTED : i == 0 ? Palette.TEXT : Palette.ACCENT;
                    c.text(cell, i == 0 ? x : x + widths[i] - 4 - m.width(cell), y, colour);
                    x += widths[i];
                }
                if (r == 0) c.hLine(box.x() + 2, box.right() - 2, y + 10, Palette.LINE);
            }
        }

        static Column.Group group(String title, String hint, Column.Row... rows) {
            return new Column.Group(title, hint, List.of(rows));
        }

        static Column.Buttons switchRow(String off, String on, boolean isOn) {
            return new Column.Buttons(List.of(new Column.Button("off", off, isOn, null, !isOn), new Column.Button("on", on, !isOn, null, isOn)));
        }

        static Column.Field field(String key, String label, String unit) {
            return new Column.Field(key, label, unit, null);
        }

        /** A meter: a value the plant reads, drawn by the skin as an LED meter (an entry is an LCD). */
        static Column.Reading reading(String key, String label, String value, String unit) {
            return new Column.Reading(key, label, value, unit, null);
        }

        static List<Column.Row> rows(Column.Row... rows) {
            return new ArrayList<>(List.of(rows));
        }
    }
}
