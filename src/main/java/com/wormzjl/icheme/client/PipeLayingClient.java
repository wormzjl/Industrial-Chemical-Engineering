package com.wormzjl.icheme.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wormzjl.icheme.IChemE;
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
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.PipeRackBlockEntity;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The laying tool on the client (batch 2026-10-26-pipe-laying-system, plan 4.1, 4.6 and 4.7). The client decides what a
 * use means, because only it knows whether a line has a start, and sends the server one command: open the settings, lay
 * a route, or dismantle a pipe; a line's start stays until the server answers that it laid the line ({@link #answered}).
 * It searches the route, draws it as a ghost and reports it on the action bar, and marks the equipment ports around the
 * player. Everything here reads blocks the client already holds: nothing is requested from the server and no process
 * data is read. Render thread only.
 */
@EventBusSubscriber(modid = IChemE.MOD_ID, value = Dist.CLIENT)
public final class PipeLayingClient {
    private static final Object START_SLOT = "icheme:pipe_start", ROUTE_SLOT = "icheme:pipe_route", AIM_SLOT = "icheme:pipe_aim";
    /** A pipe block is one box to outline, a lane at most three legs. */
    private static final Object[] REMOVE_SLOTS = {"icheme:pipe_remove", "icheme:pipe_remove_arm", "icheme:pipe_remove_leg"};
    private static final int START_COLOUR = 0x55FF55, REFUSED_COLOUR = 0xFF5555, OPEN_COLOUR = 0xFFDD33, FREE_COLOUR = 0x55FF55;
    /** A start this far from the player is dropped, blocks; ports and open ends are marked this far, at most {@link #MAX_MARKS} of each. */
    private static final double DROP_RANGE = 96, PORT_RANGE = 48;
    private static final int MAX_MARKS = 256, RECHECK_TICKS = 20, HUD_TICKS = 5;
    private static final ResourceLocation PLATE = ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "block/painted_shell");
    private static final RenderType TYPE = RenderType.entityTranslucentCull(InventoryMenu.BLOCK_ATLAS);
    private static final int GHOST = 0xB0D8FF, ALPHA = 140;

    private record Start(PipeAnchor.Click click, PipeAnchor anchor, ResourceKey<Level> dimension) {
    }

    /** The route to the aimed click, or why there is none; its ghost cells, and how they are laid when found. */
    private record Plan(PipeAnchor.Click end, @Nullable PipeAnchor anchor, PipeRoute.Result route, @Nullable Component refusal, @Nullable PipeRoute.Placement placement) {
    }

    /** The slot aimed on a rack's tier before a click ({@link PipeAnchor#aimed}): its cell, frame and lane, the face the line passes through, and whether it is refused. */
    private record Slot(BlockPos cell, RackGeometry.Rack frame, RackGeometry.Lane lane, int through, boolean refused) {
    }

    private static @Nullable Start start;
    /** The start of the lay sent last, until the server answers it: one lay at a time, and a refused one keeps its start. */
    private static @Nullable Start sent;
    private static @Nullable Plan plan;
    /** The plan the server refused to lay, with its reason: kept, not re-searched, until the aim moves. */
    private static @Nullable Plan refused;
    private static @Nullable Slot slot;
    private static boolean armed = true;
    private static int age;
    /** The outliner slots of the marks shown last tick: ports, open ends and an aimed rack's free slots. */
    private static Set<Object> markSlots = Set.of();

    private PipeLayingClient() {
    }

    public static boolean holding(LocalPlayer player) {
        return tool(player.getMainHandItem()) || tool(player.getOffhandItem());
    }

    private static boolean tool(ItemStack stack) {
        return stack.is(ModItems.PIPE_LAYING_TOOL.get());
    }

    /** One command per press of the use key; a held key does not repeat it. */
    @SubscribeEvent
    public static void use(InputEvent.InteractionKeyMappingTriggered event) {
        var mc = Minecraft.getInstance();
        var player = mc.player;
        if (!event.isUseItem() || player == null || mc.level == null || !tool(player.getItemInHand(event.getHand()))) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        if (!armed) return;
        armed = false;
        boolean offHand = event.getHand() == InteractionHand.OFF_HAND;
        var hit = aimed(mc);
        if (player.isShiftKeyDown()) {
            if (start != null) {
                start = null;
                plan = null;
                say(player, PipeLaying.message("cancelled"));
            } else if (hit != null && removable(mc.level, hit) != null) {
                FluidNetwork.dismantlePipe(offHand, hit);
                event.setSwingHand(true);
            } else FluidNetwork.openPipeLaying(offHand);
            return;
        }
        if (hit == null) return;
        if (start == null) {
            double outside = PipeSizes.outside(settingsDiameter(player));
            var resolved = PipeAnchor.resolve(mc.level, hit, outside);
            var refusal = resolved.anchor() == null ? resolved.refusal() : resolved.anchor().refuses(outside);
            if (refusal != null) {
                say(player, PipeLaying.refusal(refusal));
                return;
            }
            start = new Start(hit, resolved.anchor(), mc.level.dimension());
            plan = null;
            say(player, PipeLaying.message("started", PipeLaying.describe(resolved.anchor())));
            return;
        }
        if (sent == start) return;
        var current = plan(mc.level, hit, settingsDiameter(player));
        plan = current;
        if (current.refusal != null) {
            say(player, current.refusal);
            return;
        }
        var cells = current.route.cells().stream().map(PipeAnchor::block).toList();
        FluidNetwork.layPipe(offHand, start.click, hit, cells, current.route.options());
        event.setSwingHand(true);
        sent = start;
    }

    /**
     * The server's answer to the lay sent last. Laid: the line is done and its start goes. Refused: a refused lay changes
     * nothing, so the start stays, and the route to that end shows the server's reason until the aim moves.
     */
    public static void answered(Component message, boolean laid) {
        var player = Minecraft.getInstance().player;
        if (player != null) say(player, message);
        if (sent != null && sent == start) {
            if (laid) {
                start = null;
                plan = null;
            } else if (plan != null) plan = refused = new Plan(plan.end, plan.anchor, plan.route, message, plan.placement);
        }
        sent = null;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        var player = mc.player;
        var outliner = Outliner.getInstance();
        if (!mc.options.keyUse.isDown()) armed = true;
        // Holding the tool is the request for the assemblies around the player (plan section 9).
        AssemblyToolClient.watch(player, player != null && mc.level != null && holding(player));
        if (player == null || mc.level == null || !holding(player)) {
            slot = null;
            if (start != null || plan != null || !markSlots.isEmpty()) {
                start = null;
                sent = null;
                plan = null;
                refused = null;
                for (var slot : List.of(START_SLOT, ROUTE_SLOT)) outliner.remove(slot);
                for (var slot : REMOVE_SLOTS) outliner.remove(slot);
                for (var slot : markSlots) outliner.remove(slot);
                markSlots = Set.of();
            }
            return;
        }
        age++;
        if (start != null && (start.dimension != mc.level.dimension() || start.click.clicked().getCenter().distanceToSqr(player.position()) > DROP_RANGE * DROP_RANGE)) {
            start = null;
            plan = null;
            say(player, PipeLaying.message("dropped"));
        }
        var hit = mc.screen == null ? aimed(mc) : null;
        double diameter = settingsDiameter(player), outside = PipeSizes.outside(diameter);
        if (start != null) {
            if (hit == null) plan = null;
            else if (plan == null || !same(mc.level, plan.end, hit, outside) || age % RECHECK_TICKS == 0 && plan != refused) plan = plan(mc.level, hit, diameter);
            outliner.showAABB(START_SLOT, new AABB(start.anchor.cell())).colored(START_COLOUR).lineWidth(1 / 16F);
            if (plan != null && plan.refusal != null)
                outliner.showLine(ROUTE_SLOT, start.anchor.cell().getCenter(), (plan.anchor != null ? plan.anchor.cell() : plan.end.clicked()).getCenter()).colored(REFUSED_COLOUR).lineWidth(1 / 16F);
            else outliner.remove(ROUTE_SLOT);
            if (age % HUD_TICKS == 0) say(player, hud(player));
        } else {
            plan = null;
            outliner.remove(START_SLOT);
            outliner.remove(ROUTE_SLOT);
        }
        // While Shift is held, the pipe or lane a use would dismantle is outlined in red.
        var removed = start == null && player.isShiftKeyDown() && hit != null ? removable(mc.level, hit) : null;
        for (int i = 0; i < REMOVE_SLOTS.length; i++)
            if (removed != null && i < removed.size()) outliner.showAABB(REMOVE_SLOTS[i], removed.get(i)).colored(REFUSED_COLOUR).lineWidth(1 / 16F);
            else outliner.remove(REMOVE_SLOTS[i]);
        if (start == null && hit != null && age % HUD_TICKS == 0) {
            var label = portLabel(mc.level, hit);
            if (label != null) say(player, label);
        }
        // Before each click, the slot aimed on a rack's tier: red where it is refused, or where the route to it is.
        boolean routeRefused = start != null && plan != null && plan.refusal != null;
        slot = hit != null && !player.isShiftKeyDown() && (start == null || plan == null || routeRefused) ? slot(mc.level, hit, outside, routeRefused) : null;
        var shown = new HashSet<Object>();
        ports(mc.level, player, outliner, shown);
        openEnds(mc.level, player, outliner, shown);
        freeSlots(mc.level, hit, outliner, shown);
        if (slot != null) {
            var aimed = slot;
            shown.add(AIM_SLOT);
            outliner.showAABB(AIM_SLOT, square(aimed.cell, Direction.values()[aimed.through], RackGeometry.facePoint(aimed.frame, aimed.lane, aimed.through), Math.min(.45, aimed.lane.diameter() / 2 + .04)))
                    .colored(aimed.refused ? REFUSED_COLOUR : START_COLOUR).lineWidth(1 / 32F);
        }
        for (var slot : markSlots) if (!shown.contains(slot)) outliner.remove(slot);
        markSlots = shown;
    }

    /**
     * The click a use makes (owner 2026-10-26, "Aim on the tier"): where the view ray crosses the tier of the first rack
     * cell it crosses it in, up to the block it hits (a rack's frame is open, so aiming through it still picks the rack),
     * unless the block it hits is that rack and the hit is on a lane; else the block it hits. The tier point is sent just
     * under the beams' top, where no lane resting on them is picked.
     */
    private static @Nullable PipeAnchor.Click aimed(Minecraft mc) {
        var hit = mc.hitResult instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK ? block : null;
        var vanilla = hit == null ? null : new PipeAnchor.Click(hit.getBlockPos(), hit.getDirection(), hit.getLocation().subtract(Vec3.atLowerCornerOf(hit.getBlockPos())));
        var player = mc.player;
        if (player == null || mc.level == null) return vanilla;
        var eye = player.getEyePosition();
        var look = player.getViewVector(1);
        double reach = hit != null ? hit.getLocation().distanceTo(eye) : player.blockInteractionRange();
        double[] from = {eye.x, eye.y, eye.z}, along = {look.x, look.y, look.z}, next = new double[3], delta = new double[3];
        int[] at = {(int) Math.floor(eye.x), (int) Math.floor(eye.y), (int) Math.floor(eye.z)}, step = new int[3];
        for (int a = 0; a < 3; a++) {
            step[a] = along[a] > 0 ? 1 : -1;
            delta[a] = along[a] == 0 ? Double.MAX_VALUE : 1 / Math.abs(along[a]);
            next[a] = along[a] == 0 ? Double.MAX_VALUE : (along[a] > 0 ? at[a] + 1 - from[a] : from[a] - at[a]) * delta[a];
        }
        var entered = Direction.UP;
        for (double t = 0; t <= reach + 1e-6; ) {
            var cell = new BlockPos(at[0], at[1], at[2]);
            boolean last = hit != null && cell.equals(hit.getBlockPos());
            if (mc.level.getBlockEntity(cell) instanceof PipeRackBlockEntity rack) {
                if (last && onLane(rack, vanilla)) return vanilla;
                double tier = rack.frame().beam() * LineGeometry.OFFSET, crossing = along[1] == 0 ? -1 : (cell.getY() + tier - eye.y) / along[1];
                if (crossing >= 0) {
                    double x = eye.x + crossing * look.x - cell.getX(), z = eye.z + crossing * look.z - cell.getZ();
                    if (x >= 0 && x <= 1 && z >= 0 && z <= 1) return new PipeAnchor.Click(cell, entered, new Vec3(x, tier - LineGeometry.OFFSET, z));
                }
            }
            if (last) break;
            int a = next[0] < next[1] ? (next[0] < next[2] ? 0 : 2) : (next[1] < next[2] ? 1 : 2);
            t = next[a];
            next[a] += delta[a];
            at[a] += step[a];
            entered = Direction.values()[2 * ((a + 2) % 3) + (step[a] > 0 ? 0 : 1)];
        }
        return vanilla;
    }

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
        if (!(level.getBlockEntity(click.clicked()) instanceof PipeRackBlockEntity rack) || onLane(rack, click)) return null;
        var aim = PipeAnchor.aimed(rack, click.local(), outside);
        if (aim == null) return null;
        int code = PipeSizes.code(outside), width = RackGeometry.width(PipeSizes.outsideOf(code));
        var lane = new RackGeometry.Lane(aim.option(), width, 1 << aim.through() | 1 << aim.open(), 1 << aim.through(), code);
        return new Slot(click.clicked(), rack.frame(), lane, aim.through(), refused || !aim.fits());
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
        return tool(player.getMainHandItem()) ? player.getMainHandItem() : player.getOffhandItem();
    }

    private static double settingsDiameter(LocalPlayer player) {
        var settings = held(player).get(ModItems.PIPE_LAYING_SETTINGS.get());
        return settings == null ? .05 : settings.diameter();
    }

    /** The route from the start to {@code end}, searched now on this thread within the search limits. */
    private static Plan plan(Level level, PipeAnchor.Click end, double diameter) {
        double outside = PipeSizes.outside(diameter);
        var nothing = new PipeRoute.Result(List.of(), List.of(), null);
        var resolved = PipeAnchor.resolve(level, end, outside);
        if (resolved.anchor() == null) return new Plan(end, null, nothing, PipeLaying.refusal(resolved.refusal()), null);
        var from = start.anchor;
        var to = resolved.anchor();
        var refused = PipeAnchor.refuses(from, to, outside);
        if (refused != null) return new Plan(end, to, nothing, PipeLaying.refusal(refused), null);
        int heading = from.leaving();
        var route = PipeRoute.search(from.end(), heading, to.end(), outside, PipeAnchor.space(level), PipeRoute.Limits.DEFAULT);
        if (!route.found()) return new Plan(end, to, route, PipeLaying.refusal(route.refusal()), null);
        return new Plan(end, to, route, null, PipeRoute.placement(route.cells(), from.end(), heading, to.end()));
    }

    /** Start → end, the length, the elbows, the size and the pipes needed; or why the aimed spot takes no line. */
    private static Component hud(LocalPlayer player) {
        if (plan == null) return PipeLaying.message("hud.aim", PipeLaying.describe(start.anchor));
        if (plan.refusal != null) return plan.refusal;
        int cells = plan.route.cells().size();
        double diameter = settingsDiameter(player);
        int elbows = PipeRoute.elbows(plan.route.cells(), start.anchor.end(), plan.anchor.end());
        return PipeLaying.message("hud.route", PipeLaying.describe(start.anchor), PipeLaying.describe(plan.anchor), cells, elbows, String.format(Locale.ROOT, "%.3f", diameter),
                player.getAbilities().instabuild ? Component.literal("—") : Component.literal(Integer.toString(cells)));
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
    private static void ports(Level level, LocalPlayer player, Outliner outliner, Set<Object> shown) {
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
        marks.sort((a, b) -> Double.compare(a.distance, b.distance));
        for (var mark : marks.subList(0, Math.min(MAX_MARKS, marks.size()))) {
            var slot = "icheme:port:" + mark.cell.asLong() + ":" + mark.face.ordinal();
            shown.add(slot);
            var shell = level.getBlockEntity(mark.cell) instanceof EquipmentShellBlockEntity cell ? cell : null;
            boolean connected = connected(level, mark.cell, mark.face);
            int assemblyColour = shell == null ? -1 : AssemblyToolClient.colour(shell, mark.face);
            int colour = assemblyColour >= 0 ? assemblyColour : connected ? PortColours.CONNECTED : PortColours.of(mark.port);
            outliner.showAABB(slot, square(mark.cell, mark.face, faceCentre(mark.face), .3)).colored(colour).lineWidth(1 / 32F);
            // A watched assembly's port: a frame round the square (AssemblyToolClient).
            int frame = shell == null ? -1 : AssemblyToolClient.frame(shell, mark.face);
            if (frame < 0 && assemblyColour >= 0 && connected) frame = PortColours.CONNECTED;
            if (frame < 0) continue;
            var framed = slot + ":frame";
            shown.add(framed);
            outliner.showAABB(framed, square(mark.cell, mark.face, faceCentre(mark.face), .4)).colored(frame).lineWidth(1 / 32F);
        }
    }

    private record Mark(BlockPos cell, Direction face, EquipmentShape.PortClass port, double distance) {
    }

    /**
     * Marks the open line ends around the player in yellow (plan 4.7), where a line laid to them joins: a square on each
     * open face of a pipe, and on each open face of a rack lane at the lane's point there; at most {@link #MAX_MARKS},
     * nearest first.
     */
    private static void openEnds(Level level, LocalPlayer player, Outliner outliner, Set<Object> shown) {
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
        ends.sort((a, b) -> Double.compare(a.distance, b.distance));
        for (var end : ends.subList(0, Math.min(MAX_MARKS, ends.size()))) {
            var slot = "icheme:open:" + end.cell.asLong() + ":" + end.face.ordinal() + ":" + end.option;
            shown.add(slot);
            outliner.showAABB(slot, square(end.cell, end.face, end.point, Math.min(.45, end.outside / 2 + .04))).colored(OPEN_COLOUR).lineWidth(1 / 32F);
        }
    }

    /** An open end: its cell and face, the lane's option (-1 for a pipe), its point on the face (block-local), and its size. */
    private record End(BlockPos cell, Direction face, int option, double[] point, double outside, double distance) {
    }

    private static double[] faceCentre(Direction face) {
        return new double[]{.5 + .5 * face.getStepX(), .5 + .5 * face.getStepY(), .5 + .5 * face.getStepZ()};
    }

    /** While a rack is aimed, a small cube on each tier position no lane takes: where a new lane may go. */
    private static void freeSlots(Level level, @Nullable PipeAnchor.Click hit, Outliner outliner, Set<Object> shown) {
        if (hit == null || !(level.getBlockEntity(hit.clicked()) instanceof PipeRackBlockEntity rack)) return;
        var frame = rack.rack();
        int taken = RackGeometry.taken(frame, rack.drawnLanes());
        for (int position = 0; position < RackGeometry.POSITIONS; position++) {
            if ((taken >> position & 1) != 0) continue;
            var c = frame.world(RackGeometry.centre(position, 1), (RackGeometry.BEAM_TOP + 2) * LineGeometry.OFFSET, .5);
            double half = .04;
            var key = "icheme:free_slot:" + position;
            shown.add(key);
            outliner.showAABB(key, new AABB(c[0] - half, c[1] - half, c[2] - half, c[0] + half, c[1] + half, c[2] + half).move(hit.clicked())).colored(FREE_COLOUR).lineWidth(1 / 32F);
        }
    }

    /** A flat square {@code 2 half} blocks across on {@code face} of {@code cell}, centred at block-local {@code point} on that face. */
    private static AABB square(BlockPos cell, Direction face, double[] point, double half) {
        double[] low = new double[3], high = new double[3];
        for (int a = 0; a < 3; a++) {
            double extent = a == face.getAxis().ordinal() ? .01 : half;
            low[a] = point[a] - extent;
            high[a] = point[a] + extent;
        }
        return new AABB(low[0], low[1], low[2], high[0], high[1], high[2]).move(cell);
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
        buffers.endBatch(TYPE);
    }

    /** One cell's polygons drawn translucent in {@code colour} on the plate sprite, at {@code cell}. */
    private static void ghost(VertexConsumer consumer, PoseStack pose, Vec3 camera, TextureAtlasSprite sprite, BlockPos cell, List<Mesh.Polygon> polygons, int colour) {
        pose.pushPose();
        pose.translate(cell.getX() - camera.x, cell.getY() - camera.y, cell.getZ() - camera.z);
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
