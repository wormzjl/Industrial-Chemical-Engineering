import com.wormzjl.icheme.client.PortColours;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn;
import com.wormzjl.icheme.client.gui.common.DeviceColumn;
import com.wormzjl.icheme.client.gui.common.DialogColumns;
import com.wormzjl.icheme.client.gui.common.HeatDiagram;
import com.wormzjl.icheme.client.gui.common.HeatFlowsheet;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.FlowsheetPainter;
import com.wormzjl.icheme.client.gui.common.kit.FlowsheetPainter.Look;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.PanelSkin;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.ScrollView;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.screens.inventory.FluidDeviceScreen.FluidGuiLayout;
import com.wormzjl.icheme.science.assembly.ExchangerBlueprint;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import javax.imageio.ImageIO;

/**
 * Design sheets of the in-game process simulator (batch 2026-11-02-process-simulator, owner: "make some design drawings for
 * me to approve before moving to implementation"). Nothing here is built yet: the doors, entries, meters, glass, plates and
 * keys are the shipped kit's ({@code Column}, {@code PanelSkin}, {@code FlowsheetPainter}, {@code CalculatorColumn},
 * {@code HeatFlowsheet}, {@code DeviceColumn}), in the game font and both doors. The sample flowsheet's numbers are the
 * shipped science's, from {@code tools/process-simulator/SampleFlowsheetProbe.java}, the loops' from {@code LoopProbe.java} there.
 * Magenta numbers are notes for the owner, not part of the screens. Run from the worktree root (JDK 22+ compiles the folder's three files):
 * <pre>java -Dfile.encoding=UTF-8 -Duser.language=en -Djava.awt.headless=true -cp build/classes/java/main tools/heat-gui-renders/SimulatorPrototypes.java
 *     src/main/resources/assets/icheme/lang/en_us.json tmp/sim-prototypes [minecraft resources jar]</pre>
 */
public final class SimulatorPrototypes {
    /** A 1920 by 1080 window at scale 2 (and 3840 by 2160 at scale 4): the screen fills it, 8 px round. */
    static final int W = 944, H = 524;
    /** The device frame the unit doors use: FluidGuiLayout's image at the same window. */
    static final int DW = 790, DH = 524;
    static final int MAGENTA = 0xFFFF3EA5;

    public static void main(String[] args) throws Exception {
        HeatDiagramRender.lang = HeatDiagramRender.lang(Path.of(args[0]));
        var out = Path.of(args[1]);
        Files.createDirectories(out);
        var jar = Path.of(args.length > 2 ? args[2] : "build/moddev/artifacts/neoforge-21.1.219-client-extra-aka-minecraft-resources.jar");
        HeatDiagramRender.font = Files.exists(jar) ? HeatDiagramRender.GameFont.load(jar) : null;

        both(out, "01-main-screen", W, H, (d, s) -> screen(d, Layout.WIDE, Show.NOTES),
                "The main screen with nothing selected: the canvas takes the whole width, the run reads on the status display",
                List.of("1 Tabs: Flowsheet, Results, Sizing, Basis, always shown; Sizing lights once the sheet reaches a steady state. 2 File keys: the block keeps this flowsheet and its last results, on the server, so it is there for every player and after a restart. Save stores it on the block now; Save as and Open use the player's own files in .minecraft/IChemE/flowsheets.",
                        "3 The one status display: the run's result and its gaps, yellow when a warning stands. 4 The canvas on the HMI glass: pan by dragging the grid, zoom by the wheel or the keys.",
                        "5 A stream is a named line; a pill starts it (teal, a feed) or ends it (a product). 6 The loop's tear, chosen by the solver, marked T. 7 A unit's colour is its state: teal solved, yellow warning, red failed, grey not solved.",
                        "8 A connection point: T-101 is one block, and its condenser, its three pumparound coolers and its steam feed each gave it ports from the door's settings (sheet 19). Any stream may use a pair, not only the feed. A free port has the laying tool's colours, grey once connected.",
                        "9 The palette, the list of items below: category keys over the tiles. Drag a tile to the canvas. An unlit tile has no process model yet.",
                        "10 Run is the confirming key; Stop cancels a run. The run is on the client; this flowsheet's numbers are a real solve by the mod's own thermo and column code, on a plain JVM with no game classes."));
        var selections = new ArrayList<BufferedImage>();
        selections.add(page(W, H, (d, s) -> screen(d, Layout.WIDE, Show.UNIT)));
        selections.add(page(W, H, (d, s) -> screen(d, Layout.WIDE, Show.FEED)));
        selections.add(page(W, H, (d, s) -> screen(d, Layout.WIDE, Show.STREAM)));
        stack(out.resolve("02-selection-panel.png"), List.of("A unit selected: the panel opens over the canvas's right end, and the sheet does not move",
                        "A feed stream selected: left click shows its settings, because the player sets it", "A stream a unit makes selected: left click shows its properties"), selections,
                List.of("Decision D1: layout A, with the right panel only while something is selected. It opens over the empty end of the glass, so no wire, tile or key moves, and closes on a click on empty canvas.",
                        "Left click selects. On a stream the panel depends on the situation: a feed shows its settings, as entries that take effect as typed; any other stream shows its properties, as meters. Double click opens the full door, in both cases."));
        var gestures = new ArrayList<BufferedImage>();
        gestures.add(page(W, H, (d, s) -> screen(d, Layout.WIDE, Show.INTERACTION)));
        gestures.add(page(W, H, (d, s) -> screen(d, Layout.WIDE, Show.RECONNECT)));
        stack(out.resolve("03-interaction.png"), List.of("Dragging, connecting and renaming", "Right click a stream to reconnect it: S-8 moves from P-102 to the new pump P-103"), gestures,
                List.of("1 A tile dragged from the palette: its symbol follows the cursor, snapped to the grid, and it lands with its next free tag (D-101).",
                        "2 Dragging from a port draws a stream that follows the cursor. Ports that can take it are ringed; a port that cannot stays dark (an outlet to an outlet, a second stream on a one-stream port). This free port is a fourth draw just added on T-101's door.",
                        "3 Dropping on empty canvas ends the stream in a product pill; dragging from a free inlet port makes a feed pill. So every open end is a named stream.",
                        "4 Double click a name to type it in place, on a stream or a unit. A used name is refused on the status display. Tags come from the kind: T, E, H, P, K, V, D.",
                        "5 Double click a symbol, a pill or a line to open its door. 6 A port's hover names it and says what happens if it stays free.",
                        "7 Right click a stream: the end nearer the click lifts off its port and follows the cursor, with no key held. The stream keeps its name and every setting. Its old port shows free.",
                        "8 Ports that can take that end are ringed. Left click one to land it. Left click on empty canvas ends it in a new pill. Right click or Esc puts it back. What lies downstream greys until the next run. Undo restores it."));
        single(out, "04-palette", 760, 462, (d, s) -> paletteSheet(d), "The palette: one tile for each function, from the constructor's equipment list plus streams and piping",
                List.of("Equipment with the same function is one tile: Storage tank, Fired heater, Drum, Compressor, Steam turbine. The tile runs with no type, because the world's law is the same for both types. The sizing stage proposes the type (sheet 28), and the symbol on the sheet then follows it.",
                        "Lit tiles have a process model on day one. Unlit tiles are drawn so the list stays whole: their hover says no process model yet, and the coverage gate forces a choice for every new equipment."));
        var small = new ArrayList<BufferedImage>();
        small.add(GuiPrototypes.render(new GuiPrototypes.Prototype("x", 624, 344, (d, s) -> screen(d, Layout.COMPACT, Show.PLAIN)), PanelSkin.LIGHT, false));
        small.add(GuiPrototypes.render(new GuiPrototypes.Prototype("x", 464, 254, (d, s) -> screen(d, Layout.TINY, Show.PLAIN)), PanelSkin.DARK, false));
        stack(out.resolve("05-small-windows.png"), List.of("1280 by 720 window, scale 2: 624 by 344 GUI px; the door column folds, tiles lose their captions",
                "The design minimum, 464 by 254 GUI px: the palette is one strip; both are shown at the Fit key's zoom"), small, List.of());
        var runs = new ArrayList<BufferedImage>();
        runs.add(page(W, H, (d, s) -> screen(d, Layout.WIDE, Show.RUNNING)));
        runs.add(page(W, H, (d, s) -> screen(d, Layout.WIDE, Show.VIEWER)));
        stack(out.resolve("06-run-in-progress.png"), List.of("A run in progress: the screen stays usable, the run is a background thread on the client", "A second player at the same block, read only while another edits"), runs,
                List.of("The ringed unit is the one being solved, the grey ones wait for it. The crude's pass through T-101's coolers is solved already: their duties are the door's, so it is no loop. Run is off and Stop is live; Stop reaches the column's own cancel check, so a long column stops too.",
                        "Editing during a run is refused on the status display. The run's result replaces the shown results only when the run ends; a stopped run keeps every unit it finished.",
                        "Decision D2: the run stays on the client, and the flowsheet with its last results stays on the block, on the server. The first player to open the block edits; a second one reads the last saved sheet, its tiles and keys off, and may Save as to a file of their own. The server stores what it is sent and never solves it."));

        both(out, "07-feed-stream", DW, DH, (d, s) -> feedOverview(d), "A feed stream's door: the Fluid Generator's page, with its flow and name",
                List.of("The same frame, groups and display keys as the world's Fluid Generator. Set point, then Flow, then Phases. Double click on the CRUDE pill opens it. Entries take effect as they are typed: nothing goes to a server."));
        both(out, "08-feed-composition", DW, DH, (d, s) -> feedComposition(d), "A feed stream's Composition tab: Templates, Copy and Paste",
                List.of("The composition is the calculator's feed table. Templates loads a stream template (a mixture, with its conditions if it has them). Copy and Paste use one clipboard shared by every screen; the group says what it holds."));
        both(out, "09-reservoir-copy", DW, DH, (d, s) -> reservoirCopy(d), "Copying a reservoir: one key on the tank's own page",
                List.of("Copy takes the composition, temperature and pressure the page already shows, from the last published snapshot: no packet, no solve. Paste in a stream takes it on the client."));
        both(out, "10-product-stream", DW, DH, (d, s) -> productDoor(d), "A product stream's door: a reader, every value a meter",
                List.of("Double click on a product pill or a line. Nothing to set: the stream is what its unit makes. Copy puts it on the clipboard, so a product can feed another flowsheet."));
        grid(out, "11-unit-doors", List.of("Heat exchanger: the shipped drawing on the glass", "Fired heater: the shipped drawing on the glass", "Pump", "Drum: outlets per phase", "Control valve", "Splitter"),
                List.of(page(DW, DH, (d, s) -> exchangerDoor(d)), page(DW, DH, (d, s) -> heaterDoor(d)), page(DW, DH, (d, s) -> pumpDoor(d)), page(DW, DH, (d, s) -> drumDoor(d)),
                        page(DW, DH, (d, s) -> valveDoor(d)), page(DW, DH, (d, s) -> splitterDoor(d))));
        grid(out, "12-column-door", List.of("The column's door: the calculator's Column page; flow and temperature are meters read from S-7", "The calculator's Results page, T-101's real profile",
                        "The Equipment page: the door's settings add the block's connection points, listed on the glass"),
                List.of(page(DW, DH, (d, s) -> columnDoor(d, CalculatorColumn.Page.COLUMN)), page(DW, DH, (d, s) -> columnDoor(d, CalculatorColumn.Page.RESULTS)), page(DW, DH, (d, s) -> columnDoor(d, CalculatorColumn.Page.EQUIPMENT))));
        grid(out, "13-templates", List.of("Stream templates: a mixture, with conditions if it has them", "Column templates: the column alone, no feed in it"),
                List.of(page(533, 280, (d, s) -> streamTemplates(d)), page(533, 280, (d, s) -> columnTemplates(d))));
        both(out, "14-results-streams", W, H, (d, s) -> results(d), "The Results tab: pick streams, read their properties",
                List.of("1 Views: Streams, Units, Log. 2 Tick the streams to compare; one column each. 3 Properties, then Composition, on one sheet that scrolls. The display keys change units and basis at once. Export writes a CSV beside the flowsheet file."));
        var views = new ArrayList<BufferedImage>();
        views.add(page(W, H, (d, s) -> resultsUnits(d)));
        views.add(page(W, H, (d, s) -> resultsLog(d)));
        stack(out.resolve("15-results-units-log.png"), List.of("The Units view: what each unit did, and the flowsheet's balance", "The Log view: the solver's order and passes"), views, List.of());
        both(out, "16-basis", W, H, (d, s) -> basis(d), "The Basis tab: the flowsheet's package and components",
                List.of("The package fixes the component list every stream carries; the crude-specific packages are gone (a crude is now a stream template). Solver holds the flowsheet's tolerance and passes; threads are the client's own."));
        grid(out, "17-file-and-repair", List.of("Open: the player's own files, which replace the sheet on this block", "After an update: what changed, nothing lost"),
                List.of(page(533, 280, (d, s) -> openDialog(d)), page(533, 280, (d, s) -> repairDialog(d))));
        single(out, "18-missing-unit", W, H, (d, s) -> screen(d, Layout.WIDE, Show.MISSING), "A flowsheet after an update removed a unit (light door shown)",
                List.of("1 A removed equipment stays on the sheet as a hatched placeholder, its name, ports, streams and settings kept. Replace offers the units with the same ports. Everything upstream solves; the rest stays grey until then.",
                        "2 The status display names the first thing to fix. A removed component is parked the same way: the Basis tab lists it with its amounts, and Results says which streams lost it."));
        single(out, "19-block-ports", W, 442, (d, s) -> blockPorts(d), "One block: the door's settings add the connection points on the main flowsheet",
                List.of("The same column block in four configurations, as dropped on the canvas: free ports in the laying tool's colours, each port's name shown on hover. Cyan names are what that configuration added.",
                        "Condenser set to Exchanger: Condenser in and out, for cooling water; Air cooler: none. Reboiler duty above zero: Reboiler in and out, for steam. A pumparound row: PA in and out, the cold side of its cooler. A steam row: Steam. A draw row: Draw. Top below ambient pressure: Motive steam for the ejector.",
                        "These are the utility sides the shipped assembly already has: ColumnBlueprint's condenser, kettle and PA cooler tube sides, steam valves and ejector. So the ports come from the same design the constructor builds.",
                        "A pair left free means a utility takes the heat, as the calculator does today: no warning. A wired pair puts the heat into whatever stream the player runs through it, not only the feed: crude, boiler feed water, another unit's stream.",
                        "Removing a row removes its ports: their streams stay, each ending in an open pill, and the status says so. Ports are saved by the row's own id, so removing PA 1 does not move PA 2's wires."));
        single(out, "20-pa-options", W, 462, (d, s) -> paOptions(d), "Pumparound heat exchangers: two ways to connect them (option A chosen, D11)",
                List.of("Both on the sample's real numbers. A keeps the column's model as it is: a pumparound is a heat sink on its trays, with no circulating stream. The cooler stays in the block, and a stream goes through its tube side. The crude is the sample's; any stream may take the pair (D7).",
                        "In A the duties are the door's, so the column never reads the crude's ports. The crude's pass through the block is no loop, and the sample closes in 2 passes. The hot side is checked against the draw tray's temperature; with no circulation rate, the cold end is not checked until the sizing stage sets one.",
                        "B makes a real stream: circulation set on the door, its state from the draw tray. It allows two exchangers on one pumparound. The price is 3 more units, 6 more streams, and a duty that comes back as a result: the column solves again whenever a return temperature moves, and a broken loop must be caught."));
        single(out, "21-other-blocks", W, 402, (d, s) -> otherBlocks(d), "The same rule for every block: ports come from its configuration",
                List.of("Each is one tile and one door. An exchanger's bypass or ratio station stays inside its block, as in ExchangerBlueprint. A heater's coils in series are one pair; set apart, the convection coil can take other duty, such as superheating the stripping steam.",
                        "Fuel stays off the sheet: the mod has no combustion model, so a heater is set by its outlet or its duty. Unlit tiles take their ports from their nozzles when a process model lights them, so a new unit brings its ports with it."));
        for (var option : SimulatorIcons.Style.values()) {
            String file = "23-icons-" + switch (option) {
                case SOLID -> "A-silhouette";
                case FRAMED -> "B-framed";
                case LINE -> "C-line";
            };
            single(out, file, W, 540, (d, s) -> iconSheet(d, option), switch (option) {
                        case SOLID -> "Symbols, option A: flat silhouettes in the state's colour, details cut out";
                        case FRAMED -> "Symbols, option B: dark fill, outline and details in the state's colour, as the shipped door drawings";
                        case LINE -> "Symbols, option C: outlines only, with a process drawing's schematic marks";
                    },
                    List.of("All three share one geometry, true to the mod's equipment: straight corners, only heads and domes round, no gradients and no light. Each symbol is its own: piston and turbo compressors differ, a column's trays stop short of one wall in turn as downcomers do, a heater has its radiant box, convection bank and stack.",
                            "Drawn at true size for a 1920 by 1080 window, with the zoomed views at twice that. In the game each symbol is a baked mask in a texture, tinted by the unit's state, so its edges stay smooth at every window scale and zoom."));
        }
        var loops = new ArrayList<BufferedImage>();
        loops.add(page(W, H, (d, s) -> screen(d, Layout.WIDE, Show.RECYCLE)));
        loops.add(page(W, H, (d, s) -> loopLog(d, false)));
        loops.add(page(W, H, (d, s) -> loopLog(d, true)));
        stack(out.resolve("22-loops.png"), List.of("A material recycle: 15 % of the residue back to the crude, one tear closes both loops", "An energy loop that moves the column: the furnace on a fixed duty, 8 MW short", "No steady state: all of the residue back, so its heavy ends have no way out"), loops,
                List.of("Every number is the real column and flash code (LoopProbe). The furnace that holds 365.0 °C decouples the column, so the sample closes in 2 passes. A furnace on duty does not: the feed falls, the bottoms fall, the residue preheats less, and the loop's gain is about 0.5.",
                        "The status says Steady state only when every tear has stopped moving and mass closes. Otherwise it says Not steady, the last pass stays on show, and the Log names the tear and what grew. A trial step that fails a column falls back to a plain pass."));
        var sizing = new ArrayList<BufferedImage>();
        var shells = List.<Column.Row>of(entry("shells", "Shells", ""), entry("shell", "Shell", "m"), entry("tubes", "Tubes", "m"));
        sizing.add(page(W, H, (d, s) -> SimulatorSizing.units(d, "E-101", shells, Map.of("shells", "1", "shell", "1.00", "tubes", "6"), false)));
        sizing.add(page(W, H, (d, s) -> SimulatorSizing.units(d, "T-101", List.of(entry("diameter", "Diameter", "m"), meter("Trays", "40", "")), Map.of("diameter", "8.0"), false)));
        sizing.add(page(W, H, (d, s) -> SimulatorSizing.units(d, "E-101", shells, Map.of("shells", "1", "shell", "0.75", "tubes", "6"), true)));
        stack(out.resolve("24-sizing-units.png"), List.of("The Sizing tab, Units: every unit, its size and the limit closest to it", "T-101 selected: its block's members are rows under it", "A size the player keeps: Fixed rates it, and a red row stops Send to block"), sizing,
                List.of("1 Sizing lights once the sheet reaches a steady state. Size all sizes every unit and line at the Design flow, 110 % of the steady flow. Every number here is a real run of SizingProbe on the sample, with the mod's own laws and the world's films.",
                        "2 The lamps. Green meets every limit. Yellow is a Should: outside good practice, and it still runs. Red is a Must: the world will not run it or the constructor cannot build it. An exchanger is set by its surface and its drops, both from the duty and the flows.",
                        "3 Auto takes the smallest size that meets every limit. Fixed keeps the player's size and rates it against the same limits, so one stage both sizes and rates. A kept shell of 0.75 m holds 253 of the 444 m² the duty needs: red, and the status says why.",
                        "4 A block is one row with its members under it, as its door builds them. A pumparound has no circulation until here: PA fall gives it, so its pump, lines and cooler get real flows.",
                        "5 Sizing moves the run in two places: a new column diameter moves the tray drops, and a sized unit's drops follow its flow where the door's were typed. So Size all runs the sheet again and rates once more. Here the door's 8.0 m is what Auto picks: 7.5 m floods at 80.9 %, over the 80 % limit."));
        single(out, "25-sizing-lines", W, H, (d, s) -> SimulatorSizing.lines(d, "S-7"), "The Sizing tab, Lines: each line's service gives its limits",
                List.of("1 The service comes from the stream's phase, pressure and place: what feeds a pump, what leaves a tray, and so on. The key changes it, as the player may know better. It gives the limits: speed, drop per 100 m, ρv².",
                        "2 S-7 is the transfer line, two-phase at 359.5 °C: ρv² decides it, 8144 of 10000. That takes the pipe tool's largest bore, 0.80 m. Over that a line goes to two parallel runs, with a yellow lamp. The block's own lines follow the sheet's."));
        var budget = new ArrayList<BufferedImage>();
        budget.add(page(W, H, (d, s) -> SimulatorSizing.pressure(d, 0)));
        budget.add(page(W, H, (d, s) -> SimulatorSizing.pressure(d, 1)));
        stack(out.resolve("26-sizing-pressure.png"), List.of("The Sizing tab, Pressure: P-101's rise against what the sized train loses", "The residue line has no pump: the column's pressure and its sump must push it"), budget,
                List.of("A path runs from a machine's outlet to the next pressure the sheet sets. The door's drops were the player's estimates; this view adds up the sized ones at design flow, from each unit's flow, with the lines at the Line length. Typed is the door's estimate, which the sized drop replaces in the next run.",
                        "Spare under zero is red: the built plant cannot reach the sheet's pressure at this flow. Spare over zero is what a valve or a smaller rise takes. Here H-101's door took 7.60 bar and its coils lose 1.95, so 5.34 bar stays spare."));
        var build = new ArrayList<BufferedImage>();
        build.add(page(W, H, (d, s) -> SimulatorSizing.build(d)));
        build.add(page(533, 280, (d, s) -> SimulatorSizing.constructor(d)));
        stack(out.resolve("27-build-list.png"), List.of("The Sizing tab, Build: what Send to block stores on the block", "The constructor's Import lists every unit of the flowsheet"), build,
                List.of("Send to block stores each unit's design on the block, as Save design does for a column today. The equipment constructor's Import then lists them all, with the block's members. The line list stays with the player, who lays each line with the pipe tool at its bore."));
        var types = new ArrayList<BufferedImage>();
        types.add(page(W, H, (d, s) -> SimulatorSizing.units(d, "H-101", List.of(new Column.Line("Type", Palette.MUTED), new Column.Buttons(List.of(key("vertical", "Vertical", false), key("cabin", "Cabin", true))), entry("width", "Width", "m"), entry("radiant", "Length", "m")), Map.of("width", "11.0", "radiant", "41"), false)));
        types.add(page(W, H, (d, s) -> SimulatorSizing.storage(d, "NAPHTHA")));
        types.add(page(DW, DH, (d, s) -> exchangerSized(d)));
        stack(out.resolve("28-sizing-types.png"), List.of("One Fired heater tile: the stage proposes the type, by duty", "One Storage tank tile, as a what-if on the sample's products: the stage proposes the type, by pressure", "An exchanger's door after sizing: its drops follow the flow"), types,
                List.of("1 A tile with several types runs with none, because the world's law is the same for each. On Auto the stage proposes the type and the size; a key keeps the player's own, and it is rated. The symbol on the sheet follows the type.",
                        "2 A heater goes by duty: a vertical while the largest vertical holds it, 54.9 MW, a cabin above. H-101 needs 86.2 MW. A cabin under 23 MW is yellow if the player keeps one.",
                        "3 A tank goes by pressure: a floating roof while the liquid's bubble pressure at the tile's temperature is at most 0.766 bar, a sphere above. Unstabilised naphtha takes a sphere at 2.30 bar; a kerosene draw takes a sphere at 140 °C and a floating roof at 40 °C.",
                        "4 Before sizing a drop is the player's estimate. Once a unit has a size, its drops come from its passages at the run's flow, as meters on its door, and the next run uses them. A drum's type follows its second liquid, a compressor's its inlet flow."));
    }

