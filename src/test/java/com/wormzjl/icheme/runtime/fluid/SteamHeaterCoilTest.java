package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.runtime.ProcessSolveServices;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.flowsheet.sizing.HeaterSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.UnitType;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The crude unit's steam heater H-102 in the world's fluid network (batch 2026-11-12-tutorial-system, P5 stage B, proof 2):
 * water as a fluid converter delivers it, 25 °C at 5 bar, held at the tower's 259 kg/h by a flow valve, runs through the
 * convection then the radiant coil of the vertical heater Size all gives H-102 (2.5 m by 4 m) to the tower's foot at 2.7 bar,
 * the burners on a stiff methane line with their fans powered. Physics: 440 kJ/kg to warm to saturation (403 K at 2.7 bar),
 * 2,170 to boil, 260 to superheat to 533 K, about 2,875 kJ/kg or 0.21 MW. The coils bring it to their 260 °C set point as
 * vapour after the coils' water has boiled off (about 20 minutes of process time; 80 slices of 20 s), the slices settle,
 * and no slice holds (a hold is the solver's throw). Measured: 533.1 K, 207.5 kW (the sheet's H-102 207.2 kW), 209.6 kW fired. The harness is FiredHeaterFuelTest's: the
 * coils' island and the fuel's, sliced in turn with the heaters' plans and bookings.
 */
class SteamHeaterCoilTest {
    private final MaterialCatalog catalog = MaterialCatalog.bundled();
    private final FluidThermodynamics model = FluidTestSupport.networkModel();
    private static final PipeResistance.Geometry STUB = new PipeResistance.Geometry(1, .05, .000045, 0);
    private static final long CONVECTION = 30, RADIANT = 31, BURNERS = 40, FUEL = 5, WATER = 1, TOWER = 2;
    /** The tower's steam, kg/s (259 kg/h), its set point, K, the feed's pressure and the tower foot's, Pa. */
    private static final double FLOW = 259 / 3600.0, SET = 533.15, FEED = 500_000, FOOT = 270_000;

    private static PhysicalFluidTopology.Position at(int x, int y, int z) {
        return new PhysicalFluidTopology.Position("minecraft:overworld", x, y, z);
    }

    private static PhysicalFluidTopology.Device plain(long id, int x, int z, Kind kind, FlowControl control) {
        return new PhysicalFluidTopology.Device(id, at(x, 0, z), kind, EAST, STUB, control);
    }

    private static PhysicalFluidTopology.Port port(PhysicalFluidTopology.Position cell, PhysicalFluidTopology.Direction face, PhysicalFluidTopology.ConnectionRole role) {
        return new PhysicalFluidTopology.Port(cell, face, PassiveNetwork.PhasePort.BULK, role);
    }

    /** H-102 as Size all sizes it: its coils' definitions, convection first. */
    private static List<HeatSides.Definition> coils() {
        var shape = new HeaterSizing.Size(UnitType.VERTICAL_HEATER, 1, 2.5, 4).shape();
        return HeatSides.of(Objects.requireNonNull(shape));
    }

    /** A coil from {@code x} to {@code x + 2}: its inlet on the west, its outlet on the east. */
    private static PhysicalFluidTopology.Device coil(long id, int x, HeatSides.Definition def, long partner) {
        var heat = new HeatUnit(HeatSides.Unit.FIRED_HEATER, def.side(), def.area(), partner).withControl(new HeatUnit.Control(HeatUnit.Mode.FIRING, SET, 0, 1));
        var ports = List.of(port(at(x, 0, 0), WEST, PhysicalFluidTopology.ConnectionRole.INLET), port(at(x + 2, 0, 0), EAST, PhysicalFluidTopology.ConnectionRole.OUTLET));
        return new PhysicalFluidTopology.Device(id, at(x, 0, 0), Kind.HEAT_SIDE, NORTH, def.passage(), new FlowControl.Passive(), ports, true,
                PhysicalFluidTopology.Cutoff.NONE, false, PhysicalFluidTopology.ALL_FACES, 0, 0, heat);
    }

    private static PhysicalFluidTopology.Device burners() {
        return new PhysicalFluidTopology.Device(BURNERS, at(3, 0, 20), Kind.VOID, NORTH, STUB, FlowControl.ControlValve.burner(),
                List.of(port(at(3, 0, 20), WEST, PhysicalFluidTopology.ConnectionRole.VESSEL)), true, PhysicalFluidTopology.Cutoff.NONE, false,
                PhysicalFluidTopology.ALL_FACES, 0, 0, new HeatUnit(HeatSides.Unit.FIRED_HEATER, HeatSides.Side.BURNER, 100, RADIANT));
    }

