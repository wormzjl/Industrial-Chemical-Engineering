package com.wormzjl.icheme.science.tutorial.scene;

import com.wormzjl.icheme.science.equipment.Mesh;

import java.util.List;

/** A film's actor: its polygons in scene coordinates (metres, y up), convex and counter-clockwise seen from outside. */
public record Actor(String id, List<Mesh.Polygon> polygons) {
    public Actor {
        polygons = List.copyOf(polygons);
    }

    /** The box round every vertex: low corner then high corner; all zeros for an actor with no polygon. */
    public double[] bounds() {
        double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (var polygon : polygons) {
            var p = polygon.positions();
            for (int i = 0; i < p.length; i += 3)
                for (int a = 0; a < 3; a++) {
                    b[a] = Math.min(b[a], p[i + a]);
                    b[3 + a] = Math.max(b[3 + a], p[i + a]);
                }
        }
        return polygons.isEmpty() ? new double[6] : b;
    }
}
