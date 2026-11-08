package com.wormzjl.icheme.world.level.block;

import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.science.equipment.*;

import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.*;

/** Sole server command owner and preview design entry for fluid, data and power lines. */
public final class LineLaying {
    public static final int START_RANGE = 64;

    public enum Action {
        LAY,
        ATTACH,
        DISMANTLE
    }

    public record Request(
            UUID id,
            boolean offHand,
            LineProfile profile,
            Action action,
            LineTarget first,
            LineTarget last,
            List<BlockPos> path,
            List<Integer> options) {
        public Request {
            Objects.requireNonNull(id);
            Objects.requireNonNull(profile);
            Objects.requireNonNull(action);
            Objects.requireNonNull(first);
            Objects.requireNonNull(last);
            path = path.stream().map(BlockPos::immutable).toList();
            options = List.copyOf(options);
            if (path.size() > PipeRoute.Limits.DEFAULT.cells()
                    || options.size() != path.size()
                    || action != Action.LAY && (!path.isEmpty() || !first.equals(last))
                    || options.stream().anyMatch(o -> o < -1 || o >= RackGeometry.OPTIONS))
                throw new IllegalArgumentException("Invalid line command");
        }
    }

    public record Result(UUID id, boolean success, Component message, int cells) {
        public Result {
            Objects.requireNonNull(id);
            Objects.requireNonNull(message);
            if (cells < 0 || cells > PipeRoute.Limits.DEFAULT.cells())
                throw new IllegalArgumentException("Invalid line result");
        }
    }

    public record Design(
            LineLayout layout,
            Component refusal,
            PipeRoute.Result route,
            LineLayout.Anchor start,
            LineLayout.Anchor end) {}

    private LineLaying() {}

    /** A line-laying message ({@code message.icheme.pipe_laying.<key>}); every medium shares them. */
    public static Component message(String key, Object... args) {
        return Component.translatable("message.icheme.pipe_laying." + key, args);
    }

    public static Component refusal(PipeAnchor.Refusal reason) {
        return message("anchor." + name(reason));
    }

    public static Component refusal(PipeRoute.Refusal reason) {
        return message("route." + name(reason));
    }

    /** What an anchor is, for the HUD: a nozzle, a pipe end, a rack lane, a free block. */
    public static Component describe(PipeAnchor anchor) {
        return message("end." + name(anchor.kind()));
    }

