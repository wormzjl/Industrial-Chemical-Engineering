package com.wormzjl.icheme.fluid.gametest;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * The dev-only world preset "IChemE column test" (batch 2026-11-16-review-plant-sizing, COLUMN_TEST_WORLD.md): a superflat
 * creative world ({@code data/icheme_fluid_test/worldgen/world_preset/column_test.json}, listed in the Create World screen
 * through the {@code minecraft:normal} preset tag) in which the review rig's crude unit ({@link ColumnOperationReviewWorld}) is
 * built at spawn on the world's first load, by the rig's own code, and started in the rig's order. Loaded only with the test
 * mod ({@code runReviewClient}, the GameTest servers); the plain client never sees the preset.
 *
 * <p>The start is a small state machine on the server tick that only reads published state (registrations, views, the
 * column's phase) and queues the rig's own engine events (device edits, the charge drive's {@code Configure}, the column's
 * start): it never solves or computes process state.
 */
final class ColumnTestWorld {
    private ColumnTestWorld() {}

    /** The preset's key. */
    static final ResourceKey<WorldPreset> PRESET = ResourceKey.create(Registries.WORLD_PRESET, ResourceLocation.fromNamespaceAndPath("icheme_fluid_test", "column_test"));
    /** The test mod's saved flag on the overworld ({@link Flag}): {@code data/icheme_fluid_test_column_test_world.dat}. */
    static final String DATA = "icheme_fluid_test_column_test_world";
    /** Online ticks each start step may wait for its condition before the start stops (5 minutes of play). */
    static final int PATIENCE = 6000;
    /** Blocks between the plant's box and the spawn, north of it. */
    static final int SPAWN_GAP = 4;

    /** The start running in this server, or null. */
    private static @Nullable Start running;

    static void register(IEventBus bus) {
        bus.addListener(ColumnTestWorld::started);
        bus.addListener(ColumnTestWorld::tick);
        bus.addListener(ColumnTestWorld::login);
        bus.addListener(ColumnTestWorld::stopping);
    }

    /**
     * Whether {@code generator} is this preset's overworld: a flat generator whose settings encode exactly as the preset's
     * (its layers, its biome, no lakes, no features and an empty structure list). The GameTest world's flat preset carries the
     * villages, a player's Classic Flat too; only a flat world rebuilt by hand from an empty-structure preset to these very
     * layers could match, and {@link #started} builds only in a world at game time 0 with the test mod loaded.
     */
    static boolean isPreset(MinecraftServer server, ChunkGenerator generator) {
        if (!(generator instanceof FlatLevelSource flat)) return false;
        var own = presetSettings(server);
        if (own == null) return false;
        var ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        Optional<JsonElement> a = FlatLevelGeneratorSettings.CODEC.encodeStart(ops, flat.settings()).result(), b = FlatLevelGeneratorSettings.CODEC.encodeStart(ops, own).result();
        return a.isPresent() && a.equals(b);
    }

    /** The preset's overworld flat settings, or null where the preset is not loaded or its overworld is not flat. */
    static @Nullable FlatLevelGeneratorSettings presetSettings(MinecraftServer server) {
        var preset = server.registryAccess().registry(Registries.WORLD_PRESET).flatMap(r -> r.getOptional(PRESET)).orElse(null);
        if (preset == null) return null;
        var stem = preset.overworld().orElse(null);
        return stem != null && stem.generator() instanceof FlatLevelSource f ? f.settings() : null;
    }

    static Flag flag(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(new SavedData.Factory<>(Flag::new, Flag::load, null), DATA);
    }

    /** On a fresh world of this preset: the world set up, the plant built at spawn and its start begun. */
    private static void started(ServerStartedEvent event) {
        var server = event.getServer();
        var level = server.overworld();
        if (!isPreset(server, level.getChunkSource().getGenerator())) return;
        var flag = flag(level);
        if (flag.chunks.length > 0) {
            // The world closed during the start: the rig's state was in memory, so the start does not resume.
            IChemE.LOGGER.info("column_test_world step=start not finished before the world closed; finish it by hand (heaters, charge drive, column)");
            release(level, flag);
        }
        if (flag.built) return;
        if (level.getGameTime() != 0) {
            IChemE.LOGGER.info("column_test_world skipped: a superflat world like the preset's that has run before (game time {}) is not built in", level.getGameTime());
            return;
        }
        setUp(server, level);
        flag.built = true;
        flag.setDirty();
        ColumnOperationReviewWorld.Rig rig;
        try {
            rig = build(level, level.getSharedSpawnPos());
        } catch (RuntimeException e) {
            IChemE.LOGGER.error("column_test_world build failed", e);
            return;
        }
        var chunks = rig.plant.chunks();
        flag.chunks = chunks.stream().mapToLong(ChunkPos::toLong).toArray();
        flag.setDirty();
        spawn(level, rig);
        begin(new Start(rig, ok -> {
            release(level, flag);
            flag.started = ok;
            flag.setDirty();
        }));
    }

    /** Creative, no daylight, weather or mob cycle, clear weather at noon, players spawned on the spot. */
    static void setUp(MinecraftServer server, ServerLevel level) {
        server.setDefaultGameType(GameType.CREATIVE);
        var rules = level.getGameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        rules.getRule(GameRules.RULE_SPAWN_RADIUS).set(0, server);
        level.setWeatherParameters(12000, 0, false, false);
        level.setDayTime(6000);
    }

    /**
     * The review plant built by the rig's code ({@link ColumnOperationReviewWorld#review}, {@link ColumnOperationReviewWorld#rig(ServerLevel,
     * BlockPos, ColumnOperationReviewWorld.Options)}, {@link ColumnOperationReviewWorld.Rig#build}) with its column's origin on the
     * ground at {@code at}'s x and z; its chunks are held loaded by the build (the rig's {@code hold}) until the start ends.
     */
    static ColumnOperationReviewWorld.Rig build(ServerLevel level, BlockPos at) {
        return build(level, at, ColumnOperationReviewWorld.review(MaterialRuntime.active()));
    }

    /** {@link #build(ServerLevel, BlockPos)} on sizes already made ({@code options}, the rig's {@code review}). */
    static ColumnOperationReviewWorld.Rig build(ServerLevel level, BlockPos at, ColumnOperationReviewWorld.Options options) {
        level.getChunk(at.getX() >> 4, at.getZ() >> 4);
        int ground = level.getHeight(Heightmap.Types.WORLD_SURFACE, at.getX(), at.getZ());
        var base = new BlockPos(at.getX(), ground, at.getZ());
        var rig = ColumnOperationReviewWorld.rig(level, base, options);
        rig.build();
        IChemE.LOGGER.info("column_test_world built origin={} devices={} structures={} box={}..{}", base.toShortString(), rig.plant.devices.size(), rig.plant.structures.size(),
                rig.plant.low.toShortString(), rig.plant.high.toShortString());
        return rig;
    }

    /** The world spawn north of the plant's box, outside it, at the column's x, facing south at the column. */
    static void spawn(ServerLevel level, ColumnOperationReviewWorld.Rig rig) {
        var plant = rig.plant;
        var at = new BlockPos(plant.base.getX(), plant.base.getY(), plant.low.getZ() - SPAWN_GAP);
        level.setDefaultSpawnPos(at, 0);
        IChemE.LOGGER.info("column_test_world spawn={} facing=south", at.toShortString());
    }

    private static void release(ServerLevel level, Flag flag) {
        for (long chunk : flag.chunks) level.setChunkForced(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false);
        flag.chunks = new long[0];
        flag.setDirty();
    }

    /** {@code start} driven by the server tick from the next tick on (the preset's own path; its GameTest's too). */
    static void begin(Start start) {
        running = start;
    }

    private static void tick(ServerTickEvent.Post event) {
        var start = running;
        if (start != null && start.step()) running = null;
    }

    /** Every player joins a column test world in creative. */
    private static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var server = player.getServer();
        if (server == null) return;
        var flag = server.overworld().getDataStorage().get(new SavedData.Factory<>(Flag::new, Flag::load, null), DATA);
        if (flag != null && flag.built && player.gameMode.getGameModeForPlayer() != GameType.CREATIVE) player.setGameMode(GameType.CREATIVE);
    }

    private static void stopping(ServerStoppingEvent event) {
        running = null;
    }

    /**
     * The rig's start in its GameTest's order ({@code ColumnOperationReviewWorld.aCrudeColumnRuns}), one step a tick at most:
     * the valves installed; 20 ticks; every nozzle joined; the charge pump's train compiled, then its set flow and drive held
     * ({@link ColumnOperationReviewWorld.Rig#holdCharge}) and the heaters lit ({@link ColumnOperationReviewWorld.Rig#light});
     * the heaters' fuel and power, then the charge drive started and the crude opened ({@link ColumnOperationReviewWorld.Rig#openCrude});
     * the charge line hot (or the rig's {@code HOT_TICKS}), then the plant validated complete and the column started
     * ({@link ColumnOperationReviewWorld.Rig#start}); the column past its first solve. A step whose condition does not hold
     * within {@link #PATIENCE} ticks logs {@code column_test_world step=<name> not reached} and ends the start.
     */
    static final class Start {
        enum Step { VALVES, SETTLE, DRIVE, FUEL, WARM, FIRST_SOLVE, DONE, STOPPED }

        final ColumnOperationReviewWorld.Rig rig;
        private final Consumer<Boolean> end;
        private final boolean paced;
        private Step step = Step.VALVES;
        private int waited;
        private final String[] failure = {null};

        /** {@code end} is told, once, whether the column started and passed its first solve. */
        Start(ColumnOperationReviewWorld.Rig rig, Consumer<Boolean> end) {
            this(rig, end, false);
        }

        /**
         * {@code paced}: each tick first waits for the solves running on workers ({@link ColumnOperationReviewWorld.Rig#paced}),
         * as the rig's GameTest does: a GameTest server ticks far ahead of the solves. A game runs at 20 ticks a second: unpaced.
         */
        Start(ColumnOperationReviewWorld.Rig rig, Consumer<Boolean> end, boolean paced) {
            this.rig = rig;
            this.end = end;
            this.paced = paced;
            IChemE.LOGGER.info("column_test_world step=valves waiting");
        }

        Step state() {
            return step;
        }

        /** One tick of the start; returns whether it has ended (done or stopped). */
        boolean step() {
            if (step == Step.DONE || step == Step.STOPPED) return true;
            if (paced) rig.paced();
            try {
                switch (step) {
                    case VALVES -> {
                        if (rig.plant.valvesInstalled()) next(Step.SETTLE);
                    }
                    case SETTLE -> {
                        if (waited >= 20) {
                            var unjoined = rig.plant.unjoined();
                            if (unjoined != null) failure[0] = "unjoined nozzles: " + unjoined;
                            else next(Step.DRIVE);
                        }
                    }
                    case DRIVE -> {
                        if (rig.chargeDriven()) {
                            rig.holdCharge(failure);
                            if (failure[0] == null) rig.light(failure);
                            if (failure[0] == null) next(Step.FUEL);
                        }
                    }
                    case FUEL -> {
                        if (rig.fueled()) {
                            if (rig.chargeEnd != null) rig.openCrude();
                            next(Step.WARM);
                        }
                    }
                    case WARM -> {
                        if (rig.warmed()) {
                            rig.start(failure);
                            if (failure[0] == null) next(Step.FIRST_SOLVE);
                        }
                    }
                    case FIRST_SOLVE -> {
                        if (rig.past()) {
                            IChemE.LOGGER.info("column_test_world done t={} column={}", rig.plant.world.onlineTick(), rig.plant.column.origin().toShortString());
                            finish(Step.DONE);
                            return true;
                        }
                    }
                    default -> {
                    }
                }
            } catch (RuntimeException e) {
                failure[0] = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            if (failure[0] != null) {
                IChemE.LOGGER.info("column_test_world step={} failed: {}", name(step), failure[0]);
                finish(Step.STOPPED);
                return true;
            }
            if (++waited > PATIENCE) {
                IChemE.LOGGER.info("column_test_world step={} not reached", name(step));
                finish(Step.STOPPED);
                return true;
            }
            return false;
        }

        private void next(Step to) {
            IChemE.LOGGER.info("column_test_world step={} reached t={} ticks={}", name(step), rig.plant.world.onlineTick(), waited);
            step = to;
            waited = 0;
        }

        private void finish(Step to) {
            step = to;
            end.accept(to == Step.DONE);
        }

        private static String name(Step step) {
            return step.name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * The test mod's own flag on the overworld (new; not a saved format of the mod): whether the plant was built (never
     * again), whether its start ended with the column past its first solve, and the chunks the build holds loaded until the
     * start ends (released at the next server start if the world closed before).
     */
    static final class Flag extends SavedData {
        boolean built, started;
        long[] chunks = new long[0];

        static Flag load(CompoundTag tag, HolderLookup.Provider registries) {
            var flag = new Flag();
            flag.built = tag.getBoolean("built");
            flag.started = tag.getBoolean("started");
            flag.chunks = tag.getLongArray("chunks");
            return flag;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putBoolean("built", built);
            tag.putBoolean("started", started);
            tag.putLongArray("chunks", chunks);
            return tag;
        }
    }
}
