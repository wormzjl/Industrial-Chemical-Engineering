package com.wormzjl.icheme.runtime.control;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.UUID;
import java.util.function.Function;

/** Physical face matching shared by bus compilation, laying anchors and client terminal marks. */
public final class CableConnections {
    private CableConnections() {}

    public static boolean binds(DataBus.Cable cable, DataPort port) {
        if (port == null
                || !port.id().equals(cable.endpoints().get(port.face()))
                || cable.geometry().medium() != port.kind().medium()
                || !cable.site().dimension().equals(port.dimension())
                || !cable.site().cell().equals(port.cell())
                || (cable.geometry().joints() & 1 << port.face().ordinal()) == 0) return false;
        return point(cable, port.face()).distanceToSqr(port.cablePoint()) < 1e-12;
    }

    public static boolean meets(DataBus.Cable cable, Direction face, DataBus.Cable other) {
        return cable.geometry().medium() == other.geometry().medium()
                && cable.site().dimension().equals(other.site().dimension())
                && (cable.geometry().joints() & 1 << face.ordinal()) != 0
                && (other.geometry().joints() & 1 << face.getOpposite().ordinal()) != 0
                && point(cable, face).distanceToSqr(point(other, face.getOpposite())) < 1e-12;
    }

    private static Vec3 point(DataBus.Cable cable, Direction face) {
        var p = cable.geometry().point(face.ordinal());
        return Vec3.atLowerCornerOf(cable.site().cell()).add(p[0], p[1], p[2]);
    }

    /**
     * Includes unjointed visible arms and broken joints; readers must inspect only already-loaded
     * neighbours.
     */
    public static int openFaces(
            DataBus.Cable cable,
            Function<BlockPos, ? extends Collection<DataBus.Cable>> neighbours,
            Function<UUID, DataPort> ports) {
        int arms = cable.arms();
        if (cable.site().lane() == 0) {
            arms = cable.geometry().joints();
            if (arms == 0) arms = 48;
            else if (Integer.bitCount(arms) == 1)
                arms |= 1 << (Integer.numberOfTrailingZeros(arms) ^ 1);
        }
        int open = 0;
        for (var face : Direction.values())
            if ((arms & 1 << face.ordinal()) != 0) {
                var endpoint = cable.endpoints().get(face);
                if (endpoint != null && binds(cable, ports.apply(endpoint))) continue;
                boolean joined = false;
                for (var other : neighbours.apply(cable.site().cell().relative(face)))
                    if (meets(cable, face, other)) {
                        joined = true;
                        break;
                    }
                if (!joined) open |= 1 << face.ordinal();
            }
        return open;
    }
}
