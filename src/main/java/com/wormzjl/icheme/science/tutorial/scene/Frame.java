package com.wormzjl.icheme.science.tutorial.scene;

import com.wormzjl.icheme.science.equipment.Mesh;

import java.util.List;

/**
 * A film at one moment ({@link Scene#at}): the camera, the actors drawn, the dots, the captions shown and the stop the
 * film plays toward. Two frames of the same scene and time are equal; an actor's polygon list is the same instance as
 * long as its cut does not move, so a view keys its buffer on it.
 */
public record Frame(CameraState camera, List<ActorFrame> actors, List<DotFrame> dots, List<String> captions, int stop) {
    public Frame {
        actors = List.copyOf(actors);
        dots = List.copyOf(dots);
        captions = List.copyOf(captions);
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
