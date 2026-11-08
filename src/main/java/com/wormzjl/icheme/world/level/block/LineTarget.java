package com.wormzjl.icheme.world.level.block;

import net.minecraft.core.*;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** A physical click, optionally identifying a service socket or an explicit rack lane. */
public record LineTarget(UUID port, BlockPos clicked, Direction face, Vec3 local, int lane) {
    public LineTarget {
        Objects.requireNonNull(clicked);
        Objects.requireNonNull(face);
        Objects.requireNonNull(local);
        clicked = clicked.immutable();
        if (lane < 0
                || lane > com.wormzjl.icheme.science.equipment.RackGeometry.OPTIONS
                || !Double.isFinite(local.x + local.y + local.z)
                || local.x < 0
                || local.x > 1
                || local.y < 0
                || local.y > 1
                || local.z < 0
                || local.z > 1) throw new IllegalArgumentException("Invalid line target");
    }

    public static LineTarget of(PipeAnchor.Click click) {
        return new LineTarget(null, click.clicked(), click.face(), click.local(), 0);
    }

    public PipeAnchor.Click click() {
        return new PipeAnchor.Click(clicked, face, local);
    }
}
