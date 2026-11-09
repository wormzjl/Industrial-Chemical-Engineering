package com.wormzjl.icheme.science.equipment;

import static com.wormzjl.icheme.science.equipment.Mesh.*;

import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.List;

/**
 * Cable sockets for data and FE lines: the datum a line enters at, the sockets an equipment's service plan carries,
 * and the weatherproof gland drawn there. Structural registration, rendering and the laying framework read the same
 * datums. The cable entry can sit off-centre on a block face.
 */
public final class SocketGeometry {
    private SocketGeometry() {}

    /**
     * A socket datum: the gland's centre in the owner's local metres, the face index it opens to, and whether the gland
     * sits on a junction box.
     */
    public record Socket(double x, double y, double z, int face, boolean junctionBox) {}

    /** The equipment's data socket, or null where its service plan has none. */
    public static Socket data(EquipmentShape shape) {
        return ShapeRasterizer.services(shape).data();
    }

    /** The equipment's FE socket, or null where its service plan has none. */
    public static Socket power(EquipmentShape shape) {
        return ShapeRasterizer.services(shape).power();
    }

    /** The equipment's socket for {@code medium}, or null. */
    public static Socket of(EquipmentShape shape, CableMedium medium) {
        return medium == CableMedium.POWER ? power(shape) : data(shape);
    }

    /**
     * The gland at a socket, built around {@code centre} facing {@code outward}. Both media use the same gland; its
     * identification ring is {@link Material#GUARD} (blue) on a data socket and {@link Material#ACTUATOR} (orange) on
     * an FE socket.
     */
    public static List<Polygon> polygons(
            double[] centre,
            double[] outward,
            boolean junctionBox,
            CableMedium medium,
            RenderDetail detail) {
        var mesh = new Mesh(detail);
        double cableOutside = medium.outside();
        double opening = Math.max(.035, cableOutside / 2 + .00375);
        double gland = Math.max(.075, opening / .8660254037844386 + .012);
        var ref = Math.abs(outward[1]) < .8 ? new double[] {0, 1, 0} : new double[] {1, 0, 0};
        var f = new Frame(centre, outward, ref);
        if (junctionBox) {
            mesh.block(
                    f.along(-.10),
                    scale(f.ref(), .13),
                    scale(f.side(), .13),
                    scale(outward, .08),
                    Material.MACHINE);
            mesh.block(
                    f.along(-.012),
                    scale(f.ref(), .115),
                    scale(f.side(), .115),
                    scale(outward, .008),
                    Material.FLANGE);
            for (int a : new int[] {-1, 1})
                for (int b : new int[] {-1, 1})
                    mesh.nut(
                            new Frame(f.at(DeterministicMath.atan2(b, a), .13, -.003), outward, ref),
                            .008,
                            0,
                            .01,
                            6,
                            Material.BOLT);
        }
        mesh.cylinder(f, Math.max(.03, cableOutside / 2), -.22, -.04, 8, Material.PIPE);
        mesh.cylinder(f, .11, -.04, .015, 8, Material.FLANGE);
        mesh.prism(f, gland, .015, .05, 6, Material.PIPE);
        mesh.annulus(f, opening, gland, .05, 6, 1, Material.GUARD);
        mesh.cylinder(f, opening, .025, .055, 8, Material.OPENING);
        var polygons = LineGeometry.overhanging(mesh);
        if (medium != CableMedium.POWER) return polygons;
        return polygons.stream()
                .map(p -> p.material() == Material.GUARD
                        ? new Polygon(p.positions(), p.uvs(), p.normals(), Material.ACTUATOR)
                        : p)
                .toList();
    }
}
