package com.wormzjl.icheme.runtime.control;

import com.wormzjl.icheme.science.equipment.ConduitGeometry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Persistent physical socket and cable entry. Process bindings use sensor instance identities,
 * independently.
 */
public record DataPort(
        UUID id,
        Kind kind,
        long host,
        String dimension,
        BlockPos owner,
        Vec3 centre,
        BlockPos cell,
        Direction face,
        int u,
        int v) {
    public enum Kind {
        SENSOR,
        ACTUATOR,
        CONTROLLER,
        DCS,
        POWER_INPUT,
        POWER_OUTPUT,
        POWER_BOTH;

        public boolean power() {
            return ordinal() >= POWER_INPUT.ordinal();
        }

        public com.wormzjl.icheme.science.equipment.CableMedium medium() {
            return power()
                    ? com.wormzjl.icheme.science.equipment.CableMedium.POWER
                    : com.wormzjl.icheme.science.equipment.CableMedium.DATA;
        }
    }

    private static final Set<String> FIELDS =
            Set.of(
                    "id",
                    "kind",
                    "host",
                    "dimension",
                    "owner",
                    "x",
                    "y",
                    "z",
                    "cell",
                    "face",
                    "u",
                    "v");

    public DataPort {
        Objects.requireNonNull(id);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(dimension);
        Objects.requireNonNull(owner);
        Objects.requireNonNull(centre);
        Objects.requireNonNull(cell);
        Objects.requireNonNull(face);
        owner = owner.immutable();
        cell = cell.immutable();
        if (net.minecraft.resources.ResourceLocation.tryParse(dimension) == null
                || host < 0
                || dimension.isBlank()
                || dimension.length() > 128
                || u < -16
                || u > 15
                || v < -16
                || v > 15
                || !Double.isFinite(centre.x)
                || !Double.isFinite(centre.y)
                || !Double.isFinite(centre.z)
                || centre.distanceToSqr(cell.getCenter()) > 25)
            throw new IllegalArgumentException("Invalid data port");
        if (kind.power() ? host != 0 : (kind == Kind.SENSOR || kind == Kind.ACTUATOR) != (host > 0))
            throw new IllegalArgumentException("Invalid port host");
    }

    /**
     * The way the socket faces, out of its owner. A wired socket never flips to another side
     * ({@link DataPorts#remount}), so this is also the way its cable leaves.
     */
    public Direction outward() {
        return face.getOpposite();
    }

    public Vec3 cablePoint() {
        var p = new ConduitGeometry.Key(0, 0).connect(face.ordinal(), u, v).point(face.ordinal());
        return new Vec3(cell.getX() + p[0], cell.getY() + p[1], cell.getZ() + p[2]);
    }

    public CompoundTag save() {
        var t = new CompoundTag();
        t.putUUID("id", id);
        t.putString("kind", kind.name());
        t.putLong("host", host);
        t.putString("dimension", dimension);
        t.putLong("owner", owner.asLong());
        t.putDouble("x", centre.x);
        t.putDouble("y", centre.y);
        t.putDouble("z", centre.z);
        t.putLong("cell", cell.asLong());
        t.putString("face", face.name());
        t.putInt("u", u);
        t.putInt("v", v);
        return t;
    }

    public static DataPort load(CompoundTag t) {
        if (!t.getAllKeys().equals(FIELDS) || !t.hasUUID("id"))
            throw new IllegalArgumentException("Damaged data port facts");
        for (var key : List.of("host", "owner", "cell"))
            if (!t.contains(key, 4)) throw new IllegalArgumentException("Invalid data port " + key);
        for (var key : List.of("x", "y", "z"))
            if (!t.contains(key, 6)) throw new IllegalArgumentException("Invalid data port " + key);
        for (var key : List.of("kind", "dimension", "face"))
            if (!t.contains(key, 8)) throw new IllegalArgumentException("Invalid data port " + key);
        if (!t.contains("u", 3) || !t.contains("v", 3))
            throw new IllegalArgumentException("Invalid cable face point");
        return new DataPort(
                t.getUUID("id"),
                Kind.valueOf(t.getString("kind")),
                t.getLong("host"),
                t.getString("dimension"),
                BlockPos.of(t.getLong("owner")),
                new Vec3(t.getDouble("x"), t.getDouble("y"), t.getDouble("z")),
                BlockPos.of(t.getLong("cell")),
                Direction.valueOf(t.getString("face")),
                t.getInt("u"),
                t.getInt("v"));
    }
}
