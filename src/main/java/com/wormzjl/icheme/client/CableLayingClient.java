package com.wormzjl.icheme.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wormzjl.icheme.network.*;
import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.runtime.control.CableConnections;
import com.wormzjl.icheme.runtime.control.DataBus;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.world.item.PipeLayingToolItem;
import com.wormzjl.icheme.world.level.block.*;
import com.wormzjl.icheme.world.level.block.entity.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.*;

import java.util.*;

/** Signal/FE anchor, physical plan and drawing adapter for the shared line-tool session. */
public final class CableLayingClient {
    private CableLayingClient() {}

    // Read-only drawing projection of the common session.
    private static LineTarget start, end, hovered;
    private static LineLaying.Design attachGhost;
    private static boolean attachRefused;
    private static final LineToolMarkers markers = new LineToolMarkers();

    private record Mark(Object id, AABB bounds, int colour, double distance) {}

    private static List<Mark> nearbyMarks = List.of();
    private static Component refusal;
    private static LineLayout.Anchor from, to;
    private static List<DataBus.Cable> hostGhost = List.of();
    private static net.minecraft.world.level.Level markedLevel;
    private static CableMedium markedMedium;

    public static CableMedium medium() {
        return PipeLayingToolItem.medium(LayingToolAim.held(Minecraft.getInstance().player));
    }

    private static int age;
    private static List<DataBus.Cable> ghost = List.of();
    private static PipeRoute.Result route;

    public static boolean holding(LocalPlayer p) {
        return LayingToolAim.holding(p) && PipeLayingToolItem.conduit(LayingToolAim.held(p));
    }

    private static LineTarget aimed() {
        var mc = Minecraft.getInstance();
        var p = mc.player;
        var ports =
                DataControlRenderer.ports(
                        mc.level, p.getEyePosition(), p.blockInteractionRange() + 1, medium());
        var port = LayingToolAim.port(mc, ports.values());
        double solid =
                mc.hitResult == null
                        ? Double.POSITIVE_INFINITY
                        : mc.hitResult.getLocation().distanceTo(p.getEyePosition());
        if (port != null)
            return new LineTarget(
                    port.id(), port.owner(), port.face().getOpposite(), new Vec3(.5, .5, .5), 0);
        LineTarget picked = null;
        double nearest = solid + .04;
        var eye = p.getEyePosition();
        var end = eye.add(p.getLookAngle().scale(p.blockInteractionRange()));
        for (var rack : PipeRackBlockEntity.clientRacks())
            if (rack.getLevel() == mc.level
                    && rack.getBlockPos().getCenter().distanceToSqr(eye)
                            < Math.pow(p.blockInteractionRange() + 2, 2))
                for (var cable : rack.cables()) {
                    if (cable.geometry().medium() != medium()) continue;
                    var lane = rack.cableLane(cable);
                    for (var box : ConduitGeometry.laneBoxes(rack.rack(), lane, cable.geometry())) {
                        var hit =
                                new AABB(box[0], box[1], box[2], box[3], box[4], box[5])
                                        .move(rack.getBlockPos())
                                        .inflate(.025)
                                        .clip(eye, end)
                                        .orElse(null);
                        if (hit == null || hit.distanceTo(eye) >= nearest) continue;
                        nearest = hit.distanceTo(eye);
                        var local = hit.subtract(Vec3.atLowerCornerOf(rack.getBlockPos()));
                        int face = -1;
                        double closest = Double.POSITIVE_INFINITY;
                        var actual = ConduitGeometry.laneKey(rack.rack(), lane, cable.geometry());
                        for (int f = 0; f < 6; f++)
                            if ((cable.arms() & (1 << f)) != 0) {
                                var q = actual.point(f);
                                double distance = local.distanceToSqr(new Vec3(q[0], q[1], q[2]));
                                if (distance < closest) {
                                    closest = distance;
                                    face = f;
                                }
                            }
                        picked =
                                new LineTarget(
                                        null,
                                        rack.getBlockPos(),
                                        Direction.values()[face],
                                        new Vec3(
                                                Math.clamp(local.x, 0, 1),
                                                Math.clamp(local.y, 0, 1),
                                                Math.clamp(local.z, 0, 1)),
                                        cable.site().lane());
                    }
                }
        if (picked != null) return picked;
        var click = LayingToolAim.aimed(mc, (rack, hit) -> false);
        return click == null
                ? null
                : new LineTarget(null, click.clicked(), click.face(), click.local(), 0);
    }

