package com.wormzjl.icheme.science.tutorial.scene;

import com.wormzjl.icheme.science.equipment.Mesh;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A film (batch 2026-11-12-tutorial-system, TUTORIAL_SYSTEM_PLAN.md section 3.1): its title's language key, its actors,
 * its tracks and its stops (seconds, ascending; the last is the film's length). {@link #at} is a pure function of the
 * time: scrubbing, replay, the game's view and the offline render tool all call it. No Minecraft type.
 */
public final class Scene {
    private final String title;
    private final List<Actor> actors;
    private final List<Track> tracks;
    private final List<Double> stops;
    private final Track.Camera camera;
    /** Each cut actor's clipped polygon lists by step, built once on demand. */
    private final Map<String, CutSteps> cuts;

    private Scene(String title, List<Actor> actors, List<Track> tracks, List<Double> stops) {
        this.title = title;
        this.actors = List.copyOf(actors);
        this.tracks = List.copyOf(tracks);
        this.stops = List.copyOf(stops);
        if (this.stops.isEmpty()) throw new IllegalArgumentException("A film needs a stop");
        for (int i = 0; i < this.stops.size(); i++)
            if (!(this.stops.get(i) > (i == 0 ? 0 : this.stops.get(i - 1)))) throw new IllegalArgumentException("Stops must ascend from above 0");
        var ids = new HashMap<String, Actor>();
        for (var actor : this.actors) if (ids.put(actor.id(), actor) != null) throw new IllegalArgumentException("Actor twice: " + actor.id());
        Track.Camera found = null;
        var cutMap = new LinkedHashMap<String, CutSteps>();
        for (var track : this.tracks) {
            switch (track) {
                case Track.Camera c -> {
                    if (found != null) throw new IllegalArgumentException("A film has one camera");
                    found = c;
                }
                case Track.Visible v -> actor(ids, v.actor());
                case Track.Cut c -> {
                    if (cutMap.put(c.actor(), new CutSteps(actor(ids, c.actor()), c)) != null) throw new IllegalArgumentException("One cut an actor: " + c.actor());
                }
                case Track.Caption c -> {
                }
                case Track.Dots d -> {
                }
            }
        }
        if (found == null) throw new IllegalArgumentException("A film needs a camera");
        camera = found;
        cuts = Map.copyOf(cutMap);
    }

    private static Actor actor(Map<String, Actor> ids, String id) {
        var actor = ids.get(id);
        if (actor == null) throw new IllegalArgumentException("No actor " + id);
        return actor;
    }

    public static Builder builder(String title) {
        return new Builder(title);
    }

    public String title() {
        return title;
    }

    public List<Actor> actors() {
        return actors;
    }

    public List<Track> tracks() {
        return tracks;
    }

    public List<Double> stops() {
        return stops;
    }

    public double length() {
        return stops.getLast();
    }

    /** Every caption's language key, in track order. */
    public List<String> captionKeys() {
        var out = new ArrayList<String>();
        for (var track : tracks) if (track instanceof Track.Caption c && !out.contains(c.key())) out.add(c.key());
        return out;
    }

    /** Whether a track over {@code from} to {@code to} acts at {@code t}: on (from, to], and at 0 when it starts at 0. */
    public static boolean active(double from, double to, double t) {
        return t > from && t <= to || t == 0 && from <= 0 && to >= 0;
    }

    /** The film at {@code seconds}, held to 0 and the length. */
    public Frame at(double seconds) {
        double t = Math.max(0, Math.min(length(), seconds));
        var drawn = new ArrayList<Frame.ActorFrame>();
        for (var actor : actors) {
            double alpha = alpha(actor.id(), t);
            if (alpha <= 0) continue;
            var cut = cuts.get(actor.id());
            int step = cut == null ? 0 : cut.track.step(t);
            drawn.add(new Frame.ActorFrame(actor.id(), cut == null ? actor.polygons() : cut.list(step), alpha, step > 0));
        }
        var dots = new ArrayList<Frame.DotFrame>();
        var captions = new ArrayList<String>();
        for (var track : tracks) {
            if (track instanceof Track.Dots d && active(d.from(), d.to(), t)) DotMotion.place(d, t, dots);
            if (track instanceof Track.Caption c && active(c.from(), c.to(), t) && !captions.contains(c.key())) captions.add(c.key());
        }
        int stop = 0;
        while (stop < stops.size() - 1 && stops.get(stop) < t) stop++;
        return new Frame(camera.at(t), drawn, dots, captions, stop);
    }

    /** An actor's alpha: 1 with no visibility track, else the largest its tracks give. */
    private double alpha(String id, double t) {
        boolean tracked = false;
        double alpha = 0;
        for (var track : tracks) {
            if (!(track instanceof Track.Visible v) || !v.actor().equals(id)) continue;
            tracked = true;
            if (!active(v.from(), v.to(), t)) continue;
            double in = v.fade() <= 0 ? 1 : (t - v.from()) / v.fade(), out = v.fade() <= 0 || v.to() >= length() ? 1 : (v.to() - t) / v.fade();
            alpha = Math.max(alpha, Math.max(0, Math.min(1, Math.min(in, out))));
        }
        return tracked ? alpha : 1;
    }

    /** A cut actor's polygon lists: step 0 is the whole actor, steps 1 to 8 the plane moving in from outside its bounds. */
    private static final class CutSteps {
        final Actor actor;
        final Track.Cut track;
        /** How far past the plane's place the actor reaches along the normal, m: where the plane starts. */
        final double reach;
        @SuppressWarnings("unchecked")
        final List<Mesh.Polygon>[] lists = new List[Track.Cut.STEPS + 1];

        CutSteps(Actor actor, Track.Cut track) {
            this.actor = actor;
            this.track = track;
            var b = actor.bounds();
            double reach = 0;
            for (int i = 0; i < 8; i++) {
                double x = (i & 1) == 0 ? b[0] : b[3], y = (i & 2) == 0 ? b[1] : b[4], z = (i & 4) == 0 ? b[2] : b[5];
                var n = track.normal();
                var p = track.point();
                reach = Math.max(reach, (x - p[0]) * n[0] + (y - p[1]) * n[1] + (z - p[2]) * n[2]);
            }
            this.reach = reach;
        }

        synchronized List<Mesh.Polygon> list(int step) {
            if (step == 0) return actor.polygons();
            var list = lists[step];
            if (list == null) {
                double off = reach * (1 - step / (double) Track.Cut.STEPS);
                var n = track.normal();
                var p = track.point();
                list = lists[step] = Clip.keep(actor.polygons(), new double[]{p[0] + n[0] * off, p[1] + n[1] * off, p[2] + n[2] * off}, n);
            }
            return list;
        }
    }

    /** Builds a scene: actors, tracks, stops in order. */
    public static final class Builder {
        private final String title;
        private final List<Actor> actors = new ArrayList<>();
        private final List<Track> tracks = new ArrayList<>();
        private final List<Double> stops = new ArrayList<>();

        private Builder(String title) {
            this.title = title;
        }

        public Builder actor(Actor actor) {
            actors.add(actor);
            return this;
        }

        public Builder track(Track track) {
            tracks.add(track);
            return this;
        }

        /** The next stop, seconds; the last is the film's length. */
        public Builder stop(double seconds) {
            stops.add(seconds);
            return this;
        }

        public Scene build() {
            return new Scene(title, actors, tracks, stops);
        }
    }
}