    /** The water's way: converter, flow valve, convection coil, radiant coil, the tower's foot; and the fuel line. */
    private List<PhysicalFluidTopology.Device> devices() {
        var defs = coils();
        assertEquals(HeatSides.Side.CONVECTION, defs.get(0).side());
        assertEquals(HeatSides.Side.RADIANT, defs.get(1).side());
        var passive = new FlowControl.Passive();
        return List.of(plain(WATER, 0, 0, Kind.GENERATOR, passive), plain(130, 1, 0, Kind.PIPE, new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW, FLOW, new UUID(1, 1), 1)),
                coil(CONVECTION, 2, defs.get(0), RADIANT), plain(230, 5, 0, Kind.PIPE, passive), coil(RADIANT, 6, defs.get(1), CONVECTION),
                plain(231, 9, 0, Kind.PIPE, passive), plain(TOWER, 10, 0, Kind.VOID, passive),
                plain(FUEL, 0, 20, Kind.GENERATOR, passive), plain(140, 1, 20, Kind.PIPE, passive), plain(141, 2, 20, Kind.PIPE, passive), burners());
    }

    private FluidThermodynamics.State pure(String component, double temperature, double pressure) {
        int c = model.components().indexOf(component);
        assertTrue(c >= 0, component);
        double[] n = new double[model.componentCount()];
        n[c] = 1;
        n[c] /= model.flashTP(temperature, pressure, n, () -> {
        }).volume();
        return model.flashTP(temperature, pressure, n, () -> {
        });
    }

    private static PassiveNetwork.Reservoir reservoir(long id, FluidThermodynamics.State state, PassiveNetwork.NodeKind kind) {
        return new PassiveNetwork.Reservoir(id, 0, state, kind);
    }

    private static IslandCoordinator coordinator(long[] epoch) {
        return new IslandCoordinator(new IslandCoordinator.Dispatcher() {
            public int availableWorkers() {
                return 1;
            }

            public long nextRequestId() {
                return 1;
            }

            public boolean submit(IslandCoordinator.Attempt attempt, ProcessSolveServices.FluidIslandCommand command) {
                throw new AssertionError("nothing is dispatched");
            }

            public void cancel(long request) {
                throw new AssertionError("no deadlines here");
            }
        }, changed -> {
        }, () -> 0L, new IslandCoordinator.Settings(30_000_000_000L, 20_000_000_000L, 64, false, 100, CertificatePolicy.defaults()), () -> epoch[0]);
    }

    @Test
    void theSteamHeaterRaisesTheTowersSteamFromWaterWithoutAHold() {
        var compiled = PhysicalFluidTopology.compile(devices(), Map.of(WATER, reservoir(WATER, pure("Water", 298.15, FEED), PassiveNetwork.NodeKind.GENERATOR),
                TOWER, reservoir(TOWER, pure("Water", SET, FOOT), PassiveNetwork.NodeKind.VOID), FUEL, reservoir(FUEL, pure("Methane", 300, 500_000), PassiveNetwork.NodeKind.GENERATOR),
                BURNERS, reservoir(BURNERS, pure("Nitrogen", Burners.AIR, Burners.PRESSURE), PassiveNetwork.NodeKind.VOID)));
        PassiveNetwork coils = null, fuel = null;
        for (var island : compiled.islands())
            for (var pipe : island.graph().pipes()) {
                if (pipe.heat() instanceof FiredCoil) coils = island.graph();
                if (pipe.control() instanceof FlowControl.ControlValve v && v.source() == FlowControl.ValveSource.BURNER) fuel = island.graph();
            }
        assertNotNull(coils, "the coils' island");
        assertNotNull(fuel, "the burners' island");
        long[] epoch = {0};
        var coordinator = coordinator(epoch);
        var n2 = FluidDeviceSpec.nitrogen(catalog).composition();
        var active = new TreeMap<Long, WorldTopologyLedger.Registration>();
        for (var d : devices())
            if (d.id() == RADIANT || d.id() == CONVECTION || d.id() == BURNERS)
                active.put(d.id(), new WorldTopologyLedger.Registration(d, new FluidDeviceSpec(1, 300, 101325, n2), 0));
        var empty = WorldTopologyLedger.Snapshot.empty(catalog);
        var ledger = new WorldTopologyLedger(new WorldTopologyLedger.Snapshot(0, 1000, active, List.of(), empty.constructed(), empty.destroyed(), empty.basis()));
        var stores = new ElectricStores(ledger, device -> null, coordinator, () -> ElectricStores.Settings.DEFAULT);
        var heaters = new FiredHeaters(ledger, coordinator, stores, FluidWorldAuthority.heatingValues(catalog, model), model.molecularWeights(), FluidWorldAuthority.formulas(catalog, model));
        for (int i = 0; i < 400 && !stores.powered(BURNERS); i++) {
            epoch[0]++;
            stores.receive(BURNERS, Integer.MAX_VALUE, false);
            if (epoch[0] % ElectricStores.CADENCE == 0) stores.cadence();
        }
        assertTrue(stores.powered(BURNERS), "the fans are powered");
        var coilSolver = new PassiveIntervalSolver(model);
        var fuelSolver = new PassiveIntervalSolver(model);
        PassiveIntervalSolver.Result result = null;
        double[] duties = new double[80];
        double[] outlets = new double[80];
        for (int i = 0; i < duties.length; i++) {
            long start = epoch[0];
            result = slice(heaters, coilSolver, coils, 1, start);
            coils = result.graph();
            fuel = slice(heaters, fuelSolver, fuel, 2, start).graph();
            epoch[0] += 400;
            duties[i] = heat(result, CONVECTION) + heat(result, RADIANT);
            outlets[i] = outlet(coils, RADIANT).temperature();
        }
        var out = outlet(coils, RADIANT);
        int radiantPipe = coils.pipes().indexOf(pipe(coils, RADIANT));
        double flow = result.endpointFlows()[radiantPipe];
        var view = heaters.view(BURNERS);
        System.out.printf(Locale.ROOT, "steam heater: flow %.2f kg/h, outlet %.2f K at %.0f kPa, vapour %.3f, convection %.1f kW, radiant %.1f kW, fired %.1f kW, fuel %.2f kg/h%n",
                3600 * flow, out.temperature(), out.pressure() / 1e3, vapour(out), heat(result, CONVECTION) / 1e3, heat(result, RADIANT) / 1e3, view.fired() / 1e3, 3600 * view.fuelFlow());
        assertEquals(List.of(), view.alarms(), "the burners raise no alarm");
        assertEquals(FLOW, flow, .02 * FLOW, "the tower's 259 kg/h");
        assertEquals(SET, out.temperature(), 2, "the set point at the radiant outlet");
        assertTrue(out.pressure() > 250_000 && out.pressure() < FEED, "at the tower foot's side of the coils: " + out.pressure());
        assertEquals(1, vapour(out), 1e-6, "all vapour");
        double duty = duties[duties.length - 1];
        assertEquals(.21e6, duty, .1 * .21e6, "about 2,875 kJ/kg on 259 kg/h");
        assertEquals(duty, duties[duties.length - 2], .01 * duty, "steady: the last two slices agree");
        assertEquals(outlets[outlets.length - 1], outlets[outlets.length - 2], .5, "steady outlet");
    }

    private PassiveIntervalSolver.Result slice(FiredHeaters heaters, PassiveIntervalSolver solver, PassiveNetwork graph, long island, long start) {
        var plans = new HashMap<Long, ShaftPlan>();
        heaters.plans(graph, plans);
        var result = solver.solve(ShaftPlan.apply(graph, plans), 20, PassiveIntervalSolver.Settings.defaults(), () -> {
        });
        heaters.booked(island, start, start + 400, result);
        return result;
    }

    private static PassiveNetwork.Pipe pipe(PassiveNetwork graph, long side) {
        return graph.pipes().stream().filter(p -> p.id() == PhysicalFluidTopology.sideIdentity(side)).findFirst().orElseThrow();
    }

    private static FluidThermodynamics.State outlet(PassiveNetwork graph, long side) {
        return graph.reservoirs().get(pipe(graph, side).second()).state();
    }

    /** The vapour's share of a state's mass. */
    private double vapour(FluidThermodynamics.State state) {
        return model.vaporMass(state) / state.mass();
    }

    /** A coil's heat over the slice, W. */
    private static double heat(PassiveIntervalSolver.Result result, long side) {
        double joule = 0;
        for (var b : result.boundaries())
            if (b.kind() == ConservativeTransport.BoundaryTransfer.Kind.HEAT && b.nodeId() == PhysicalFluidTopology.sideIdentity(side)) joule += b.totalEnergyJoule();
        return joule / result.advancedSeconds();
    }
}
