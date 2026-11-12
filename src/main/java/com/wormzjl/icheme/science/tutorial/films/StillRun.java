package com.wormzjl.icheme.science.tutorial.films;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Mesh;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.tutorial.scene.Actors;
import com.wormzjl.icheme.science.tutorial.scene.Scene;
import com.wormzjl.icheme.science.tutorial.scene.Series;
import com.wormzjl.icheme.science.tutorial.scene.Track;
import com.wormzjl.icheme.science.tutorial.scene.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * The film "a still run" (batch 2026-11-12-tutorial-system, P2b; guide step {@code still/watch}), replaying the recorded
 * run {@code still_run.json} ({@link StillRunData}) in five stops; the film computes nothing, it reads the recording
 * through a {@link Value.Timeline}. 1: the fire is lit; the pot's readout climbs. 2: the first drop falls from the spout,
 * the readout at the recorded temperature. 3: the readout climbs on while the level falls and the light dots leave, to
 * the end of the naphtha part. 4: the stream is switched to the second drum; the middle dots leave there, its liquid
 * darker. 5: the fire is put out before the end, the readouts held at the recording's last values.
 *
 * <p>The still is cut open as in "inside the still" (three actors, three cuts); the two receivers are small upright drums
 * in front of its spout, joined to the distillate nozzle by a plain line with a tee. Each stop maps onto a stretch of the
 * recording: {@link #FILM} to {@link #recording}.</p>
 */
public final class StillRun {
    public static final String NAME = "still_run";
    public static final String TITLE = "tutorial.icheme.film.still_run";
    /** The stops, seconds: the first drop, the end of the naphtha (the switch), the end of the kerosene (the fire out), the run's end. */
    static final double STOP_1 = 10, STOP_2 = 22, STOP_3 = 28, STOP_4 = 36;
    /** The film's keys of its timeline: the start and the stops. */
    static final double[] FILM = {0, STOP_1, STOP_2, STOP_3, STOP_4};
    /** The readouts' and labels' language keys. */
    public static final String POT = "tutorial.icheme.film.readout.pot", DISTILLED = "tutorial.icheme.film.readout.distilled",
            FIRST = "tutorial.icheme.film.part.drum_first", SECOND = "tutorial.icheme.film.part.drum_second", SPOUT = "tutorial.icheme.film.part.spout";
    /** The receivers' liquids (RGB): the light part pale, the heavier one darker. */
    public static final int FIRST_LIQUID = 0xE8C46A, SECOND_LIQUID = 0x9A5A1E;
    /** The drums' places (their origin), m: in front of the still, below its outlet. */
    static final double[] DRUM_1 = {2.75, -1.1, -1.6}, DRUM_2 = {3.75, -1.1, -1.6};
    /** A drum's inside, from its origin: low and high corners (the 0.5 m drum's shell, a hair inside). */
    static final double[] DRUM_LO = {.28, .06, .28}, DRUM_HI = {.72, 1.2, .72};
    /** A drum's top inlet, from its origin. */
    static final double[] DRUM_TOP = {.5, 1.25, .5};
    /** The share of a drum the recorded part fills when it is all in, for the picture. */
    static final double DRUM_FULL = .7;
    /** Dots: the light and middle ones that leave, the heavy ones that stay; their speed on the way, m/s. */
    static final int LIGHT = 14, MIDDLE = 10, HEAVY = 10, MIDDLE_LEAVING = 4;
    static final double SPEED = 1.1;

    private StillRun() {
    }

    public static EquipmentShape drum() {
        return EquipmentShape.drum(.5, 1, EquipmentShape.Orientation.VERTICAL, EquipmentShape.Heading.NORTH, 0);
    }

    /** The recording's times the film's keys {@link #FILM} map onto: the start, the first drop, the end of the naphtha, the end of the kerosene, the run's end (the law cuts the fire). */
    public static double[] recording(Series.Recording run) {
        return new double[]{0, run.line("first", "time"), run.line("naphtha", "time"), run.line("kerosene", "time"), run.line("end", "time")};
    }

    public static Value.Timeline timeline(Series.Recording run) {
        return new Value.Timeline(FILM, recording(run));
    }

    /** The share of the charge's gas and naphtha (the first cut, from the recording's set-up), and the share taken overhead by the run's end. */
    static double[] cuts(Series.Recording run) {
        return new double[]{StillRunData.share(run, "gas") + StillRunData.share(run, "naphtha"), StillRunData.overhead(run, run.time().end())};
    }

    public static Scene build() {
        var run = StillRunData.load();
        var timeline = timeline(run);
        var shape = StillInside.shape();
        var parts = ShapeRasterizer.stillParts(shape);
        var pot = parts.pot();
        var cooler = parts.condenser();
        double px = (pot[0] + pot[3]) / 2, pz = (pot[2] + pot[5]) / 2, cx = (cooler[0] + cooler[3]) / 2, cy = (cooler[1] + cooler[4]) / 2;
        double neckY = parts.vapour().get(2)[1], neckFloor = cooler[4] + .1, split = (px + cx) / 2;
        var neck = Actors.equipment("neck", shape, c -> c[1] > neckFloor);
        var condenser = Actors.equipment("cooler", shape, c -> c[1] <= neckFloor && (c[0] - split) * (cx - px) > 0);
        var still = Actors.equipment("pot", shape, c -> c[1] <= neckFloor && (c[0] - split) * (cx - px) <= 0);
        double yaw = StillInside.YAW, pitch = .3;
        double[] toCamera = {-DeterministicMath.sin(yaw), 0, -DeterministicMath.cos(yaw)};
        var drum = Actors.equipment("drum", drum());
        var drum1 = Actors.moved("drum1", drum, DRUM_1[0], DRUM_1[1], DRUM_1[2]);
        var drum2 = Actors.moved("drum2", drum, DRUM_2[0], DRUM_2[1], DRUM_2[2]);
        // The line: from the distillate nozzle's end forward to the tee, then a branch down into each drum's top.
        var outlet = StillBuild.end(shape, EquipmentShape.Role.DISTILLATE);
        double[] tee = {outlet[0], outlet[1], DRUM_1[2] + DRUM_TOP[2]};
        double[] top1 = add(DRUM_1, DRUM_TOP), top2 = add(DRUM_2, DRUM_TOP);
        var line = Actors.pipe("line", List.of(outlet, tee), .05, Mesh.Material.PIPE);
        var branch1 = Actors.pipe("branch1", List.of(tee, new double[]{top1[0], tee[1], tee[2]}, top1), .05, Mesh.Material.PIPE);
        var branch2 = Actors.pipe("branch2", List.of(tee, new double[]{top2[0], tee[1], tee[2]}, top2), .05, Mesh.Material.PIPE);

        double[] cuts = cuts(run);
        double firstCut = cuts[0], last = cuts[1];
        Value overhead = t -> StillRunData.overhead(run, t);
        // The receivers fill with what the recording took overhead: the first up to the cut, the second after it to the run's end.
        Value fill1 = timeline.replay(t -> DRUM_FULL * Math.min(1, overhead.at(t) / firstCut));
        Value fill2 = timeline.replay(t -> DRUM_FULL * Math.clamp((overhead.at(t) - firstCut) / (last - firstCut), 0, 1));
        Value level = timeline.replay(t -> run.series("level").at(t));
        // The fire: lit at once, left on past the fire out of stop 3 (what if), cut by the still itself over the last second.
        Value fire = t -> StillInside.FIRE * Math.clamp(t / .8, 0, 1) * Math.clamp(STOP_4 - t, 0, 1);

        // The pot's liquid as a body (its share of the pot from floor to apex), the dots inside its lowest level.
        double r = .95 * (pot[3] - pot[0]) / 2;
        var body = new Track.Box(new double[]{px - r, pot[1] + .02, pz - r}, new double[]{px + r, pot[4], pz + r});
        double lowest = run.series("level").value(run.time().size() - 1);
        double half = .62 * (pot[3] - pot[0]) / 2 - Track.Dots.SIZE;
        var liquid = new Track.Box(new double[]{px - half, pot[1] + .02 + Track.Dots.SIZE, pz - half},
                new double[]{px + half, pot[1] + .02 + .92 * lowest * (pot[4] - pot[1] - .02), pz + half});
        var way = new ArrayList<double[]>();
        way.add(new double[]{px, liquid.hi()[1], pz});
        way.addAll(parts.vapour());
        way.addAll(parts.condensate());
        way.add(outlet);
        way.add(tee);
        var toFirst = new ArrayList<>(way);
        toFirst.add(new double[]{top1[0], tee[1], tee[2]});
        toFirst.add(top1);
        toFirst.add(new double[]{top1[0], top1[1] - .5, top1[2]});
        var toSecond = new ArrayList<>(way);
        toSecond.add(new double[]{top2[0], tee[1], tee[2]});
        toSecond.add(top2);
        toSecond.add(new double[]{top2[0], top2[1] - .5, top2[2]});
        // The first light dot reaches the spout at stop 1; the rest follow to the end of stop 2. The middle ones go to the second drum after it.
        double toSpout = length(way.subList(0, way.size() - 2)) + .3;
        double lightFrom = STOP_1 - toSpout / SPEED, lightGap = (STOP_2 - 1.5 - lightFrom) / LIGHT;
        double middleFrom = STOP_2 + .5, middleGap = (STOP_4 - 2 - middleFrom) / MIDDLE_LEAVING;
        double radius = .025;
        var drumBox1 = new Track.Box(add(DRUM_1, new double[]{DRUM_LO[0] + .05, DRUM_LO[1] + .05, DRUM_LO[2] + .05}),
                add(DRUM_1, new double[]{DRUM_HI[0] - .05, DRUM_LO[1] + .4, DRUM_HI[2] - .05}));
        var drumBox2 = new Track.Box(add(DRUM_2, new double[]{DRUM_LO[0] + .05, DRUM_LO[1] + .05, DRUM_LO[2] + .05}),
                add(DRUM_2, new double[]{DRUM_HI[0] - .05, DRUM_LO[1] + .25, DRUM_HI[2] - .05}));
        // The camera follows the run: the fire and pot, the condenser down to the first drum, the whole still, the drums, the fire again.
        double[] whole = {(pot[0] + top2[0]) / 2 + .3, (DRUM_1[1] + neckY) / 2 + .35, (top1[2] + pot[5]) / 2};
        double[] fireBed = StillInside.fireBed(shape);
        double[] atPot = {px - .2, (fireBed[1] + pot[4]) / 2 + .25, pz};
        double[] atCooler = {(cx + top1[0]) / 2, (top1[1] + cooler[4]) / 2, (top1[2] + cooler[5]) / 2};
        double[] atDrums = {(top1[0] + top2[0]) / 2, (DRUM_1[1] + tee[1]) / 2 + .05, top1[2]};
        return Scene.builder(TITLE)
                .actor(still).actor(neck).actor(condenser).actor(line).actor(branch1).actor(branch2).actor(drum1).actor(drum2)
                .track(new Track.Camera(List.of(
                        key(0, yaw, pitch, POT_SCALE, atPot), key(LIT, yaw, pitch, POT_SCALE, atPot),
                        key(LIT + 1.5, yaw, pitch, COOLER_SCALE, atCooler), key(STOP_1, yaw, pitch, COOLER_SCALE, atCooler),
                        key(STOP_1 + 2, yaw, pitch, WHOLE_SCALE, whole), key(STOP_2, yaw, pitch, WHOLE_SCALE, whole),
                        key(STOP_2 + 1.5, yaw, pitch, DRUM_SCALE, atDrums), key(STOP_3 - 2.5, yaw, pitch, DRUM_SCALE, atDrums),
                        key(STOP_3 - 1, yaw, pitch, WHOLE_SCALE, whole), key(STOP_3, yaw, pitch, WHOLE_SCALE, whole),
                        key(STOP_3 + 2, yaw, pitch, POT_SCALE, atPot), key(STOP_4, yaw, pitch, POT_SCALE, atPot))))
                .track(new Track.Cut("pot", new double[]{px, 0, pz}, toCamera, 0, 0))
                .track(new Track.Cut("neck", new double[]{px, neckY, pz}, new double[]{0, 1, 0}, 0, 0))
                .track(new Track.Cut("cooler", new double[]{cx, cy, 0}, new double[]{Math.signum(toCamera[0]), 0, 0}, 0, 0))
                .track(new Track.Cut("drum1", add(DRUM_1, DRUM_TOP), toCamera, 0, 0))
                .track(new Track.Cut("drum2", add(DRUM_2, DRUM_TOP), toCamera, 0, 0))
                .track(new Track.Caption("tutorial.icheme.film.still_run.s1", 0, STOP_1))
                .track(new Track.Caption("tutorial.icheme.film.still_run.s2", STOP_1, STOP_2))
                .track(new Track.Caption("tutorial.icheme.film.still_run.s3", STOP_2, STOP_3))
                .track(new Track.Caption("tutorial.icheme.film.still_run.s4", STOP_3, STOP_4))
                .track(new Track.Readout(POT, timeline.replay(run.series("pot")), Track.Reading.TEMPERATURE, 0, STOP_4))
                .track(new Track.Readout(DISTILLED, timeline.replay(run.series("distilled")), Track.Reading.SHARE, 0, STOP_4))
                .track(new Track.Legend(0, STOP_4))
                .track(new Track.Flame(StillInside.fireBed(shape), fire, 0, STOP_4))
                .track(new Track.Level(body, level, StillInside.CRUDE, "pot", 0, STOP_4))
                .track(new Track.Level(new Track.Box(add(DRUM_1, DRUM_LO), add(DRUM_1, DRUM_HI)), fill1, FIRST_LIQUID, "drum1", 0, STOP_4))
                .track(new Track.Level(new Track.Box(add(DRUM_2, DRUM_LO), add(DRUM_2, DRUM_HI)), fill2, SECOND_LIQUID, "drum2", 0, STOP_4))
                // The pointer lights the fire, switches the stream at the tee, and puts the fire out; stop 4 shows the fire left on.
                .track(new Track.Pointer(Track.Sign.RIGHT_CLICK, "", StillInside.door(shape), 0, LIT))
                .track(new Track.Pointer(Track.Sign.RIGHT_CLICK, "", tee, STOP_2 - 3, STOP_2))
                .track(new Track.Pointer(Track.Sign.RIGHT_CLICK, "", StillInside.door(shape), STOP_3 - 2, STOP_3))
                .track(new Track.Label(top1, FIRST, STOP_1 - 4, STOP_1))
                .track(new Track.Label(outlet, SPOUT, STOP_1 - 4, STOP_1))
                .track(new Track.Label(top2, SECOND, STOP_2 - 4, STOP_2))
                .track(new Track.Label(new double[]{px + r * toCamera[0], (pot[1] + pot[4]) / 2, pz + r * toCamera[2]}, StillInside.POT, STOP_4 - 3, STOP_4))
                // The dots: heavy ones stay in the pot; light ones leave to the first drum, middle ones to the second after the cut.
                .track(new Track.Dots(Track.Kind.HEAVY, liquid, 0, STOP_4, HEAVY, .06))
                .track(new Track.Dots(Track.Kind.LIGHT, new Track.Line(toFirst, radius, liquid, LIGHT, lightGap, .12), lightFrom, STOP_4, LIGHT, SPEED))
                .track(new Track.Dots(Track.Kind.MIDDLE, new Track.Line(toSecond, radius, liquid, MIDDLE_LEAVING, middleGap, .09), middleFrom, STOP_4, MIDDLE, SPEED))
                .track(new Track.Dots(Track.Kind.MIDDLE, liquid, 0, middleFrom, MIDDLE, .09))
                .track(new Track.Dots(Track.Kind.LIGHT, liquid, 0, lightFrom, LIGHT, .12))
                // What each drum has collected: as many dots as the share it holds.
                .track(new Track.Dots(Track.Kind.LIGHT, drumBox1, STOP_1, STOP_4, LIGHT, .05, t -> fill1.at(t) / DRUM_FULL))
                .track(new Track.Dots(Track.Kind.MIDDLE, drumBox2, STOP_2, STOP_4, MIDDLE_LEAVING, .05, t -> fill2.at(t) / DRUM_FULL))
                .stop(STOP_1).stop(STOP_2).stop(STOP_3).stop(STOP_4)
                .build();
    }

    /** When the fire is lit and the camera leaves the pot for the cooler, s. */
    static final double LIT = 4;
    /** The camera's scales at the pot, the condenser, the whole still and the drums, px/m at the design size. */
    static final double POT_SCALE = 46, COOLER_SCALE = 38, WHOLE_SCALE = 30, DRUM_SCALE = 62;

    private static Track.Key key(double t, double yaw, double pitch, double scale, double[] at) {
        return new Track.Key(t, yaw, pitch, scale, at[0], at[1], at[2]);
    }

    private static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    static double length(List<double[]> points) {
        double l = 0;
        for (int i = 0; i + 1 < points.size(); i++) {
            var a = points.get(i);
            var b = points.get(i + 1);
            l += Math.sqrt((b[0] - a[0]) * (b[0] - a[0]) + (b[1] - a[1]) * (b[1] - a[1]) + (b[2] - a[2]) * (b[2] - a[2]));
        }
        return l;
    }
}
