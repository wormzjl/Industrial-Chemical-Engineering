package com.wormzjl.icheme.world.level.block;

import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.world.level.block.entity.PipeRackBlockEntity;

import net.minecraft.core.*;
import net.minecraft.world.level.Level;

import java.util.*;

/**
 * One physical target resolver: rack arms, branches, fit and free cells. Backends provide facts and
 * domain ports.
 */
public final class LineAnchors {
    public record Resolved(LineLayout.Anchor anchor, PipeAnchor.Refusal refusal) {}

    private LineAnchors() {}

    public static Resolved resolve(Level level, LineTarget target, LineProfile.Settings settings) {
        target = LinePieces.target(level, target, settings.profile());
        if (!level.hasChunkAt(target.clicked()))
            return new Resolved(null, PipeAnchor.Refusal.OUTSIDE);
        var backend = LineBackend.of(settings.profile());
        var at = target.clicked();
        var be = level.getBlockEntity(at);
        var hosts = backend.hosts(level, at, settings.profile());
        LineBackend.Host host = null;
        int face = target.face().ordinal();
        if (be instanceof PipeRackBlockEntity rack) {
            int lane = target.lane();
            if (lane == 0 && settings.profile() == LineProfile.FLUID) {
                var pick = rack.pick(target.local().x, target.local().y, target.local().z);
                if (pick.lane() >= 0) {
                    lane = rack.lanes().get(pick.lane()).option() + 1;
                    face = pick.face();
                }
            }
            if (lane > 0) {
                int selected = lane;
                host = hosts.stream().filter(h -> h.lane() == selected).findFirst().orElse(null);
                if (host == null) return new Resolved(null, PipeAnchor.Refusal.LANE_CLOSED);
            } else
                return wrap(
                        PipeAnchor.rack(at, rack, target.local(), settings.outside()),
                        null,
                        null,
                        -1,
                        -1);
        } else if (target.lane() > 0) return new Resolved(null, PipeAnchor.Refusal.FITTING);
        else if (target.port() == null && !hosts.isEmpty()) host = hosts.getFirst();
        if (host != null) {
            var out = Direction.values()[face];
            var next = at.relative(out);
            boolean continued =
                    level.hasChunkAt(next)
                            && level.getBlockEntity(next) instanceof PipeRackBlockEntity;
            int packed = (int) (host.points() >> 10 * face) & 1023;
            PipeAnchor.Fit fit = PipeAnchor.Fit.ANY;
            int edit = -1;
            if (host.lane() > 0) {
                int open = backend.openFaces(level, host, settings.profile());
                face = PipeAnchor.openArm(open, face);
                if (face < 0) return new Resolved(null, PipeAnchor.Refusal.LANE_CLOSED);
                out = Direction.values()[face];
                next = at.relative(out);
                continued =
                        level.hasChunkAt(next)
                                && level.getBlockEntity(next) instanceof PipeRackBlockEntity;
                int option = host.lane() - 1, outgoing = face;
                if (!continued
                        && ((PipeRackBlockEntity) be)
                                .layingLanes().stream()
                                        .anyMatch(
                                                l ->
                                                        l.option() != option
                                                                && (l.joined() & 1 << outgoing)
                                                                        != 0))
                    return new Resolved(null, PipeAnchor.Refusal.FACE_TAKEN);
                packed = (int) (host.points() >> 10 * face) & 1023;
                int width = RackGeometry.width(PipeSizes.outsideOf(PipeSizes.code(host.outside())));
                fit =
                        continued
                                ? new PipeAnchor.Fit(
                                        width,
                                        width,
                                        RackGeometry.height(
                                                PipeSizes.outsideOf(
                                                        PipeSizes.code(host.outside()))))
                                : new PipeAnchor.Fit(1, width, -1);
                if ((host.joints() & 1 << face) == 0) edit = host.joints() | 1 << face;
            } else {
                if ((host.allowedFaces() & 1 << face) == 0)
                    return new Resolved(null, PipeAnchor.Refusal.FITTING);
                if (!host.automatic() && (host.joints() & 1 << face) == 0) {
                    if (!host.branchable()) return new Resolved(null, PipeAnchor.Refusal.FITTING);
                    edit = PipeRoute.hostJoints(host.joints(), 1 << face, false);
                    if (edit < 0) return new Resolved(null, PipeAnchor.Refusal.JUNCTION);
                }
                if (continued) {
                    var rack = (PipeRackBlockEntity) level.getBlockEntity(next);
                    int opposite = face ^ 1;
                    if (rack.layingLanes().stream()
                            .anyMatch(l -> (l.joined() & 1 << opposite) != 0))
                        return new Resolved(null, PipeAnchor.Refusal.FACE_TAKEN);
                    fit =
                            new PipeAnchor.Fit(
                                    RackGeometry.width(
                                            PipeSizes.outsideOf(PipeSizes.code(host.outside()))),
                                    RackGeometry.MAX_WIDTH,
                                    -1);
                }
                if (host.fluidTransition()
                        && (continued || meetsRack(level, host))
                        && (!host.transitionAllowed()
                                || host.automatic()
                                || Integer.bitCount(edit >= 0 ? edit : host.joints()) > 2))
                    return new Resolved(null, PipeAnchor.Refusal.TRANSITION);
            }
            var physical =
                    new PipeAnchor(
                            host.lane() > 0 ? PipeAnchor.Kind.LANE : PipeAnchor.Kind.PIPE,
                            next,
                            out.getOpposite(),
                            at,
                            host.lane(),
                            edit,
                            out,
                            host.fluidTransition() && !continued ? -1 : packed,
                            fit,
                            null);
            return wrap(
                    PipeAnchor.validate(level, physical),
                    host,
                    null,
                    face,
                    host.lane() > 0 || !host.fluidTransition() ? packed : -1);
        }
        var port = backend.socket(level, target, settings);
        if (port.recognized()) {
            if (port.socket() == null) return new Resolved(null, port.refusal());
            var socket = port.socket();
            var result =
                    PipeAnchor.validate(
                            level,
                            new PipeAnchor(
                                    socket.kind(),
                                    socket.cell(),
                                    socket.face(),
                                    socket.owner(),
                                    0,
                                    -1,
                                    socket.face().getOpposite(),
                                    socket.point(),
                                    PipeAnchor.Fit.ANY,
                                    null));
            return wrap(result, null, socket, -1, socket.point());
        }
        return wrap(PipeAnchor.open(level, target.click(), settings.outside()), null, null, -1, -1);
    }

    private static boolean meetsRack(Level level, LineBackend.Host host) {
        if (host.automatic()) return false;
        for (var face : Direction.values())
            if ((host.joints() & 1 << face.ordinal()) != 0
                    && level.hasChunkAt(host.cell().relative(face))
                    && level.getBlockEntity(host.cell().relative(face))
                            instanceof PipeRackBlockEntity rack
                    && rack.layingLanes().stream()
                            .anyMatch(l -> (l.joined() & 1 << face.getOpposite().ordinal()) != 0))
                return true;
        return false;
    }

    private static Resolved wrap(
            PipeAnchor.Resolved result,
            LineBackend.Host host,
            LineBackend.Socket socket,
            int face,
            int point) {
        return new Resolved(
                result.anchor() == null
                        ? null
                        : new LineLayout.Anchor(result.anchor(), host, socket, face, point),
                result.refusal());
    }
}
