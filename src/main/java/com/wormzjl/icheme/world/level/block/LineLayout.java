package com.wormzjl.icheme.world.level.block;

import com.wormzjl.icheme.science.equipment.*;

import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

import java.util.*;

/**
 * Immutable physical line design. Preview and server reconstruction use the same cells, points and
 * host edits.
 */
public record LineLayout(
        LinePlacement.Plan spatial,
        List<Cell> cells,
        List<HostEdit> hosts,
        List<Endpoint> endpoints) {
    public record Site(BlockPos cell, int lane) {
        public Site {
            cell = cell.immutable();
            if (lane < 0 || lane > RackGeometry.OPTIONS)
                throw new IllegalArgumentException("Invalid line lane");
        }
    }

    public record Cell(Site site, int joints, int arms, long points, int facing, double outside) {}

    public record Anchor(
            PipeAnchor placement,
            LineBackend.Host host,
            LineBackend.Socket socket,
            int face,
            int point) {}

    public record Connection(LineBackend.Host host, int face, int point) {}

    public record HostChanges(List<HostEdit> edits, Component refusal) {
        public HostChanges {
            edits = List.copyOf(edits);
        }
    }

    public record HostEdit(LineBackend.Host before, int joints, int changedFaces, long points) {}

    public record Endpoint(Site site, int face, UUID socket) {}

    public record Built(LineLayout layout, Component refusal) {}

    public LineLayout {
        cells = List.copyOf(cells);
        hosts = List.copyOf(hosts);
        endpoints = List.copyOf(endpoints);
    }

    private static long point(long points, int face, int packed) {
        return points & ~(1023L << 10 * face) | (long) (packed & 1023) << 10 * face;
    }

    /** Combine both route ends before enforcing a retained segment's joint/point constraints. */
    public static HostChanges mergeHosts(List<Connection> connections) {
        var edits = new LinkedHashMap<Site, HostEdit>();
        for (var connection : connections) {
            var host = connection.host();
            int face = connection.face();
            if (face < 0 || face > 5 || connection.point() < 0 || connection.point() > 1023)
                throw new IllegalArgumentException("Invalid host connection");
            var old = edits.get(host.site());
            int before = old == null ? host.joints() : old.joints();
            int after =
                    host.automatic()
                            ? before
                            : PipeRoute.hostJoints(before, 1 << face, host.lane() > 0);
            if (after < 0)
                return new HostChanges(
                        List.of(),
                        LineLaying.message(
                                host.lane() > 0 ? "anchor.lane_closed" : "anchor.junction"));
            edits.put(
                    host.site(),
                    new HostEdit(
                            host,
                            after,
                            (old == null ? 0 : old.changedFaces()) | 1 << face,
                            point(
                                    old == null ? host.points() : old.points(),
                                    face,
                                    connection.point())));
        }
        return new HostChanges(List.copyOf(edits.values()), null);
    }

    public static Built build(
            Level level,
            Anchor start,
            Anchor end,
            PipeRoute.Result route,
            LineProfile.Settings settings) {
        var path = route.cells().stream().map(PipeAnchor::block).toList();
        var leads = new ArrayList<LinePlacement.Lead>();
        for (var anchor : List.of(start, end))
            if (anchor.socket() != null && anchor.socket().lead() != null)
                leads.add(anchor.socket().lead());
        var spatial =
                new LinePlacement.Plan(
                        start.placement(),
                        end.placement(),
                        path,
                        route.options(),
                        settings.outside(),
                        leads);
        var placement =
                PipeRoute.placement(
                        route.cells(),
                        start.placement().end(),
                        start.placement().leaving(),
                        end.placement().end());
        var cells = new ArrayList<Cell>();
        int width = RackGeometry.width(PipeSizes.outsideOf(PipeSizes.code(settings.outside())));
        for (int i = 0; i < path.size(); i++) {
            int option = route.options().get(i);
            long points = 0;
            if (option >= 0) {
                var rack =
                        (com.wormzjl.icheme.world.level.block.entity.PipeRackBlockEntity)
                                level.getBlockEntity(path.get(i));
                points =
                        RackGeometry.points(
                                rack.rack(),
                                new RackGeometry.Lane(
                                        option,
                                        width,
                                        placement.arms()[i],
                                        placement.joints()[i],
                                        PipeSizes.code(settings.outside())));
            }
            cells.add(
                    new Cell(
                            new Site(path.get(i), option + 1),
                            placement.joints()[i],
                            option >= 0 ? placement.arms()[i] : 0,
                            points,
                            placement.facings()[i],
                            settings.outside()));
        }
        var connections = new ArrayList<Connection>();
        var endpoints = new ArrayList<Endpoint>();
        for (int k = 0; k < 2; k++) {
            var anchor = k == 0 ? start : end;
            int index = k == 0 ? 0 : cells.size() - 1;
            var cell = cells.get(index);
            int face = anchor.placement().end().joint();
            if (face >= 0) {
                int packed =
                        anchor.point() >= 0
                                ? anchor.point()
                                : (int) (cell.points() >> 10 * face) & 1023;
                cell =
                        new Cell(
                                cell.site(),
                                cell.joints(),
                                cell.arms(),
                                point(cell.points(), face, packed),
                                cell.facing(),
                                cell.outside());
                cells.set(index, cell);
                if (anchor.socket() != null && anchor.socket().id() != null)
                    endpoints.add(new Endpoint(cell.site(), face, anchor.socket().id()));
            }
            var host = anchor.host();
            if (host == null) continue;
            int packed = anchor.point() >= 0 ? anchor.point() : 0;
            if (cell.site().lane() > 0) {
                int opposite = anchor.face() ^ 1;
                packed = (int) (cell.points() >> 10 * opposite) & 1023;
            }
            connections.add(new Connection(host, anchor.face(), packed));
        }
        // Every plain cell next to a rack meets the rack's actual point, in either direction.
        for (int i = 0; i < cells.size(); i++) {
            var cell = cells.get(i);
            if (cell.site().lane() > 0) continue;
            long points = cell.points();
            for (int j : new int[] {i - 1, i + 1})
                if (j >= 0 && j < cells.size() && cells.get(j).site().lane() > 0) {
                    var other = cells.get(j);
                    for (var face : Direction.values())
                        if (cell.site().cell().relative(face).equals(other.site().cell()))
                            points =
                                    point(
                                            points,
                                            face.ordinal(),
                                            (int)
                                                            (other.points()
                                                                    >> 10
                                                                            * face.getOpposite()
                                                                                    .ordinal())
                                                    & 1023);
                }
            cells.set(
                    i,
                    new Cell(
                            cell.site(),
                            cell.joints(),
                            cell.arms(),
                            points,
                            cell.facing(),
                            cell.outside()));
        }
        var hosts = mergeHosts(connections);
        return hosts.refusal() != null
                ? new Built(null, hosts.refusal())
                : new Built(new LineLayout(spatial, cells, hosts.edits(), endpoints), null);
    }
}