    private record Plan(
            LineTarget end,
            LineLayout.Anchor from,
            LineLayout.Anchor to,
            PipeRoute.Result route,
            Component refusal,
            List<DataBus.Cable> ghost,
            List<DataBus.Cable> hosts) {}

    private static final LineToolSession<LineTarget, LineTarget, Plan> SESSION =
            new LineToolSession<>(
                    new LineToolSession.Adapter<>() {
                        public LineTarget aimed() {
                            return CableLayingClient.aimed();
                        }

                        public BlockPos cell(LineTarget click) {
                            return click.clicked();
                        }

                        public LineToolSession.Selected<LineTarget> select(LineTarget click) {
                            var mc = Minecraft.getInstance();
                            var anchor =
                                    LineAnchors.resolve(
                                                    mc.level, click, CableLayingClient.settings())
                                            .anchor();
                            return new LineToolSession.Selected<>(
                                    anchor == null ? null : click,
                                    anchor == null ? noRoute() : null);
                        }

                        public Plan plan(LineTarget selected, LineTarget end) {
                            return build(selected, end);
                        }

                        public boolean same(LineTarget a, LineTarget b) {
                            if (!a.clicked().equals(b.clicked())
                                    || a.face() != b.face()
                                    || a.lane() != b.lane()
                                    || !Objects.equals(a.port(), b.port())) return false;
                            var mc = Minecraft.getInstance();
                            return a.port() != null
                                    || a.lane() > 0
                                    || !(mc.level.getBlockEntity(a.clicked())
                                            instanceof PipeRackBlockEntity rack)
                                    || Objects.equals(
                                            PipeAnchor.aimed(rack, a.local(), medium().outside()),
                                            PipeAnchor.aimed(rack, b.local(), medium().outside()));
                        }

                        public Component refusal(Plan plan) {
                            return plan.refusal();
                        }

                        public Component started(LineTarget selected) {
                            return Component.translatable(
                                    "gui.icheme."
                                            + (medium() == CableMedium.POWER ? "power" : "data")
                                            + ".started");
                        }

                        public Component hud(LineTarget selected, Plan plan) {
                            if (plan == null)
                                return Component.translatable(
                                        "gui.icheme."
                                                + (medium() == CableMedium.POWER ? "power" : "data")
                                                + ".started");
                            var p = Minecraft.getInstance().player;
                            return LineToolSession.routeHud(
                                    plan.from() == null ? null : plan.from().placement(),
                                    plan.to() == null ? null : plan.to().placement(),
                                    plan.route(),
                                    medium().outside(),
                                    p.getAbilities().instabuild,
                                    (medium() == CableMedium.POWER
                                                    ? ModItems.POWER_CABLE
                                                    : ModItems.DATA_CABLE)
                                            .get()
                                            .getDescription());
                        }

                        public void show(LineToolSession.View<LineTarget, LineTarget, Plan> view) {
                            project(view);
                        }

                        public void message(Component message) {
                            var p = Minecraft.getInstance().player;
                            if (p != null) p.displayClientMessage(message, true);
                        }

                        public boolean removable(LineTarget click) {
                            var mc = Minecraft.getInstance();
                            var resolved =
                                    LinePieces.target(mc.level, click, LineProfile.of(medium()));
                            return ConduitLineBackend.cables(mc.level, resolved.clicked()).stream()
                                    .anyMatch(
                                            c ->
                                                    c.site().lane() == resolved.lane()
                                                            && c.geometry().medium() == medium());
                        }

                        public void dismantle(boolean offHand, LineTarget click) {
                            LineToolSession.command(
                                    offHand,
                                    LineLaying.Action.DISMANTLE,
                                    click,
                                    click,
                                    List.of(),
                                    List.of());
                        }

                        public void settings(boolean offHand) {
                            FluidNetwork.openPipeLaying(offHand);
                        }

                        public LineToolSession.Immediate immediate(LineTarget click) {
                            var mc = Minecraft.getInstance();
                            var ports =
                                    DataControlRenderer.ports(
                                            mc.level, mc.player.position(), 96, medium());
                            var port = click.port() == null ? null : ports.get(click.port());
                            if (port == null
                                    || !(mc.level.getBlockEntity(port.cell())
                                            instanceof ConduitBlockEntity wire)
                                    || wire.geometry().medium() != medium()
                                    || Objects.equals(
                                            wire.cable().endpoints().get(port.face()), port.id()))
                                return null;
                            var attachment = attachment(click);
                            var reason = attachment == null ? noRoute() : attachment.refusal();
                            return new LineToolSession.Immediate(
                                    reason,
                                    offHand -> {
                                        LineToolSession.command(
                                                offHand,
                                                LineLaying.Action.ATTACH,
                                                click,
                                                click,
                                                List.of(),
                                                List.of());
                                    });
                        }

                        public void lay(
                                boolean offHand,
                                LineTarget click,
                                LineTarget selected,
                                LineTarget end,
                                Plan plan) {
                            LineToolSession.command(
                                    offHand,
                                    LineLaying.Action.LAY,
                                    click,
                                    end,
                                    plan.route().cells().stream().map(PipeAnchor::block).toList(),
                                    plan.route().options());
                        }
                    });

