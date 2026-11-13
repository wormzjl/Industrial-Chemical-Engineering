package com.wormzjl.icheme.science.tutorial.films;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Mesh;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.tutorial.scene.Actor;
import com.wormzjl.icheme.science.tutorial.scene.Actors;
import com.wormzjl.icheme.science.tutorial.scene.Scene;
import com.wormzjl.icheme.science.tutorial.scene.Track;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The film "building the still" (batch 2026-11-12-tutorial-system, P2b; guide step {@code still/build}), in four stops.
 * 1: an Equipment Constructor on a ground plate, a right click on it. 2: the 500 L still rises part by part: its brick
 * setting, the pot, the neck, the condenser. 3: its parts named. 4: its connections named, each label at its place on
 * the still: the crude's inlet, the product's outlet, the drain, the vent (its nozzles, from the shape) and the fuel door
 * (the still's named part {@code door}). Every place comes from the still's own shape.
 */
public final class StillBuild {
    public static final String NAME = "still_build";
    public static final String TITLE = "tutorial.icheme.film.still_build";
    /** The stops, seconds. */
    static final double STOP_1 = 4, STOP_2 = 11, STOP_3 = 15, STOP_4 = 21;
    /** When each part starts to show, and its fade, s. */
    static final double SETTING = 4.6, POT = 6.2, NECK = 7.8, COOLER = 9.2, FADE = 1.2;
    /** The constructor block: low and high corners, m (in front of the still, to its left). */
    static final double[] BLOCK_LO = {-2.4, 0, -.9}, BLOCK_HI = {-1.4, 1, .1};
    /** The connections' language keys by nozzle role, then the door's. */
    public static final Map<EquipmentShape.Role, String> NOZZLES = Map.of(
            EquipmentShape.Role.CHARGE, "tutorial.icheme.film.nozzle.charge",
            EquipmentShape.Role.DISTILLATE, "tutorial.icheme.film.nozzle.distillate",
            EquipmentShape.Role.DRAIN, "tutorial.icheme.film.nozzle.drain",
            EquipmentShape.Role.VENT, "tutorial.icheme.film.nozzle.vent");
    public static final String DOOR = "tutorial.icheme.film.nozzle.door";
    /** The order the connections are labelled in. */
    static final List<EquipmentShape.Role> ORDER = List.of(EquipmentShape.Role.CHARGE, EquipmentShape.Role.DISTILLATE, EquipmentShape.Role.VENT, EquipmentShape.Role.DRAIN);
    /** The parts' language keys. */
    public static final String FIRE = "tutorial.icheme.film.part.fire", POT_KEY = "tutorial.icheme.film.part.pot", NECK_KEY = "tutorial.icheme.film.part.neck",
            CONDENSER = StillInside.COOLER, CONSTRUCTOR = "block.icheme.equipment_constructor";

    private StillBuild() {
    }

    /** A nozzle's outer end: its base moved out along its axis by its length. */
    public static double[] end(EquipmentShape shape, EquipmentShape.Role role) {
        var n = shape.nozzles().stream().filter(z -> z.role() == role).findFirst().orElseThrow();
        var t = shape.transform();
        var base = t.point(n.base());
        double[] out = new double[3];
        out[n.axis()] = n.sign() * n.length();
        var tip = t.point(new double[]{n.base()[0] + out[0], n.base()[1] + out[1], n.base()[2] + out[2]});
        return new double[]{(base[0] + tip[0]) / 2, (base[1] + tip[1]) / 2, (base[2] + tip[2]) / 2};
    }

    /** The still's parts as four actors, split as they rise: the brick setting and its door, the pot, the neck over the condenser, and the condenser with its stand and line. */
    static List<Actor> parts(EquipmentShape shape) {
        var parts = ShapeRasterizer.stillParts(shape);
        var pot = parts.pot();
        var cooler = parts.condenser();
        double px = (pot[0] + pot[3]) / 2, cx = (cooler[0] + cooler[3]) / 2, neckFloor = cooler[4] + .1, split = (px + cx) / 2;
        Predicate<double[]> coolerSide = c -> c[1] <= neckFloor && (c[0] - split) * (cx - px) > 0;
        var all = Actors.equipment("all", shape);
        var setting = new java.util.ArrayList<Mesh.Polygon>();
        var potPart = new java.util.ArrayList<Mesh.Polygon>();
        var neck = new java.util.ArrayList<Mesh.Polygon>();
        var condenser = new java.util.ArrayList<Mesh.Polygon>();
        for (var polygon : all.polygons()) {
            var c = Actors.centroid(polygon);
            var m = polygon.material();
            if (c[1] > neckFloor) neck.add(polygon);
            else if (coolerSide.test(c)) condenser.add(polygon);
            else if (m == Mesh.Material.BRICK || m == Mesh.Material.IRON || m == Mesh.Material.OPENING) setting.add(polygon);
            else potPart.add(polygon);
        }
        return List.of(new Actor("setting", setting), new Actor("pot", potPart), new Actor("neck", neck), new Actor("condenser", condenser));
    }

    public static Scene build() {
        var shape = StillInside.shape();
        var parts = ShapeRasterizer.stillParts(shape);
        var pot = parts.pot();
        var cooler = parts.condenser();
        double px = (pot[0] + pot[3]) / 2, pz = (pot[2] + pot[5]) / 2, r = (pot[3] - pot[0]) / 2;
        var plate = Actors.box("ground", new double[]{-3.5, -.1, -2}, new double[]{5, 0, 4}, Mesh.Material.CONCRETE);
        var block = Actors.box("constructor", BLOCK_LO, BLOCK_HI, Mesh.Material.MACHINE);
        double[] blockTop = {(BLOCK_LO[0] + BLOCK_HI[0]) / 2, BLOCK_HI[1], BLOCK_LO[2]};
        var b = Scene.builder(TITLE).actor(plate).actor(block);
        for (var part : parts(shape)) b.actor(part);
        // The side view from the front left shows every connection: the inlet and outlet at the front, the drain at the back, the vent on top.
        double yaw = 1.05, pitch = .34;
        b.track(new Track.Camera(List.of(
                new Track.Key(0, .55, .42, 70, blockTop[0], .5, BLOCK_LO[2] + .5),
                new Track.Key(STOP_1, .55, .42, 70, blockTop[0], .5, BLOCK_LO[2] + .5),
                new Track.Key(STOP_1 + 1.6, .62, .38, 30, 1.1, 1.8, 1.2),
                new Track.Key(STOP_2, .62, .38, 30, 1.1, 1.8, 1.2),
                new Track.Key(STOP_3, .62, .38, 34, 2, 2, 1.5),
                new Track.Key(STOP_3 + 1.5, yaw, pitch, 34, 2, 2, 1.5),
                new Track.Key(STOP_4, yaw, pitch, 34, 2, 2, 1.5))));
        b.track(new Track.Visible("setting", SETTING, STOP_4, FADE));
        b.track(new Track.Visible("pot", POT, STOP_4, FADE));
        b.track(new Track.Visible("neck", NECK, STOP_4, FADE));
        b.track(new Track.Visible("condenser", COOLER, STOP_4, FADE));
        b.track(new Track.Caption("tutorial.icheme.film.still_build.s1", 0, STOP_1));
        b.track(new Track.Caption("tutorial.icheme.film.still_build.s2", STOP_1, STOP_2));
        b.track(new Track.Caption("tutorial.icheme.film.still_build.s3", STOP_2, STOP_3));
        b.track(new Track.Caption("tutorial.icheme.film.still_build.s4", STOP_3, STOP_4));
        // Stop 1: the constructor named and right-clicked.
        b.track(new Track.Label(new double[]{BLOCK_LO[0], BLOCK_HI[1], BLOCK_LO[2]}, CONSTRUCTOR, 0, STOP_1));
        b.track(new Track.Pointer(Track.Sign.RIGHT_CLICK, "", new double[]{blockTop[0], .6, BLOCK_LO[2]}, .8, STOP_1));
        // Stop 3: the parts named.
        double neckY = parts.vapour().get(2)[1];
        b.track(new Track.Label(StillInside.door(shape), FIRE, STOP_2, STOP_3));
        b.track(new Track.Label(new double[]{px - r, (pot[1] + pot[4]) / 2, pz}, POT_KEY, STOP_2, STOP_3));
        b.track(new Track.Label(new double[]{px, neckY, pz}, NECK_KEY, STOP_2, STOP_3));
        b.track(new Track.Label(new double[]{(cooler[0] + cooler[3]) / 2, cooler[4], (cooler[2] + cooler[5]) / 2}, CONDENSER, STOP_2, STOP_3));
        // Stop 4: the connections named at their nozzles and the door.
        for (var role : ORDER) b.track(new Track.Label(end(shape, role), NOZZLES.get(role), STOP_3, STOP_4));
        b.track(new Track.Label(StillInside.door(shape), DOOR, STOP_3, STOP_4));
        return b.stop(STOP_1).stop(STOP_2).stop(STOP_3).stop(STOP_4).build();
    }
}