    // ---- The sample's numbers: tools/process-simulator/SampleFlowsheetProbe.java on the shipped science, 2026-11-02 ----

    /** A stream as the results sheet shows it. */
    record Stream(String name, String t, String p, String vapour, String kmol, String tonnes, String volume, String density, String liquidMu, String gasMu, String weight, String enthalpy) {}

    static final List<Stream> STREAMS = List.of(
            new Stream("CRUDE", "25.0", "8.00", "0.0", "2652.3", "572.09", "659.7", "861.1", "9.80", "", "215.7", "-301.3"),
            new Stream("STEAM", "260.0", "3.50", "100.0", "1200.0", "21.62", "21.7", "1.4", "", "0.019", "18.0", "438.9"),
            new Stream("CWS", "30.0", "4.00", "0.0", "190000.0", "3422.90", "3429.7", "995.8", "0.80", "", "18.0", "-2453.1"),
            new Stream("S-1", "25.1", "14.00", "0.0", "2652.3", "572.09", "659.7", "861.5", "9.78", "", "215.7", "-300.6"),
            new Stream("S-2", "67.9", "13.70", "0.0", "2652.3", "572.09", "659.7", "841.7", "2.84", "", "215.7", "-219.8"),
            new Stream("S-3", "122.0", "13.40", "0.0", "2652.3", "572.09", "659.7", "812.2", "1.11", "", "215.7", "-107.2"),
            new Stream("S-4", "153.3", "13.10", "0.0", "2652.3", "572.09", "659.7", "792.4", "0.77", "", "215.7", "-36.7"),
            new Stream("S-5", "218.4", "12.60", "0.0", "2652.3", "572.09", "659.7", "742.8", "0.41", "", "215.7", "122.3"),
            new Stream("S-6", "365.0", "5.00", "71.1", "2652.3", "572.09", "659.7", "30.2", "0.58", "0.012", "215.7", "615.4"),
            new Stream("S-7", "359.5", "2.72", "77.9", "2652.3", "572.09", "659.7", "14.8", "0.73", "0.012", "215.7", "615.4"),
            new Stream("OFFGAS", "59.0", "2.50", "99.3", "115.2", "6.55", "10.6", "5.4", "0.23", "0.009", "56.9", "44.1"),
            new Stream("NAPHTHA", "59.0", "2.50", "0.0", "585.5", "46.40", "67.1", "652.1", "0.23", "", "79.2", "-294.2"),
            new Stream("WATER", "59.0", "2.50", "0.0", "1191.2", "21.46", "21.5", "983.8", "0.47", "", "18.0", "-2332.1"),
            new Stream("KERO", "140.1", "2.54", "0.0", "491.3", "56.65", "73.5", "671.2", "0.35", "", "115.3", "-109.0"),
            new Stream("S-8", "213.4", "2.58", "0.0", "515.4", "83.45", "101.6", "679.2", "0.37", "", "161.9", "85.7"),
            new Stream("DIESEL", "213.5", "6.00", "0.0", "515.4", "83.45", "101.6", "680.5", "0.37", "", "161.9", "86.2"),
            new Stream("AGO", "273.8", "2.64", "0.0", "165.2", "33.63", "39.5", "674.4", "0.34", "", "203.6", "255.9"),
            new Stream("S-9", "300.2", "2.72", "0.0", "788.5", "345.56", "367.5", "829.7", "1.01", "", "438.3", "356.6"),
            new Stream("S-10", "200.0", "2.42", "0.0", "788.5", "345.56", "367.5", "880.5", "2.75", "", "438.3", "93.4"),
            new Stream("RESIDUE", "70.0", "2.22", "0.0", "788.5", "345.56", "367.5", "926.3", "76.70", "", "438.3", "-195.5"),
            new Stream("CWR", "39.9", "3.70", "0.0", "190000.0", "3422.90", "3429.7", "992.4", "0.65", "", "18.0", "-2411.9"));