    private static String name(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    public static Design search(
            Level level, LineTarget first, LineTarget last, LineProfile.Settings settings) {
        first = LinePieces.target(level, first, settings.profile());
        last = LinePieces.target(level, last, settings.profile());
        var a = LineAnchors.resolve(level, first, settings);
        var b = LineAnchors.resolve(level, last, settings);
        if (a.anchor() == null || b.anchor() == null)
            return new Design(
                    null,
                    LineLaying.refusal(a.anchor() == null ? a.refusal() : b.refusal()),
                    null,
                    a.anchor(),
                    b.anchor());
        var leads = leads(a.anchor(), b.anchor());
        var refused =
                PipeAnchor.refuses(
                        a.anchor().placement(), b.anchor().placement(), settings.outside());
        if (refused != null)
            return new Design(null, LineLaying.refusal(refused), null, a.anchor(), b.anchor());
        var leadFailure = LinePlacement.checkLeads(level, leads);
        if (leadFailure != null) return new Design(null, leadFailure, null, a.anchor(), b.anchor());
        var route =
                LinePlacement.search(
                        level,
                        a.anchor().placement(),
                        b.anchor().placement(),
                        settings.outside(),
                        leads);
        if (!route.found())
            return new Design(
                    null, LineLaying.refusal(route.refusal()), route, a.anchor(), b.anchor());
        var built = LineLayout.build(level, a.anchor(), b.anchor(), route, settings);
        return new Design(built.layout(), built.refusal(), route, a.anchor(), b.anchor());
    }

    public static List<LinePlacement.Lead> leads(LineLayout.Anchor a, LineLayout.Anchor b) {
        var result = new ArrayList<LinePlacement.Lead>();
        for (var anchor : List.of(a, b))
            if (anchor.socket() != null && anchor.socket().lead() != null)
                result.add(anchor.socket().lead());
        return List.copyOf(result);
    }

    /**
     * Existing entry geometry is retained and merged by the same physical edit model as an ordinary
     * lay.
     */
    public static Design attachment(Level level, LineTarget target, LineProfile.Settings settings) {
        if (!level.hasChunkAt(target.clicked()))
            return failed(LineLaying.message("anchor.outside"));
        var backend = LineBackend.of(settings.profile());
        var found = backend.socket(level, target, settings);
        var port = found.socket();
        if (port == null || port.id() == null || !level.hasChunkAt(port.cell()))
            return failed(LineLaying.message("anchor.fitting"));
        var host =
                backend.hosts(level, port.cell(), settings.profile()).stream()
                        .filter(h -> h.lane() == 0)
                        .findFirst()
                        .orElse(null);
        if (host == null) return failed(LineLaying.message("anchor.fitting"));
        var edits =
                LineLayout.mergeHosts(
                        List.of(
                                new LineLayout.Connection(
                                        host, port.face().ordinal(), port.point())));
        if (edits.refusal() != null) return failed(edits.refusal());
        var bound = backend.binding(level, host, port.face().ordinal());
        if (bound != null && !bound.equals(port.id()))
            return failed(LineLaying.message("anchor.fitting"));
        var spatial =
                new LinePlacement.Plan(
                        null,
                        null,
                        List.of(port.cell()),
                        List.of(),
                        settings.outside(),
                        port.lead() == null ? List.of() : List.of(port.lead()));
        var layout =
                new LineLayout(
                        spatial,
                        List.of(),
                        edits.edits(),
                        List.of(
                                new LineLayout.Endpoint(
                                        host.site(), port.face().ordinal(), port.id())));
        return new Design(
                layout,
                LinePlacement.check(level, spatial),
                null,
                new LineLayout.Anchor(null, host, port, port.face().ordinal(), port.point()),
                null);
    }

    private static Design failed(Component reason) {
        return new Design(null, reason, null, null, null);
    }

    public static Result execute(ServerPlayer player, Request request) {
        var hand = request.offHand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        var tool = player.getItemInHand(hand);
        if (!tool.is(ModItems.PIPE_LAYING_TOOL.get())
                || LineProfile.of(tool) != request.profile()
                || player.isSpectator()
                || !player.mayBuild()
                || request.profile() == LineProfile.FLUID
                        && tool.get(ModItems.PIPE_LAYING_SETTINGS.get()) == null)
            return result(request, LineLaying.message("not_allowed"), 0);
        var level = player.serverLevel();
        var world = FluidWorldAuthority.find(player.server).orElse(null);
        if (world == null) return result(request, LineLaying.message("not_allowed"), 0);
        if (!level.hasChunkAt(request.first().clicked())
                || !level.hasChunkAt(request.last().clicked()))
            return result(request, LineLaying.message("anchor.outside"), 0);
        var settings = request.profile().settings(tool);
        var backend = LineBackend.of(request.profile());
        if (request.first().clicked().getCenter().distanceToSqr(player.position())
                        > START_RANGE * START_RANGE
                || !reachable(player, request.last(), settings))
            return result(request, LineLaying.message("out_of_reach"), 0);
        if (request.action() == Action.DISMANTLE)
            return result(request, dismantle(player, world, request.first(), settings), 0);
        Design design;
        if (request.action() == Action.ATTACH)
            design = attachment(level, request.first(), settings);
        else {
            var a =
                    LineAnchors.resolve(
                            level,
                            LinePieces.target(level, request.first(), settings.profile()),
                            settings);
            var b =
                    LineAnchors.resolve(
                            level,
                            LinePieces.target(level, request.last(), settings.profile()),
                            settings);
            if (a.anchor() == null || b.anchor() == null)
                return result(
                        request,
                        LineLaying.refusal(a.anchor() == null ? a.refusal() : b.refusal()),
                        0);
            if (request.path().isEmpty())
                return result(request, LineLaying.message("not_allowed"), 0);
            var spatial =
                    new LinePlacement.Plan(
                            a.anchor().placement(),
                            b.anchor().placement(),
                            request.path(),
                            request.options(),
                            settings.outside(),
                            leads(a.anchor(), b.anchor()));
            var refused = LinePlacement.check(level, spatial);
            if (refused != null) return result(request, refused, 0);
            var built =
                    LineLayout.build(
                            level,
                            a.anchor(),
                            b.anchor(),
                            new PipeRoute.Result(
                                    request.path().stream().map(PipeAnchor::cell).toList(),
                                    request.options(),
                                    null),
                            settings);
            design = new Design(built.layout(), built.refusal(), null, a.anchor(), b.anchor());
        }
        if (design.refusal() != null) return result(request, design.refusal(), 0);
        var refused = backend.validateFacts(level, world, design.layout());
        if (refused != null) return result(request, refused, 0);
        int count = design.layout().cells().size();
        refused =
                LinePlacement.commit(
                        player,
                        design.layout().spatial(),
                        request.profile().item(),
                        count,
                        Component.translatable(
                                "message.icheme.conduit.items",
                                count,
                                request.profile().item().getDescription()),
                        () -> {
                            for (var anchor : Arrays.asList(design.start(), design.end()))
                                if (anchor != null)
                                    backend.registerSocket(level, world, anchor.socket());
                            return backend.register(level, world, design.layout(), settings);
                        });
        return result(request, refused, count);
    }

    private static boolean reachable(
            ServerPlayer player, LineTarget target, LineProfile.Settings settings) {
        if (target.port() == null) return player.canInteractWithBlock(target.clicked(), 1);
        var socket =
                LineBackend.of(settings.profile())
                        .socket(player.level(), target, settings)
                        .socket();
        return socket != null
                && socket.centre().distanceToSqr(player.getEyePosition())
                        <= Math.pow(player.blockInteractionRange() + 1, 2);
    }

    private static Component dismantle(
            ServerPlayer player,
            FluidWorldAuthority world,
            LineTarget target,
            LineProfile.Settings settings) {
        var level = player.serverLevel();
        if (!player.canInteractWithBlock(target.clicked(), 1)
                || !LinePlacement.editable(player, target.clicked()))
            return LineLaying.message("not_allowed");
        target = LinePieces.target(level, target, settings.profile());
        if (!LinePlacement.editable(player, target.clicked()))
            return LineLaying.message("not_allowed");
        var backend = LineBackend.of(settings.profile());
        var hosts = backend.hosts(level, target.clicked(), settings.profile());
        int lane = target.lane();
        if (level.getBlockEntity(target.clicked())
                        instanceof
                        com.wormzjl.icheme.world.level.block.entity.PipeRackBlockEntity rack
                && lane == 0
                && settings.profile() == LineProfile.FLUID) {
            var pick = rack.pick(target.local().x, target.local().y, target.local().z);
            if (pick.lane() >= 0) lane = rack.lanes().get(pick.lane()).option() + 1;
        }
        int selected = lane;
        var host = hosts.stream().filter(h -> h.lane() == selected).findFirst().orElse(null);
        if (host == null) return LineLaying.message("not_a_pipe");
        for (var piece : LinePieces.at(level, host.cell()))
            if (piece.parent().cell().equals(host.cell()) && piece.parent().lane() == host.lane())
                for (var cell : piece.cells().keySet())
                    if (!LinePlacement.editable(player, cell))
                        return LineLaying.message("not_allowed");
        if (host.lane() == 0) {
            boolean done = player.gameMode.destroyBlock(host.cell());
            LinePieces.flush(player.server);
            return done ? null : LineLaying.message("not_allowed");
        }
        backend.forgetLane(level, world, host);
        backend.refresh(level, List.of(host.cell()));
        LinePieces.flush(player.server);
        if (!player.getAbilities().instabuild)
            player.getInventory()
                    .placeItemBackInInventory(new ItemStack(settings.profile().item()));
        return null;
    }

    /**
     * BlockItem placement uses the same host merge, socket checks and mutation owner as a tool
     * command. Fluid hand pipes retain their native automatic-face registration; conduits carry
     * explicit structural joints.
     */
    public static void handPlaced(
            Level level,
            BlockPos cell,
            LineProfile profile,
            Direction facing,
            ServerPlayer player) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel server)) return;
        var world = FluidWorldAuthority.find(server.getServer()).orElse(null);
        if (world == null) return;
        var backend = LineBackend.of(profile);
        var host =
                backend.hosts(level, cell, profile).stream()
                        .filter(h -> h.lane() == 0)
                        .findFirst()
                        .orElse(null);
        if (host == null || host.joints() != 0) return;
        var settings = new LineProfile.Settings(profile, host.bore(), host.outside(), 0, 0);
        List<LineLayout.Connection> connections = new ArrayList<>();
        var endpoints = new ArrayList<LineLayout.Endpoint>();
        var sockets = new ArrayList<LineBackend.Socket>();
        int joints = 0;
        if (player != null)
            for (var socket : backend.socketsAt(level, world, cell, settings)) {
                int face = socket.face().ordinal();
                if ((joints & 1 << face) != 0) continue;
                var design =
                        attachment(
                                level,
                                new LineTarget(
                                        socket.id(),
                                        socket.owner(),
                                        socket.face().getOpposite(),
                                        new net.minecraft.world.phys.Vec3(.5, .5, .5),
                                        0),
                                settings);
                if (design.layout() == null
                        || design.refusal() != null
                        || LinePlacement.touched(design.layout().spatial()).stream()
                                .anyMatch(p -> !LinePlacement.editable(player, p))) continue;
                var candidate = new ArrayList<>(connections);
                candidate.add(new LineLayout.Connection(host, face, socket.point()));
                if (LineLayout.mergeHosts(candidate).refusal() != null) continue;
                connections = candidate;
                joints |= 1 << face;
                sockets.add(socket);
                endpoints.add(new LineLayout.Endpoint(host.site(), face, socket.id()));
            }
        for (var face : Direction.values()) {
            if ((joints & 1 << face.ordinal()) != 0) continue;
            var neighbour =
                    backend.hosts(level, cell.relative(face), profile).stream()
                            .filter(h -> (h.joints() & 1 << face.getOpposite().ordinal()) != 0)
                            .findFirst()
                            .orElse(null);
            if (neighbour == null) continue;
            var candidate = new ArrayList<>(connections);
            candidate.add(
                    new LineLayout.Connection(
                            host,
                            face.ordinal(),
                            (int) (neighbour.points() >> 10 * face.getOpposite().ordinal())
                                    & 1023));
            if (LineLayout.mergeHosts(candidate).refusal() != null) continue;
            connections = candidate;
            joints |= 1 << face.ordinal();
        }
        if (joints == 0) {
            connections.add(new LineLayout.Connection(host, facing.ordinal(), 0));
            connections.add(new LineLayout.Connection(host, facing.getOpposite().ordinal(), 0));
        } else if (Integer.bitCount(joints) == 1)
            connections.add(
                    new LineLayout.Connection(host, Integer.numberOfTrailingZeros(joints) ^ 1, 0));
        var spatial =
                new LinePlacement.Plan(
                        null,
                        null,
                        List.of(cell),
                        List.of(),
                        settings.outside(),
                        sockets.stream()
                                .map(LineBackend.Socket::lead)
                                .filter(Objects::nonNull)
                                .distinct()
                                .toList());
        // A multi-socket hand placement also checks the combined physical leads before binding any
        // endpoint.
        if (LinePlacement.check(level, spatial) != null) {
            endpoints.clear();
            sockets.clear();
            spatial =
                    new LinePlacement.Plan(
                            null, null, List.of(cell), List.of(), settings.outside(), List.of());
            connections =
                    List.of(
                            new LineLayout.Connection(host, facing.ordinal(), 0),
                            new LineLayout.Connection(host, facing.getOpposite().ordinal(), 0));
        }
        var edits = LineLayout.mergeHosts(connections);
        if (edits.refusal() != null) return;
        var layout = new LineLayout(spatial, List.of(), edits.edits(), endpoints);
        sockets.forEach(socket -> backend.registerSocket(level, world, socket));
        LinePlacement.apply(level, backend.register(level, world, layout, settings));
        LinePieces.flush(server.getServer());
    }

    /**
     * Native destruction of a derived copy delegates to the same segment registration/removal
     * owner.
     */
    public static void breakPiece(ServerPlayer player, LinePieces.Piece piece, BlockPos clicked) {
        if (player.isSpectator() || !player.mayBuild()) return;
        for (var cell : piece.cells().keySet()) if (!LinePlacement.editable(player, cell)) return;
        var target =
                new LineTarget(
                        null,
                        clicked,
                        piece.port().face(),
                        new net.minecraft.world.phys.Vec3(.5, .5, .5),
                        piece.parent().lane());
        FluidWorldAuthority.find(player.server)
                .ifPresent(
                        world ->
                                dismantle(
                                        player,
                                        world,
                                        target,
                                        new LineProfile.Settings(
                                                LineProfile.of(piece.port().kind().medium()),
                                                piece.port().kind().medium().outside(),
                                                piece.port().kind().medium().outside(),
                                                0,
                                                0)));
    }

    private static Result result(Request request, Component refusal, int count) {
        return new Result(
                request.id(),
                refusal == null,
                refusal != null
                        ? refusal
                        : LineLaying.message(
                                request.action() == Action.DISMANTLE ? "dismantled" : "laid",
                                count),
                count);
    }
}
