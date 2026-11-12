package com.wormzjl.icheme.science.tutorial.scene;

import com.wormzjl.icheme.science.equipment.Mesh;

import java.util.List;

/**
 * A film at one moment ({@link Scene#at}): the camera, the actors drawn, the dots, the captions shown and the stop the
 * film plays toward; since P2b also the labels, whether the legend shows, the fires, the liquids, the readouts and the
 * pointers. Two frames of the same scene and time are equal; an actor's polygon list is the same instance as long as its
 * cut does not move, so a view keys its buffer on it. Fires and liquids are values: their shapes come from
 * {@link Bodies}, built where they are drawn.
 */
public record Frame(CameraState camera, List<ActorFrame> actors, List<DotFrame> dots, List<String> captions, int stop, List<LabelFrame> labels, boolean legend,
                    List<FlameFrame> flames, List<LiquidFrame> liquids, List<ReadoutFrame> readouts, List<PointerFrame> pointers) {
    public Frame {
        actors = List.copyOf(actors);
        dots = List.copyOf(dots);
        captions = List.copyOf(captions);
        labels = List.copyOf(labels);
        flames = List.copyOf(flames);
        liquids = List.copyOf(liquids);
        readouts = List.copyOf(readouts);
        pointers = List.copyOf(pointers);
    }

    /** A frame with no overlay, fire or liquid (P0's frame). */
    public Frame(CameraState camera, List<ActorFrame> actors, List<DotFrame> dots, List<String> captions, int stop) {
        this(camera, actors, dots, captions, stop, List.of(), false, List.of(), List.of(), List.of(), List.of());
    }

    /** A label's language key and its scene point. */
    public record LabelFrame(String key, double x, double y, double z) {
    }

    /** A fire: its foot (scene point), its tongues' height (m) and the film's time (its flicker). */
    public record FlameFrame(double x, double y, double z, double size, double time) {
    }

    /** A plane: a point on it and its unit normal, pointing at the part removed. */
    public record Plane(double px, double py, double pz, double nx, double ny, double nz) {
    }

    /**
     * A liquid's body: an upright prism round (x, z) of {@code radius}, from {@code bottom} to {@code top} (m), its colour
     * and alpha, cut by {@code cut} (null: whole).
     */
    public record LiquidFrame(double x, double z, double radius, double bottom, double top, int rgb, double alpha, Plane cut) {
    }

    /** A readout: its name's language key, its value (K or a share) and what kind of number it is. */
    public record ReadoutFrame(String key, double value, Track.Reading reading) {
    }

    /** A pointer: its sign, a key's letter (or empty) and its scene point. */
    public record PointerFrame(Track.Sign sign, String text, double x, double y, double z) {
    }

    /** The camera: yaw and pitch (radians), scale (px/m at the design size), target (m); as {@link Track.Camera} says. */
    public record CameraState(double yaw, double pitch, double scale, double x, double y, double z) {
    }

    /**
     * An actor as drawn: its polygons (scene coordinates), its alpha, and whether both sides of its faces are drawn (a cut
     * actor, so its inside shows through the cut; the shade stays the outer face's).
     */
    public record ActorFrame(String id, List<Mesh.Polygon> polygons, double alpha, boolean twoSided) {
    }

    /** A dot: its centre (m), kind and radius (m). */
    public record DotFrame(double x, double y, double z, Track.Kind kind, double size) {
    }
}