    static Stream stream(String name) {
        return STREAMS.stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    static final String[] COMPONENTS = {"Methane", "Ethane", "Propane", "Isobutane", "N-butane", "Isopentane", "N-pentane", "Crude oil, NBP below 84°C", "Crude oil, NBP 84–135°C",
            "Crude oil, NBP 135–183°C", "Crude oil, NBP 183–231°C", "Crude oil, NBP 231–280°C", "Crude oil, NBP 280–329°C", "Crude oil, NBP 329–378°C", "Crude oil, NBP 378–450°C",
            "Crude oil, NBP 450–550°C", "Crude oil, NBP 550–650°C", "Crude oil, NBP 650–750°C", "Crude oil, NBP above 750°C", "Nitrogen", "Water"};
    static final double[] WEIGHTS = {16.0, 30.1, 44.1, 58.1, 58.1, 72.1, 72.1, 81.1, 105.5, 131.6, 161.7, 197.1, 238.3, 286.5, 358.2, 475.4, 678.4, 937.8, 1474.8, 28.0, 18.0};
    static final double[] BOILS = {-161.5, -88.6, -42.1, -11.8, -0.5, 27.8, 36.1, 58.3, 110.3, 158.7, 207.0, 255.9, 304.8, 353.5, 413.8, 492.8, 595.1, 691.8, 821.9, -195.8, 100.0};
    static final double[] CRUDE = {0.50, 0.12, 1.06, 0.65, 2.23, 1.66, 2.46, 16.63, 12.31, 11.77, 10.47, 8.70, 7.34, 6.15, 6.06, 6.61, 2.97, 1.50, 0.81, 0, 0};
    static final double[] OFFGAS = {10.84, 2.17, 13.90, 5.65, 15.97, 6.73, 8.11, 28.19, 0.82, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 7.62};
    static final double[] NAPHTHA = {0.13, 0.11, 2.01, 1.79, 6.74, 5.95, 9.08, 64.54, 9.65, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    static final double[] KERO = {0, 0, 0.03, 0.04, 0.15, 0.21, 0.37, 4.59, 51.58, 42.99, 0.04, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    static final double[] RESIDUE = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.02, 0.47, 18.44, 20.70, 20.37, 22.23, 9.98, 5.05, 2.74, 0, 0};
    /** T-101's temperature down its 42 nodes, °C. */
    static final double[] PROFILE = {59.0, 81.7, 99.4, 111.0, 116.5, 118.7, 119.6, 120.3, 121.8, 128.6, 140.1, 154.4, 163.2, 167.9, 170.8, 174.3, 180.7, 194.8, 213.4, 233.4, 245.9, 252.9, 256.5, 258.4,
            259.4, 260.3, 261.6, 266.4, 273.8, 283.5, 291.2, 296.9, 300.6, 302.9, 304.5, 306.3, 310.1, 328.9, 321.3, 315.9, 309.9, 300.2};

    static String f(int digits, double value) {
        return String.format(Locale.ROOT, "%." + digits + "f", value);
    }

    /** A composition's table on {@code total} kmol: the component, mole %, kmol, the parts it holds only. */
    static List<String[]> composition(double[] shares, double total, String amount) {
        var table = new ArrayList<String[]>();
        table.add(new String[]{"Component", "Mole %", amount});
        for (int i = 0; i < shares.length; i++) if (shares[i] > 0) table.add(new String[]{COMPONENTS[i], f(2, shares[i]), f(1, shares[i] / 100 * total)});
        return table;
    }

    // ---- The sheets' frames ----

    static BufferedImage page(int w, int h, BiConsumer<GuiPrototypes.Door, Skin> body) {
        return GuiPrototypes.render(new GuiPrototypes.Prototype("x", w, h, body), PanelSkin.LIGHT, false);
    }

    static void both(Path out, String file, int w, int h, BiConsumer<GuiPrototypes.Door, Skin> body, String title, List<String> notes) throws Exception {
        var p = new GuiPrototypes.Prototype(file, w, h, body);
        stack(out.resolve(file + ".png"), List.of(title + " (light door)", title + " (dark door)"), List.of(GuiPrototypes.render(p, PanelSkin.LIGHT, false), GuiPrototypes.render(p, PanelSkin.DARK, false)), notes);
    }

    static void single(Path out, String file, int w, int h, BiConsumer<GuiPrototypes.Door, Skin> body, String title, List<String> notes) throws Exception {
        var p = new GuiPrototypes.Prototype(file, w, h, body);
        stack(out.resolve(file + ".png"), List.of(title), List.of(GuiPrototypes.render(p, PanelSkin.LIGHT, false)), notes);
    }

    static void grid(Path out, String file, List<String> titles, List<BufferedImage> pages) throws Exception {
        HeatDiagramRender.grid(out.resolve(file + ".png"), titles, pages);
    }

    /** Pages one under another, each under its title, the notes at the foot. */
    static void stack(Path file, List<String> titles, List<BufferedImage> pages, List<String> notes) throws Exception {
        int width = pages.stream().mapToInt(BufferedImage::getWidth).max().orElse(0), label = 40;
        var lines = wrapped(notes, width - 28);
        int height = lines.size() * 30 + (lines.isEmpty() ? 0 : 16);
        for (var p : pages) height += p.getHeight() + label;
        var sheet = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = sheet.createGraphics();
        g.setColor(new Color(0x101418));
        g.fillRect(0, 0, width, height);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int y = 0;
        for (int i = 0; i < pages.size(); i++) {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
            g.setColor(new Color(0xEAF1F5));
            g.drawString(titles.get(i), 14, y + 29);
            g.drawImage(pages.get(i), 0, y + label, null);
            y += label + pages.get(i).getHeight();
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
        for (var line : lines) {
            g.setColor(new Color(0xFFB7D8));
            g.drawString(line, 14, y + 26);
            y += 30;
        }
        g.dispose();
        ImageIO.write(sheet, "png", file.toFile());
    }

    /** The notes in lines that fit {@code width} px of the sheet's font, words whole. */
    static List<String> wrapped(List<String> notes, int width) {
        var g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        var metrics = g.getFontMetrics(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
        var lines = new ArrayList<String>();
        for (var note : notes) {
            var line = new StringBuilder();
            for (var word : note.split(" ")) {
                if (!line.isEmpty() && metrics.stringWidth(line + " " + word) > width) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                if (!line.isEmpty()) line.append(' ');
                line.append(word);
            }
            lines.add(line.toString());
        }
        g.dispose();
        return lines;
    }

    // ---- The main screen ----

    enum Layout { WIDE, COMPACT, TINY }

    enum Show {
        PLAIN, NOTES, UNIT, FEED, STREAM, INTERACTION, RECONNECT, MISSING, RUNNING, RECYCLE, VIEWER;

        /** Whether something is selected, so the panel stands open over the canvas. */
        boolean panel() {
            return switch (this) {
                case UNIT, FEED, STREAM, MISSING, RECYCLE -> true;
                default -> false;
            };
        }
    }

    /** A numbered note on the drawing: not part of the screen. */
    static void note(Canvas c, int x, int y, int n) {
        var p = new FlowsheetPainter(c, 0, 0, 1, 1);
        p.circle(x, y, 7, new Look(MAGENTA, 0xFFFFFFFF, 0xFFFFFFFF));
        String t = Integer.toString(n);
        c.text(t, x - c.measure().width(t) / 2, y - 4, 0xFFFFFFFF);
    }

    /** The frame every page of the screen shares: title, tabs, file keys, the status once. */
    static void frame(GuiPrototypes.Door d, int tab, String status, int tone, boolean narrow) {
        frame(d, tab, status, tone, narrow, false);
    }

    /** {@code reader}: a second player's frame, which can only save a copy. */
    static void frame(GuiPrototypes.Door d, int tab, String status, int tone, boolean narrow, boolean reader) {
        d.title("Process simulator: CDU train");
        String[] tabs = {"Flowsheet", "Results", "Sizing", "Basis"};
        int tw = narrow ? 56 : 84;
        for (int i = 0; i < tabs.length; i++) d.skin.button(d.c, new Rect(10 + i * (tw + 4), 29, tw, 18), tabs[i], i == tab ? Skin.Press.CURRENT : Skin.Press.REST, false);
        String[] file = {"New", "Open", "Save", "Save as"};
        int kw = narrow ? 36 : 48, x = d.w - 10 - file.length * kw - (file.length - 1) * 4;
        for (int i = 0; i < file.length; i++) d.skin.button(d.c, new Rect(x + i * (kw + 4), 29, kw, 18), narrow && i == 3 ? "As.." : file[i], reader && i < 3 ? Skin.Press.OFF : Skin.Press.REST, false);
        d.skin.status(d.c, new Rect(10, 59, d.w - 20, 9), d.m.cut(status, d.w - 24), tone);
    }

    /** The footer: Close, Run, Stop, Undo and Redo; the display keys on their tray at the right. */
    static void footer(GuiPrototypes.Door d, Show show, boolean tiny) {
        boolean running = show == Show.RUNNING, reader = show == Show.VIEWER;
        d.skin.hDivider(d.c, 8, d.w - 8, d.h - 35);
        int x = 10, y = d.h - 27;
        d.skin.button(d.c, new Rect(x, y, 50, 18), "Close", Skin.Press.REST, false);
        x += 54;
        d.skin.button(d.c, new Rect(x, y, 60, 18), "Run", running || reader ? Skin.Press.OFF : Skin.Press.PRIMARY, false);
        x += 64;
        d.skin.button(d.c, new Rect(x, y, 50, 18), "Stop", running ? Skin.Press.REST : Skin.Press.OFF, false);
        x += 54;
        if (!tiny) for (var o : List.of("Undo", "Redo")) {
            d.skin.button(d.c, new Rect(x, y, 50, 18), o, reader ? Skin.Press.OFF : Skin.Press.REST, false);
            x += 54;
        }
        String[] keys = {"Mole", "°C", "bar", "/h"};
        int kw = 40, strip = 4 * kw + 18, kx = d.w - 10 - strip;
        d.skin.softKeys(d.c, new Rect(kx - 4, d.h - 31, strip + 8, 26));
        for (int i = 0; i < 4; i++) d.skin.softKey(d.c, new Rect(kx + i * (kw + 6), y, kw, 18), keys[i], false);
    }

    static void screen(GuiPrototypes.Door d, Layout layout, Show show) {
        boolean tiny = layout == Layout.TINY, compact = layout == Layout.COMPACT || tiny;
        String warning = "1 warning: P-102 takes its liquid at the bubble point.";
        String status = switch (show) {
            case MISSING -> "H-101: this version has no such equipment. Replace it to solve what lies downstream.";
            case RUNNING -> "Running, 3.1 s: pass 2 of at most 30, T-101 from its last state.";
            case RECYCLE -> "Steady state in 4 passes, 2.3 s: 25 streams, 10 units, mass gap 0.00 %, H-101 84.50 MW. " + warning;
            case VIEWER -> "Read only: Alex is editing this flowsheet. Shown: the last run saved on the block, steady state in 2 passes.";
            case RECONNECT -> "S-8: pick a port for this end. Right click or Esc puts it back.";
            default -> compact ? "Steady state in 2 passes, 3.6 s. " + warning : "Steady state in 2 passes, 3.6 s: 21 streams, 7 units, mass gap 0.00 %, heat gap 0.00 %. " + warning;
        };
        int tone = show == Show.RUNNING || show == Show.RECONNECT || show == Show.VIEWER ? Palette.ACCENT : Palette.WARN;
        frame(d, 0, status, tone, compact, show == Show.VIEWER);
        int paletteH = tiny ? 40 : compact ? 58 : 80, bottom = d.h - 38;
        var box = d.well(new Rect(5, 71, d.w - 10, bottom - paletteH - 4 - 71));
        double scale = compact ? Math.min((box.w() - 4) / 700.0, (box.h() - 4) / 300.0) : 1;
        canvas(d, box, scale, show);
        palette(d, new Rect(5, bottom - paletteH, d.w - 10, paletteH), layout, show, 2);
        if (show.panel()) panel(d, box, show);
        footer(d, show, tiny);
        int ox = box.x() + 2, oy = box.y() + 2;
        if (show == Show.INTERACTION) {
            // The drag's path from the tile to the symbol it carries.
            int tile = box.x() + 2 + 68 + 33, top = bottom - paletteH + 22;
            dashes(d.c, tile, top, ox + 138, oy + 168, Palette.TEXT);
        }
        if (show == Show.NOTES) {
            note(d.c, 372, 38, 1);
            note(d.c, 718, 38, 2);
            note(d.c, d.w - 22, 64, 3);
            note(d.c, ox + 200, oy + 128, 4);
            note(d.c, ox + 36, oy + 68, 5);
            note(d.c, ox + 338, oy + 282, 6);
            note(d.c, ox + 304, oy + 200, 7);
            note(d.c, ox + 400, oy + 106, 8);
            note(d.c, d.w - 30, bottom - 30, 9);
            note(d.c, 298, d.h - 18, 10);
        }
        if (show == Show.MISSING) {
            note(d.c, ox + 304, oy + 200, 1);
            note(d.c, d.w - 22, 64, 2);
        }
    }

    /** A dashed path up from ({@code x0}, {@code y0}), then across to ({@code x1}, {@code y1}): a gesture, not part of the screen. */
    static void dashes(Canvas c, int x0, int y0, int x1, int y1, int colour) {
        for (int y = Math.min(y0, y1); y < Math.max(y0, y1); y += 6) c.fill(x0, y, x0 + 1, Math.min(y + 3, Math.max(y0, y1)), colour);
        for (int x = Math.min(x0, x1); x < Math.max(x0, x1); x += 6) c.fill(x, y1, Math.min(x + 3, Math.max(x0, x1)), y1 + 1, colour);
    }

    /** The grid, the sample flowsheet, the zoom keys. */
    static void canvas(GuiPrototypes.Door d, Rect box, double scale, Show show) {
        var c = d.c;
        c.clip(box);
        int step = Math.max(8, (int) Math.round(16 * scale));
        for (int y = box.y() + step / 2; y < box.bottom(); y += step)
            for (int x = box.x() + step / 2; x < box.right(); x += step) c.fill(x, y, x + 1, y + 1, 0x24FFFFFF);
        // The Fit key's view is centred, so the sample clears the zoom keys.
        int left = scale < 1 ? (int) Math.round((box.w() - 700 * scale) / 2) : 2;
        var p = new FlowsheetPainter(c, box.x() + left, box.y() + 2, scale, 1);
        Sample.draw(d, p, show);
        c.unclip();
        // The zoom keys in their tray, at the canvas's top left, where the sample leaves room.
        int kw = 26, tray = 3 * kw + 8, x = box.x() + 5;
        d.skin.softKeys(c, new Rect(x - 3, box.y() + 2, tray + 6, 21));
        String[] keys = {"-", "+", "Fit"};
        for (int i = 0; i < 3; i++) d.skin.softKey(c, new Rect(x + i * (kw + 4), box.y() + 5, kw, 15), keys[i], false);
    }

    /** The panel's width over the canvas: a door column and its margins. */
    static final int PANEL = 182;

    /** The selection's panel: a door over the glass's right end, so the sheet under it does not move. */
    static void panel(GuiPrototypes.Door d, Rect box, Show show) {
        int x = box.right() + 3 - PANEL, y = box.y() - 3;
        d.c.fill(x, y, box.right() + 3, box.bottom() + 3, 0xFF000000);
        d.skin.page(d.c, new Rect(x + 1, y, PANEL - 1, box.h() + 6));
        selection(d, x + 7, y + 5, PANEL - 18, show);
    }

    /** What the panel holds: a unit's readings, a feed's settings, a made stream's properties, or a placeholder's keys. */
    static void selection(GuiPrototypes.Door d, int x, int y, int w, Show show) {
        var selected = new ArrayList<Column.Row>();
        selected.add(new Column.Gap(20));
        String name = "E-101";
        var values = Map.<String, String>of();
        switch (show) {
            case MISSING -> {
                name = "H-101";
                selected.add(new Column.Line("Removed equipment", Palette.WARN));
                selected.add(new Column.Buttons(List.of(new Column.Button("replace", "Replace", true, null), new Column.Button("delete", "Delete", true, null))));
            }
            case RECYCLE -> {
                name = "TEE-1";
                selected.add(new Column.Line("Splitter", Palette.MUTED));
                selected.add(meter("To S-12", "15.0", "%"));
                selected.add(meter("S-12 kmol/h", "136.9", ""));
                selected.add(meter("RESIDUE kmol/h", "775.7", ""));
                selected.add(new Column.Buttons(List.of(new Column.Button("open", "Open", true, null), new Column.Button("delete", "Delete", true, null))));
            }
            case FEED -> {
                name = "CRUDE";
                selected.add(new Column.Line("Feed stream, to P-101", Palette.MUTED));
                selected.add(entry("t", "Temperature", "°C"));
                selected.add(entry("p", "Pressure", "bar"));
                selected.add(entry("f", "Flow", "kmol/h"));
                selected.add(new Column.Line("Crude oil, 19 components", Palette.MUTED));
                selected.add(new Column.Buttons(List.of(new Column.Button("open", "Open", true, null), new Column.Button("delete", "Delete", true, null))));
                values = Map.of("t", "25.0", "p", "8.00", "f", "2652.3");
            }
            case STREAM -> {
                name = "S-5";
                var s = stream("S-5");
                selected.add(new Column.Line("From E-101 to H-101", Palette.MUTED));
                selected.add(meter("Temperature", s.t(), "°C"));
                selected.add(meter("Pressure", s.p(), "bar"));
                selected.add(meter("Vapour", s.vapour(), "%"));
                selected.add(meter("Flow", s.kmol(), "kmol/h"));
                selected.add(meter("Mass flow", s.tonnes(), "t/h"));
                selected.add(meter("Density", s.density(), "kg/m³"));
                selected.add(new Column.Buttons(List.of(new Column.Button("open", "Open", true, null), new Column.Button("copy", "Copy", true, null))));
            }
            default -> {
                selected.add(new Column.Line("Heat exchanger", Palette.MUTED));
                selected.add(meter("Duty", "25.26", "MW"));
                selected.add(meter("Tube out", "218.4", "°C"));
                selected.add(meter("Shell out", "200.0", "°C"));
                selected.add(meter("Approach", "46.7", "K"));
                selected.add(new Column.Buttons(List.of(new Column.Button("open", "Open", true, null), new Column.Button("delete", "Delete", true, null))));
            }
        }
        var column = d.column(List.of(new Column.Group("Selected", "What is selected on the canvas: a unit or a stream", selected)), x, y, w, values);
        // The name's box across the column: the kit's text row, still to build (the row left for it here is a gap).
        for (var q : column.rows()) if (q.row() instanceof Column.Gap) {
            d.skin.field(d.c, new Rect(q.rect().x(), q.rect().y(), q.rect().w(), 18), name, "", false);
            return;
        }
    }

    /** An option sheet: the sample at true size, a few symbols at twice that, every tile. */
    static void iconSheet(GuiPrototypes.Door d, SimulatorIcons.Style option) {
        var before = SimulatorIcons.style;
        SimulatorIcons.style = option;
        d.skin.title(d.c, 10, 8, "Sample at true size, zoomed symbols, every tile");
        var box = d.well(new Rect(5, 26, d.w - 10, 318));
        canvas(d, box, 1, Show.PLAIN);
        var zoom = new FlowsheetPainter(d.c, box.x() + 716, box.y() + 8, 2, 1);
        glyph(zoom, Glyph.EXCHANGER, 0, 0, 64, 28, Look.TEAL);
        glyph(zoom, Glyph.AIR_COOLER, 0, 38, 60, 32, Look.YELLOW);
        glyph(zoom, Glyph.PUMP, 0, 80, 30, 34, Look.TEAL);
        glyph(zoom, Glyph.BOOT_DRUM, 38, 84, 64, 28, Look.TEAL);
        var tiles = d.well(new Rect(5, 346, d.w - 10, 188));
        int k = 0, across = (tiles.w() - 4) / 68;
        for (var row : TILES)
            for (var t : row) {
                tile(d, tiles.x() + 2 + k % across * 68, tiles.y() + k / across * 60, 66, 58, t, true, false);
                k++;
            }
        SimulatorIcons.style = before;
    }

    // ---- The palette ----

    record Tile(Glyph glyph, String name, boolean on) {}

    static final List<String> CATEGORIES = List.of("Streams", "Piping", "Vessels", "Heat", "Machines", "Reactors", "Solid fuel");
    static final List<List<Tile>> TILES = List.of(
            List.of(new Tile(Glyph.FEED, "Feed stream", true), new Tile(Glyph.PRODUCT, "Product stream", true)),
            List.of(new Tile(Glyph.VALVE, "Control valve", true), new Tile(Glyph.MIXER, "Mixer", true), new Tile(Glyph.SPLITTER, "Splitter", true), new Tile(Glyph.FILTER, "In-line filter", true)),
            List.of(new Tile(Glyph.COLUMN, "Column", true), new Tile(Glyph.DRUM, "Drum", true), new Tile(Glyph.TANK, "Storage tank", true), new Tile(Glyph.ADSORBER, "Adsorber", false), new Tile(Glyph.CYCLONE, "Cyclone", false)),
            List.of(new Tile(Glyph.EXCHANGER, "Heat exchanger", true), new Tile(Glyph.KETTLE, "Kettle reboiler", true), new Tile(Glyph.AIR_COOLER, "Air cooler", true), new Tile(Glyph.HEATER, "Fired heater", true)),
            List.of(new Tile(Glyph.PUMP, "Pump", true), new Tile(Glyph.COMPRESSOR, "Compressor", true), new Tile(Glyph.EJECTOR, "Steam ejector", false), new Tile(Glyph.TURBINE, "Steam turbine", false)),
            List.of(new Tile(Glyph.REACTOR, "Stirred reactor", false), new Tile(Glyph.TRICKLE, "Trickle bed", false), new Tile(Glyph.EBULLATED, "Ebullated bed", false), new Tile(Glyph.FCC, "FCC converter", false),
                    new Tile(Glyph.COKER, "Delayed coker", false)),
            List.of(new Tile(Glyph.CFB, "CFB boiler", false), new Tile(Glyph.FLUID_GAS, "Fluid-bed gasifier", false), new Tile(Glyph.GRATE, "Grate boiler", false), new Tile(Glyph.FIXED_GAS, "Fixed-bed gasifier", false)));

    static void palette(GuiPrototypes.Door d, Rect r, Layout layout, Show show, int selected) {
        boolean tiny = layout == Layout.TINY, compact = layout == Layout.COMPACT || tiny;
        var tiles = show == Show.VIEWER ? TILES.get(selected).stream().map(t -> new Tile(t.glyph(), t.name(), false)).toList() : TILES.get(selected);
        if (tiny) {
            // One strip: the category as one key that steps, then symbols only.
            d.skin.button(d.c, new Rect(r.x() + 5, r.y() + 3, 54, 18), CATEGORIES.get(selected), Skin.Press.CURRENT, false);
            d.skin.button(d.c, new Rect(r.x() + 5, r.y() + 22, 26, 15), "<", Skin.Press.REST, false);
            d.skin.button(d.c, new Rect(r.x() + 33, r.y() + 22, 26, 15), ">", Skin.Press.REST, false);
            var box = d.well(new Rect(r.x() + 62, r.y(), r.w() - 62, r.h()));
            for (int i = 0; i < tiles.size() && (i + 1) * 44 <= box.w(); i++) tile(d, box.x() + i * 44, box.y(), 42, box.h(), tiles.get(i), false, false);
            return;
        }
        int gap = 3, n = CATEGORIES.size(), kw = (r.w() - 10 - (n - 1) * gap) / n;
        for (int i = 0; i < n; i++)
            d.skin.button(d.c, new Rect(r.x() + 5 + i * (kw + gap), r.y(), kw, 18), CATEGORIES.get(i), i == selected ? Skin.Press.CURRENT : Skin.Press.REST, false);
        var box = d.well(new Rect(r.x(), r.y() + 20, r.w(), r.h() - 20));
        int tw = compact ? 48 : 66;
        for (int i = 0; i < tiles.size() && (i + 1) * (tw + 2) <= box.w(); i++) tile(d, box.x() + 2 + i * (tw + 2), box.y(), tw, box.h(), tiles.get(i), !compact, show == Show.INTERACTION && i == 1);
    }

    /** The whole list: each category on its glass. */
    static void paletteSheet(GuiPrototypes.Door d) {
        d.skin.title(d.c, 10, 10, "Palette");
        int y = 26, h = 61;
        for (int i = 0; i < CATEGORIES.size(); i++, y += h) {
            var box = d.well(new Rect(5, y, d.w - 10, h - 2));
            d.c.text(CATEGORIES.get(i), box.x() + 5, box.y() + box.h() / 2 - 4, Palette.TEXT);
            var tiles = TILES.get(i);
            for (int k = 0; k < tiles.size(); k++) tile(d, box.x() + 64 + k * 68, box.y(), 66, box.h(), tiles.get(k), true, false);
        }
    }

    /** A tile: its symbol, its name in two lines at most; an unlit tile has no process model yet; a tile being dragged shows ringed. */
    static void tile(GuiPrototypes.Door d, int x, int y, int w, int h, Tile t, boolean captions, boolean dragged) {
        var c = d.c;
        var p = new FlowsheetPainter(c, 0, 0, 1, 1);
        var look = t.on() ? new Look(FlowsheetPainter.BODY, Palette.ACCENT, Palette.TEXT) : new Look(0xFF20292F, 0xFF46545C, 0xFF72828C);
        if (dragged) c.outline(x, y + 1, w, h - 2, Palette.TEXT);
        int top = y + 2, room = captions ? h - 24 : h - 6;
        int[] size = t.glyph().tile(w - 10, room);
        glyph(p, t.glyph(), x + (w - size[0]) / 2, top + (room - size[1]) / 2, size[0], size[1], look);
        if (!captions) return;
        var lines = d.m.wrap(t.name(), w - 2);
        int ty = y + h - 1 - lines.size() * 9;
        for (var line : lines) {
            c.text(line, x + (w - d.m.width(line)) / 2, ty, t.on() ? Palette.TEXT : 0xFF72828C);
            ty += 9;
        }
    }

    // ---- The symbols: drawn in a box, at any size, through the shipped painter ----

    enum Glyph {
        FEED(40, 18), PRODUCT(40, 18), PUMP(30, 34), COMPRESSOR(34, 34), TURBO(34, 34), VALVE(24, 24), MIXER(22, 26), SPLITTER(22, 26), FILTER(30, 26), EXCHANGER(64, 28), KETTLE(70, 34), AIR_COOLER(60, 32),
        HEATER(58, 88), CABIN(74, 74), DRUM(28, 60), LYING(64, 28), BOOT_DRUM(64, 28), TANK(54, 42), SPHERE(44, 44), ADSORBER(26, 60), CYCLONE(26, 60), COLUMN(44, 248), EJECTOR(50, 20), TURBINE(34, 34), CONDENSING(34, 34),
        REACTOR(34, 52), TRICKLE(26, 60), EBULLATED(26, 60), FCC(48, 56), COKER(44, 56), CFB(50, 60), GRATE(50, 60), FLUID_GAS(30, 60), FIXED_GAS(30, 60), COOLING_TOWER(50, 60), MISSING(58, 88);

        final int w, h;

        Glyph(int w, int h) {
            this.w = w;
            this.h = h;
        }

        /** The box a tile gives it: its natural shape, a column's squatter, fitted to {@code room}. */
        int[] tile(int roomW, int room) {
            int gw = this == COLUMN ? 16 : w, gh = this == COLUMN ? 44 : h, feet = this == LYING ? 7 : 0;
            double s = Math.min(1, Math.min(roomW / (double) gw, room / (double) (gh + feet)));
            return new int[]{(int) Math.round(gw * s), (int) Math.round(gh * s)};
        }
    }

    /** {@code g} in the design box from ({@code x}, {@code y}), {@code w} by {@code h}: vector shapes, painted straight onto the image. */
    static void glyph(FlowsheetPainter p, Glyph g, int x, int y, int w, int h, Look look) {
        var c = p.canvas();
        int x0 = p.x(x), y0 = p.y(y), x1 = p.x(x + w), y1 = p.y(y + h), pw = x1 - x0, ph = y1 - y0;
        if (g == Glyph.MISSING) {
            p.box(x0, y0, x1, y1, Look.RED);
            c.clip(new Rect(x0 + 1, y0 + 1, pw - 2, ph - 2));
            for (int k = -ph; k < pw; k += 8) p.line(x0 + k, y1, x0 + k + ph, y0, 0xFF7A2A2A);
            c.unclip();
            return;
        }
        SimulatorIcons.paint(((HeatDiagramRender.Java2dCanvas) c).graphics(), g, x0, y0, pw, ph, look);
    }

    /** A triangle filling its box, its point right ({@code right}) or left. */
    static void triangle(Canvas c, int x0, int y0, int x1, int y1, boolean right, int colour) {
        triangle(c, x0, y0, x1, y1, right, colour, false);
    }

    /** {@code up}: a flame, its point at the top ({@code right} ignored). */
    static void triangle(Canvas c, int x0, int y0, int x1, int y1, boolean right, int colour, boolean up) {
        int w = Math.max(1, x1 - x0), h = Math.max(1, y1 - y0);
        if (up) {
            for (int y = y0; y < y1; y++) {
                int half = (y - y0) * w / (2 * h);
                c.fill((x0 + x1) / 2 - half, y, (x0 + x1) / 2 + half + 1, y + 1, colour);
            }
            return;
        }
        for (int x = x0; x < x1; x++) {
            int half = (right ? x1 - x : x - x0) * h / (2 * w);
            c.fill(x, (y0 + y1) / 2 - half, x + 1, (y0 + y1) / 2 + half + 1, colour);
        }
    }

    static void triangleEdge(Canvas c, int x0, int y0, int x1, int y1, boolean right, int colour) {
        int w = Math.max(1, x1 - x0), h = Math.max(1, y1 - y0);
        for (int x = x0; x < x1; x++) {
            int half = (right ? x1 - x : x - x0) * h / (2 * w), my = (y0 + y1) / 2;
            c.fill(x, my - half, x + 1, my - half + 1, colour);
            c.fill(x, my + half, x + 1, my + half + 1, colour);
        }
        c.fill(right ? x0 : x1 - 1, y0, (right ? x0 : x1 - 1) + 1, y1, colour);
    }

    // ---- The sample flowsheet: the owner's crude unit, the pumparounds heat the crude, the residue next, the furnace sets the final temperature ----

    /** A stream's end as a pill: a feed's in teal, a product's plain. */
    static void pill(FlowsheetPainter p, String name, int x, int y, int w, boolean feed, int colour) {
        p.pill(x, y, w, 9);
        var c = p.canvas();
        if (feed) c.outline(p.x(x), p.y(y - 9), p.x(x + w) - p.x(x), p.y(y + 9) - p.y(y - 9), Palette.ACCENT);
        p.fitted(name, x + w / 2, y, w, feed ? Palette.ACCENT : colour);
    }

    static final class Sample {
        static final Look SOLVED = Look.TEAL, WARN = Look.YELLOW, NONE = new Look(FlowsheetPainter.BODY, Palette.LINE, Palette.TEXT), ACTIVE = new Look(FlowsheetPainter.BODY, Palette.TEXT, Palette.TEXT);
        static final int PLATE = 0xFF10181D;

        record Unit(String name, Glyph glyph, int x, int y, int w, int h, Look look, int tagX, int tagY) {}

        record Wire(String name, int[] points, int lx, int ly) {}

        record Pill(String name, int x, int y, int w, boolean feed) {}

        static void draw(GuiPrototypes.Door d, FlowsheetPainter p, Show show) {
            boolean missing = show == Show.MISSING, running = show == Show.RUNNING, recycle = show == Show.RECYCLE;
            Look done = missing ? NONE : SOLVED, later = missing || running ? NONE : SOLVED;
            var units = new ArrayList<>(List.of(new Unit("P-101", Glyph.PUMP, 84, 75, 30, 34, SOLVED, 99, 68), new Unit("E-101", Glyph.EXCHANGER, 130, 216, 64, 28, done, 162, 209),
                    new Unit("H-101", missing ? Glyph.MISSING : Glyph.HEATER, 232, 186, 58, 88, done, 261, 179), new Unit("V-101", Glyph.VALVE, 340, 216, 24, 24, done, 352, 209),
                    new Unit("T-101", Glyph.COLUMN, 438, 24, 44, 248, running ? ACTIVE : done, 508, 262), new Unit("P-102", Glyph.PUMP, 560, 160, 30, 34, missing || running ? NONE : WARN, 575, 201),
                    new Unit("E-102", Glyph.AIR_COOLER, 62, 266, 60, 32, later, 92, 259)));
            var wires = new ArrayList<>(List.of(new Wire("S-1", new int[]{114, 88, 438, 88}, 276, 80), new Wire("S-2", new int[]{438, 102, 420, 102, 420, 122, 438, 122}, 420, 112),
                    new Wire("S-3", new int[]{438, 136, 420, 136, 420, 156, 438, 156}, 420, 146), new Wire("S-4", new int[]{438, 170, 114, 170, 114, 230, 130, 230}, 276, 162),
                    new Wire("S-5", new int[]{194, 230, 212, 230, 212, 252, 232, 252}, 212, 241), new Wire("S-6", new int[]{290, 228, 340, 228}, 315, 220), new Wire("S-7", new int[]{364, 228, 438, 228}, 401, 220),
                    new Wire("", new int[]{384, 252, 438, 252}, 0, 0), new Wire("", new int[]{376, 38, 410, 38, 410, 52, 438, 52}, 0, 0), new Wire("", new int[]{438, 66, 376, 66}, 0, 0),
                    new Wire("", new int[]{460, 24, 460, 12, 642, 12}, 0, 0), new Wire("", new int[]{482, 46, 642, 46}, 0, 0), new Wire("", new int[]{482, 80, 642, 80}, 0, 0), new Wire("", new int[]{482, 126, 642, 126}, 0, 0),
                    new Wire("S-8", new int[]{482, 173, 560, 173}, 521, 165), new Wire("", new int[]{590, 173, 642, 173}, 0, 0), new Wire("", new int[]{482, 226, 642, 226}, 0, 0),
                    new Wire("S-9", new int[]{460, 272, 460, 292, 178, 292, 178, 244}, 236, 292), new Wire("S-10", new int[]{146, 244, 146, 274, 122, 274}, 146, 258)));
            var pills = new ArrayList<>(List.of(new Pill("STEAM", 332, 252, 52, true), new Pill("CWS", 330, 38, 46, true), new Pill("CWR", 330, 66, 46, false), new Pill("OFFGAS", 642, 12, 56, false),
                    new Pill("NAPHTHA", 642, 46, 56, false), new Pill("WATER", 642, 80, 56, false), new Pill("KERO", 642, 126, 56, false), new Pill("DIESEL", 642, 173, 56, false), new Pill("AGO", 642, 226, 56, false)));
            if (recycle) {
                // The material loop: a share of the cooled residue, up the sheet's left edge and back to the crude ahead of P-101.
                units.add(new Unit("M-101", Glyph.MIXER, 50, 75, 22, 26, SOLVED, 61, 68));
                units.add(new Unit("TEE-1", Glyph.SPLITTER, 30, 196, 22, 26, SOLVED, 41, 189));
                units.add(new Unit("P-103", Glyph.PUMP, 62, 137, 30, 34, SOLVED, 77, 130));
                wires.addAll(List.of(new Wire("", new int[]{42, 81, 50, 81}, 0, 0), new Wire("S-14", new int[]{72, 88, 84, 88}, 0, 0), new Wire("S-11", new int[]{62, 274, 18, 274, 18, 209, 30, 209}, 18, 241),
                        new Wire("", new int[]{52, 215, 60, 215}, 0, 0), new Wire("S-12", new int[]{52, 203, 56, 203, 56, 150, 62, 150}, 56, 178),
                        new Wire("S-13", new int[]{92, 150, 100, 150, 100, 118, 38, 118, 38, 95, 50, 95}, 68, 118)));
                pills.add(new Pill("CRUDE", 4, 81, 38, true));
                pills.add(new Pill("RESIDUE", 60, 215, 48, false));
            } else {
                wires.addAll(List.of(new Wire("", new int[]{62, 88, 84, 88}, 0, 0), new Wire("", new int[]{62, 274, 54, 274}, 0, 0)));
                pills.add(new Pill("CRUDE", 10, 88, 52, true));
                pills.add(new Pill("RESIDUE", 4, 274, 50, false));
            }
            for (var w : wires) {
                // The stream being reconnected is drawn by the gesture, from its port to the cursor.
                if (show == Show.RECONNECT && w.name().equals("S-8")) continue;
                p.wire(w.points(), true, false, w.name().equals("S-9") ? Palette.ACCENT : show == Show.STREAM && w.name().equals("S-5") ? 0xFFFFFFFF : Palette.MUTED);
            }
            for (var u : units) {
                glyph(p, u.glyph(), u.x(), u.y(), u.w(), u.h(), u.look());
                boolean ringed = u.name().equals(running ? "T-101" : missing ? "H-101" : recycle ? "TEE-1" : show == Show.UNIT ? "E-101" : "");
                if (ringed) p.canvas().outline(p.x(u.x()) - 3, p.y(u.y()) - 3, p.px(u.w()) + 6, p.px(u.h()) + 6, Palette.TEXT);
                if (u.glyph() == Glyph.MISSING && p.labels()) {
                    p.fitted("Old heater", u.x() + u.w() / 2, u.y() + u.h() / 2 - 6, u.w(), Look.RED.text());
                    p.fitted("(removed)", u.x() + u.w() / 2, u.y() + u.h() / 2 + 6, u.w(), Look.RED.text());
                }
                if (p.labels()) p.tag(u.name(), u.tagX(), u.tagY(), 0.5f, u.look() == WARN ? Palette.WARN : Palette.TEXT, PLATE);
            }
            // Each wire's ends are ports, greyed because connected; a mixer's and a splitter's arms are their own.
            for (var w : wires) {
                if (show == Show.RECONNECT && w.name().equals("S-8")) continue;
                int[] q = w.points();
                p.square(q[0], q[1], 5, PortColours.CONNECTED);
                p.square(q[q.length - 2], q[q.length - 1], 5, PortColours.CONNECTED);
                if (show == Show.STREAM && w.name().equals("S-5"))
                    for (int k : new int[]{0, q.length - 2}) p.canvas().outline(p.x(q[k]) - 4, p.y(q[k + 1]) - 4, 9, 9, 0xFFFFFFFF);
            }
            // The block's connection points, named on the block.
            if (p.labels())
                for (var mark : List.of(new Object[]{"Cond.", 59}, new Object[]{"PA 1", 95}, new Object[]{"PA 2", 129}, new Object[]{"PA 3", 163}, new Object[]{"Feed", 228}, new Object[]{"Steam", 252}))
                    p.tag((String) mark[0], 460, (Integer) mark[1], 0.5f, Palette.TEXT, PLATE);
            if (p.labels())
                for (var w : wires)
                    if (!w.name().isEmpty() && !w.name().equals("S-14") && !(show == Show.INTERACTION && w.name().equals("S-9")) && !(show == Show.RECONNECT && w.name().equals("S-8"))) p.tag(w.name(), w.lx(), w.ly(), 0.5f, w.name().equals("S-9") ? Palette.ACCENT : Palette.MUTED, PLATE);
            tear(p, 320, 292);
            for (var e : pills) pill(p, e.name(), e.x(), e.y(), e.w(), e.feed(), missing && !e.feed() ? Palette.MUTED : Palette.TEXT);
            if (show == Show.FEED) {
                // The selected feed: its line and its pill ringed.
                p.canvas().outline(p.x(10) - 3, p.y(88) - 12, p.px(52) + 6, 24, 0xFFFFFFFF);
            }
            if (show == Show.INTERACTION) interaction(d, p);
            if (show == Show.RECONNECT) reconnect(d, p);
        }

        /** The loop's tear: a small diamond with a T on its line. */
        static void tear(FlowsheetPainter p, int x, int y) {
            var c = p.canvas();
            int cx = p.x(x), cy = p.y(y), r = Math.max(4, p.px(7));
            for (int dy = -r; dy <= r; dy++) c.hLine(cx - (r - Math.abs(dy)), cx + (r - Math.abs(dy)), cy + dy, Palette.ACCENT);
            if (p.labels()) c.text("T", cx - 2, cy - 3, 0xFF0B1216);
        }

        /** Right click on S-8 near P-102: its end follows the cursor, the old port shows free, the port that can take it is ringed. */
        static void reconnect(GuiPrototypes.Door d, FlowsheetPainter p) {
            var c = p.canvas();
            // The fixed end stays on T-101's draw; the lifted end follows the cursor, up towards the spare pump.
            p.square(482, 173, 5, PortColours.CONNECTED);
            p.wire(new int[]{482, 173, 530, 173, 530, 146, 586, 146}, false, false, 0xFFFFFFFF);
            c.outline(p.x(586) - 3, p.y(146) - 3, 7, 7, 0xFFFFFFFF);
            p.tag("S-8", 506, 165, 0.5f, 0xFFFFFFFF, PLATE);
            // P-102's suction is free again; its pump stays, with DIESEL on its outlet.
            p.square(560, 173, 5, PortColours.LIQUID);
            // The new pump: its suction takes the end, so it is ringed; its discharge cannot, so it is not.
            glyph(p, Glyph.PUMP, 600, 133, 30, 34, NONE);
            p.tag("P-103", 616, 164, 0.5f, Palette.TEXT, PLATE);
            c.outline(p.x(600) - 7, p.y(146) - 7, 15, 15, 0xFFFFFFFF);
            p.square(600, 146, 5, PortColours.LIQUID);
            p.square(630, 146, 5, PortColours.LIQUID);
            note(c, p.x(512), p.y(188), 7);
            note(c, p.x(652), p.y(146), 8);
        }

        /** The sheet of gestures: a dragged tile, a stream being drawn with the port that can take it, a name being typed, a port's hover. */
        static void interaction(GuiPrototypes.Door d, FlowsheetPainter p) {
            var c = p.canvas();
            // 1: a drum dragged out of the palette, over the canvas; its tag in place.
            glyph(p, Glyph.DRUM, 124, 112, 28, 56, new Look(0x6033434D, Palette.TEXT, Palette.TEXT));
            p.tag("D-101", 138, 105, 0.5f, Palette.TEXT, PLATE);
            note(c, p.x(106), p.y(126), 1);
            // 2: a stream drawn from T-101's free draw port to the cursor; a pump that is not connected yet has its inlet ringed.
            p.square(482, 146, 5, PortColours.BULK);
            glyph(p, Glyph.PUMP, 600, 133, 30, 34, NONE);
            p.tag("P-103", 616, 164, 0.5f, Palette.TEXT, PLATE);
            p.wire(new int[]{482, 146, 578, 146}, false, false, Palette.TEXT);
            c.outline(p.x(578) - 3, p.y(146) - 3, 7, 7, Palette.TEXT);
            c.outline(p.x(600) - 7, p.y(146) - 7, 15, 15, 0xFFFFFFFF);
            p.square(600, 146, 5, PortColours.LIQUID);
            p.square(630, 146, 5, PortColours.LIQUID);
            p.tag("S-11", 530, 138, 0.5f, Palette.TEXT, PLATE);
            note(c, p.x(556), p.y(136), 2);
            // 3: dropping on empty canvas makes a product pill, as AGO is.
            note(c, p.x(708), p.y(226), 3);
            // 4: a name being typed in place, on the line it names.
            int nx = p.x(204), ny = p.y(292) - 8;
            d.skin.field(c, new Rect(nx, ny, 66, 15), "RES-HOT", "", false);
            c.text("RES-HOT", nx + 4, ny + 3, 0xFF000000);
            c.fill(nx + 4 + d.m.width("RES-HOT"), ny + 2, nx + 5 + d.m.width("RES-HOT"), ny + 12, 0xFF000000);
            note(c, nx + 78, ny - 4, 4);
            // 5: double click.
            note(c, p.x(304), p.y(200), 5);
            // 6: the hover on the crude's port on PA 1.
            c.outline(p.x(438) - 5, p.y(88) - 5, 11, 11, 0xFFFFFFFF);
            int hx = p.x(232), hy = p.y(98), hw = 176;
            c.fill(hx, hy, hx + hw, hy + 42, 0xF01C2630);
            c.outline(hx, hy, hw, 42, Palette.ACCENT);
            c.text("T-101, PA 1 cooler, tube inlet", hx + 5, hy + 4, Palette.TEXT);
            c.text("Heat into this stream: 12.84 MW", hx + 5, hy + 16, Palette.MUTED);
            c.text("Left free, a utility takes it", hx + 5, hy + 28, Palette.MUTED);
            note(c, hx - 11, hy + 20, 6);
        }
    }

    // ---- A stream's door: the world's Fluid Generator page ----

    /** The device frame: title, tabs, the status once, the door column in its view, the divider, the glass; returns the glass's inner box. */
    static Rect door(GuiPrototypes.Door d, String title, String[] tabs, int tab, String status, int tone, List<Column.Row> rows, Map<String, String> values) {
        d.title(title);
        var slots = FluidGuiLayout.tabs(d.w, tabs.length);
        for (int i = 0; i < tabs.length; i++) d.skin.button(d.c, new Rect(slots.get(i).x(), 29, slots.get(i).width(), 18), tabs[i], i == tab ? Skin.Press.CURRENT : Skin.Press.REST, false);
        d.skin.status(d.c, new Rect(10, 59, d.w - 20, 9), status, tone);
        int side = FluidGuiLayout.side(d.w), top = FluidGuiLayout.DOOR_TOP, bottom = d.h - FluidGuiLayout.DOOR_FOOT;
        d.column(rows, 10, top, side - 10, values);
        d.skin.vDivider(d.c, side + 13, top, bottom);
        return d.well(new Rect(side + 15, 72, d.w - side - 20, d.h - 50 - 72));
    }

    /** The door's footer: Close, then {@code others}; the display keys on their tray. Entries take effect as typed, so there is no Apply. */
    static void unitFooter(GuiPrototypes.Door d, String... others) {
        d.skin.hDivider(d.c, 8, d.w - 8, d.h - 49);
        var slots = FluidGuiLayout.footer(d.w);
        d.skin.softKeys(d.c, FluidGuiLayout.softKeys(d.w, d.h));
        String[] keys = {"Mole", "°C", "bar", "/h"};
        int y = d.h - 27;
        d.skin.button(d.c, new Rect(slots.get(0).x(), y, slots.get(0).width(), 18), "Close", Skin.Press.REST, false);
        int x = slots.get(1).x();
        for (var o : others) {
            int w = Math.max(slots.get(1).width(), d.m.width(o) + 14);
            d.skin.button(d.c, new Rect(x, y, w, 18), o, Skin.Press.REST, false);
            x += w + 4;
        }
        for (int i = 0; i < 4; i++) d.skin.softKey(d.c, new Rect(slots.get(2 + i).x(), y, slots.get(2 + i).width(), 18), keys[i], false);
    }

    /** A meter; a unit wider than its place is the label's last word (the alignment rule). */
    static Column.Reading meter(String label, String value, String unit) {
        boolean wide = unit.length() > 4;
        return new Column.Reading(label.toLowerCase(Locale.ROOT), wide ? label + " " + unit : label, value, wide ? "" : unit, null);
    }

    static Column.Field entry(String key, String label, String unit) {
        boolean wide = unit.length() > 4;
        return new Column.Field(key, wide ? label + " " + unit : label, wide ? "" : unit, null);
    }

    static Column.Button key(String id, String text, boolean latched) {
        return new Column.Button(id, text, !latched, null, latched);
    }

    static Column.Share share(String label, double value, int colour) {
        return new Column.Share(label, f(1, value) + " %", value, colour);
    }

    /** The phase split by mole, as the shipped pages draw it. */
    static Column.Group phases(double organic, double aqueous, double gas, double solid) {
        return new Column.Group("Phases", null, List.of(new Column.Split(List.of(share("Organic", organic, 0xFFD0A85A), share("Aqueous", aqueous, 0xFF3377FF), share("Gas", gas, 0xFFF0F0F0), share("Solid", solid, 0xFF72828C)), null)));
    }

    static Column.Group nameGroup() {
        return new Column.Group("Name", null, List.of(new Column.Gap(20)));
    }

    /** The name's box across the door column: the kit's text row, still to build. */
    static void name(GuiPrototypes.Door d, String text) {
        var column = Column.place(List.of(nameGroup()), 10, FluidGuiLayout.DOOR_TOP, FluidGuiLayout.side(d.w) - 10, d.m, Column.Metrics.COMPACT);
        for (var p : column.rows()) if (p.row() instanceof Column.Gap) d.skin.field(d.c, new Rect(p.rect().x(), p.rect().y(), p.rect().w(), 18), text, "", false);
    }

    static void feedOverview(GuiPrototypes.Door d) {
        var rows = List.<Column.Row>of(nameGroup(),
                new Column.Group("Set point", null, List.of(entry("pressure", "Pressure", "bar"), entry("temperature", "Temperature", "°C"))),
                new Column.Group("Flow", null, List.of(new Column.Buttons(List.of(key("mole", "Mole", true), key("mass", "Mass", false), key("volume", "Volume", false))), entry("flow", "Flow", "kmol/h"))),
                phases(100, 0, 0, 0),
                new Column.Group("Readings", null, List.of(meter("Mass flow", "572.09", "t/h"), meter("Density", "861.1", "kg/m³"), meter("Mean weight", "215.7", "kg/kmol"))));
        var box = door(d, "Feed stream", new String[]{"Overview", "Composition", "Solids"}, 0, "Ready: liquid, 2652.3 kmol/h at 25.0 °C, 8.00 bar.", Palette.ACCENT, rows,
                Map.of("pressure", "8.00", "temperature", "25.0", "flow", "2652.3"));
        name(d, "CRUDE");
        GuiPrototypes.contents(d, box, 0, composition(CRUDE, 2652.3, "kmol/h"));
        unitFooter(d);
    }

    static void feedComposition(GuiPrototypes.Door d) {
        var rows = List.<Column.Row>of(new Column.Group("Mixture", null, List.of(new Column.Buttons(List.of(new Column.Button("templates", "Templates", true, null), new Column.Button("save", "Save as", true, null))))),
                new Column.Group("Clipboard", null, List.of(new Column.Line("Tank at 120, 64, -30", Palette.ACCENT), new Column.Line("40.0 °C, 1.01 bar, 19 parts", Palette.MUTED),
                        new Column.Buttons(List.of(new Column.Button("copy", "Copy", true, null), new Column.Button("paste", "Paste", true, null))),
                        new Column.Buttons(List.of(new Column.Button("paste_state", "Paste with T and P", true, null))))),
                new Column.Group("Total", null, List.of(meter("Parts", "19", ""), meter("Share", "100.0", "%"))));
        var box = door(d, "Feed stream", new String[]{"Overview", "Composition", "Solids"}, 1, "Pasted 19 components from a tank.", Palette.ACCENT, rows, Map.of());
        // The search box of the component dropdown, above the calculator's feed table.
        d.skin.field(d.c, new Rect(box.x() + 3, box.y() + 3, box.w() - 6, 16), "", "Add a component", false);
        var area = new Rect(box.x() + 2, box.y() + 24, box.w() - 8 - ScrollView.BAR, box.h() - 26);
        var feed = new ArrayList<CalculatorColumn.FeedRow>();
        var amounts = new ArrayList<String>();
        for (int i = 0; i < CRUDE.length; i++) {
            if (CRUDE[i] <= 0) continue;
            double kmol = CRUDE[i] / 100 * 2652.3;
            amounts.add(f(2, CRUDE[i]));
            feed.add(new CalculatorColumn.FeedRow(feed.size(), COMPONENTS[i], f(1, CRUDE[i]), f(1, kmol), f(2, kmol * WEIGHTS[i] / 1000)));
        }
        var table = CalculatorColumn.feedTable(List.of("Component", "Amount", "Mole %", "kmol/h", "t/h"), feed, area, d.m);
        d.c.clip(area);
        CalculatorColumn.draw(d.c, table, area, 0);
        for (int i = 0; i < table.entries().size(); i++) {
            var e = table.entries().get(i);
            if (e.amount().bottom() > area.bottom()) break;
            d.skin.field(d.c, e.amount(), amounts.get(i), "", false);
            d.skin.button(d.c, e.remove(), "x", Skin.Press.REST, false);
        }
        d.c.unclip();
        var view = new ScrollView();
        view.layout(new Rect(box.x(), area.y(), box.w(), area.h()), box.right() - ScrollView.BAR, table.height());
        view.drawBar(d.c, d.skin);
        unitFooter(d);
    }

    static void reservoirCopy(GuiPrototypes.Door d) {
        var in = new DeviceColumn.Input(Kind.RESERVOIR, true, false, true, "bar", "°C", "/s", DeviceColumn.Valve.NONE, null, false,
                new DeviceColumn.Level(0.63, "25200.0", "40000.0"), null, List.of(), GuiPrototypes.phases("volume", 25.2, 0, 14.8, 0),
                List.of(GuiPrototypes.meter("temperature", "40.0", "°C"), GuiPrototypes.meter("top_pressure", "1.01", "bar"), GuiPrototypes.meter("bottom_pressure", "1.25", "bar"),
                        GuiPrototypes.meter("net_flow", "-0.4", "kg/s"), GuiPrototypes.meter("mass", "21700.0", "kg")));
        d.title("Atmospheric tank");
        d.tabs(0, "Overview", "Outlets", "Datasheet");
        d.status("Copied: 19 components, 40.0 °C, 1.01 bar.", Palette.ACCENT);
        var box = GuiPrototypes.device(d, in, Map.of());
        GuiPrototypes.contents(d, box, 0, composition(CRUDE, 100600, "mol"));
        unitFooter(d, "Copy");
        var slots = FluidGuiLayout.footer(d.w);
        note(d.c, slots.get(1).x() + slots.get(1).width() + 12, d.h - 18, 1);
    }

    /** A product: every value a meter, the composition on the glass. */
    static void productDoor(GuiPrototypes.Door d) {
        var s = stream("NAPHTHA");
        var rows = List.<Column.Row>of(nameGroup(), new Column.Group("Conditions", null, List.of(meter("Pressure", s.p(), "bar"), meter("Temperature", s.t(), "°C"))),
                new Column.Group("Flow", null, List.of(meter("Mole flow", s.kmol(), "kmol/h"), meter("Mass flow", s.tonnes(), "t/h"), meter("Liquid volume", s.volume(), "m³/h"))), phases(100, 0, 0, 0),
                new Column.Group("Readings", null, List.of(meter("Density", s.density(), "kg/m³"), meter("Viscosity", s.liquidMu(), "mPa s"), meter("Mean weight", s.weight(), "kg/kmol"), meter("Enthalpy", s.enthalpy(), "kJ/kg"))));
        var box = door(d, "Product stream", new String[]{"Overview", "Solids"}, 0, "From T-101, its liquid distillate.", Palette.ACCENT, rows, Map.of());
        name(d, s.name());
        GuiPrototypes.contents(d, box, 0, composition(NAPHTHA, 585.5, "kmol/h"));
        unitFooter(d, "Copy");
    }

    // ---- Unit doors ----

    static final Map<String, String> PORTS = Map.ofEntries(Map.entry("port:TUBE_INLET", "153.3 °C"), Map.entry("port:TUBE_OUTLET", "218.4 °C"), Map.entry("port:SHELL_INLET", "300.2 °C"), Map.entry("port:SHELL_OUTLET", "200.0 °C"),
            Map.entry("port:CONVECTION_INLET", "218.4 °C"), Map.entry("port:RADIANT_OUTLET", "365.0 °C"));

    static HeatFlowsheet.Data data(String duty) {
        return new HeatFlowsheet.Data() {
            @Override
            public String value(HeatDiagram.Value v) {
                for (var s : v.sources()) {
                    switch (v.show()) {
                        case DUTY -> {
                            return duty;
                        }
                        case TEMPERATURE -> {
                            if (PORTS.containsKey(s)) return PORTS.get(s);
                        }
                        default -> {
                            if (s.startsWith("port:TUBE_INLET") || s.startsWith("port:CONVECTION_INLET")) return "158.9 kg/s";
                            if (s.startsWith("port:SHELL_INLET")) return "96.0 kg/s";
                        }
                    }
                }
                return "";
            }

            @Override
            public String state(String slot) {
                return "OK";
            }

            @Override
            public String text(AssemblyDiagram.Text text) {
                return HeatDiagramRender.tr(HeatDiagram.KEY + text.key(), text.args().toArray());
            }
        };
    }

    static void exchangerDoor(GuiPrototypes.Door d) {
        var rows = List.<Column.Row>of(nameGroup(),
                new Column.Group("Target", null, List.of(new Column.Buttons(List.of(key("t", "Outlet", true), key("q", "Duty", false), key("a", "Area", false))),
                        new Column.Buttons(List.of(key("tube", "Tube side", false), key("shell", "Shell side", true))))),
                new Column.Group("Set point", null, List.of(entry("temperature", "Outlet", "°C"))),
                new Column.Group("Drops", null, List.of(entry("tube", "Tube", "bar"), entry("shell", "Shell", "bar"))),
                new Column.Group("Readings", null, List.of(meter("Duty", "25.26", "MW"), meter("Approach", "46.7", "K"), meter("Area needed", "403", "m²"))));
        var box = door(d, "Heat exchanger", new String[]{"Design", "Streams"}, 0, "Solved. Duty 25.26 MW, smallest approach 46.7 K.", Palette.ACCENT, rows, Map.of("temperature", "200.0", "tube", "0.50", "shell", "0.30"));
        name(d, "E-101");
        var sheet = new HeatFlowsheet(HeatDiagram.of(HeatSides.Unit.EXCHANGER, HeatSides.Side.TUBE, ExchangerBlueprint.Design.NONE), HeatDiagram.roles(HeatSides.Unit.EXCHANGER, HeatSides.Side.TUBE));
        sheet.fit(box.x(), box.y(), box.w(), box.h());
        sheet.draw(d.c, data("25.26 MW"));
        unitFooter(d);
    }

    /** The same door once its unit has a size: the surface is the target, the drops are meters at the run's flow. */
    static void exchangerSized(GuiPrototypes.Door d) {
        var drops = SimulatorSizing.rows("RUNDROP").getFirst();
        var rows = List.<Column.Row>of(nameGroup(),
                new Column.Group("Target", null, List.of(new Column.Buttons(List.of(key("t", "Outlet", true), key("q", "Duty", false), key("a", "Area", false))),
                        new Column.Buttons(List.of(key("tube", "Tube side", false), key("shell", "Shell side", true))))),
                new Column.Group("Set point", null, List.of(entry("temperature", "Outlet", "°C"))),
                new Column.Group("Drops at this flow", null, List.of(meter("Tube", drops[1], "bar"), meter("Shell", drops[2], "bar"))),
                new Column.Group("Readings", null, List.of(meter("Duty", "25.26", "MW"), meter("Surface", "450", "m²"), meter("Area needed", "403", "m²"))));
        var box = door(d, "Heat exchanger", new String[]{"Design", "Streams"}, 0, "Sized: shell 1.00 m, tubes 6 m. Drops follow the flow.", Palette.ACCENT, rows, Map.of("temperature", "200.0"));
        name(d, "E-101");
        var sheet = new HeatFlowsheet(HeatDiagram.of(HeatSides.Unit.EXCHANGER, HeatSides.Side.TUBE, ExchangerBlueprint.Design.NONE), HeatDiagram.roles(HeatSides.Unit.EXCHANGER, HeatSides.Side.TUBE));
        sheet.fit(box.x(), box.y(), box.w(), box.h());
        sheet.draw(d.c, data("25.26 MW"));
        unitFooter(d);
    }

    static void heaterDoor(GuiPrototypes.Door d) {
        var rows = List.<Column.Row>of(nameGroup(),
                new Column.Group("Target", null, List.of(new Column.Buttons(List.of(key("t", "Outlet", true), key("q", "Duty", false))))),
                new Column.Group("Set point", null, List.of(entry("temperature", "Coil outlet", "°C"), entry("duty", "Duty limit", "MW"))),
                new Column.Group("Drops", null, List.of(entry("radiant", "Radiant", "bar"), entry("convection", "Convection", "bar"))),
                new Column.Group("Readings", null, List.of(meter("Duty", "78.37", "MW"), meter("Vapour out", "71.1", "%"))));
        var box = door(d, "Fired heater", new String[]{"Design", "Streams"}, 0, "Solved. Duty 78.37 MW of the 90.0 MW limit.", Palette.ACCENT, rows, Map.of("temperature", "365.0", "duty", "90.0", "radiant", "5.10", "convection", "2.50"));
        name(d, "H-101");
        var sheet = new HeatFlowsheet(HeatDiagram.of(HeatSides.Unit.FIRED_HEATER, HeatSides.Side.RADIANT, ExchangerBlueprint.Design.NONE), HeatDiagram.roles(HeatSides.Unit.FIRED_HEATER, HeatSides.Side.RADIANT));
        sheet.fit(box.x(), box.y(), box.w(), box.h());
        sheet.draw(d.c, data("78.37 MW"));
        unitFooter(d);
    }

    /** The unit on the glass above its table: the symbol, its stream in, its streams out as pills. Returns the glass left under it. */
    static Rect sketch(GuiPrototypes.Door d, Rect box, Glyph glyph, String tag, String in, List<String> out) {
        int height = 118, mid = 58, gx = box.w() / 2 - glyph.w / 2, gy = glyph == Glyph.PUMP ? mid - 13 : mid - glyph.h / 2, pillW = 56;
        var p = new FlowsheetPainter(d.c, box.x(), box.y(), 1, 1);
        int inX = gx - 96, outX = gx + glyph.w + 40;
        p.wire(new int[]{inX + pillW, mid, gx, mid}, true, false, Palette.MUTED);
        glyph(p, glyph, gx, gy, glyph.w, glyph.h, Look.TEAL);
        pill(p, in, inX, mid, pillW, false, Palette.TEXT);
        for (int i = 0; i < out.size(); i++) {
            int y = out.size() == 1 ? mid : mid - 36 + i * 72 / (out.size() - 1), cx = gx + glyph.w / 2;
            int[] points = out.size() == 1 || y == mid ? new int[]{gx + glyph.w, mid, outX, mid} : y < mid ? new int[]{cx, gy, cx, y, outX, y} : new int[]{cx, gy + glyph.h, cx, y, outX, y};
            p.wire(points, true, false, Palette.MUTED);
            p.square(points[0], points[1], 5, PortColours.CONNECTED);
            pill(p, out.get(i), outX, y, pillW, false, out.get(i).equals("none") ? Palette.MUTED : Palette.TEXT);
        }
        p.square(gx, mid, 5, PortColours.CONNECTED);
        p.tag(tag, out.size() > 1 ? gx - 8 : gx + glyph.w / 2, out.size() > 1 ? gy + glyph.h + 12 : gy - 8, 0.5f, Palette.TEXT, Sample.PLATE);
        d.c.hLine(box.x() + 2, box.right() - 2, box.y() + height, Palette.LINE);
        return new Rect(box.x(), box.y() + height + 3, box.w(), box.h() - height - 3);
    }

    /** Streams side by side on the glass. */
    static void streams(GuiPrototypes.Door d, Rect box, String[] headers, String[][] rows) {
        int[] widths = new int[headers.length];
        widths[0] = box.w() - (headers.length - 1) * 90 - 6;
        for (int i = 1; i < widths.length; i++) widths[i] = 90;
        var table = new ArrayList<String[]>();
        table.add(headers);
        table.addAll(List.of(rows));
        d.table(box, widths, table);
    }

    static void pumpDoor(GuiPrototypes.Door d) {
        var rows = List.<Column.Row>of(nameGroup(),
                new Column.Group("Target", null, List.of(new Column.Buttons(List.of(key("p", "Outlet", true), key("r", "Rise", false))))),
                new Column.Group("Set point", null, List.of(entry("pressure", "Outlet", "bar"))),
                new Column.Group("Readings", null, List.of(meter("Power", "110.7", "kW"), meter("Rise", "6.00", "bar"), meter("Head", "71.0", "m"))));
        var box = door(d, "Pump", new String[]{"Design", "Streams"}, 0, "Solved. Liquid at the inlet, 0.0 % vapour by volume.", Palette.ACCENT, rows, Map.of("pressure", "14.00"));
        name(d, "P-101");
        var rest = sketch(d, box, Glyph.PUMP, "P-101", "CRUDE", List.of("S-1"));
        streams(d, rest, new String[]{"", "Inlet CRUDE", "Outlet S-1"}, new String[][]{{"Temperature, °C", "25.0", "25.1"}, {"Pressure, bar", "8.00", "14.00"}, {"Flow, kmol/h", "2652.3", "2652.3"},
                {"Flow, t/h", "572.09", "572.09"}, {"Vapour, mole %", "0.0", "0.0"}, {"Density, kg/m³", "861.1", "861.5"}});
        unitFooter(d);
    }

    static void drumDoor(GuiPrototypes.Door d) {
        var rows = List.<Column.Row>of(nameGroup(),
                new Column.Group("Pressure", null, List.of(new Column.Buttons(List.of(key("in", "Inlet", true), key("set", "Set", false))), entry("drop", "Drop", "bar"))),
                new Column.Group("Heat", null, List.of(new Column.Buttons(List.of(key("adiabatic", "None", true), key("t", "Temp.", false), key("q", "Duty", false))))),
                phases(28.9, 0, 71.1, 0),
                new Column.Group("Readings", null, List.of(meter("Temperature", "365.0", "°C"), meter("Pressure", "5.00", "bar"))));
        var box = door(d, "Drum", new String[]{"Design", "Streams"}, 0, "Solved. Two phases: gas and organic.", Palette.ACCENT, rows, Map.of("drop", "0.00"));
        name(d, "D-101");
        var rest = sketch(d, box, Glyph.DRUM, "D-101", "S-6", List.of("S-14", "S-15", "none"));
        streams(d, rest, new String[]{"", "Inlet S-6", "Gas S-14", "Oil S-15", "Water"}, new String[][]{{"Temperature, °C", "365.0", "365.0", "365.0", ""}, {"Pressure, bar", "5.00", "5.00", "5.00", ""},
                {"Flow, kmol/h", "2652.3", "1885.3", "767.0", "0.0"}, {"Flow, t/h", "572.09", "255.15", "316.94", "0.00"}});
        unitFooter(d);
    }

    static void valveDoor(GuiPrototypes.Door d) {
        var rows = List.<Column.Row>of(nameGroup(),
                new Column.Group("Target", null, List.of(new Column.Buttons(List.of(key("p", "Outlet", true), key("r", "Drop", false))))),
                new Column.Group("Set point", null, List.of(entry("pressure", "Outlet", "bar"))),
                phases(22.1, 0, 77.9, 0), new Column.Group("Readings", null, List.of(meter("Drop", "2.28", "bar"), meter("Cooled", "5.5", "K"))));
        var box = door(d, "Control valve", new String[]{"Design", "Streams"}, 0, "Solved. Isenthalpic; the outlet holds 77.9 % vapour by mole.", Palette.ACCENT, rows, Map.of("pressure", "2.72"));
        name(d, "V-101");
        var rest = sketch(d, box, Glyph.VALVE, "V-101", "S-6", List.of("S-7"));
        streams(d, rest, new String[]{"", "Inlet S-6", "Outlet S-7"}, new String[][]{{"Temperature, °C", "365.0", "359.5"}, {"Pressure, bar", "5.00", "2.72"}, {"Flow, kmol/h", "2652.3", "2652.3"},
                {"Vapour, mole %", "71.1", "77.9"}, {"Density, kg/m³", "30.2", "14.8"}});
        unitFooter(d);
    }

    static void splitterDoor(GuiPrototypes.Door d) {
        var rows = List.<Column.Row>of(nameGroup(),
                new Column.Group("Split", null, List.of(new Column.Buttons(List.of(key("ratio", "Shares", true), key("flow", "Flows", false))))),
                new Column.Group("Readings", null, List.of(meter("Sum", "75.0", "%"), meter("Rest", "S-13", ""))));
        var box = door(d, "Splitter", new String[]{"Design", "Streams"}, 0, "Solved. S-13 takes what the others leave.", Palette.ACCENT, rows, Map.of());
        name(d, "TEE-1");
        var rest = sketch(d, box, Glyph.SPLITTER, "TEE-1", "DIESEL", List.of("S-11", "S-12", "S-13"));
        int y = rest.y() + 4;
        d.c.text("Outlet", rest.x() + 4, y, Palette.MUTED);
        d.c.text("Share, %", rest.x() + 110, y, Palette.MUTED);
        d.c.text("kmol/h", rest.right() - 4 - d.m.width("kmol/h"), y, Palette.MUTED);
        d.c.hLine(rest.x() + 2, rest.right() - 2, y + 10, Palette.LINE);
        String[][] outlets = {{"S-11", "40.0", "206.2"}, {"S-12", "35.0", "180.4"}, {"S-13", "", "128.9"}};
        for (int i = 0; i < 3; i++) {
            int ry = y + 14 + i * 22;
            if (i % 2 == 1) d.c.fill(rest.x(), ry - 2, rest.right(), ry + 20, DialogColumns.BAND);
            d.c.text(outlets[i][0], rest.x() + 4, ry + 5, Palette.TEXT);
            d.skin.field(d.c, new Rect(rest.x() + 110, ry, Column.BOX, 18), outlets[i][1], "Rest", false);
            d.c.text(outlets[i][2], rest.right() - 4 - d.m.width(outlets[i][2]), ry + 5, Palette.ACCENT);
        }
        unitFooter(d);
    }

    /** T-101's entries, by the calculator's keys. */
    static final Map<String, String> COLUMN = Map.ofEntries(Map.entry("s2", "42"), Map.entry("s9", "8.00"), Map.entry("s7", "2.50"), Map.entry("s3", "38"), Map.entry("s4", "59.0"), Map.entry("s6", "4.17"),
            Map.entry("s5", "0.0"), Map.entry("d0rate", "491.3"), Map.entry("d0stage", "11"), Map.entry("tube", "0.30"));

    /** The block's connection points on the Equipment page's glass: what each carries, read from the last run. */
    static void connections(GuiPrototypes.Door d, Rect box) {
        String[][] rows = {{"Feed, tray 38", "S-7", "359.5 °C", ""}, {"Steam, tray 42", "STEAM", "260.0 °C", ""}, {"Condenser in", "CWS", "30.0 °C", "39.23 MW"}, {"Condenser out", "CWR", "39.9 °C", ""},
                {"PA 1 in", "S-1", "25.1 °C", "12.84 MW"}, {"PA 1 out", "S-2", "67.9 °C", ""}, {"PA 2 in", "S-2", "67.9 °C", "17.89 MW"}, {"PA 2 out", "S-3", "122.0 °C", ""}, {"PA 3 in", "S-3", "122.0 °C", "11.20 MW"},
                {"PA 3 out", "S-4", "153.3 °C", ""}, {"Overhead", "OFFGAS", "59.0 °C", ""}, {"Distillate", "NAPHTHA", "59.0 °C", ""}, {"Free water", "WATER", "59.0 °C", ""}, {"Draw 1, tray 11", "KERO", "140.1 °C", ""},
                {"Draw 2, tray 19", "S-8", "213.4 °C", ""}, {"Draw 3, tray 29", "AGO", "273.8 °C", ""}, {"Bottoms", "S-9", "300.2 °C", ""}};
        int x0 = box.x() + 4, y = box.y() + 4;
        String[] head = {"Connection point", "Stream", "Temperature", "Heat"};
        int[] at = {0, 124, 200, 280};
        for (int i = 0; i < head.length; i++) d.c.text(head[i], x0 + at[i], y, Palette.MUTED);
        d.c.hLine(box.x() + 2, box.right() - 2, y + 10, Palette.LINE);
        y += 14;
        for (int r = 0; r < rows.length; r++, y += 14) {
            if (r % 2 == 1) d.c.fill(box.x(), y - 3, box.right(), y + 11, DialogColumns.BAND);
            for (int i = 0; i < head.length; i++) d.c.text(rows[r][i], x0 + at[i], y, i == 0 ? Palette.TEXT : i == 1 ? Palette.MUTED : Palette.ACCENT);
        }
    }

    /**
     * The column's door: the calculator's frame, tabs, columns and glass as {@code GuiPrototypes.calculatorDoor} draws them, with
     * its name, flow and temperature read from the inlet stream, and T-101's real drawing and profile.
     */
    static void columnDoor(GuiPrototypes.Door d, CalculatorColumn.Page page) {
        var t = GuiPrototypes.DIALOG;
        boolean drawing = page == CalculatorColumn.Page.COLUMN, equipment = page == CalculatorColumn.Page.EQUIPMENT;
        d.skin.title(d.c, 10, 10, "Distillation column");
        d.skin.iconKey(d.c, CalculatorColumn.theme(d.w), Skin.Icon.THEME, false);
        var tabs = CalculatorColumn.tabs(page, t);
        var tabSlots = CalculatorColumn.tabSlots(d.w);
        for (int i = 0; i < tabs.size(); i++) {
            var spec = tabs.get(i);
            d.skin.button(d.c, tabSlots.get(i), spec.text(), spec.latched() ? Skin.Press.CURRENT : !spec.active() ? Skin.Press.OFF : Skin.Press.REST, false);
        }
        d.skin.status(d.c, CalculatorColumn.display(d.w), drawing ? "Solved: 3 draws, 3 pumparounds, 1 steam feed." : equipment ? "17 connection points, all connected." : "Solved. Drop 21.5 kPa, flooding at most 64.8 % at tray 33.", Palette.ACCENT);
        var rows = drawing ? CalculatorColumn.column(new CalculatorColumn.ColumnInput(false, true, "°C", "bar", "kmol/h", CalculatorColumn.Connection.DRAW, 0, true, true, true, true), t)
                : equipment ? CalculatorColumn.equipment(new CalculatorColumn.EquipmentInput(false, ColumnComponents.Condenser.EXCHANGER, 3, true), t)
                : CalculatorColumn.results(new CalculatorColumn.ResultsInput(false, CalculatorColumn.View.PROFILES, CalculatorColumn.Metric.TEMPERATURE, List.of(), 0, 10, 40), t);
        boolean two = rows.two();
        d.skin.hDivider(d.c, 8, d.w - 8, d.h - CalculatorColumn.DIVIDER);
        d.skin.softKeys(d.c, CalculatorColumn.keys(d.w, d.h));
        for (int x : CalculatorColumn.dividers(d.w, d.h, two)) d.skin.vDivider(d.c, x, CalculatorColumn.TOP, d.h - CalculatorColumn.FOOT);
        var box = d.well(CalculatorColumn.glass(d.w, d.h, two));
        d.c.clip(box);
        if (drawing) {
            double min = java.util.Arrays.stream(PROFILE).min().orElse(0), max = java.util.Arrays.stream(PROFILE).max().orElse(1);
            var shares = java.util.Arrays.stream(PROFILE).map(v -> (v - min) / (max - min)).toArray();
            var in = new CalculatorColumn.DrawingInput(40, 37, List.of(new CalculatorColumn.Mark(0, 10, 0), new CalculatorColumn.Mark(1, 18, 0), new CalculatorColumn.Mark(2, 28, 0)),
                    List.of(new CalculatorColumn.Mark(0, 10, 8), new CalculatorColumn.Mark(1, 18, 16), new CalculatorColumn.Mark(2, 28, 26)), List.of(new CalculatorColumn.Mark(0, 41, 0)), 10,
                    CalculatorColumn.Connection.DRAW, 0, shares);
            CalculatorColumn.draw(d.c, CalculatorColumn.drawing(in, box, 0, d.m, t, node -> ""), t);
        } else if (equipment) {
            connections(d, box);
        } else {
            int head = CalculatorColumn.heading(d.c, box, t.get("column.temperature", "°C"), List.of(), d.m);
            CalculatorColumn.profile(d.c, CalculatorColumn.plot(box, head), new double[][]{PROFILE}, new int[]{Palette.ACCENT});
        }
        d.c.unclip();
        // The left column: the name; the calculator's Column group; the feed from the stream, its tray still the player's.
        var right = new ArrayList<>(rows.right());
        if (equipment) right.add(right.size() - 1, new Column.Group("Tube sides", null, List.of(entry("tube", "Drop", "bar"))));
        List<List<Column.Row>> lists = !two ? List.of(right) : !drawing ? List.of(rows.left(), rows.right())
                : List.of(List.of(nameGroup(), rows.left().getFirst(), new Column.Group("Feed", null, List.of(meter("Flow", "2652.3", "kmol/h"), meter("Temperature", "359.5", "°C"), new Column.Field("s3", "Tray", "", null)))), rows.right());
        var areas = CalculatorColumn.door(d.w, d.h, two);
        for (int i = 0; i < areas.size(); i++) {
            var a = areas.get(i);
            var column = Column.place(lists.get(i), a.x(), a.y(), a.w(), d.m, Column.Metrics.COMPACT);
            column.draw(d.c, 0, d.skin);
            for (var p : column.rows()) {
                List<Column.Button> specs = p.row() instanceof Column.Buttons b ? b.buttons() : p.row() instanceof Column.Setting st && st.steps() ? List.of(st.minus(), st.plus()) : List.of();
                for (var spec : specs) d.skin.button(d.c, p.slots().get(spec.key()), spec.text(), spec.latched() ? Skin.Press.CURRENT : !spec.active() ? Skin.Press.OFF : Skin.Press.REST, false);
                if (p.row() instanceof Column.Field f) d.skin.field(d.c, p.slots().get(f.key()), COLUMN.getOrDefault(f.key(), ""), "", false);
                if (p.row() instanceof Column.Gap) d.skin.field(d.c, new Rect(p.rect().x(), p.rect().y(), p.rect().w(), 18), "T-101", "", false);
            }
        }
        var footer = CalculatorColumn.footer(d.w, d.h);
        var keys = CalculatorColumn.footerButtons(new CalculatorColumn.Footer(false, false, true, false, "°C"), t);
        d.skin.button(d.c, footer.get(0), "Close", Skin.Press.REST, false);
        d.skin.button(d.c, footer.get(1), "Solve alone", Skin.Press.PRIMARY, false);
        d.skin.button(d.c, footer.get(2), "Templates", Skin.Press.REST, false);
        for (int i = 3; i < 5; i++) d.skin.softKey(d.c, footer.get(i), keys.get(i).text(), false);
    }

    // ---- Templates ----

    /** A list on a dialog's glass under its title: bands, the first row chosen. */
    static void list(GuiPrototypes.Door d, String title, String[] head, int[] at, String[][] rows, String[] keys, int primary) {
        d.skin.title(d.c, 10, 10, title);
        var box = d.well(new Rect(5, 26, d.w - 10, d.h - 26 - 40));
        int y = box.y() + 4, x0 = box.x() + 4;
        for (int i = 0; i < head.length; i++) d.c.text(head[i], x0 + at[i], y, Palette.MUTED);
        d.c.hLine(box.x() + 2, box.right() - 2, y + 10, Palette.LINE);
        y += 14;
        for (int r = 0; r < rows.length; r++, y += 14) {
            if (r % 2 == 1) d.c.fill(box.x(), y - 3, box.right(), y + 11, DialogColumns.BAND);
            if (r == 0) d.c.outline(box.x() + 1, y - 3, box.w() - 2, 14, Palette.TEXT);
            for (int i = 0; i < head.length; i++) {
                boolean warn = i == head.length - 1 && !rows[r][i].equals("Ready") && !rows[r][i].equals("Bundled") && !rows[r][i].equals("Yours");
                d.c.text(rows[r][i], x0 + at[i], y, i == 0 ? Palette.TEXT : warn ? Palette.WARN : Palette.ACCENT);
            }
        }
        var slots = DialogColumns.footer(d.w, d.h, keys.length);
        for (int i = 0; i < keys.length; i++) d.skin.button(d.c, slots.get(i), keys[i], i == primary ? Skin.Press.PRIMARY : Skin.Press.REST, false);
    }

    static void streamTemplates(GuiPrototypes.Door d) {
        list(d, "Stream templates", new String[]{"Name", "Parts", "Conditions", "From"}, new int[]{0, 216, 262, 460}, new String[][]{{"Tia Juana Light (default)", "19", "2652 kmol/h, 365.0 °C", "Bundled"},
                {"Tia Juana Light + 2 % nitrogen", "20", "2652 kmol/h, 365.0 °C", "Bundled"}, {"Tia Juana Light atmospheric residue", "6", "mixture only", "Bundled"}, {"Bonga", "19", "2650 kmol/h, 365.0 °C", "Bundled"},
                {"Cold Lake Blend", "19", "2652 kmol/h, 365.0 °C", "Bundled"}, {"Dalia", "19", "2649 kmol/h, 365.0 °C", "Bundled"}, {"Upper Zakum", "19", "2653 kmol/h, 365.0 °C", "Bundled"},
                {"WTI Light - Export", "19", "2653 kmol/h, 365.0 °C", "Bundled"}, {"Tank at 120, 64, -30", "19", "40.0 °C, 1.01 bar", "Yours"}}, new String[]{"Cancel", "Delete", "Save current", "Load"}, 3);
    }

    static void columnTemplates(GuiPrototypes.Door d) {
        list(d, "Column templates", new String[]{"Name", "Trays", "Connections", "Design feed", "From"}, new int[]{0, 170, 212, 352, 460}, new String[][]{{"Crude tower (Tia Juana Light)", "42", "3 draws, 3 PA, 1 steam", "2652 kmol/h", "Bundled"},
                {"Crude tower (Bonga)", "42", "3 draws, 3 PA, 1 steam", "2650 kmol/h", "Bundled"}, {"Crude tower (Cold Lake Blend)", "42", "3 draws, 3 PA, 1 steam", "2652 kmol/h", "Bundled"},
                {"Crude tower (Dalia)", "42", "3 draws, 3 PA, 1 steam", "2649 kmol/h", "Bundled"}, {"Crude tower (Upper Zakum)", "42", "3 draws, 3 PA, 1 steam", "2653 kmol/h", "Bundled"},
                {"Crude tower (WTI Light)", "42", "3 draws, 3 PA, 1 steam", "2653 kmol/h", "Bundled"}, {"Two-tray pot", "2", "none", "360 kmol/h", "Bundled"}, {"My vacuum tower", "24", "2 draws, 2 PA, 1 steam", "770 kmol/h", "Yours"}},
                new String[]{"Cancel", "Delete", "Save current", "Load"}, 3);
    }

    // ---- Connection points: what a block's configuration adds to the main flowsheet ----

    /** Names in this colour are what a configuration added; the port classes' colours are the laying tool's. */
    static final int ADDED = 0xFF000000 | PortColours.BULK;

    /** A connection point: its name, its class's colour, whether this configuration added it. */
    record Point(String name, int rgb, boolean added) {
        static Point vapour(String name) {
            return new Point(name, PortColours.VAPOUR, false);
        }

        static Point liquid(String name) {
            return new Point(name, PortColours.LIQUID, false);
        }

        static Point bulk(String name) {
            return new Point(name, PortColours.BULK, false);
        }

        Point marked() {
            return new Point(name, rgb, true);
        }
    }

    /** A port at ({@code x}, {@code y}) on a block's edge: its square, a stub towards ({@code dx}, {@code dy}), its name at the stub's end. */
    static void point(FlowsheetPainter p, int x, int y, int dx, int dy, Point port) {
        p.wire(new int[]{x, y, x + dx, y + dy}, false, false, Palette.MUTED);
        p.square(x, y, 5, port.rgb());
        int colour = port.added() ? ADDED : Palette.MUTED;
        if (dy == 0) p.label(port.name(), x + dx + (dx < 0 ? -3 : 3), y, dx < 0 ? 1f : 0f, colour);
        else p.label(port.name(), x, y + dy + (dy < 0 ? -6 : 7), 0.5f, colour);
    }

    /** Ports down a block's side, spaced to fill {@code y0} to {@code y1}. */
    static void side(FlowsheetPainter p, int x, int y0, int y1, int dx, List<Point> ports) {
        for (int i = 0; i < ports.size(); i++) {
            int y = ports.size() == 1 ? (y0 + y1) / 2 : y0 + i * (y1 - y0) / (ports.size() - 1);
            point(p, x, y, dx, 0, ports.get(i));
        }
    }

    /** A column block as dropped on the canvas, under its door's settings. */
    static void columnPanel(GuiPrototypes.Door d, Rect box, String title, List<String> door, List<Point> left, List<Point> right) {
        var c = d.c;
        c.text(title, box.x() + 5, box.y() + 5, Palette.TEXT);
        for (int i = 0; i < door.size(); i++) c.text(door.get(i), box.x() + 5, box.y() + 18 + i * 10, Palette.MUTED);
        var p = new FlowsheetPainter(c, box.x(), box.y(), 1, 1);
        int cx = box.w() / 2, top = 84, bottom = box.h() - 24;
        glyph(p, Glyph.COLUMN, cx - 14, top, 28, bottom - top, Sample.NONE);
        p.tag("T-101", cx, bottom + 9, 0.5f, Palette.TEXT, Sample.PLATE);
        side(p, cx - 14, top + 24, bottom - 18, -12, left);
        side(p, cx + 14, top + 24, bottom - 18, 12, right);
    }

    static void blockPorts(GuiPrototypes.Door d) {
        int gap = 4, n = 4, w = (d.w - 10 - (n - 1) * gap) / n;
        var overhead = Point.vapour("Overhead");
        var distillate = Point.liquid("Distillate");
        var water = Point.liquid("Free water");
        var bottoms = Point.liquid("Bottoms");
        var feed = Point.bulk("Feed");
        var in = Point.bulk("Condenser in").marked();
        var out = Point.bulk("Condenser out").marked();
        List<List<String>> doors = List.of(List.of("Condenser: Air cooler", "Reboiler duty: 0", "Pumparounds: none", "Steam: none", "Draws: none", "Top: 2.50 bar"),
                List.of("Condenser: Exchanger", "Reboiler duty: 18.00 MW", "Pumparounds: none", "Steam: none", "Draws: none", "Top: 2.50 bar"),
                List.of("Condenser: Exchanger", "Reboiler duty: 0", "Pumparounds: 3", "Steam: 1", "Draws: 3", "Top: 2.50 bar"),
                List.of("Condenser: Exchanger", "Reboiler duty: 0", "Pumparounds: 2", "Steam: 1", "Draws: 2", "Top: 0.10 bar"));
        List<String> titles = List.of("Air cooler, no reboiler", "Exchanger and kettle", "The sample's T-101", "Vacuum tower");
        List<List<Point>> lefts = List.of(List.of(feed),
                List.of(in, out, feed, Point.bulk("Reboiler in").marked(), Point.bulk("Reboiler out").marked()),
                List.of(in, out, Point.bulk("PA 1 in").marked(), Point.bulk("PA 1 out").marked(), Point.bulk("PA 2 in").marked(), Point.bulk("PA 2 out").marked(), Point.bulk("PA 3 in").marked(), Point.bulk("PA 3 out").marked(), feed, Point.bulk("Steam 1").marked()),
                List.of(Point.bulk("Motive steam").marked(), in, out, Point.bulk("PA 1 in").marked(), Point.bulk("PA 1 out").marked(), Point.bulk("PA 2 in").marked(), Point.bulk("PA 2 out").marked(), feed, Point.bulk("Steam 1").marked()));
        List<List<Point>> rights = List.of(List.of(overhead, distillate, water, bottoms), List.of(overhead, distillate, water, bottoms),
                List.of(overhead, distillate, water, Point.bulk("Draw 1").marked(), Point.bulk("Draw 2").marked(), Point.bulk("Draw 3").marked(), bottoms),
                List.of(overhead, distillate, water, Point.bulk("Draw 1").marked(), Point.bulk("Draw 2").marked(), bottoms));
        for (int i = 0; i < n; i++) columnPanel(d, d.well(new Rect(5 + i * (w + gap), 5, w, d.h - 10)), titles.get(i), doors.get(i), lefts.get(i), rights.get(i));
    }

    /** Text lines on the glass from ({@code x}, {@code y}), every second line's band across {@code box}. */
    static void lines(GuiPrototypes.Door d, Rect box, int x, int y, List<String> lines) {
        for (int i = 0; i < lines.size(); i++, y += 13) {
            if (i % 2 == 1) d.c.fill(box.x(), y - 2, box.right(), y + 11, DialogColumns.BAND);
            d.c.text(lines.get(i), x, y, Palette.TEXT);
        }
    }

    /** A hot side's reading beside a block: its tray, its heat. */
    static void hot(FlowsheetPainter p, int x, int y, String first, String second) {
        p.label(first, x, y - 5, 0f, Palette.TEXT);
        p.label(second, x, y + 6, 0f, Palette.ACCENT);
    }

    static void paOptions(GuiPrototypes.Door d) {
        int gap = 4, w = (d.w - 10 - gap) / 2;
        // A: the crude through the block.
        var a = d.well(new Rect(5, 5, w, d.h - 10));
        var p = new FlowsheetPainter(d.c, a.x(), a.y(), 1, 1);
        d.c.text("A: the cooler stays in the block, the crude runs through it", a.x() + 5, a.y() + 5, Palette.TEXT);
        p.wire(new int[]{64, 76, 300, 76}, true, false, Palette.MUTED);
        p.wire(new int[]{300, 92, 276, 92, 276, 148, 300, 148}, true, false, Palette.MUTED);
        p.wire(new int[]{300, 164, 276, 164, 276, 220, 300, 220}, true, false, Palette.MUTED);
        p.wire(new int[]{300, 236, 64, 236}, true, false, Palette.MUTED);
        p.wire(new int[]{64, 296, 300, 296}, true, false, Palette.MUTED);
        glyph(p, Glyph.COLUMN, 300, 28, 44, 312, Sample.SOLVED);
        for (var y : new int[]{76, 92, 148, 164, 220, 236, 296}) p.square(300, y, 5, PortColours.CONNECTED);
        for (var e : new Object[][]{{"S-1", 76}, {"S-4", 236}}) pill(p, (String) e[0], 12, (Integer) e[1], 52, e[0].equals("S-1"), Palette.TEXT);
        pill(p, "S-7", 12, 296, 52, true, Palette.TEXT);
        p.tag("25.1 °C", 170, 68, 0.5f, Palette.MUTED, Sample.PLATE);
        p.tag("S-2 67.9 °C", 234, 120, 0.5f, Palette.MUTED, Sample.PLATE);
        p.tag("S-3 122.0 °C", 234, 192, 0.5f, Palette.MUTED, Sample.PLATE);
        p.tag("153.3 °C, to E-101", 170, 228, 0.5f, Palette.MUTED, Sample.PLATE);
        p.tag("359.5 °C, from V-101", 170, 288, 0.5f, Palette.MUTED, Sample.PLATE);
        for (var e : new Object[][]{{"PA 1", 84}, {"PA 2", 156}, {"PA 3", 228}, {"Feed", 296}}) p.tag((String) e[0], 322, (Integer) e[1], 0.5f, Palette.TEXT, Sample.PLATE);
        hot(p, 352, 84, "Tray 11, 140.1 °C", "12.84 MW");
        hot(p, 352, 156, "Tray 19, 213.4 °C", "17.89 MW");
        hot(p, 352, 228, "Tray 29, 273.8 °C", "11.20 MW");
        lines(d, a, a.x() + 5, a.y() + 352, List.of("Ports: 3 pairs on T-101. No units or streams added.", "Duty: a door entry. The crude's outlet is the result.", "Solver: the column does not read these ports, so no loop.",
                "Model: as today, a heat sink on the trays. No new column code.", "Check: the crude stays under the draw tray's temperature.", "Sample: 2 passes, H-101 78.37 MW.", "Later: a target on the crude's outlet, the duty then a result."));
        // B: a pumparound stream out of the block, an exchanger on the sheet.
        var b = d.well(new Rect(5 + w + gap, 5, w, d.h - 10));
        p = new FlowsheetPainter(d.c, b.x(), b.y(), 1, 1);
        d.c.text("B: a real pumparound stream leaves the block and comes back", b.x() + 5, b.y() + 5, Palette.TEXT);
        int[] rows = {84, 164, 244};
        String[] names = {"E-201", "E-202", "E-203"};
        for (int i = 0; i < 3; i++) {
            int y = rows[i];
            p.wire(new int[]{320, y - 30, 188, y - 30, 188, y - 14}, true, false, Palette.ACCENT);
            p.wire(new int[]{216, y - 14, 216, y - 20, 320, y - 20}, true, false, Palette.ACCENT);
            glyph(p, Glyph.EXCHANGER, 170, y - 14, 64, 28, Sample.SOLVED);
            p.tag(names[i], 202, y + 21, 0.5f, Palette.TEXT, Sample.PLATE);
            for (var q : new int[][]{{188, y - 14}, {216, y - 14}, {170, y}, {234, y}, {320, y - 30}, {320, y - 20}}) p.square(q[0], q[1], 5, PortColours.CONNECTED);
        }
        p.wire(new int[]{64, 84, 170, 84}, true, false, Palette.MUTED);
        p.wire(new int[]{234, 84, 250, 84, 250, 122, 150, 122, 150, 164, 170, 164}, true, false, Palette.MUTED);
        p.wire(new int[]{234, 164, 250, 164, 250, 202, 150, 202, 150, 244, 170, 244}, true, false, Palette.MUTED);
        p.wire(new int[]{234, 244, 250, 244, 250, 278, 64, 278}, true, false, Palette.MUTED);
        p.wire(new int[]{64, 310, 320, 310}, true, false, Palette.MUTED);
        glyph(p, Glyph.COLUMN, 320, 28, 44, 312, Sample.SOLVED);
        for (var y : new int[]{54, 64, 134, 144, 214, 224}) p.square(320, y, 5, PortColours.CONNECTED);
        p.square(320, 310, 5, PortColours.CONNECTED);
        pill(p, "S-1", 12, 84, 52, true, Palette.TEXT);
        pill(p, "S-4", 12, 278, 52, false, Palette.TEXT);
        pill(p, "S-7", 12, 310, 52, true, Palette.TEXT);
        p.tag("25.1 °C", 112, 76, 0.5f, Palette.MUTED, Sample.PLATE);
        p.tag("S-2 67.9 °C", 200, 122, 0.5f, Palette.MUTED, Sample.PLATE);
        p.tag("S-3 122.0 °C", 200, 202, 0.5f, Palette.MUTED, Sample.PLATE);
        p.tag("153.3 °C", 112, 270, 0.5f, Palette.MUTED, Sample.PLATE);
        p.tag("359.5 °C", 112, 302, 0.5f, Palette.MUTED, Sample.PLATE);
        for (var e : new Object[][]{{"PA 1", 59}, {"PA 2", 139}, {"PA 3", 219}, {"Feed", 310}}) p.tag((String) e[0], 342, (Integer) e[1], 0.5f, Palette.TEXT, Sample.PLATE);
        hot(p, 370, 54, "346.0 t/h", "140.1 to 78.3 °C");
        hot(p, 370, 134, "323.8 t/h", "213.4 to 130.6 °C");
        hot(p, 370, 214, "203.6 t/h", "273.8 to 198.4 °C");
        lines(d, b, b.x() + 5, b.y() + 352, List.of("Ports: 3 pairs on T-101, 3 exchangers, 6 more streams.", "Circulation: a door entry. The duty comes back as a result.", "Solver: every moved return re-solves the column, a loop per PA.",
                "Model: the same heat sink; the stream is what the door shows.", "Check: what returns must equal what left.", "Not measured: passes on this form.", "Allows two exchangers on one pumparound."));
    }

    /** A panel for one block: its name, its glyph at the middle, its lines at the foot. */
    static FlowsheetPainter blockPanel(GuiPrototypes.Door d, Rect box, String title, List<String> foot) {
        d.c.text(title, box.x() + 5, box.y() + 5, Palette.TEXT);
        lines(d, box, box.x() + 5, box.bottom() - 4 - foot.size() * 13, foot);
        return new FlowsheetPainter(d.c, box.x(), box.y(), 1, 1);
    }

    static void otherBlocks(GuiPrototypes.Door d) {
        int gap = 4, w = (d.w - 10 - 2 * gap) / 3, h = (d.h - 10 - gap) / 2;
        var tubeIn = Point.bulk("Tube in");
        var tubeOut = Point.bulk("Tube out");
        // The exchanger.
        var box = d.well(new Rect(5, 5, w, h));
        var p = blockPanel(d, box, "Heat exchanger", List.of("Bypass and ratio lines stay inside the block."));
        int cx = box.w() / 2, cy = box.h() / 2 - 8;
        glyph(p, Glyph.EXCHANGER, cx - 32, cy - 14, 64, 28, Sample.NONE);
        point(p, cx - 32, cy, -16, 0, tubeIn);
        point(p, cx + 32, cy, 16, 0, tubeOut);
        point(p, cx + 14, cy - 14, 0, -18, Point.bulk("Shell in"));
        point(p, cx - 14, cy + 14, 0, 18, Point.bulk("Shell out"));
        // The kettle.
        box = d.well(new Rect(5 + w + gap, 5, w, h));
        p = blockPanel(d, box, "Kettle", List.of("Gas and liquid outlets, a tube side for the heat."));
        glyph(p, Glyph.KETTLE, cx - 35, cy - 17, 70, 34, Sample.NONE);
        side(p, cx - 35, cy - 6, cy + 6, -16, List.of(tubeIn, tubeOut));
        point(p, cx - 8, cy + 17, 0, 18, Point.bulk("Feed"));
        point(p, cx + 8, cy - 17, 0, -18, Point.vapour("Vapour out"));
        point(p, cx + 35, cy + 6, 16, 0, Point.liquid("Liquid out"));
        // The ejector.
        box = d.well(new Rect(5 + 2 * (w + gap), 5, w, h));
        p = blockPanel(d, box, "Ejector, unlit", List.of("Lights with the last work package, W10."));
        glyph(p, Glyph.EJECTOR, cx - 25, cy - 10, 50, 20, new Look(0xFF2A333A, Palette.LINE, Palette.MUTED));
        point(p, cx - 25, cy, -16, 0, Point.bulk("Suction"));
        point(p, cx - 8, cy - 8, 0, -22, Point.bulk("Motive"));
        point(p, cx + 25, cy, 16, 0, Point.bulk("Discharge"));
        // The heater, coils in series.
        box = d.well(new Rect(5, 5 + h + gap, w, h));
        p = blockPanel(d, box, "Fired heater, coils in series", List.of("One pair: the sample's H-101."));
        glyph(p, Glyph.HEATER, cx - 29, cy - 44, 58, 88, Sample.NONE);
        point(p, cx - 29, cy + 22, -16, 0, Point.bulk("Process in"));
        point(p, cx + 29, cy - 2, 16, 0, Point.bulk("Process out"));
        // The heater, coils apart.
        box = d.well(new Rect(5 + w + gap, 5 + h + gap, w, h));
        p = blockPanel(d, box, "Fired heater, coils apart", List.of("Two pairs: steam can superheat in the convection coil."));
        glyph(p, Glyph.HEATER, cx - 29, cy - 44, 58, 88, Sample.NONE);
        point(p, cx - 22, cy - 24, -23, 0, Point.bulk("Convection in").marked());
        point(p, cx + 22, cy - 24, 23, 0, Point.bulk("Convection out").marked());
        point(p, cx - 29, cy + 22, -16, 0, Point.bulk("Radiant in").marked());
        point(p, cx + 29, cy - 2, 16, 0, Point.bulk("Radiant out").marked());
        // A later unit.
        box = d.well(new Rect(5 + 2 * (w + gap), 5 + h + gap, w, h));
        p = blockPanel(d, box, "Trickle-bed reactor, later", List.of("Unlit until it has a reaction model."));
        glyph(p, Glyph.REACTOR, cx - 17, cy - 26, 34, 52, new Look(0xFF2A333A, Palette.LINE, Palette.MUTED));
        point(p, cx - 6, cy - 26, 0, -14, Point.bulk("Feed"));
        point(p, cx + 17, cy - 12, 16, 0, Point.bulk("Hydrogen"));
        point(p, cx - 17, cy, -16, 0, Point.bulk("Quench"));
        point(p, cx, cy + 26, 0, 14, Point.bulk("Effluent"));
    }

    // ---- Loops ----

    /** The Log view of a loop's run: the furnace on a fixed duty, or, with {@code stuck}, the recycle with no way out. */
    static void loopLog(GuiPrototypes.Door d, boolean stuck) {
        int doorW = 168, bottom = d.h - 38;
        var more = stuck ? List.<Column.Row>of(new Column.Group("Run", null, List.of(meter("Passes", "4", ""))), new Column.Group("Tear", null, List.of(meter("S-9", "2975.4", "kmol/h"), meter("Rises by", "724.3", "kmol/h"))))
                : List.<Column.Row>of(new Column.Group("Run", null, List.of(meter("Time", "4.7", "s"), meter("Loop passes", "7", ""), meter("Mass gap", "0.00", "%"))),
                        new Column.Group("Moved", null, List.of(meter("S-7", "336.4", "°C"), meter("S-9", "281.0", "°C"), meter("E-101", "21.25", "MW"))));
        resultsFrame(d, stuck ? "Not steady after 4 passes: S-9 gains the same amount each pass. What enters this loop has no way out." : "Steady state in 7 passes, 4.7 s. H-101 fixed at 70.37 MW, 8.00 MW under the sample's.", stuck ? Palette.WARN : Palette.ACCENT, 2, more);
        var box = d.well(new Rect(doorW + 9, 71, d.w - doorW - 14, bottom - 71));
        String[] head = stuck ? new String[]{"Pass", "S-9 in, kmol/h", "S-9 out, kmol/h", "Largest change, kmol/h", "Component"} : new String[]{"Pass", "S-9 in, °C", "S-9 out, °C", "Change, K", "Step"};
        int[] at = stuck ? new int[]{0, 44, 140, 240, 372} : new int[]{0, 44, 140, 240, 320};
        String[][] rows = stuck ? new String[][]{{"1", "none yet", "789.2", "175.2", "Crude oil, NBP 450–550°C"}, {"2", "789.2", "1524.2", "175.2", "Crude oil, NBP 450–550°C"}, {"3", "1524.2", "2251.0", "175.2", "Crude oil, NBP 450–550°C"},
                {"4", "2251.0", "2975.4", "175.2", "Crude oil, NBP 450–550°C"}}
                : new String[][]{{"1", "300.22", "289.77", "10.45", "Plain"}, {"2", "289.77", "285.79", "3.98", "Plain"}, {"3", "283.34", "282.18", "1.16", "Accelerated"}, {"4", "280.70", "280.87", "0.17", "Accelerated"},
                {"5", "281.04", "280.98", "0.06", "Accelerated"}, {"6", "280.95", "280.98", "0.03", "Accelerated"}, {"7", "280.98", "280.98", "0.00", "Accelerated"}};
        List<String> before = stuck ? List.of("Order: tear S-9, E-101's shell, E-102, TEE-1, P-103, M-101, P-101, T-101's PA 1 to 3, E-101's tube, H-101, V-101, T-101.") : List.of("Order: P-101, T-101's PA 1 to 3, then the loop E-101, H-101, V-101, T-101 with tear S-9.");
        List<String> after = stuck ? List.of("Each pass adds the same 175.2 kmol/h of this cut, all the crude holds of it. The loop's slope is 1.00, so more passes cannot close it.", "Results on show are pass 4, not a steady state.",
                "To close it: give the loop a way out. Lower TEE-1's share, or add a purge.")
                : List.of("Steady state in 7 passes, 4.7 s. Mass gap 0.00 %. Plain passes alone take 12, 8.1 s.", "The feed fell from 359.5 to 336.4 °C, the bottoms from 300.2 to 281.0 °C, E-101 from 25.26 to 21.25 MW.");
        int y = box.y() + 5, x0 = box.x() + 4;
        d.c.text(before.getFirst(), x0, y, Palette.TEXT);
        y += 16;
        for (int i = 0; i < head.length; i++) d.c.text(head[i], x0 + at[i], y, Palette.MUTED);
        d.c.hLine(box.x() + 2, box.right() - 2, y + 10, Palette.LINE);
        y += 14;
        for (int r = 0; r < rows.length; r++, y += 14) {
            if (r % 2 == 1) d.c.fill(box.x(), y - 3, box.right(), y + 11, DialogColumns.BAND);
            for (int i = 0; i < head.length; i++) d.c.text(rows[r][i], x0 + at[i], y, i == 0 ? Palette.TEXT : i == 4 ? Palette.MUTED : Palette.ACCENT);
        }
        y += 8;
        for (int i = 0; i < after.size(); i++, y += 14) {
            if (i % 2 == 1) d.c.fill(box.x(), y - 3, box.right(), y + 11, DialogColumns.BAND);
            d.c.text(after.get(i), x0, y, stuck && i == 0 ? Palette.WARN : Palette.TEXT);
        }
    }

    // ---- Results ----

    /** The Results tab's door column and footer; returns the door's lower edge. */
    static int resultsFrame(GuiPrototypes.Door d, String status, int view, List<Column.Row> more) {
        return resultsFrame(d, status, Palette.ACCENT, view, more);
    }

    static int resultsFrame(GuiPrototypes.Door d, String status, int tone, int view, List<Column.Row> more) {
        frame(d, 1, status, tone, false);
        int doorW = 168, bottom = d.h - 38;
        var rows = new ArrayList<Column.Row>();
        rows.add(new Column.Group("View", null, List.of(new Column.Buttons(List.of(key("streams", "Streams", view == 0), key("units", "Units", view == 1), key("log", "Log", view == 2))))));
        rows.add(new Column.Group("Sheet", null, List.of(new Column.Buttons(List.of(new Column.Button("export", "Export CSV", true, null))))));
        rows.addAll(more);
        var column = d.column(rows, 10, 74, doorW - 10, Map.of());
        d.skin.vDivider(d.c, doorW + 3, 74, bottom);
        footer(d, Show.PLAIN, false);
        return column.bounds().bottom();
    }

    static void results(GuiPrototypes.Door d) {
        var shown = List.of("CRUDE", "S-7", "OFFGAS", "NAPHTHA", "KERO", "RESIDUE");
        int doorW = 168, bottom = d.h - 38;
        int listTop = resultsFrame(d, "Showing 6 of 21 streams. Steady state in 2 passes.", 0, List.of()) + 4;
        var list = d.well(new Rect(5, listTop, doorW - 2, bottom - listTop));
        d.c.text("Streams", list.x() + 4, list.y() + 3, Palette.MUTED);
        for (int i = 0; i < STREAMS.size(); i++) {
            int y = list.y() + 16 + i * 14;
            boolean ticked = shown.contains(STREAMS.get(i).name());
            if (i % 2 == 1) d.c.fill(list.x(), y - 3, list.right(), y + 11, DialogColumns.BAND);
            d.skin.mark(d.c, list.x() + 5, y, ticked);
            d.c.text(STREAMS.get(i).name(), list.x() + 18, y, ticked ? Palette.TEXT : Palette.MUTED);
        }
        var box = d.well(new Rect(doorW + 9, 71, d.w - doorW - 14, bottom - 71));
        int x0 = box.x() + 4, first = 160, cell = (box.w() - first - 10) / 6, y = box.y() + 4;
        for (int i = 0; i < 6; i++) d.c.text(shown.get(i), x0 + first + (i + 1) * cell - d.m.width(shown.get(i)), y, Palette.TEXT);
        d.c.hLine(box.x() + 2, box.right() - 12, y + 10, Palette.LINE);
        y += 14;
        d.c.text("Properties", x0, y, Palette.MUTED);
        y += 12;
        var picked = shown.stream().map(SimulatorPrototypes::stream).toList();
        var rows = new ArrayList<String[]>();
        rows.add(row("Temperature, °C", picked.stream().map(Stream::t).toList()));
        rows.add(row("Pressure, bar", picked.stream().map(Stream::p).toList()));
        rows.add(row("Vapour, mole %", picked.stream().map(Stream::vapour).toList()));
        rows.add(row("Flow, kmol/h", picked.stream().map(Stream::kmol).toList()));
        rows.add(row("Flow, t/h", picked.stream().map(Stream::tonnes).toList()));
        rows.add(row("Liquid volume, m³/h", picked.stream().map(Stream::volume).toList()));
        rows.add(row("Density, kg/m³", picked.stream().map(Stream::density).toList()));
        rows.add(row("Liquid viscosity, mPa s", picked.stream().map(Stream::liquidMu).toList()));
        rows.add(row("Gas viscosity, mPa s", picked.stream().map(Stream::gasMu).toList()));
        rows.add(row("Mean weight, kg/kmol", picked.stream().map(Stream::weight).toList()));
        rows.add(row("Enthalpy, kJ/kg", picked.stream().map(Stream::enthalpy).toList()));
        y = table(d, box, rows, x0, y, first, cell);
        y += 4;
        d.c.hLine(box.x() + 2, box.right() - 12, y, Palette.LINE);
        y += 4;
        d.c.text("Composition, mole %", x0, y, Palette.MUTED);
        y += 12;
        var comp = new ArrayList<String[]>();
        double[][] shares = {CRUDE, CRUDE, OFFGAS, NAPHTHA, KERO, RESIDUE};
        for (int i = 0; i < COMPONENTS.length; i++) {
            if (COMPONENTS[i].equals("Nitrogen")) continue;
            var cells = new String[7];
            cells[0] = COMPONENTS[i];
            for (int k = 0; k < 6; k++) cells[k + 1] = f(2, shares[k][i]);
            comp.add(cells);
        }
        table(d, box, comp, x0, y, first, cell);
        var view = new ScrollView();
        view.layout(new Rect(box.x(), box.y(), box.w(), box.h()), box.right() - ScrollView.BAR, box.h() + 16);
        view.drawBar(d.c, d.skin);
        note(d.c, doorW - 12, 82, 1);
        note(d.c, list.right() - 12, list.y() + 8, 2);
        note(d.c, box.x() + 100, box.y() + 22, 3);
    }

    static String[] row(String label, List<String> values) {
        var cells = new String[values.size() + 1];
        cells[0] = label;
        for (int i = 0; i < values.size(); i++) cells[i + 1] = values.get(i);
        return cells;
    }

    /** Rows on bands, their numbers right-aligned in {@code cell} wide columns after {@code first}; returns the y under them. */
    static int table(GuiPrototypes.Door d, Rect box, List<String[]> rows, int x0, int y, int first, int cell) {
        for (int r = 0; r < rows.size() && y + 10 < box.bottom(); r++, y += 12) {
            if (r % 2 == 1) d.c.fill(box.x(), y - 2, box.right() - 10, y + 10, DialogColumns.BAND);
            var cells = rows.get(r);
            d.c.text(cells[0], x0, y, Palette.TEXT);
            for (int i = 1; i < cells.length; i++) d.c.text(cells[i], x0 + first + i * cell - d.m.width(cells[i]), y, Palette.ACCENT);
        }
        return y;
    }

    static void resultsUnits(GuiPrototypes.Door d) {
        int doorW = 168, bottom = d.h - 38;
        resultsFrame(d, "7 units solved, steady state, 1 warning.", 1, List.of(new Column.Group("Mass", null, List.of(meter("In", "4016.61", "t/h"), meter("Out", "4016.61", "t/h"), meter("Gap", "0.00", "%"))),
                new Column.Group("Heat", null, List.of(meter("Fired", "78.37", "MW"), meter("Recovered", "67.19", "MW"), meter("Cooled", "66.96", "MW"), meter("Gap", "0.00", "%")))));
        var box = d.well(new Rect(doorW + 9, 71, d.w - doorW - 14, bottom - 71));
        String[] head = {"Unit", "Kind", "Heat or work", "Streams"};
        int[] at = {0, 46, 150, 250};
        int y = box.y() + 4, x0 = box.x() + 4;
        for (int i = 0; i < 4; i++) d.c.text(head[i], x0 + at[i], y, Palette.MUTED);
        d.c.hLine(box.x() + 2, box.right() - 2, y + 10, Palette.LINE);
        y += 14;
        String[][] rows = {{"P-101", "Pump", "110.7 kW", "CRUDE to S-1: 25.0 to 25.1 °C, 8.00 to 14.00 bar"},
                {"T-101", "Column", "12.84 MW", "PA 1, tube S-1 to S-2: 25.1 to 67.9 °C. Hot side tray 11, 140.1 °C"},
                {"", "", "17.89 MW", "PA 2, tube S-2 to S-3: 67.9 to 122.0 °C. Hot side tray 19, 213.4 °C"},
                {"", "", "11.20 MW", "PA 3, tube S-3 to S-4: 122.0 to 153.3 °C. Hot side tray 29, 273.8 °C"},
                {"E-101", "Heat exchanger", "25.26 MW", "Tube S-4 to S-5: 153.3 to 218.4 °C. Shell S-9 to S-10: 300.2 to 200.0 °C"},
                {"H-101", "Fired heater", "78.37 MW", "S-5 to S-6: 218.4 to 365.0 °C, 12.60 to 5.00 bar"},
                {"V-101", "Control valve", "0", "S-6 to S-7: 365.0 to 359.5 °C, 5.00 to 2.72 bar"},
                {"T-101", "Column", "39.23 MW", "Condenser, tube CWS to CWR: 30.0 to 39.9 °C; products at 59.0 °C"},
                {"", "", "0", "S-7 and STEAM in; KERO, S-8, AGO, S-9 out; bottoms 300.2 °C"},
                {"P-102", "Pump", "11.7 kW", "S-8 to DIESEL: 213.4 to 213.5 °C, 2.58 to 6.00 bar. Liquid at its bubble point"},
                {"E-102", "Air cooler", "-27.73 MW", "S-10 to RESIDUE: 200.0 to 70.0 °C, 2.42 to 2.22 bar"}};
        for (int r = 0; r < rows.length; r++, y += 14) {
            if (r % 2 == 1) d.c.fill(box.x(), y - 3, box.right(), y + 11, DialogColumns.BAND);
            boolean warn = rows[r][0].equals("P-102");
            for (int i = 0; i < 4; i++) d.c.text(rows[r][i], x0 + at[i], y, i == 0 ? warn ? Palette.WARN : Palette.TEXT : i == 2 ? Palette.ACCENT : Palette.MUTED);
        }
    }

    static void resultsLog(GuiPrototypes.Door d) {
        int doorW = 168, bottom = d.h - 38;
        resultsFrame(d, "Log of the last run.", 2, List.of());
        var box = d.well(new Rect(doorW + 9, 71, d.w - doorW - 14, bottom - 71));
        String[] lines = {"Order: P-101, T-101's PA 1 to 3 (their duties are the door's, so no loop), then the loop E-101, H-101, V-101, T-101 with tear S-9,",
                "then T-101's condenser, P-102, E-102.", "Pass 1: E-101 no duty yet, H-101 103.63 MW, T-101 from its learned start, 2.6 s. Bottoms 300.2 °C.",
                "Pass 2: E-101 25.26 MW, H-101 78.37 MW, T-101 from its last state, 0.6 s. S-9 moved 0.00 %.", "Steady state in 2 passes, 3.6 s. Mass gap 0.00 %, heat gap 0.00 %.",
                "Hot sides checked: the crude leaves PA 1, 2, 3 at 72.1, 91.4, 120.5 K under their draw trays. Water leaves the condenser 19.1 K under its 59.0 °C.",
                "Warning: P-102 takes its liquid at the bubble point; a real pump needs a static head above it."};
        int y = box.y() + 5;
        for (int i = 0; i < lines.length; i++, y += 14) {
            if (i % 2 == 1) d.c.fill(box.x(), y - 3, box.right(), y + 11, DialogColumns.BAND);
            d.c.text(lines[i], box.x() + 4, y, i == lines.length - 1 ? Palette.WARN : Palette.TEXT);
        }
    }

    // ---- Basis ----

    static void basis(GuiPrototypes.Door d) {
        frame(d, 3, "Package: crude oil, methane and nitrogen, as the world's network. 20 components and water.", Palette.ACCENT, false);
        int doorW = 200, bottom = d.h - 38;
        var rows = List.<Column.Row>of(new Column.Group("Package", null, List.of(new Column.Choice("Crude oil, methane, nitrogen", null, true, "a"), new Column.Choice("Crude oil, methane", null, false, "b"),
                        new Column.Choice("Crude oil, no methane", null, false, "c"), new Column.Choice("Cryogenic pilot (no column)", null, false, "d"))),
                new Column.Group("Model", null, List.of(new Column.Line("Peng-Robinson 1978", Palette.TEXT), new Column.Line("IF97 water", Palette.TEXT), new Column.Line("crude-regrouped-r1", Palette.MUTED))),
                new Column.Group("Solver", null, List.of(entry("tolerance", "Tolerance", "%"), entry("passes", "Passes", ""), entry("threads", "Threads", ""))));
        d.column(rows, 10, 74, doorW - 10, Map.of("tolerance", "0.01", "passes", "30", "threads", "2"));
        d.skin.vDivider(d.c, doorW + 3, 74, bottom);
        var box = d.well(new Rect(doorW + 9, 71, d.w - doorW - 14, bottom - 71));
        String[] head = {"Component", "Weight", "Boils at, °C", "CRUDE, mole %", "In use"};
        int[] at = {0, 230, 320, 410, 510};
        int y = box.y() + 4, x0 = box.x() + 4;
        for (int i = 0; i < head.length; i++) d.c.text(head[i], x0 + at[i], y, Palette.MUTED);
        d.c.hLine(box.x() + 2, box.right() - 2, y + 10, Palette.LINE);
        y += 14;
        for (int r = 0; r < COMPONENTS.length; r++, y += 12) {
            if (r % 2 == 1) d.c.fill(box.x(), y - 2, box.right(), y + 10, DialogColumns.BAND);
            boolean used = !COMPONENTS[r].equals("Nitrogen");
            String[] cells = {COMPONENTS[r], f(1, WEIGHTS[r]), f(1, BOILS[r]), f(2, CRUDE[r]), used ? "Yes" : "No"};
            for (int i = 0; i < 5; i++) d.c.text(cells[i], x0 + at[i], y, i == 0 ? Palette.TEXT : used ? Palette.ACCENT : Palette.MUTED);
        }
        footer(d, Show.PLAIN, false);
    }

    // ---- Files ----

    static void openDialog(GuiPrototypes.Door d) {
        list(d, "Open a flowsheet", new String[]{"Name", "Size", "Saved", "State"}, new int[]{0, 170, 226, 330}, new String[][]{{"CDU train", "7 units", "2026-11-02 12:41", "Ready"},
                {"Crude preheat study", "6 units", "2026-11-01 20:07", "Ready"}, {"Gas plant inlet", "14 units", "2026-10-30 09:15", "Needs a look: 1 unit"}, {"Vacuum tower", "5 units", "2026-10-29 17:52", "Ready"},
                {"Autosave of CDU train", "7 units", "2026-11-02 12:44", "Ready"}, {"From a newer version", "", "2026-11-03 08:00", "Newer version: cannot open"}}, new String[]{"Cancel", "Folder", "From clipboard", "Open"}, 3);
    }

    static void repairDialog(GuiPrototypes.Door d) {
        d.skin.title(d.c, 10, 10, "Gas plant inlet: changes on opening");
        var box = d.well(new Rect(5, 26, d.w - 10, d.h - 26 - 40));
        var lines = List.of(new String[]{"Unit", "H-201 Old heater: this version has no such equipment. Kept as a placeholder with its settings."},
                new String[]{"Component", "Crude oil, NBP above 750°C left the package: 0.81 mole % of FEED-2 parked, not solved."},
                new String[]{"Field", "Pump P-201 has a new setting, Suction limit: it starts at its default."},
                new String[]{"Data", "Package revision changed from r1 to r2. Results dropped: run again."});
        int y = box.y() + 4, x0 = box.x() + 4;
        for (int i = 0; i < lines.size(); i++) {
            var wrapped = d.m.wrap(lines.get(i)[1], box.w() - 80);
            if (i % 2 == 1) d.c.fill(box.x(), y - 2, box.right(), y + wrapped.size() * 11, DialogColumns.BAND);
            d.c.text(lines.get(i)[0], x0, y, i < 2 ? Palette.WARN : Palette.MUTED);
            for (int j = 0; j < wrapped.size(); j++) d.c.text(wrapped.get(j), x0 + 68, y + j * 11, Palette.TEXT);
            y += wrapped.size() * 11 + 6;
        }
        d.c.text("The file on disk stays as it was until you save.", x0, box.bottom() - 13, Palette.MUTED);
        String[] keys = {"Cancel", "Open a copy", "Open"};
        var slots = DialogColumns.footer(d.w, d.h, keys.length);
        for (int i = 0; i < keys.length; i++) d.skin.button(d.c, slots.get(i), keys[i], i == 2 ? Skin.Press.PRIMARY : Skin.Press.REST, false);
    }
}