    static LineToolSession<LineTarget, LineTarget, Plan> session() {
        return SESSION;
    }

    private static Component noRoute() {
        return Component.translatable(
                "gui.icheme." + (medium() == CableMedium.POWER ? "power" : "data") + ".no_route");
    }

    private static Plan build(LineTarget selected, LineTarget end) {
        var mc = Minecraft.getInstance();
        var design = LineLaying.search(mc.level, selected, end, settings());
        var all =
                design.layout() == null
                        ? List.<DataBus.Cable>of()
                        : ConduitLineBackend.wires(mc.level, design.layout(), settings().profile());
        int count = design.layout() == null ? 0 : design.layout().cells().size();
        return new Plan(
                end,
                design.start(),
                design.end(),
                design.route(),
                design.refusal(),
                all.subList(0, count),
                all.subList(count, all.size()));
    }

    private static LineProfile.Settings settings() {
        return LineProfile.of(medium())
                .settings(LayingToolAim.held(Minecraft.getInstance().player));
    }

    private static LineLaying.Design attachment(LineTarget target) {
        var design = LineLaying.attachment(Minecraft.getInstance().level, target, settings());
        return design.layout() == null ? null : design;
    }

    private static void project(LineToolSession.View<LineTarget, LineTarget, Plan> view) {
        start = view.start();
        hovered = view.hovered();
        if (hovered != null
                && Minecraft.getInstance().level != null
                && Minecraft.getInstance().player != null)
            hovered =
                    LinePieces.target(
                            Minecraft.getInstance().level, hovered, LineProfile.of(medium()));
        var plan = view.plan();
        end = plan == null ? null : plan.end();
        age = view.age();
        refusal = view.refusal();
        route = plan == null ? null : plan.route();
        from = plan == null ? null : plan.from();
        to = plan == null ? null : plan.to();
        ghost = plan == null || refusal != null ? List.of() : plan.ghost();
        hostGhost = plan == null || refusal != null ? List.of() : plan.hosts();
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !holding(mc.player) || mc.screen != null) {
            markers.clear();
            nearbyMarks = List.of();
            markedLevel = null;
            markedMedium = null;
            attachGhost = null;
            return;
        }
        attachGhost =
                start == null && hovered != null && hovered.port() != null
                        ? attachment(hovered)
                        : null;
        attachRefused = attachGhost != null && attachGhost.refusal() != null;
        updateMarkers(mc);
    }

    private static void updateMarkers(Minecraft mc) {
        var level = mc.level;
        var player = mc.player;
        var centre = player.position();
        if (markedLevel != level
                || markedMedium != medium()
                || age % LineToolSession.HUD_TICKS == 0) {
            markedLevel = level;
            markedMedium = medium();
            var ports =
                    DataControlRenderer.ports(level, centre, LineToolMarkers.RANGE + 8, medium());
            var sockets = new ArrayList<Mark>();
            for (var port : ports.values()) {
                var cell = BlockPos.containing(port.centre());
                var p = port.centre().subtract(Vec3.atLowerCornerOf(cell));
                sockets.add(
                        new Mark(
                                "icheme:cable_port:" + port.id(),
                                LineToolMarkers.square(
                                        cell, port.face(), new double[] {p.x, p.y, p.z}, .14),
                                DataControlRenderer.connected(level, port)
                                        ? PortColours.CONNECTED
                                        : LineToolMarkers.OPEN,
                                port.centre().distanceToSqr(centre)));
            }
            var wires = new HashMap<BlockPos, List<DataBus.Cable>>();
            for (var entity : ConduitBlockEntity.clientCables())
                if (!entity.isRemoved()
                        && entity.getLevel() == level
                        && entity.getBlockPos().getCenter().distanceToSqr(centre)
                                < Math.pow(LineToolMarkers.RANGE + 2, 2))
                    wires.put(entity.getBlockPos(), List.of(entity.cable()));
            for (var rack : PipeRackBlockEntity.clientRacks())
                if (!rack.isRemoved()
                        && rack.getLevel() == level
                        && rack.getBlockPos().getCenter().distanceToSqr(centre)
                                < Math.pow(LineToolMarkers.RANGE + 2, 2))
                    wires.put(rack.getBlockPos(), rack.cables());
            var ends = new ArrayList<Mark>();
            for (var group : wires.values())
                for (var cable : group)
                    if (cable.geometry().medium() == medium()) {
                        int open =
                                CableConnections.openFaces(
                                        cable, p -> wires.getOrDefault(p, List.of()), ports::get);
                        var key = cable.geometry();
                        if (cable.site().lane() > 0
                                && level.getBlockEntity(cable.site().cell())
                                        instanceof PipeRackBlockEntity rack)
                            key = ConduitGeometry.laneKey(rack.rack(), rack.cableLane(cable), key);
                        for (var face : Direction.values())
                            if ((open & 1 << face.ordinal()) != 0)
                                ends.add(
                                        new Mark(
                                                "icheme:cable_end:" + cable.site() + ":" + face,
                                                LineToolMarkers.square(
                                                        cable.site().cell(),
                                                        face,
                                                        key.point(face.ordinal()),
                                                        medium().outside() / 2 + .04),
                                                LineToolMarkers.OPEN,
                                                cable.site()
                                                        .cell()
                                                        .getCenter()
                                                        .distanceToSqr(centre)));
                    }
            var next = new ArrayList<>(LineToolMarkers.nearest(sockets, Mark::distance));
            next.addAll(LineToolMarkers.nearest(ends, Mark::distance));
            nearbyMarks = List.copyOf(next);
        }
        markers.begin();
        for (var mark : nearbyMarks) markers.box(mark.id(), mark.bounds(), mark.colour());
        if (start != null) {
            var anchor = LineAnchors.resolve(level, start, settings()).anchor();
            if (anchor != null) {
                var p =
                        anchor.socket() == null
                                ? anchor.placement().cell().getCenter()
                                : anchor.socket().centre();
                markers.box(
                        "icheme:cable_start", new AABB(p, p).inflate(.17), LineToolMarkers.START);
            }
        }
        if (start != null && end != null && refusal != null)
            markers.line(
                    "icheme:cable_refused",
                    from == null
                            ? start.clicked().getCenter()
                            : from.placement().cell().getCenter(),
                    to == null ? end.clicked().getCenter() : to.placement().cell().getCenter(),
                    LineToolMarkers.REFUSED);
        if (hovered != null && player.isShiftKeyDown() && start == null) {
            List<double[]> boxes = List.of();
            var be = level.getBlockEntity(hovered.clicked());
            if (be instanceof ConduitBlockEntity wire && wire.geometry().medium() == medium())
                boxes = ConduitGeometry.boxes(wire.geometry());
            else if (be instanceof PipeRackBlockEntity rack)
                for (var cable : rack.cables())
                    if (cable.site().lane() == hovered.lane()
                            && cable.geometry().medium() == medium())
                        boxes =
                                ConduitGeometry.laneBoxes(
                                        rack.rack(), rack.cableLane(cable), cable.geometry());
            for (int i = 0; i < boxes.size(); i++) {
                var b = boxes.get(i);
                markers.box(
                        "icheme:cable_remove:" + i,
                        new AABB(b[0], b[1], b[2], b[3], b[4], b[5]).move(hovered.clicked()),
                        LineToolMarkers.REFUSED);
            }
            int pieceIndex = 0;
            for (var piece : LinePieces.at(level, hovered.clicked()))
                if (piece.parent().cell().equals(hovered.clicked())
                        && piece.parent().lane() == hovered.lane())
                    for (var part : piece.cells().entrySet())
                        for (var box : part.getValue().toAabbs())
                            markers.box(
                                    "icheme:cable_remove_lead:" + pieceIndex++,
                                    box.move(part.getKey()),
                                    LineToolMarkers.REFUSED);
        } else if (hovered != null
                && hovered.port() == null
                && hovered.lane() == 0
                && level.getBlockEntity(hovered.clicked()) instanceof PipeRackBlockEntity rack) {
            int i = 0;
            for (var free : LayingToolAim.freeSlots(rack))
                markers.box("icheme:cable_slot:" + i++, free, LineToolMarkers.START);
        }
        markers.end();
    }

    public static void draw(PoseStack pose, MultiBufferSource buffers, Vec3 camera) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || !holding(mc.player)) return;
        if (hovered != null) {
            if (start == null && !mc.player.isShiftKeyDown() && hovered.port() != null) {
                var attachment = attachGhost;
                if (attachment != null) {
                    boolean refused = attachRefused;
                    var cable =
                            ConduitLineBackend.wires(
                                            mc.level, attachment.layout(), settings().profile())
                                    .getFirst();
                    var at = cable.site().cell();
                    DataControlRenderer.draw(
                            pose,
                            buffers,
                            camera,
                            Vec3.atLowerCornerOf(at),
                            ConduitGeometry.polygons(
                                    cable.geometry(),
                                    ConduitGeometry.markedAt(at.getX(), at.getY(), at.getZ())),
                            refused ? 0xff5555 : medium().colour(),
                            135);
                    if (!refused)
                        DataControlRenderer.drawLead(
                                pose,
                                buffers,
                                camera,
                                DataControlRenderer.ports(
                                                mc.level, mc.player.position(), 96, medium())
                                        .get(hovered.port()),
                                true);
                }
            }
            if (!mc.player.isShiftKeyDown()
                    && hovered.port() == null
                    && hovered.lane() == 0
                    && mc.level.getBlockEntity(hovered.clicked())
                            instanceof PipeRackBlockEntity rack) {
                if (start == null || route == null || !route.found()) {
                    var slot =
                            LayingToolAim.slot(
                                    mc.level,
                                    new PipeAnchor.Click(
                                            hovered.clicked(), hovered.face(), hovered.local()),
                                    medium().outside());
                    if (slot != null) {
                        boolean refused =
                                !slot.fits() || start != null && route != null && !route.found();
                        DataControlRenderer.draw(
                                pose,
                                buffers,
                                camera,
                                Vec3.atLowerCornerOf(rack.getBlockPos()),
                                ConduitGeometry.lane(
                                        rack.rack(),
                                        slot.lane(),
                                        medium(),
                                        ConduitGeometry.markedAt(
                                                rack.getBlockPos().getX(),
                                                rack.getBlockPos().getY(),
                                                rack.getBlockPos().getZ())),
                                refused ? 0xff5555 : medium().colour(),
                                135);
                    }
                }
            }
        }
        for (var cable : ghost) {
            var at = cable.site().cell();
            List<Mesh.Polygon> polygons;
            if (cable.site().lane() > 0
                    && mc.level.getBlockEntity(at) instanceof PipeRackBlockEntity rack)
                polygons =
                        ConduitGeometry.lane(
                                rack.rack(),
                                rack.cableLane(cable),
                                cable.geometry(),
                                ConduitGeometry.markedAt(at.getX(), at.getY(), at.getZ()));
            else
                polygons =
                        ConduitGeometry.polygons(
                                cable.geometry(),
                                ConduitGeometry.markedAt(at.getX(), at.getY(), at.getZ()));
            DataControlRenderer.draw(
                    pose,
                    buffers,
                    camera,
                    Vec3.atLowerCornerOf(at),
                    polygons,
                    medium().colour(),
                    135);
        }
        if (start != null && end != null && route != null && route.found() && refusal == null)
            for (var click : List.of(start, end)) {
                if (click.port() != null) {
                    var port =
                            DataControlRenderer.ports(mc.level, mc.player.position(), 96, medium())
                                    .get(click.port());
                    if (port != null)
                        DataControlRenderer.drawLead(pose, buffers, camera, port, true);
                }
            }
        for (var wire : hostGhost) {
            var at = wire.site().cell();
            boolean marked = ConduitGeometry.markedAt(at.getX(), at.getY(), at.getZ());
            var polygons =
                    mc.level.getBlockEntity(at) instanceof PipeRackBlockEntity rack
                            ? ConduitGeometry.lane(
                                    rack.rack(), rack.cableLane(wire), wire.geometry(), marked)
                            : ConduitGeometry.polygons(wire.geometry(), marked);
            DataControlRenderer.draw(
                    pose,
                    buffers,
                    camera,
                    Vec3.atLowerCornerOf(at),
                    polygons,
                    medium().colour(),
                    135);
        }
    }
}
