package com.wormzjl.icheme;

import com.mojang.logging.LogUtils;
import com.wormzjl.icheme.network.ProcessSimulatorNetwork;
import com.wormzjl.icheme.network.ProcessSolveCoordinator;
import com.wormzjl.icheme.registry.ModBlockEntities;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.registry.ModMenus;
import com.wormzjl.icheme.runtime.ProcessSolveServices;
import com.wormzjl.icheme.runtime.WorkerAllocation;
import com.wormzjl.icheme.science.column.v3.V3ColumnCalculator;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.column.v3.V3NeuralRegistry;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.time.Duration;

@Mod(IChemE.MOD_ID)
public final class IChemE {
    public static final String MOD_ID = "icheme";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static final ModConfigSpec CONFIG_SPEC;
    private static final ModConfigSpec.BooleanValue CALCULATION_LOGGING;
    private static final ModConfigSpec.IntValue SOLVER_WORKERS;
    private static final ModConfigSpec.IntValue SOLVER_AUTOMATIC_WORKER_LIMIT;
    private static final ModConfigSpec.IntValue SOLVER_READY_CAPACITY;
    private static final ModConfigSpec.IntValue SOLVER_DEADLINE_MILLISECONDS;
    private static final ModConfigSpec.IntValue SOLVER_GRACEFUL_SHUTDOWN_MILLISECONDS;
    private static final ModConfigSpec.IntValue SOLVER_FORCED_SHUTDOWN_MILLISECONDS;
    private static final ModConfigSpec.IntValue FLUID_INITIAL_INTERVAL_SECONDS,FLUID_WALL_BUDGET_MILLISECONDS;
    private static final ModConfigSpec.DoubleValue SOLID_IMMOBILE_VISCOSITY,SOLID_TRACE_FRACTION,SOLID_SUSPENSION_MULTIPLIER,FILTER_CAPACITY,FILTER_RESISTANCE;
    private static final ModConfigSpec.DoubleValue FLUID_INITIAL_VOLUME,FLUID_MAXIMUM_VELOCITY,FLUID_TRACE_CUTOFF;
    private static final ModConfigSpec.BooleanValue FLUID_DEBUG_CHAT,FLUID_ADAPTIVE_CADENCE;
    private static final ModConfigSpec.BooleanValue FLUID_REST_DETECTION;
    private static final ModConfigSpec.DoubleValue FLUID_CERTIFICATE_TOLERANCE,FLUID_CERTIFICATE_BUDGET;
    private static final ModConfigSpec.IntValue FLUID_CERTIFICATE_MAXIMUM_INTERVALS,FLUID_REST_CONFIRM_INTERVALS,FLUID_REST_RECHECK_SECONDS;
    private static final ModConfigSpec.DoubleValue COLUMN_V3_LIQUID_SUPPLY_SCREEN_RATIO;
    private static final ModConfigSpec.ConfigValue<String> EQUIPMENT_PRIMARY_COLOUR, EQUIPMENT_SECONDARY_COLOUR;
    private static final ModConfigSpec.DoubleValue EQUIPMENT_SHEEN;
    private static final ModConfigSpec.DoubleValue ENERGY_JOULES_PER_FE;
    private static final ModConfigSpec.BooleanValue ENERGY_MACHINES_NEED_POWER;
    private static final ModConfigSpec.BooleanValue WEAR_ENABLED;
    private static final ModConfigSpec.DoubleValue WEAR_CAVITATION_HOURS, WEAR_DRY_MINUTES;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        CALCULATION_LOGGING = builder
                .comment("Log process-equipment calculation inputs, results, diagnostics, timing, and outputs.")
                .define("enableCalculationLogging", true);
        builder.push("solver");
        SOLVER_WORKERS = builder
                .comment("Shared CPU solve workers. 0 allocates by demand within automaticWorkerLimit and available processors - 2; 1-32 sets a fixed limit.",
                        "Applied when the server starts. Performance qualification uses an explicit value of 2.")
                .defineInRange("workers", 0, 0, WorkerAllocation.MAXIMUM_WORKERS);
        SOLVER_AUTOMATIC_WORKER_LIMIT=builder.comment("Maximum shared CPU workers in automatic mode; idle capacity scales down with demand.")
                .defineInRange("automaticWorkerLimit",WorkerAllocation.DEFAULT_AUTOMATIC_LIMIT,1,WorkerAllocation.MAXIMUM_WORKERS);
        SOLVER_READY_CAPACITY = builder
                .comment("Maximum admitted process solves waiting for a worker.")
                .defineInRange("readyQueueCapacity", 8, 1, 32);
        SOLVER_DEADLINE_MILLISECONDS = builder
                .comment("Cooperative wall-clock deadline for one process solve, in milliseconds; iterative kernels poll it.")
                .defineInRange("deadlineMilliseconds", 45_000, 100, 60_000);
        SOLVER_GRACEFUL_SHUTDOWN_MILLISECONDS = builder
                .comment("Server-stop grace period before interrupting solver workers, in milliseconds.")
                .defineInRange("gracefulShutdownMilliseconds", 1_000, 0, 10_000);
        SOLVER_FORCED_SHUTDOWN_MILLISECONDS = builder
                .comment("Bounded wait after solver-worker interruption, in milliseconds.")
                .defineInRange("forcedShutdownMilliseconds", 1_000, 0, 10_000);
        builder.pop();
        builder.push("fluid");
        var solidDefaults=com.wormzjl.icheme.science.fluid.transport.SlurryTransport.Settings.defaults();
        SOLID_IMMOBILE_VISCOSITY=builder.comment("Liquid phases above this dynamic viscosity (Pa.s) cannot be transported. Applied at server start.").defineInRange("solidViscosityPascalSeconds",solidDefaults.immobileViscosity(),1e-6,1e12);
        SOLID_TRACE_FRACTION=builder.comment("Ignore smaller particle volume fractions for blockage only; all mass and size records remain conserved. 0 disables masking.").defineInRange("solidTraceVolumeFraction",solidDefaults.traceVolumeFraction(),0,0.01);
        SOLID_SUSPENSION_MULTIPLIER=builder.comment("Gameplay multiplier applied to particle settling speed for minimum liquid transport velocity.").defineInRange("solidSuspensionMultiplier",solidDefaults.suspensionMultiplier(),.01,10000.0);
        FILTER_CAPACITY=builder.comment("Retained solid volume capacity of an in-line filter (m3). Existing captured material is preserved when this changes.").defineInRange("filterSolidCapacityCubicMetres",solidDefaults.filterCapacity(),1e-9,1000.0);
        FILTER_RESISTANCE=builder.comment("Clean in-line filter resistance at 1 mPa.s, in Pa.s/m3.").defineInRange("filterCleanResistance",solidDefaults.filterResistance(),1.0,1e15);
        FLUID_INITIAL_INTERVAL_SECONDS=builder.comment("Starting hydraulic cadence for newly created islands. Existing saved clocks retain their cadence; adaptation uses 1-20 s.").defineInRange("initialIntervalSeconds",5,1,20);
        FLUID_ADAPTIVE_CADENCE=builder.comment("Adapt island cadence to measured CPU load. Disable for reproducible fixed-cadence qualification; saved simulation debt is retained.").define("adaptiveCadence",true);
        FLUID_WALL_BUDGET_MILLISECONDS=builder.comment("Hard worker budget per island interval: a solve past it counts as failed, and the adaptive cadence reads 75% of it as its slow-solve threshold. Applied on server start.").defineInRange("wallBudgetMilliseconds",2000,100,60000);
        FLUID_MAXIMUM_VELOCITY=builder.comment("Maximum bulk pipe velocity in m/s, also limited by the current fluid's acoustic bound. Caps mass transfer inside the coupled equations; does not set temperature. Captured on server start.").defineInRange("maximumVelocityMetresPerSecond",100.0,.01,100000.0);
        FLUID_INITIAL_VOLUME=builder.comment("Volume of newly placed reservoirs in cubic metres. Applied on server start.").defineInRange("reservoirVolumeCubicMetres",1.0,.001,1000.0);
        FLUID_TRACE_CUTOFF=builder
                .comment("Trace cutoff as a mole fraction for the hydraulic network's Newton unknowns, not a feed filter.",
                        "A component whose seed mole fraction is below this in one hydrocarbon phase and at or above it",
                        "in the other keeps a single unknown - its component total, in the phase it is actually in - and",
                        "loses its equilibrium row; the omitted phase is held at exactly zero, so every component total,",
                        "transport split and conservation audit stays exact. The converged fugacity coefficients put a",
                        "component back into both phases as soon as its equilibrium fraction reaches ten times this value.",
                        "0 is the exact off switch: the identical numerical path, not a very small cutoff.",
                        "Applied on server start.")
                .defineInRange("fluidTraceCutoffMoleFraction",FluidThermodynamics.DEFAULT_TRACE_CUTOFF_MOLE_FRACTION,
                        0,FluidThermodynamics.MAX_TRACE_CUTOFF_MOLE_FRACTION);
        FLUID_DEBUG_CHAT=builder.comment("Report held fluid intervals in chat, at most once per second; full details remain in the server log.").define("debugChat",false);
        FLUID_REST_DETECTION=builder.comment("Certify islands whose interval map is stationary and advance them without solving, by replaying the last solved interval",
                "(the identity when nothing moved). Off disables certificates; islands solve every interval. Captured on server start.")
                .define("restDetection",true);
        FLUID_CERTIFICATE_TOLERANCE=builder.comment("Largest change between two consecutive intervals that still counts as stationary: per node and component,",
                "relative to that inventory; per node energy, relative to its thermal scale; per pipe, relative to the island's largest flow;",
                "and the largest per-interval drift of a node's temperature or pressure. 0 admits only intervals that repeat exactly.",
                "The default 1e-7 lets through-flow islands whose holdup drifts by a few 1e-7 per interval certify; their replay stays within the inventory budget. Captured on server start.")
                .defineInRange("certificateStationaryTolerance",1e-7,0,1e-6);
        FLUID_CERTIFICATE_BUDGET=builder.comment("Largest relative inventory change a certificate may extrapolate before one interval is solved again. Captured on server start.")
                .defineInRange("certificateInventoryBudget",1e-6,1e-12,1e-3);
        FLUID_CERTIFICATE_MAXIMUM_INTERVALS=builder.comment("Most intervals one certificate of an island on which something moves may replay; 17280 is one game day at a 5 s cadence. Captured on server start.")
                .defineInRange("certificateMaximumIntervals",17_280,1,1_000_000);
        FLUID_REST_CONFIRM_INTERVALS=builder.comment("Consecutive stationary solved intervals before an island certifies, and again after a property hold. Captured on server start.")
                .defineInRange("restConfirmIntervals",2,1,10);
        FLUID_REST_RECHECK_SECONDS=builder.comment("Solve a certified island on which nothing moves (the identity map) again after this many simulated seconds; 0 means never. Captured on server start.")
                .defineInRange("restRecheckSeconds",0,0,86_400);
        builder.pop();
        builder.push("columnV3");
        COLUMN_V3_LIQUID_SUPPLY_SCREEN_RATIO = builder
                .comment("Request-only liquid-supply screen: reject a specification whose authored side draws withdraw",
                        "at least this fraction of the liquid that reflux, feed and authored pumparound condensation",
                        "can deliver to their trays. No flash and no thermodynamics; a rejected request fails in",
                        "microseconds with INFEASIBLE_SPECIFICATION instead of seconds of Newton work.",
                        "0.30 is calibrated, not proved: over 474 independently generated solvable requests the worst",
                        "reached 0.2176, and 0.30 typed 16 of 405 and 11 of 252 never-solved requests with no false",
                        "positive. Do not lower it below 0.2176 without re-measuring. A ratio at or above 1 is",
                        "physically necessary and is always rejected, whatever this value is.",
                        "0 disables the calibrated tier and leaves only that necessary rejection, which is what a",
                        "research probe on the draw-wall specifications wants.",
                        "Captured at admission; config reloads do not change in-flight solves.")
                .defineInRange("columnV3LiquidSupplyScreenRatio",
                        V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO, 0.0, 1.0);
        builder.pop();
        builder.push("equipment");
        EQUIPMENT_PRIMARY_COLOUR = builder
                .comment("Primary colour of new equipment designs as #RRGGBB: shell, heads, cladding and unit casings.",
                        "Built-in: RAL 9006 white aluminium. The constructor's Paint page changes it per design.")
                .define("primaryColour", Paint.hex(Paint.DEFAULT.body()), Paint::isHex);
        EQUIPMENT_SECONDARY_COLOUR = builder
                .comment("Secondary colour of new equipment designs as #RRGGBB: skirts, saddles and steelwork.",
                        "Built-in: RAL 7031 blue grey.")
                .define("secondaryColour", Paint.hex(Paint.DEFAULT.structure()), Paint::isHex);
        EQUIPMENT_SHEEN = builder
                .comment("Metallic sheen of round equipment bodies, 0 to 0.4: a fixed highlight baked over their shade (0: none).",
                        "It is a vertex colour, so it costs nothing per frame and shows under Sodium and Iris shader packs too.",
                        "A round body seen from the north or south shows a bright band, from the east or west bright rims.",
                        "Read when the models are baked: restart the client or reload resources (F3+T) after a change.")
                .defineInRange("metallicSheen", 0.2, 0.0, 0.4);
        builder.pop();
        // Electricity (batch 2026-11-03-rotating-machinery W5, reading A66).
        builder.push("energy");
        ENERGY_JOULES_PER_FE = builder.comment("Joules per forge energy unit (FE). Motors, fans and generators convert their watts at this rate; stores keep joules, so a change keeps the energy they hold. Default 2.5, Mekanism's joule.")
                .defineInRange("joulesPerFE", 2.5, 0.01, 1e6);
        ENERGY_MACHINES_NEED_POWER = builder.comment("Whether motors, air cooler fans and cooling tower fans need FE at their terminal box. False: nothing is drawn, nothing stops for want of power, and the ports still accept FE (a pack without energy mods).")
                .define("machinesNeedPower", true);
        builder.pop();
        // Pump wear (batch 2026-11-03-rotating-machinery W10b, owner decision D34).
        builder.push("machines");
        builder.push("wear");
        WEAR_ENABLED = builder.comment("Whether pumps wear from cavitation and from running dry, and fail at 0 % health until a repair kit restores them. False: nothing wears and no pump stands failed.")
                .define("enabled", true);
        WEAR_CAVITATION_HOURS = builder.comment("A pump's life in online hours at the worst cavitation (its NPSH available about twice its NPSH3). Longer elsewhere: 5.1 h at NPSH3, 18.9 h at 3.5 times it, none from 4 times it up.")
                .defineInRange("cavitationHours", 2.0, 0.001, 1e6);
        WEAR_DRY_MINUTES = builder.comment("A pump's life in online minutes running dry: its driver on while it is gas- or vapour-locked or its suction holds no liquid.")
                .defineInRange("dryMinutes", 10.0, 0.01, 1e6);
        builder.pop();
        builder.pop();
        CONFIG_SPEC = builder.build();
    }

    public IChemE(IEventBus modEventBus, ModContainer modContainer) {
        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        com.wormzjl.icheme.registry.ModCreativeTabs.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModMenus.register(modEventBus);
        modEventBus.addListener(ProcessSimulatorNetwork::register);
        modEventBus.addListener(com.wormzjl.icheme.network.FluidNetwork::register);
        modEventBus.addListener(com.wormzjl.icheme.network.EquipmentNetwork::register);
        modEventBus.addListener(com.wormzjl.icheme.network.AssemblyNetwork::register);
        modEventBus.addListener(com.wormzjl.icheme.network.MachineNetwork::register);
        modEventBus.addListener(IChemE::registerCapabilities);
        modContainer.registerConfig(ModConfig.Type.COMMON, CONFIG_SPEC, "icheme-common.toml");
        // The client's own settings (the process screens' panel theme); a dedicated server never loads them.
        modContainer.registerConfig(ModConfig.Type.CLIENT, com.wormzjl.icheme.client.ClientConfig.SPEC, "icheme-client.toml");
        // A dedicated server never draws equipment: its geometries keep no facets once laid out.
        if (FMLEnvironment.dist.isDedicatedServer()) com.wormzjl.icheme.science.equipment.ShapeRasterizer.serverOnly();

        // ProcessSolveServices is thread-confined to the logical server. Pin every lifecycle edge to the GAME bus.
        IEventBus gameEventBus = NeoForge.EVENT_BUS;
        gameEventBus.addListener(com.wormzjl.icheme.material.MaterialReloadListener::register);
        gameEventBus.addListener(IChemE::onServerStarting);
        com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority.listen(com.wormzjl.icheme.runtime.assembly.AssemblyService::edited);
        gameEventBus.addListener(IChemE::onServerTickPost);
        gameEventBus.addListener(IChemE::onServerStopping);
        gameEventBus.addListener(IChemE::onServerStopped);
        gameEventBus.addListener(IChemE::onFluidChunkLoaded);
        gameEventBus.addListener(IChemE::onPlayerLoggedOut);
        gameEventBus.addListener(IChemE::onLevelTickPost);
    }
    /** The FE port of a structure's terminal box (W5): the shell block entity's energy capability, on its terminal cell only. */
    private static void registerCapabilities(net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK, ModBlockEntities.EQUIPMENT_SHELL.get(), (shell, side) -> shell.energy());
    }
    /** The floating roofs that rise this tick lift what stands on them (a read of each tank's glide; nothing solves). */
    private static void onLevelTickPost(net.neoforged.neoforge.event.tick.LevelTickEvent.Post event) {
        if(!event.getLevel().isClientSide())com.wormzjl.icheme.world.level.block.FloatingRoofs.carry(event.getLevel());
    }
    /** A player who leaves watches no tank any more. */
    private static void onPlayerLoggedOut(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player&&player.server.isSameThread())
            com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority.find(player.server).ifPresent(world->world.forgetWatcher(player.getUUID()));
    }
    private static void onFluidChunkLoaded(net.neoforged.neoforge.event.level.ChunkEvent.Load event) {
        if(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            var server=level.getServer();var dimension=level.dimension();long chunk=event.getChunk().getPos().toLong();
            // Always queue: a load event may fire before the chunk is visible to getChunkNow, even on this thread.
            server.tell(new net.minecraft.server.TickTask(server.getTickCount(),()->{
                com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority.loadedChunk(server,dimension,chunk);
                var loaded=server.getLevel(dimension);if(loaded!=null)com.wormzjl.icheme.runtime.assembly.AssemblyService.chunkLoaded(loaded,new net.minecraft.world.level.ChunkPos(chunk));
            }));
        }
    }

    /** Joules per FE (W5; default 2.5 before the config loads). */
    public static double joulesPerFE() {
        return CONFIG_SPEC.isLoaded() ? ENERGY_JOULES_PER_FE.get() : 2.5;
    }

    /** The pumps' wear settings (W10b; the defaults before the config loads). */
    public static com.wormzjl.icheme.science.machine.Wear.Settings wearSettings() {
        if (!CONFIG_SPEC.isLoaded()) return com.wormzjl.icheme.science.machine.Wear.Settings.DEFAULT;
        return new com.wormzjl.icheme.science.machine.Wear.Settings(WEAR_ENABLED.get(), WEAR_CAVITATION_HOURS.get(), WEAR_DRY_MINUTES.get());
    }

    /** Whether machines need FE (W5; default true before the config loads). */
    public static boolean machinesNeedPower() {
        return !CONFIG_SPEC.isLoaded() || ENERGY_MACHINES_NEED_POWER.get();
    }

    /** The equipment's metallic sheen, 0 to 0.4 (0: none; default 0.2). */
    public static float equipmentSheen() {
        return EQUIPMENT_SHEEN.get().floatValue();
    }

    /** The paint of a new equipment design: the config's primary and secondary colours. */
    public static Paint defaultPaint() {
        return new Paint(Paint.parseHex(EQUIPMENT_PRIMARY_COLOUR.get()), Paint.parseHex(EQUIPMENT_SECONDARY_COLOUR.get()));
    }

    public static boolean calculationLoggingEnabled() {
        return CALCULATION_LOGGING.getAsBoolean();
    }
    public record FluidOptions(int initialCadenceTicks,long wallBudgetNanos,double volume,boolean debugChat,boolean adaptiveCadence,double maximumVelocity,double traceCutoffMoleFraction,com.wormzjl.icheme.science.fluid.transport.SlurryTransport.Settings solids,com.wormzjl.icheme.runtime.fluid.CertificatePolicy certificates) {
        public FluidOptions {java.util.Objects.requireNonNull(solids);java.util.Objects.requireNonNull(certificates);}
    }
    public static FluidOptions fluidOptions() {
        return new FluidOptions(20*FLUID_INITIAL_INTERVAL_SECONDS.getAsInt(),1_000_000L*FLUID_WALL_BUDGET_MILLISECONDS.getAsInt(),FLUID_INITIAL_VOLUME.get(),FLUID_DEBUG_CHAT.getAsBoolean(),FLUID_ADAPTIVE_CADENCE.getAsBoolean(),FLUID_MAXIMUM_VELOCITY.get(),FLUID_TRACE_CUTOFF.get(),new com.wormzjl.icheme.science.fluid.transport.SlurryTransport.Settings(SOLID_IMMOBILE_VISCOSITY.get(),SOLID_TRACE_FRACTION.get(),SOLID_SUSPENSION_MULTIPLIER.get(),FILTER_CAPACITY.get(),FILTER_RESISTANCE.get()),
                new com.wormzjl.icheme.runtime.fluid.CertificatePolicy(FLUID_REST_DETECTION.getAsBoolean(),FLUID_CERTIFICATE_TOLERANCE.get(),FLUID_CERTIFICATE_BUDGET.get(),FLUID_CERTIFICATE_MAXIMUM_INTERVALS.getAsInt(),FLUID_REST_CONFIRM_INTERVALS.getAsInt(),FLUID_REST_RECHECK_SECONDS.getAsInt()));
    }

    /** Read on the server thread when admitting a V3 request, never from its worker. */
    public static double columnV3LiquidSupplyScreenRatio() {
        return COLUMN_V3_LIQUID_SUPPLY_SCREEN_RATIO.get();
    }

    private static void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        try {
            // Parse the bundled model before admitting work, as the default config did: the simulator's column starts from it (ColumnUnit.START).
            V3NeuralRegistry.bundled();
            ProcessSolveServices.ServerStarted started =
                    ProcessSolveServices.startServer(server, solveServiceConfig());
            com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority.start(server);
            if (calculationLoggingEnabled()) {
                LOGGER.info(
                        "process_solver lifecycle=STARTED epoch={} workers={} ready_capacity={} "
                                + "deadline_ms={} graceful_shutdown_ms={} forced_shutdown_ms={}",
                        started.serverEpoch(),
                        started.workerCount(),
                        started.readyCapacity(),
                        started.solveDeadlineMilliseconds(),
                        started.gracefulShutdownMilliseconds(),
                        started.forcedShutdownMilliseconds());
            }
        } catch (RuntimeException exception) {
            LOGGER.error("process_solver lifecycle=START_FAILED", exception);
            throw exception;
        }
    }

    private static void onServerTickPost(ServerTickEvent.Post event) {
        com.wormzjl.icheme.runtime.fluid.FluidRuntimeDiagnostics.Meter.enter(event.getServer());
        try {
            ProcessSolveCoordinator.drainCompletedCalculations(event.getServer());
            com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority.tick(event.getServer());
            com.wormzjl.icheme.runtime.assembly.AssemblyService.tick(event.getServer());
        } catch (RuntimeException exception) {
            LOGGER.error("process_solver lifecycle=DRAIN_FAILED", exception);
            throw exception;
        } finally {
            com.wormzjl.icheme.runtime.fluid.FluidRuntimeDiagnostics.Meter.exit(event.getServer());
        }
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        try {
            com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority.stop(event.getServer());
            ProcessSolveCoordinator.stopCalculations(event.getServer());
        } catch (RuntimeException exception) {
            LOGGER.error("process_solver lifecycle=STOP_FAILED", exception);
            throw exception;
        }
    }


    private static void onServerStopped(ServerStoppedEvent event) {
        try {
            // ServerStopped can still fire after an abnormal lifecycle path that skipped ServerStopping.
            com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority.forget(event.getServer());
            com.wormzjl.icheme.runtime.assembly.AssemblyService.forget(event.getServer());
            com.wormzjl.icheme.runtime.fluid.FluidRuntimeDiagnostics.Meter.forget(event.getServer());
            ProcessSolveCoordinator.stopCalculations(event.getServer());
            int remainingContexts = ProcessSolveServices.removeStoppedServer(event.getServer());
            com.wormzjl.icheme.science.material.MaterialRuntime.reset();
            if (remainingContexts != 0) {
                LOGGER.error(
                        "process_solver lifecycle=STOPPED unexpected_remaining_contexts={}",
                        remainingContexts);
            } else if (calculationLoggingEnabled()) {
                LOGGER.info("process_solver lifecycle=STOPPED");
            }
        } catch (RuntimeException exception) {
            LOGGER.error("process_solver lifecycle=REMOVE_FAILED", exception);
            throw exception;
        }
    }

    private static ProcessSolveServices.Config solveServiceConfig() {
        return new ProcessSolveServices.Config(
                WorkerAllocation.resolve(
                        SOLVER_WORKERS.getAsInt(), Runtime.getRuntime().availableProcessors(),SOLVER_AUTOMATIC_WORKER_LIMIT.getAsInt()),
                SOLVER_READY_CAPACITY.getAsInt(),
                Duration.ofMillis(SOLVER_DEADLINE_MILLISECONDS.getAsInt()),
                Duration.ofMillis(SOLVER_GRACEFUL_SHUTDOWN_MILLISECONDS.getAsInt()),
                Duration.ofMillis(SOLVER_FORCED_SHUTDOWN_MILLISECONDS.getAsInt()),SOLVER_WORKERS.getAsInt()==0);
    }

}
