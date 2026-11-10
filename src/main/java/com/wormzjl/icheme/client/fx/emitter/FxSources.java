package com.wormzjl.icheme.client.fx.emitter;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.client.fx.FxAmbient;
import com.wormzjl.icheme.client.fx.FxBatch;
import com.wormzjl.icheme.client.fx.FxGlide;
import com.wormzjl.icheme.client.fx.FxGuard;
import com.wormzjl.icheme.client.fx.FxKind;
import com.wormzjl.icheme.client.fx.FxSink;
import com.wormzjl.icheme.client.fx.FxStats;
import com.wormzjl.icheme.client.fx.FxWind;
import com.wormzjl.icheme.client.fx.state.CellWatch;
import com.wormzjl.icheme.client.fx.state.ProcessStates;
import com.wormzjl.icheme.network.FluidNetwork;
import com.wormzjl.icheme.network.FluidNetwork.Target;
import com.wormzjl.icheme.runtime.fluid.Flares;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.fluid.network.BatchStill;
import com.wormzjl.icheme.science.fluid.network.Burners;
import com.wormzjl.icheme.science.fluid.network.Flare;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The effects of the watched structures (batch 2026-11-10-custom-shader-system, plan section 4.5; W3 flare, W4 tower plume,
 * W5 fired heater stack, W6 fuelled still, W8 heat haze). Client thread only. Every quarter second it reads the stores of
 * {@link ProcessStates} and keeps one source per watched structure; a source aims its emitters ({@link GlideEmitter},
 * drawn by {@link FxBatch}) at each new published state, and again every {@link #RETARGET} s for the presentation wind
 * ({@link FxWind}) and the visual ambient ({@link FxAmbient}). Nothing here infers process state, plays a sound or runs
 * on the tick beyond reading the stores; a structure no longer watched loses its effects.
 * <ul>
 * <li><b>Flare</b> ({@code flare_tip}): the flame ({@link FlameModel}) and pilots; soot smoke from the flame's end, or a
 * pale plume of unburnt gas from the tip while the flame is out; haze over the flame.</li>
 * <li><b>Cooling tower</b> ({@code fan_exhaust_<i>}, or the natural tower's {@code throat}): the white plume by the
 * mixing line ({@link PlumeModel}).</li>
 * <li><b>Fired heater</b> ({@code stack_top_<i>}): the white plume; grey smoke for liquid or solid fuel; haze over a
 * hot stack.</li>
 * <li><b>Fuelled still</b> ({@code door}, {@code fire_bed}, {@code burner}, {@code flue_top}, {@code spout}): the fire at
 * the door ({@link StillModel}), the flue's smoke, the drip at the spout. An electric still has no effect.</li>
 * </ul>
 */
@EventBusSubscriber(modid = IChemE.MOD_ID, value = Dist.CLIENT)
public final class FxSources {
    /** A source re-aims this often without a new snapshot, s: the wind and the weather move. */
    public static final double RETARGET = 5;
    /** Puffs per tower or stack plume, and per smoke trail (plan section 4.6). */
    public static final int PLUME_PUFFS = 32, SMOKE_PUFFS = 16;
    /** A flare's soot smoke and unburnt gas rise at this share of the wind: buoyant smoke with no jet of its own. */
    static final double SMOKE_RATIO = .3;
    /** A small flue's natural draught at no fire and the rise at full fire, m/s: the still publishes no flue flow. */
    static final double FLUE_DRAUGHT = 1, FLUE_DRAUGHT_FIRE = 2;
    static final double DRIP_PERIOD = .5, DRIP_FALL = .3, DRIP_HALF = .012;
    private static final Map<Target, Source<?>> SOURCES = new HashMap<>();
    private static int age;

    private FxSources() {
    }

    /** Sources alive now. */
    public static int size() {
        return SOURCES.size();
    }

    /** Ends every effect: the level changed or the client left. */
    public static void clear() {
        for (var source : SOURCES.values()) source.kill();
        SOURCES.clear();
        age = 0;
    }

    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            if (!SOURCES.isEmpty()) clear();
            return;
        }
        if (mc.isPaused() || age++ % 5 != 0) return;
        double now = FxGlide.seconds(level.getGameTime(), 0);
        var wind = FxWind.vector(level.dimension().location().toString(), now);
        var seen = new HashSet<Target>();
        sync(ProcessStates.flares(), FlareSource::new, "flare", seen, level, wind, now);
        sync(ProcessStates.towers(), TowerSource::new, "tower_plume", seen, level, wind, now);
        sync(ProcessStates.heaters(), HeaterSource::new, "heater_flue", seen, level, wind, now);
        sync(ProcessStates.stills(), StillSource::new, "still_fire", seen, level, wind, now);
        var gone = new ArrayList<Target>();
        for (var target : SOURCES.keySet()) if (!seen.contains(target)) gone.add(target);
        for (var target : gone) SOURCES.remove(target).kill();
        FxStats.set(FxStats.Counter.SOURCES, SOURCES.size());
    }

    @SuppressWarnings("unchecked")
    private static <S> void sync(List<CellWatch.Watched<S>> watched, Function<CellWatch.Watched<S>, Source<S>> make, String type, Set<Target> seen,
                                 ClientLevel level, FxWind.Wind wind, double now) {
        var guard = FxGuard.effect(type);
        if (guard.disabled()) return;
        for (var w : watched) {
            seen.add(w.target());
            guard.run(() -> {
                var source = (Source<S>) SOURCES.get(w.target());
                if (source == null || source.cell != w.cell()) {
                    if (source != null) source.kill();
                    source = make.apply(w);
                    SOURCES.put(w.target(), source);
                }
                source.update(w.state(), level, wind, now);
            });
        }
    }

    /** One watched structure's effects: aimed at a new state, or every {@link #RETARGET} s. */
    /**
     * A source's effect seed from its key: all 64 bits mixed (Fibonacci hashing), then the top 12 kept. The low 12 bits of a
     * {@code BlockPos.asLong} are its y alone, so a plain mask gave every flare of a row one seed and one flame.
     */
    static int seed(long key) {
        return (int) ((key * 0x9E3779B97F4A7C15L) >>> 52) & FxSink.SEED_MASK;
    }

    abstract static class Source<S> {
        final EquipmentShellBlockEntity cell;
        final BlockPos origin;
        final Map<String, double[]> marks;
        final int seed;
        private final List<GlideEmitter> emitters = new ArrayList<>();
        private @Nullable S last;
        private double aimedAt = Double.NEGATIVE_INFINITY;

        Source(CellWatch.Watched<S> watched) {
            cell = watched.cell();
            origin = cell.origin() == null ? cell.getBlockPos() : cell.origin();
            marks = cell.shape() == null ? Map.of() : ShapeRasterizer.marks(cell.shape());
            seed = seed(watched.target().position().asLong() ^ watched.target().identity());
        }

        void update(S state, ClientLevel level, FxWind.Wind wind, double now) {
            if (state.equals(last) && now - aimedAt < RETARGET) return;
            last = state;
            aimedAt = now;
            aim(state, level, wind, now);
        }

        abstract void aim(S state, ClientLevel level, FxWind.Wind wind, double now);

        GlideEmitter add(GlideEmitter emitter) {
            emitters.add(emitter);
            FxBatch.add(emitter);
            return emitter;
        }

        void kill() {
            for (var e : emitters) e.kill();
        }

        /** Mark {@code name}'s top centre in world coordinates, or null. */
        double @Nullable [] top(String name) {
            var b = marks.get(name);
            return b == null ? null : new double[]{origin.getX() + (b[0] + b[3]) / 2, origin.getY() + b[4], origin.getZ() + (b[2] + b[5]) / 2};
        }

        /** Mark {@code name}'s centre in world coordinates, or null. */
        double @Nullable [] centre(String name) {
            var b = marks.get(name);
            return b == null ? null : new double[]{origin.getX() + (b[0] + b[3]) / 2, origin.getY() + (b[1] + b[4]) / 2, origin.getZ() + (b[2] + b[5]) / 2};
        }

        /** Mark {@code name}'s width across x, m (a mouth's diameter), or 0. */
        double width(String name) {
            var b = marks.get(name);
            return b == null ? 0 : b[3] - b[0];
        }

        /** The marks whose names start with {@code prefix}, in the design's order. */
        List<String> named(String prefix) {
            var out = new ArrayList<String>();
            for (var name : marks.keySet()) if (name.startsWith(prefix)) out.add(name);
            return out;
        }
    }

    /** The exits of a tower or a stack: their centres (world), their diameters and their flow area. */
    record Exits(double[][] world, double diameter, double area) {
        static @Nullable Exits of(Source<?> source, List<String> names) {
            if (names.isEmpty()) return null;
            var world = new double[names.size()][];
            double area = 0, diameter = 0;
            for (int i = 0; i < names.size(); i++) {
                world[i] = source.top(names.get(i));
                double d = source.width(names.get(i));
                diameter = Math.max(diameter, d);
                area += Math.PI * d * d / 4;
            }
            return new Exits(world, diameter, area);
        }

        double[] centroid() {
            double x = 0, y = 0, z = 0;
            for (var p : world) {
                x += p[0];
                y += p[1];
                z += p[2];
            }
            return new double[]{x / world.length, y / world.length, z / world.length};
        }

        /** The exits from {@code centre}, m, and the farthest's distance, last. */
        double[][] local(double[] centre) {
            var out = new double[world.length][];
            for (int i = 0; i < world.length; i++) out[i] = new double[]{world[i][0] - centre[0], world[i][1] - centre[1], world[i][2] - centre[2]};
            return out;
        }

        double reach(double[] centre) {
            double r = 0;
            for (var p : world) r = Math.max(r, Math.sqrt((p[0] - centre[0]) * (p[0] - centre[0]) + (p[1] - centre[1]) * (p[1] - centre[1]) + (p[2] - centre[2]) * (p[2] - centre[2])));
            return r;
        }
    }

    /** A plume emitter over a set of exits: its segment count is aimed with its values. */
    static final class Plume {
        final GlideEmitter emitter;
        private int segments;
        private PlumeModel.Style style = PlumeModel.Style.WHITE;

        Plume(Source<?> source, String type, FxKind kind, double[] at, double[][] exits, int seed) {
            emitter = source.add(new GlideEmitter(type, kind, at[0], at[1], at[2], 2, rest(),
                    (sink, from, to, quads) -> PlumeModel.write(sink, exits, from, to, segments, style, seed)));
        }

        static double[] rest() {
            return PlumeModel.values(0, 0, 1, 0, 0, 0, 0, 0, 0);
        }

        /** Aims at {@code values} with at most {@code puffs} puffs over {@code exits} exits. */
        void aim(double[] values, int exits, int puffs, PlumeModel.Style style, double wind, double reach, double now) {
            int most = Math.max(1, puffs / (PlumeModel.PHASES * Math.max(1, exits)));
            segments = values[PlumeModel.DENSITY] > 0 ? PlumeModel.segments(values[PlumeModel.LENGTH] - values[PlumeModel.START], wind, emitter.driftPeriod(), most) : 0;
            // A puff grows by the plume's spread over the segment it crosses, so it arrives at the next segment's size.
            emitter.growth(segments > 0 ? PlumeModel.SPREAD * (values[PlumeModel.LENGTH] - values[PlumeModel.START]) / segments : 0);
            this.style = style;
            emitter.aim(values, segments * PlumeModel.PHASES * exits, PlumeModel.radius(values, reach), now);
        }
    }

    /** Heat haze sprites (W8): {@link #HAZE_SPRITES} stacked over {@code values}' centre, glided values strength, x, y, z, size. */
    static final int HAZE_SPRITES = 3;

    static void haze(FxSink sink, double[] from, double[] to, int quads) {
        if (!(to[0] > 0)) return;
        for (int k = 0; k < HAZE_SPRITES; k++) {
            var p = new FxSink.Point(from[1], from[2] + k * .9 * from[4], from[3], to[1], to[2] + k * .9 * to[4], to[3], from[4] * (1 + .4 * k), to[4] * (1 + .4 * k));
            sink.sprite(p, FxSink.params(0, 0, 0, to[0] * (1 - .25 * k)), 1500 + k * 101);
        }
    }

    static GlideEmitter hazeEmitter(Source<?> source, String type, double[] at) {
        return source.add(new GlideEmitter(type, FxKind.HAZE, at[0], at[1], at[2], 2, new double[5], FxSources::haze));
    }

    static void aimHaze(GlideEmitter haze, double strength, double[] centre, double size, double now) {
        double s = Math.max(0, Math.min(1, strength));
        haze.aim(new double[]{s, centre[0], centre[1], centre[2], size}, s > 0 ? HAZE_SPRITES : 0,
                Math.abs(centre[0]) + Math.abs(centre[1]) + Math.abs(centre[2]) + size * (HAZE_SPRITES + 1) * 1.4, now);
    }

    /** A flare stack: flame, smoke and haze from its tip (W3). */
    static final class FlareSource extends Source<FluidNetwork.FlareState> {
        static final PlumeModel.Style SOOT = new PlumeModel.Style(new double[]{.1, .09, .08}, new double[]{.38, .34, .3});
        static final PlumeModel.Style UNBURNT = new PlumeModel.Style(new double[]{.82, .83, .85}, new double[]{.86, .87, .88});
        private final double @Nullable [] tip;
        private final double tipRadius, rated;
        private @Nullable GlideEmitter flame, haze;
        private @Nullable Plume smoke;
        private int ribbons;

        FlareSource(CellWatch.Watched<FluidNetwork.FlareState> watched) {
            super(watched);
            tip = top("flare_tip");
            tipRadius = width("flare_tip") / 2;
            rated = cell.shape() == null ? 0 : Flare.rated(cell.shape().diameter());
            if (tip == null) return;
            flame = add(new GlideEmitter("flare_flame", FxKind.FLAME, tip[0], tip[1], tip[2], 2, FlameModel.values(0, 0, 0, 1, 0, 0, tipRadius),
                    (sink, from, to, quads) -> FlameModel.write(sink, from, to, ribbons, seed)));
            smoke = new Plume(this, "flare_smoke", FxKind.SMOKE, tip, new double[][]{{0, 0, 0}}, seed);
            haze = hazeEmitter(this, "flare_haze", tip);
        }

        @Override
        void aim(FluidNetwork.FlareState s, ClientLevel level, FxWind.Wind wind, double now) {
            if (tip == null || flame == null || smoke == null || haze == null) return;
            var status = s.status() < Flares.Status.values().length ? Flares.Status.values()[s.status()] : Flares.Status.NO_FLOW;
            boolean flaring = status == Flares.Status.FLARING || status == Flares.Status.OVER_CAPACITY;
            double share = flaring ? s.flame() : 0, length = flaring ? Flare.flameLength(share * rated) : 0, u = wind.speed();
            var v = FlameModel.values(length, wind.x(), wind.z(), s.exitVelocity(), share, s.soot(), tipRadius);
            ribbons = FlameModel.ribbons(length, share);
            flame.aim(v, ribbons * FlameModel.SEGMENTS + FlameModel.PILOTS, FlameModel.radius(v), now);
            double[] end = FlameModel.end(v);
            double period = smoke.emitter.driftPeriod();
            if (flaring && s.soot() > 0) {
                int segments = 2 + (int) Math.round(3 * s.soot());
                // Soot leaves the flame's end about one flame half-width across, so neighbouring flares' trails stay apart.
                smoke.aim(PlumeModel.values(segments * u * period, SMOKE_RATIO, wind.x(), wind.z(), .9 * s.soot(), v[FlameModel.HALF], end[0], end[1], end[2]),
                        1, SMOKE_PUFFS, SOOT, u, 0, now);
            } else if (status == Flares.Status.FLAME_OUT && s.plume() > 0) {
                int segments = 2 + (int) Math.round(2 * s.plume());
                smoke.aim(PlumeModel.values(segments * u * period, s.exitVelocity() / Math.max(u, .1), wind.x(), wind.z(), .3 * s.plume(), 2 * tipRadius, 0, 0, 0),
                        1, SMOKE_PUFFS, UNBURNT, u, 0, now);
            } else {
                smoke.aim(Plume.rest(), 1, SMOKE_PUFFS, SOOT, u, 0, now);
            }
            aimHaze(haze, flaring ? .8 * Math.sqrt(share) : 0, end, Math.max(1, .25 * length), now);
        }
    }

    /** A cooling tower: the white plume from each fan stack or the throat (W4). */
    static final class TowerSource extends Source<FluidNetwork.TowerPlume> {
        private final @Nullable Exits exits;
        private final double @Nullable [] at;
        private @Nullable Plume plume;

        TowerSource(CellWatch.Watched<FluidNetwork.TowerPlume> watched) {
            super(watched);
            var names = named("fan_exhaust_");
            if (names.isEmpty() && marks.containsKey("throat")) names = List.of("throat");
            exits = Exits.of(this, names);
            at = exits == null ? null : exits.centroid();
            if (exits != null) plume = new Plume(this, "tower_plume", FxKind.PLUME, at, exits.local(at), seed);
        }

        @Override
        void aim(FluidNetwork.TowerPlume s, ClientLevel level, FxWind.Wind wind, double now) {
            if (exits == null || plume == null || at == null) return;
            double t = s.exhaustTemperature(), w = s.exhaustHumidity(), u = wind.speed();
            double exit = PlumeModel.exitVelocity(s.dryAir() * (1 + w), t, w, exits.area());
            var ambient = FxAmbient.at(level, BlockPos.containing(at[0], at[1], at[2]), 0);
            double[] visible = exit > 0 ? PlumeModel.visible(exits.diameter(), t - 273.15, w, ambient.celsius(), ambient.humidity()) : new double[3];
            plume.aim(PlumeModel.values(visible[0], visible[1], exit / Math.max(u, .1), wind.x(), wind.z(), visible[2], exits.diameter(), 0, 0, 0),
                    exits.world().length, PLUME_PUFFS, PlumeModel.Style.WHITE, u, exits.reach(at), now);
        }
    }

    /** A fired heater: the white plume, smoke for liquid or solid fuel, and haze at each stack (W5, W8). */
    static final class HeaterSource extends Source<FluidNetwork.HeaterFlue> {
        static final PlumeModel.Style OIL = new PlumeModel.Style(new double[]{.5, .5, .51}, new double[]{.62, .62, .63});
        static final PlumeModel.Style COAL = new PlumeModel.Style(new double[]{.3, .28, .26}, new double[]{.45, .42, .39});
        /** Stack smoke shows over about 30 stack diameters, within 6 to 48 m. */
        static final double SMOKE_DIAMETERS = 30, SMOKE_MIN = 6, SMOKE_MAX = 48;
        /** The stack's excess over the ambient for full shimmer, K. */
        static final double HAZE_EXCESS = 300;
        private final @Nullable Exits exits;
        private final double @Nullable [] at;
        private @Nullable Plume plume, smoke;
        private @Nullable GlideEmitter haze;

        HeaterSource(CellWatch.Watched<FluidNetwork.HeaterFlue> watched) {
            super(watched);
            exits = Exits.of(this, named("stack_top_"));
            at = exits == null ? null : exits.centroid();
            if (exits == null) return;
            var local = exits.local(at);
            plume = new Plume(this, "heater_plume", FxKind.PLUME, at, local, seed);
            smoke = new Plume(this, "heater_smoke", FxKind.SMOKE, at, local, seed + 1);
            haze = hazeEmitter(this, "heater_haze", at);
        }

        @Override
        void aim(FluidNetwork.HeaterFlue s, ClientLevel level, FxWind.Wind wind, double now) {
            if (exits == null || plume == null || smoke == null || haze == null || at == null) return;
            double t = s.stackTemperature(), w = s.waterShare(), u = wind.speed(), d = exits.diameter(), reach = exits.reach(at);
            double exit = PlumeModel.exitVelocity(s.flueFlow(), t, w, exits.area()), ratio = exit / Math.max(u, .1);
            var ambient = FxAmbient.at(level, BlockPos.containing(at[0], at[1], at[2]), 0);
            double[] visible = exit > 0 ? PlumeModel.visible(d, t - 273.15, w, ambient.celsius(), ambient.humidity()) : new double[3];
            int n = exits.world().length;
            plume.aim(PlumeModel.values(visible[0], visible[1], ratio, wind.x(), wind.z(), visible[2], d, 0, 0, 0), n, PLUME_PUFFS, PlumeModel.Style.WHITE, u, reach, now);
            var fuel = s.fuelKind();
            double soot = exit > 0 ? fuel == Burners.Fuel.SOLID ? .4 * s.firing() : fuel == Burners.Fuel.LIQUID ? .18 * s.firing() : 0 : 0;
            double length = Math.max(SMOKE_MIN, Math.min(SMOKE_MAX, SMOKE_DIAMETERS * d));
            smoke.aim(PlumeModel.values(length, ratio, wind.x(), wind.z(), soot, d, 0, 0, 0), n, SMOKE_PUFFS, fuel == Burners.Fuel.SOLID ? COAL : OIL, u, reach, now);
            double strength = exit > 0 && s.firing() > 0 ? (t - 273.15 - ambient.celsius()) / HAZE_EXCESS : 0;
            aimHaze(haze, strength, new double[]{0, .6 * d, 0}, Math.max(.6, d), now);
        }
    }

    /** A fuelled batch still: the fire at its door, its flue's smoke and the drip at its spout (W6). */
    static final class StillSource extends Source<FluidNetwork.StillState> {
        static final PlumeModel.Style WOOD = new PlumeModel.Style(new double[]{.36, .31, .26}, new double[]{.5, .46, .42});
        static final PlumeModel.Style FUEL_OIL = new PlumeModel.Style(new double[]{.7, .7, .72}, new double[]{.78, .78, .8});
        static final PlumeModel.Style WATER = new PlumeModel.Style(new double[]{.75, .8, .85}, new double[]{.75, .8, .85});
        /** A flue's smoke shows over about 40 flue widths, within 3 to 12 m. */
        static final double SMOKE_WIDTHS = 40, SMOKE_MIN = 3, SMOKE_MAX = 12;
        private final @Nullable StillModel.Door door;
        private final double @Nullable [] flue, spout;
        private final double flueWidth;
        private @Nullable GlideEmitter fire, drip;
        private @Nullable Plume smoke;
        private boolean solid;

        StillSource(CellWatch.Watched<FluidNetwork.StillState> watched) {
            super(watched);
            var d = centre("door");
            var bed = centre("fire_bed");
            var gun = centre("burner");
            var box = marks.get("door");
            door = d == null || bed == null || box == null ? null : StillModel.door(d, bed, gun, box);
            flue = top("flue_top");
            flueWidth = Math.min(width("flue_top"), marks.containsKey("flue_top") ? marks.get("flue_top")[5] - marks.get("flue_top")[2] : 0);
            var s = marks.get("spout");
            spout = s == null ? null : new double[]{origin.getX() + (s[0] + s[3]) / 2, origin.getY() + s[1] - .02, origin.getZ() + (s[2] + s[5]) / 2};
            if (door != null) fire = add(new GlideEmitter("still_fire", FxKind.FLAME, d[0], d[1], d[2], 2, new double[1],
                    (sink, from, to, quads) -> StillModel.write(sink, door, solid, from[0], to[0])));
            if (flue != null) smoke = new Plume(this, "still_flue", FxKind.SMOKE, flue, new double[][]{{0, 0, 0}}, seed);
            if (spout != null) drip = add(new GlideEmitter("still_drip", FxKind.PLUME, spout[0], spout[1], spout[2], DRIP_PERIOD, new double[1], FxSources::drip));
        }

        @Override
        void aim(FluidNetwork.StillState s, ClientLevel level, FxWind.Wind wind, double now) {
            var heater = BatchStill.Heater.values()[Math.min(s.heater(), BatchStill.Heater.values().length - 1)];
            double share = heater == BatchStill.Heater.ELECTRIC ? 0 : s.fire(), u = wind.speed();
            solid = heater == BatchStill.Heater.SOLID;
            if (fire != null) fire.aim(new double[]{share}, StillModel.quads(solid, share), 2, now);
            if (smoke != null) {
                double exit = FLUE_DRAUGHT + FLUE_DRAUGHT_FIRE * share, density = share > 0 ? solid ? .15 + .45 * share : .15 * share : 0;
                double length = Math.max(SMOKE_MIN, Math.min(SMOKE_MAX, SMOKE_WIDTHS * flueWidth));
                smoke.aim(PlumeModel.values(length, exit / Math.max(u, .1), wind.x(), wind.z(), density, flueWidth, 0, 0, 0), 1, SMOKE_PUFFS,
                        solid ? WOOD : FUEL_OIL, u, 0, now);
            }
            if (drip != null) drip.aim(new double[]{s.dripping() ? 1 : 0}, s.dripping() ? 2 : 0, 1, now);
        }
    }

    /** The drip at a still's spout: two drops half a period apart, falling {@link #DRIP_FALL} m each {@link #DRIP_PERIOD} s. */
    static void drip(FxSink sink, double[] from, double[] to, int quads) {
        if (!(to[0] > 0)) return;
        int params = StillSource.WATER.params(0, .9);
        for (int k = 0; k < 2; k++) sink.puff(FxSink.Point.still(0, 0, 0, DRIP_HALF), 0, -DRIP_FALL, 0, params, FxSink.staggered(k, 2, 0));
    }
}
