package com.wormzjl.icheme.science.equipment;

/**
 * Service-plan socket datums shared by structural registrations, rendering and the laying
 * framework.
 */
public final class DataSocketGeometry {
    private DataSocketGeometry() {}

    public record Socket(double x, double y, double z, int face, boolean junctionBox) {
        public Socket(double x, double y, double z, int face) {
            this(x, y, z, face, true);
        }
    }

    public static Socket equipment(EquipmentShape shape) {
        return ShapeRasterizer.services(shape).data();
    }

    public static Socket power(EquipmentShape shape) {
        return ShapeRasterizer.services(shape).power();
    }

    public static Socket drum(EquipmentShape shape, int requiredFace) {
        if (shape.kind() != EquipmentShape.Kind.DRUM) return null;
        var socket = equipment(shape);
        return socket != null && (requiredFace < 0 || socket.face() == requiredFace)
                ? socket
                : null;
    }
}
