package com.wormzjl.icheme.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.client.model.FluidDeviceModel;
import com.wormzjl.icheme.network.FluidNetwork;
import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.LineGeometry;
import com.wormzjl.icheme.science.equipment.Mesh;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.PipeRoute;
import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.equipment.RackGeometry;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.world.item.PipeLayingToolItem;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.PipeAnchor;
import com.wormzjl.icheme.world.level.block.PipeLaying;
import com.wormzjl.icheme.world.level.block.LinePlacement;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.PipeRackBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Fluid anchor, plan and drawing adapter for {@link LineToolSession}. The shared session owns presses, cancellation,
 * pending commands, plan refresh and refusal retention. Native pipe meshes, paints and process-assembly port roles
 * remain here. Reads loaded structural geometry only, never process state. Client thread only.
 */
@EventBusSubscriber(modid = IChemE.MOD_ID, value = Dist.CLIENT)
public final class PipeLayingClient {
    private static final Object START_SLOT = "icheme:pipe_start", ROUTE_SLOT = "icheme:pipe_route", AIM_SLOT = "icheme:pipe_aim";
    /** A pipe block is one box to outline, a lane at most three legs. */
    private static final Object[] REMOVE_SLOTS = {"icheme:pipe_remove", "icheme:pipe_remove_arm", "icheme:pipe_remove_leg"};
    private static final int START_COLOUR = LineToolMarkers.START, REFUSED_COLOUR = LineToolMarkers.REFUSED, OPEN_COLOUR = LineToolMarkers.OPEN, FREE_COLOUR = LineToolMarkers.START;
    /** Ports and open ends are marked this far, at most {@link #MAX_MARKS} of each. */
    private static final double PORT_RANGE = LineToolMarkers.RANGE;
    private static final int MAX_MARKS = LineToolMarkers.MAX_MARKS, HUD_TICKS = LineToolSession.HUD_TICKS;
    private static final ResourceLocation PLATE = ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "block/painted_shell");
    private static final RenderType TYPE = RenderType.entityTranslucentCull(InventoryMenu.BLOCK_ATLAS);
    private static final int GHOST = 0xB0D8FF, ALPHA = 140;

    private record Start(PipeAnchor.Click click, PipeAnchor anchor) {
    }

    /** The route to the aimed click, or why there is none; its ghost cells, and how they are laid when found. */
    private record Plan(PipeAnchor.Click end, @Nullable PipeAnchor anchor, PipeRoute.Result route, @Nullable Component refusal,
                        @Nullable PipeRoute.Placement placement, List<Junction> junctions) {
        private Plan(PipeAnchor.Click end, @Nullable PipeAnchor anchor, PipeRoute.Result route, @Nullable Component refusal, @Nullable PipeRoute.Placement placement) {
            this(end, anchor, route, refusal, placement, List.of());
        }
    }

    private record Host(BlockPos cell, int lane) {}
    private record Junction(BlockPos cell, List<Mesh.Polygon> polygons) {}
    private record Junctions(List<Junction> shown, @Nullable Component refusal) {}

    /** The slot aimed on a rack's tier before a click ({@link PipeAnchor#aimed}): its cell, frame and lane, the face the line passes through, and whether it is refused. */
    private record Slot(BlockPos cell, RackGeometry.Rack frame, RackGeometry.Lane lane, int through, boolean refused) {
    }

    // Rendering projection only. LineToolSession is the sole owner of selection, pending commands and refusal state.
    private static @Nullable Start start;
    private static @Nullable Plan plan;
    private static @Nullable Slot slot;
    private static final LineToolMarkers markers=new LineToolMarkers();
    private static final LineToolSession<PipeAnchor.Click,Start,Plan> SESSION=new LineToolSession<>(new LineToolSession.Adapter<>(){
        public PipeAnchor.Click aimed(){return PipeLayingClient.aimed(Minecraft.getInstance());}
        public BlockPos cell(PipeAnchor.Click click){return click.clicked();}
        public LineToolSession.Selected<Start> select(PipeAnchor.Click click){
            var mc=Minecraft.getInstance();double outside=PipeSizes.outside(settingsDiameter(mc.player));
            var resolved=PipeAnchor.resolve(mc.level,click,outside);
            var reason=resolved.anchor()==null?resolved.refusal():resolved.anchor().refuses(outside);
            return reason==null?new LineToolSession.Selected<>(new Start(click,resolved.anchor()),null):new LineToolSession.Selected<>(null,PipeLaying.refusal(reason));
        }
        public Plan plan(Start selected,PipeAnchor.Click end){var mc=Minecraft.getInstance();return PipeLayingClient.plan(mc.level,end,settingsDiameter(mc.player),selected);}
        public boolean same(PipeAnchor.Click a,PipeAnchor.Click b){var mc=Minecraft.getInstance();return PipeLayingClient.same(mc.level,a,b,PipeSizes.outside(settingsDiameter(mc.player)));}
        public Component refusal(Plan plan){return plan.refusal();}
        public Component started(Start selected){return PipeLaying.message("started",PipeLaying.describe(selected.anchor()));}
        public Component hud(Start selected,Plan plan){var p=Minecraft.getInstance().player;return plan==null?PipeLaying.message("hud.aim",PipeLaying.describe(selected.anchor())):LineToolFeedback.route(selected.anchor(),plan.anchor(),plan.route(),settingsDiameter(p),p.getAbilities().instabuild,null);}
        public void show(LineToolSession.View<PipeAnchor.Click,Start,Plan> view){project(view);}
        public void message(Component message){var p=Minecraft.getInstance().player;if(p!=null)say(p,message);}
        public boolean removable(PipeAnchor.Click click){return PipeLayingClient.removable(Minecraft.getInstance().level,click)!=null;}
        public void dismantle(boolean offHand,PipeAnchor.Click click){FluidNetwork.dismantlePipe(offHand,click);}
        public void settings(boolean offHand){FluidNetwork.openPipeLaying(offHand);}
        public void lay(boolean offHand,PipeAnchor.Click click,Start selected,PipeAnchor.Click end,Plan plan){FluidNetwork.layPipe(offHand,click,end,plan.route().cells().stream().map(PipeAnchor::block).toList(),plan.route().options());}
    });
    static LineToolSession<PipeAnchor.Click,Start,Plan> session(){return SESSION;}

    private PipeLayingClient() {
    }

    public static boolean holding(LocalPlayer player) {
        return LayingToolAim.holding(player)&&!PipeLayingToolItem.conduit(LayingToolAim.held(player));
    }

    public static void answered(Component message,boolean laid){SESSION.answered(message,laid);}

    private static void project(LineToolSession.View<PipeAnchor.Click,Start,Plan> view){
        var mc=Minecraft.getInstance();var player=mc.player;
        start=view.start();plan=view.plan();
        if(plan!=null&&view.refusal()!=null)plan=new Plan(plan.end,plan.anchor,plan.route,view.refusal(),plan.placement,plan.junctions);
        if(player==null||mc.level==null||!holding(player)||mc.screen!=null){start=null;plan=null;slot=null;markers.clear();return;}
        var hit=view.hovered();double outside=PipeSizes.outside(settingsDiameter(player));
        var shown=new HashSet<Object>();
        if(start!=null){
            shown.add(START_SLOT);Outlines.box(START_SLOT,new AABB(start.anchor.cell()),START_COLOUR,1/16F);
            if(view.refusal()!=null&&hit!=null){shown.add(ROUTE_SLOT);Outlines.line(ROUTE_SLOT,start.anchor.cell().getCenter(),(plan!=null&&plan.anchor!=null?plan.anchor.cell():hit.clicked()).getCenter(),REFUSED_COLOUR,1/16F);}
        }
        var removed=start==null&&player.isShiftKeyDown()&&hit!=null?removable(mc.level,hit):null;
        for(int i=0;removed!=null&&i<REMOVE_SLOTS.length&&i<removed.size();i++){shown.add(REMOVE_SLOTS[i]);Outlines.box(REMOVE_SLOTS[i],removed.get(i),REFUSED_COLOUR,1/16F);}
        if(start==null&&hit!=null&&view.age()%HUD_TICKS==0){var label=portLabel(mc.level,hit);if(label!=null)say(player,label);}
        boolean routeRefused=view.refusal()!=null;
        slot=hit!=null&&!player.isShiftKeyDown()&&(start==null||plan==null||routeRefused)?slot(mc.level,hit,outside,routeRefused):null;
        ports(mc.level,player,shown);openEnds(mc.level,player,shown);freeSlots(mc.level,hit,shown);
        if(slot!=null){var aimed=slot;shown.add(AIM_SLOT);Outlines.box(AIM_SLOT,square(aimed.cell,Direction.values()[aimed.through],RackGeometry.facePoint(aimed.frame,aimed.lane,aimed.through),Math.min(.45,aimed.lane.diameter()/2+.04)),aimed.refused?REFUSED_COLOUR:START_COLOUR,1/32F);}
        markers.adopt(shown);
    }

    /**
     * The click a use makes (owner 2026-10-26, "Aim on the tier"): where the view ray crosses the tier of the first rack
     * cell it crosses it in, up to the block it hits (a rack's frame is open, so aiming through it still picks the rack),
     * unless the block it hits is that rack and the hit is on a lane; else the block it hits. The tier point is sent just
     * under the beams' top, where no lane resting on them is picked.
     */
    private static @Nullable PipeAnchor.Click aimed(Minecraft mc){return LayingToolAim.aimed(mc,PipeLayingClient::onLane);}

    /** Whether {@code click} lands on a lane of {@code rack}. */
    private static boolean onLane(PipeRackBlockEntity rack, @Nullable PipeAnchor.Click click) {
        return click != null && rack.pick(click.local().x, click.local().y, click.local().z).lane() >= 0;
    }

    /** What a click picks on a rack: its lane and arm, or the slot it aims at on the tier for a line of {@code outside}; null on any other block. */
    private static @Nullable Object rackPick(Level level, PipeAnchor.Click click, double outside) {
        if (!(level.getBlockEntity(click.clicked()) instanceof PipeRackBlockEntity rack)) return null;
        var pick = rack.pick(click.local().x, click.local().y, click.local().z);
        return pick.lane() >= 0 ? pick : PipeAnchor.aimed(rack, click.local(), outside);
    }

    /** Two clicks that give the same anchor: the same block and face, and on a rack the same lane and arm, or the same aimed slot. */
    private static boolean same(Level level, PipeAnchor.Click a, PipeAnchor.Click b, double outside) {
        return a.clicked().equals(b.clicked()) && a.face() == b.face() && Objects.equals(rackPick(level, a, outside), rackPick(level, b, outside));
    }

    /** The slot {@code click} aims at on a rack's tier, as a ghost lane open at its far face; red where it does not fit, or where {@code refused}. */
    private static @Nullable Slot slot(Level level, PipeAnchor.Click click, double outside, boolean refused) {
        if(!(level.getBlockEntity(click.clicked()) instanceof PipeRackBlockEntity rack)||onLane(rack,click))return null;
        var aimed=LayingToolAim.slot(level,click,outside);if(aimed==null)return null;
        int through=Integer.numberOfTrailingZeros(aimed.lane().joined());
        return new Slot(click.clicked(),rack.frame(),aimed.lane(),through,refused||!aimed.fits());
    }

    /** The boxes of what a dismantle at {@code click} removes, in world coordinates: a pipe block or a rack lane's arms; null for anything else. */
    private static @Nullable List<AABB> removable(Level level, PipeAnchor.Click click) {
        var at = click.clicked();
        if (level.getBlockEntity(at) instanceof PipeRackBlockEntity rack) {
            var pick = rack.pick(click.local().x, click.local().y, click.local().z);
            if (pick.lane() < 0) return null;
            return RackGeometry.boxes(rack.frame(), rack.drawnLanes().get(pick.lane())).stream()
                    .map(b -> new AABB(b[0], b[1], b[2], b[3], b[4], b[5]).move(at)).toList();
        }
        return level.getBlockState(at).getBlock() instanceof FluidDeviceBlock block && block.kind() == TopologyCompiler.Kind.PIPE ? List.of(new AABB(at)) : null;
    }

    /** The laying tool the player holds: the main hand's, else the off hand's. */
    private static ItemStack held(LocalPlayer player) {
        return LayingToolAim.held(player);
    }

    private static double settingsDiameter(LocalPlayer player) {
        var settings = held(player).get(ModItems.PIPE_LAYING_SETTINGS.get());
        return settings == null ? .05 : settings.diameter();
    }

    /** The route from the start to {@code end}, searched now on this thread within the search limits. */
    private static Plan plan(Level level, PipeAnchor.Click end, double diameter, Start selected) {
        double outside = PipeSizes.outside(diameter);
        var nothing = new PipeRoute.Result(List.of(), List.of(), null);
        var resolved = PipeAnchor.resolve(level, end, outside);
        if (resolved.anchor() == null) return new Plan(end, null, nothing, PipeLaying.refusal(resolved.refusal()), null);
        var from = selected.anchor;
        var to = resolved.anchor();
        var refused = PipeAnchor.refuses(from, to, outside);
        if (refused != null) return new Plan(end, to, nothing, PipeLaying.refusal(refused), null);
        int heading = from.leaving();
        var route = LinePlacement.search(level,from,to,outside,List.of());
        if (!route.found()) return new Plan(end, to, route, PipeLaying.refusal(route.refusal()), null);
        var junctions = junctions(level, from, to, route, outside);
        return new Plan(end, to, route, junctions.refusal, PipeRoute.placement(route.cells(), from.end(), heading, to.end()), junctions.shown);
    }

    /** Endpoint hosts also change when a line is laid: merge both ends before drawing a tee, cross or lane extension. */
    private static Junctions junctions(Level level, PipeAnchor from, PipeAnchor to, PipeRoute.Result route, double outside) {
        var hosts = new LinkedHashMap<Host, List<PipeAnchor>>();
        for (var anchor : List.of(from, to)) {
            if (anchor.kind() != PipeAnchor.Kind.PIPE && anchor.kind() != PipeAnchor.Kind.LANE) continue;
            hosts.computeIfAbsent(new Host(anchor.host(), anchor.hostLane()), key -> new ArrayList<>()).add(anchor);
        }
        int code = PipeSizes.code(outside), width = RackGeometry.width(PipeSizes.outsideOf(code));
        var shown = new ArrayList<Junction>();
        for (var entry : hosts.entrySet()) {
            var host = entry.getKey();
            var entity = level.getBlockEntity(host.cell);
            if (host.lane > 0) {
                if (!(entity instanceof PipeRackBlockEntity rack)) return new Junctions(List.of(), PipeLaying.message("anchor.fitting"));
                var lane = rack.lanes().stream().filter(l -> l.option() == host.lane - 1).findFirst().orElse(null);
                if (lane == null) return new Junctions(List.of(), PipeLaying.message("anchor.fitting"));
                int joints = lane.joints();
                for (var anchor : entry.getValue()) {
                    if (anchor.joints() < 0) continue;
                    joints = PipeRoute.hostJoints(joints, anchor.joints(), true);
                    if (joints < 0) return new Junctions(List.of(), PipeLaying.message("anchor.lane_closed"));
                }
                if (joints != lane.joints()) shown.add(new Junction(host.cell, RackGeometry.lane(rack.frame(),
                        new RackGeometry.Lane(lane.option(), lane.width(), lane.arms(), joints, PipeSizes.code(PipeSizes.outside(lane.bore()))))));
            } else {
                if (!(entity instanceof FluidDeviceBlockEntity pipe) || pipe.kind() != TopologyCompiler.Kind.PIPE)
                    return new Junctions(List.of(), PipeLaying.message("anchor.fitting"));
                var before = FluidDeviceModel.blockKey(pipe.getBlockState(), pipe.getModelData());
                var after = before;
                int joints = pipe.joints();
                for (var anchor : entry.getValue()) {
                    if (anchor.joints() >= 0) {
                        joints = PipeRoute.hostJoints(joints, anchor.joints(), false);
                        if (joints < 0) return new Junctions(List.of(), PipeLaying.message("anchor.junction"));
                    }
                    int face = anchor.joint().getOpposite().ordinal();
                    int index = anchor == from ? 0 : route.cells().size() - 1;
                    int option = route.options().get(index);
                    int u = 0, v = 0;
                    if (option >= 0 && level.getBlockEntity(anchor.cell()) instanceof PipeRackBlockEntity rack) {
                        var at = RackGeometry.steps(RackGeometry.point(rack.rack(), option, width, PipeSizes.outsideOf(code), face ^ 1));
                        u = at[0];
                        v = at[1];
                    }
                    after = after.withConnection(face, code, u, v);
                }
                if (!after.equals(before)) shown.add(new Junction(host.cell, LineGeometry.polygons(after)));
            }
        }
        return new Junctions(List.copyOf(shown), null);
    }

    /**
     * Where plain route cell {@code i} meets a lane through {@code face}, as steps off the face's centre along its in-plane
     * axes: the route's own lane next to it, or the lane the line starts or ends on; null where it meets none.
     */
    private static int @Nullable [] lanePoint(Level level, Plan shown, int i, int face, double outside) {
        var cells = shown.route.cells();
        var next = cells.get(i).step(face);
        for (int j : new int[]{i - 1, i + 1}) {
            if (j < 0 || j >= cells.size() || !cells.get(j).equals(next)) continue;
            int option = shown.route.options().get(j);
            if (option < 0 || !(level.getBlockEntity(PipeAnchor.block(next)) instanceof PipeRackBlockEntity rack)) return null;
            double standard = PipeSizes.outsideOf(PipeSizes.code(outside));
            return RackGeometry.steps(RackGeometry.point(rack.rack(), option, RackGeometry.width(standard), standard, face ^ 1));
        }
        var first = start;
        for (var anchor : new PipeAnchor[]{i == 0 && first != null ? first.anchor : null, i == cells.size() - 1 ? shown.anchor : null}) {
            if (anchor == null || anchor.kind() != PipeAnchor.Kind.LANE || anchor.joint() == null || anchor.joint().ordinal() != face) continue;
            if (!(level.getBlockEntity(anchor.host()) instanceof PipeRackBlockEntity rack)) continue;
            for (var lane : rack.lanes()) if (lane.option() == anchor.hostLane() - 1) return RackGeometry.steps(rack.point(lane, face ^ 1));
        }
        return null;
    }

    /** The aimed nozzle: its role, whether a pipe is on it, and the largest bore it takes. */
    private static @Nullable Component portLabel(Level level, PipeAnchor.Click hit) {
        if (!(level.getBlockEntity(hit.clicked()) instanceof EquipmentShellBlockEntity shell)) return null;
        var port = shell.port(hit.face());
        var nozzle = shell.nozzleFacing(hit.face());
        if (port == null || nozzle == null) return null;
        var role = nozzle.role().name().toLowerCase(Locale.ROOT);
        var roleName = Component.translatableWithFallback("message.icheme.pipe_laying.role." + role, humanize(role));
        boolean connected = connected(level, hit.clicked(), hit.face());
        // The largest bore, down to the centimetre (a column's nozzle takes what its place clears, NozzleBore).
        var largest = java.math.BigDecimal.valueOf(Math.floor(shell.largestBore(hit.face()) * 100 + 1e-9) / 100).stripTrailingZeros().toPlainString();
        var label = PipeLaying.message("port", roleName, PipeLaying.message(connected ? "port.connected" : "port.free"), largest);
        // A watched assembly's port: its line too (plan section 9).
        var line = AssemblyToolClient.line(shell, hit.face());
        return line == null ? label : label.copy().append(" · ").append(line);
    }

    private static String humanize(String name) {
        var words = name.replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static boolean connected(Level level, BlockPos shell, Direction face) {
        var at = shell.relative(face);
        var state = level.getBlockState(at);
        return state.getBlock() instanceof FluidDeviceBlock && state.getValue(FluidDeviceBlock.connection(face.getOpposite()));
    }

    /**
     * Marks the ports of the equipment around the player: assembly nozzles keep their GUI colour with a grey frame when
     * connected; other nozzles use their class colour, grey when connected. Re-shown from the client's port cells; at most
     * {@link #MAX_MARKS}, nearest first.
     */
    private static void ports(Level level, LocalPlayer player, Set<Object> shown) {
        var marks = new ArrayList<Mark>();
        for (var shell : EquipmentShellBlockEntity.clientPortCells()) {
            if (shell.isRemoved() || shell.getLevel() != level || shell.shape() == null) continue;
            var at = shell.getBlockPos();
            double distance = at.getCenter().distanceToSqr(player.position());
            if (distance > PORT_RANGE * PORT_RANGE) continue;
            for (var face : Direction.values()) {
                var port = shell.port(face);
                if (port != null) marks.add(new Mark(at, face, port.port(), distance));
            }
        }
        for (var mark : LineToolMarkers.nearest(marks,Mark::distance)) {
            var slot = "icheme:port:" + mark.cell.asLong() + ":" + mark.face.ordinal();
            shown.add(slot);
            var shell = level.getBlockEntity(mark.cell) instanceof EquipmentShellBlockEntity cell ? cell : null;
            boolean connected = connected(level, mark.cell, mark.face);
            int assemblyColour = shell == null ? -1 : AssemblyToolClient.colour(shell, mark.face);
            int colour = assemblyColour >= 0 ? assemblyColour : connected ? PortColours.CONNECTED : PortColours.of(mark.port);
            Outlines.box(slot, square(mark.cell, mark.face, faceCentre(mark.face), .3), colour, 1 / 32F);
            // A watched assembly's port: a frame round the square (AssemblyToolClient).
            int frame = shell == null ? -1 : AssemblyToolClient.frame(shell, mark.face);
            if (frame < 0 && assemblyColour >= 0 && connected) frame = PortColours.CONNECTED;
            if (frame < 0) continue;
            var framed = slot + ":frame";
            shown.add(framed);
            Outlines.box(framed, square(mark.cell, mark.face, faceCentre(mark.face), .4), frame, 1 / 32F);
        }
    }

    private record Mark(BlockPos cell, Direction face, EquipmentShape.PortClass port, double distance) {
    }

    /**
     * Marks the open line ends around the player in yellow (plan 4.7), where a line laid to them joins: a square on each
     * open face of a pipe, and on each open face of a rack lane at the lane's point there; at most {@link #MAX_MARKS},
     * nearest first.
     */
    private static void openEnds(Level level, LocalPlayer player, Set<Object> shown) {
        var ends = new ArrayList<End>();
        for (var pipe : FluidDeviceBlockEntity.clientOpenEnds()) {
            if (pipe.isRemoved() || pipe.getLevel() != level) continue;
            double distance = pipe.getBlockPos().getCenter().distanceToSqr(player.position());
            if (distance > PORT_RANGE * PORT_RANGE) continue;
            int open = pipe.openEnds();
            for (var face : Direction.values())
                if ((open >> face.ordinal() & 1) != 0) ends.add(new End(pipe.getBlockPos(), face, -1, faceCentre(face), PipeSizes.outside(pipe.bore()), distance));
        }
        for (var rack : PipeRackBlockEntity.clientRacks()) {
            if (rack.isRemoved() || rack.getLevel() != level) continue;
            double distance = rack.getBlockPos().getCenter().distanceToSqr(player.position());
            if (distance > PORT_RANGE * PORT_RANGE) continue;
            var lanes = rack.lanes();
            for (int i = 0; i < lanes.size(); i++) {
                int open = rack.openFaces(i);
                var lane = lanes.get(i);
                for (var face : Direction.values())
                    if ((open >> face.ordinal() & 1) != 0)
                        ends.add(new End(rack.getBlockPos(), face, lane.option(), RackGeometry.facePoint(rack.rack(), lane.drawn(0), face.ordinal()), lane.outside(), distance));
            }
        }
        for (var end : LineToolMarkers.nearest(ends,End::distance)) {
            var slot = "icheme:open:" + end.cell.asLong() + ":" + end.face.ordinal() + ":" + end.option;
            shown.add(slot);
            Outlines.box(slot, square(end.cell, end.face, end.point, Math.min(.45, end.outside / 2 + .04)), OPEN_COLOUR, 1 / 32F);
        }
    }

    /** An open end: its cell and face, the lane's option (-1 for a pipe), its point on the face (block-local), and its size. */
    private record End(BlockPos cell, Direction face, int option, double[] point, double outside, double distance) {
    }

    private static double[] faceCentre(Direction face) {
        return new double[]{.5 + .5 * face.getStepX(), .5 + .5 * face.getStepY(), .5 + .5 * face.getStepZ()};
    }

    /** While a rack is aimed, a small cube on each tier position no lane takes: where a new lane may go. */
    private static void freeSlots(Level level, @Nullable PipeAnchor.Click hit, Set<Object> shown) {
        if (hit == null || !(level.getBlockEntity(hit.clicked()) instanceof PipeRackBlockEntity rack)) return;
        int position=0;for(var box:LayingToolAim.freeSlots(rack)){var key="icheme:free_slot:"+position++;shown.add(key);Outlines.box(key,box,FREE_COLOUR,1/32F);}
    }

    /** A flat square {@code 2 half} blocks across on {@code face} of {@code cell}, centred at block-local {@code point} on that face. */
    private static AABB square(BlockPos cell, Direction face, double[] point, double half) {
        return LineToolMarkers.square(cell,face,point,half);
    }

    /**
     * The found route drawn translucent at the tool's size, as the pipe blocks it would place are drawn; before a click,
     * the slot aimed on a rack's tier, red where it is refused.
     */
    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        var shown = start != null && plan != null && plan.refusal == null ? plan : null;
        var aimed = slot;
        var mc = Minecraft.getInstance();
        if (mc.player == null || shown == null && aimed == null) return;
        var sprite = mc.getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS).getSprite(PLATE);
        var camera = event.getCamera().getPosition();
        var pose = event.getPoseStack();
        var buffers = mc.renderBuffers().bufferSource();
        var consumer = buffers.getBuffer(TYPE);
        double outside = PipeSizes.outside(settingsDiameter(mc.player));
        int code = PipeSizes.code(outside), width = RackGeometry.width(PipeSizes.outsideOf(code));
        // A painted line's ghost shows its paint on the neutral plate sprite, as a vessel's ghost does; a bare one the ghost blue.
        int paint = PipeLayingToolItem.paint(held(mc.player));
        int colour = paint == Paint.BARE ? GHOST : Paint.tint(paint);
        if (aimed != null) ghost(consumer, pose, camera, sprite, aimed.cell, RackGeometry.lane(aimed.frame, aimed.lane), aimed.refused ? REFUSED_COLOUR : colour);
        var cells = shown == null ? List.<PipeRoute.Cell>of() : shown.route.cells();
        for (int i = 0; i < cells.size(); i++) {
            var cell = PipeAnchor.block(cells.get(i));
            int option = shown.route.options().get(i);
            int joints = shown.placement.joints()[i];
            List<Mesh.Polygon> polygons;
            // A lane is drawn where the rack will draw it, welded at every joint.
            if (option >= 0) {
                if (!(mc.level.getBlockEntity(cell) instanceof PipeRackBlockEntity rack)) continue;
                polygons = RackGeometry.lane(rack.frame(), new RackGeometry.Lane(option, width, shown.placement.arms()[i], joints, code));
            } else {
                long faces = 0, offsets = 0;
                for (int face = 0; face < 6; face++) {
                    if ((joints >> face & 1) == 0) continue;
                    faces |= (long) code << 7 * face;
                    // A plain cell beside a lane is a transition pipe to the lane's off-centre point.
                    var at = lanePoint(mc.level, shown, i, face, outside);
                    if (at != null) offsets |= LineGeometry.Key.offsets(face, at[0], at[1]);
                }
                polygons = LineGeometry.polygons(new LineGeometry.Key(TopologyCompiler.Kind.PIPE, shown.placement.facings()[i], joints, code, faces, 0, false, offsets));
            }
            ghost(consumer, pose, camera, sprite, cell, polygons, colour);
        }
        if (shown != null) for (var junction : shown.junctions)
            ghost(consumer, pose, camera, sprite, junction.cell, junction.polygons, GHOST, true);
        buffers.endBatch(TYPE);
    }

    /** One cell's polygons drawn translucent in {@code colour} on the plate sprite, at {@code cell}. */
    private static void ghost(VertexConsumer consumer, PoseStack pose, Vec3 camera, TextureAtlasSprite sprite, BlockPos cell, List<Mesh.Polygon> polygons, int colour) {
        ghost(consumer, pose, camera, sprite, cell, polygons, colour, false);
    }

    private static void ghost(VertexConsumer consumer, PoseStack pose, Vec3 camera, TextureAtlasSprite sprite, BlockPos cell, List<Mesh.Polygon> polygons, int colour, boolean overlay) {
        pose.pushPose();
        pose.translate(cell.getX() - camera.x, cell.getY() - camera.y, cell.getZ() - camera.z);
        if (overlay) {
            // Lift the existing host's preview off its opaque surface, keeping the branch visible without z-fighting.
            pose.translate(.5, .5, .5);
            pose.scale(1.005f, 1.005f, 1.005f);
            pose.translate(-.5, -.5, -.5);
        }
        var last = pose.last();
        for (var polygon : polygons) {
            double[] p = polygon.positions(), uv = polygon.uvs(), n = polygon.normals();
            // A fan of quads, as the chunk model bakes it; a last single triangle repeats its final vertex.
            for (int first = 1; first + 1 < polygon.size(); first += 2)
                for (int corner : new int[]{0, first, first + 1, Math.min(first + 2, polygon.size() - 1)})
                    consumer.addVertex(last, (float) p[3 * corner], (float) p[3 * corner + 1], (float) p[3 * corner + 2])
                            .setColor(colour >> 16 & 0xFF, colour >> 8 & 0xFF, colour & 0xFF, ALPHA)
                            .setUv(sprite.getU((float) uv[2 * corner]), sprite.getV((float) uv[2 * corner + 1]))
                            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT)
                            .setNormal(last, (float) n[3 * corner], (float) n[3 * corner + 1], (float) n[3 * corner + 2]);
        }
        pose.popPose();
    }

    private static void say(LocalPlayer player, Component message) {
        player.displayClientMessage(message, true);
    }
}
