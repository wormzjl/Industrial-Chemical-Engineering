package com.wormzjl.icheme.fluid.gametest;

import com.mojang.authlib.GameProfile;
import com.wormzjl.icheme.network.ControlNetwork;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.runtime.control.ControlService;
import com.wormzjl.icheme.runtime.control.ControlSettings;
import com.wormzjl.icheme.runtime.control.DataBus;
import com.wormzjl.icheme.runtime.control.DataPort;
import com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.runtime.instrument.Instrument;
import com.wormzjl.icheme.runtime.instrument.InstrumentHosts;
import com.wormzjl.icheme.runtime.instrument.Sensor;
import com.wormzjl.icheme.science.equipment.ConduitGeometry;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.world.inventory.ControlPanelMenu;
import com.wormzjl.icheme.world.level.block.ControlPanelBlock;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.entity.ControlPanelBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The control loop end to end in the real server: an operator's edit through the controller's menu,
 * the cadence's decision, the valve plan the solver takes and the publication to the open menu. No
 * other test drives a controller through all four.
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class ControlLoopGameTests {
    private ControlLoopGameTests() {}

    /**
     * A nitrogen line from 1.5 bar into a void at 1 atm passes a flowmeter and an unconfigured
     * control valve. With no sensor of its own the valve regulates nothing, so the line runs at its
     * free flow, set by the pipes' resistance. A local controller is wired to both. Its operator
     * applies a flow target of half the free flow. A cadence applies the edit and decides
     * CONTROLLING; the solver takes the valve plan, so the measured flow settles on the target
     * while the valve's saved law stays as installed; the open menu receives the status and the
     * reading. Switched off, the controller withdraws the plan and the free flow returns.
     */
    @GameTest(template = "empty", timeoutTicks = 4000, batch = "control-loop")
    public static void aFlowControllerHoldsItsTargetThroughTheValvePlan(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
        // Clear of the barrier ring around the one-cell template (x and z -1 to 1, y 1 and 2),
        // which would block the flowmeter's taps and the fitter's aim.
        var source = helper.absolutePos(new BlockPos(2, 1, 2));
        var meter = source.east();
        var valve = meter.east();
        var sink = valve.east(2);
        var controller = valve.south(2);
        var line = List.of(source, meter, valve, valve.east(), sink);
        level.setBlock(source, ModBlocks.FLUID_GENERATOR.get().defaultBlockState(), 3);
        for (var pipe : List.of(meter, valve, valve.east()))
            level.setBlock(
                    pipe,
                    ModBlocks.FLUID_PIPE
                            .get()
                            .defaultBlockState()
                            .setValue(FluidDeviceBlock.FACING, Direction.EAST),
                    3);
        level.setBlock(sink, ModBlocks.FLUID_VOID.get().defaultBlockState(), 3);
        long[] ids = line.stream().mapToLong(p -> identity(level, p)).toArray();
        long meterId = ids[1], valveId = ids[2];
        var nitrogen = FluidDeviceSpec.nitrogen(MaterialRuntime.active()).composition();
        TankCharge.run(world, ids[0], new FluidDeviceSpec(1, 298.15, 150_000, nitrogen));
        // The valve and the flowmeter go on as a player fits them.
        helper.assertTrue(
                world.installPipePressureValve(valveId, PhysicalFluidTopology.Direction.EAST),
                "The control valve was not queued");
        world.instruments().legacyInstalled(valveId, Instrument.Type.PRESSURE_VALVE);
        var valveEntity = (FluidDeviceBlockEntity) level.getBlockEntity(valve);
        valveEntity.refreshGeometry(valveEntity.bore(), true);
        var fitter =
                FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "ControlLoopFit"));
        fitter.setGameMode(GameType.CREATIVE);
        fitter.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.FLOWMETER.get()));
        fitter.moveTo(meter.getX() + .5, meter.getY() + 1, meter.getZ() + .5, 0, 90);
        level.setBlock(
                controller,
                ModBlocks.LOCAL_CONTROLLER
                        .get()
                        .defaultBlockState()
                        .setValue(ControlPanelBlock.FACING, Direction.NORTH),
                3);
        var box = (ControlPanelBlockEntity) level.getBlockEntity(controller);
        world.controls().loaded(box);
        UUID panel = box.identity();
        var operator = operator(level, controller);
        var cables = new ArrayList<DataBus.Site>();
        var settings = new ControlSettings[1];
        double[] free = {Double.NaN}, sample = {Double.NaN}, target = {0};
        long[] sampled = {0};
        int[] phase = {0};
        Sensor.Installed[] flowmeter = {null};
        helper.succeedWhen(
                () -> {
                    if (phase[0] == 0) {
                        // The flowmeter and the cable go in once the valve stands committed, so
                        // no socket moves after it is wired.
                        helper.assertTrue(
                                world.committedDevice(valveId)
                                        .map(d -> d.control() instanceof FlowControl.ControlValve)
                                        .orElse(false),
                                "Waiting for the committed control valve");
                        if (flowmeter[0] == null) {
                            world.instruments()
                                    .requestSensor(
                                            fitter, InteractionHand.MAIN_HAND, Sensor.Kind.FLOW);
                            flowmeter[0] =
                                    world.instruments().allSensors().stream()
                                            .filter(
                                                    s ->
                                                            s.key().host() == meterId
                                                                    && s.kind() == Sensor.Kind.FLOW)
                                            .findFirst()
                                            .orElse(null);
                            // Not a wait: a request either fits at once or never does.
                            if (flowmeter[0] == null)
                                throw new IllegalStateException(
                                        "The flowmeter was not fitted: "
                                                + fitting(level, fitter, meter));
                        }
                        helper.assertTrue(
                                box.dataPort() != null
                                        && port(level, meter) != null
                                        && port(level, valve) != null,
                                "Waiting for the three data sockets");
                        cables.addAll(
                                wire(
                                        level,
                                        world,
                                        List.of(
                                                box.dataPort(),
                                                port(level, meter),
                                                port(level, valve))));
                        phase[0] = 1;
                    }
                    if (phase[0] == 1) {
                        // The free flow is the line's own, steady over a cadence.
                        helper.assertTrue(
                                world.controls().connectedHosts(meterId).contains(valveId),
                                "The data cable does not join the flowmeter and the valve");
                        double flow = flow(helper, world, meterId);
                        helper.assertTrue(
                                flow > 0, "Waiting for flow east through the flowmeter: " + flow);
                        double previous = sample[0];
                        if (!Double.isNaN(previous))
                            helper.assertTrue(
                                    world.onlineTick() - sampled[0] >= ControlService.CADENCE,
                                    "Waiting a cadence between samples");
                        sample[0] = flow;
                        sampled[0] = world.onlineTick();
                        helper.assertTrue(
                                Math.abs(sample[0] - previous) <= 2e-3 * sample[0],
                                "Waiting for a steady free flow");
                        free[0] = sample[0];
                        target[0] = free[0] / 2;
                        ControlPanelMenu.open(operator.player, controller);
                        settings[0] =
                                new ControlSettings(
                                        "FIC-1",
                                        true,
                                        flowmeter[0].instance(),
                                        valveId,
                                        Sensor.Unit.KILOGRAM_PER_SECOND,
                                        target[0],
                                        ControlSettings.Action.OUTLET);
                        world.controls()
                                .edit(
                                        menu(helper, operator),
                                        panel,
                                        world.controls().facts().panel(panel).revision(),
                                        settings[0]);
                        phase[0] = 2;
                        helper.assertTrue(false, "Waiting for the cadence to apply the edit");
                    }
                    if (phase[0] == 2) {
                        var shown = latest(operator, panel, flowmeter[0].instance());
                        helper.assertTrue(
                                shown != null && shown.status().equals("CONTROLLING"),
                                "Waiting for CONTROLLING: "
                                        + (shown == null ? "no view" : shown.status()));
                        helper.assertTrue(
                                shown.ack() == 1 && shown.reply().equals("APPLIED"),
                                "The edit was answered " + shown.reply());
                        helper.assertTrue(
                                world.controls()
                                        .facts()
                                        .panel(panel)
                                        .settings()
                                        .equals(settings[0]),
                                "The saved controller settings differ from the edit");
                        double measured = flow(helper, world, meterId);
                        helper.assertTrue(
                                near(measured, target[0])
                                        && near(shown.value(), target[0])
                                        && shown.quality().equals("AVAILABLE"),
                                "Waiting for the flow to settle on the target: measured "
                                        + measured
                                        + ", shown "
                                        + shown.value()
                                        + ", target "
                                        + target[0]);
                        // The plan is a slice input; the valve's saved law stays as installed.
                        helper.assertTrue(
                                FlowControl.ControlValve.unconfigured()
                                        .equals(
                                                world.registrations()
                                                        .get(valveId)
                                                        .device()
                                                        .control()),
                                "The plan was written into the valve's saved law");
                        settings[0] =
                                new ControlSettings(
                                        "FIC-1",
                                        false,
                                        flowmeter[0].instance(),
                                        valveId,
                                        Sensor.Unit.KILOGRAM_PER_SECOND,
                                        target[0],
                                        ControlSettings.Action.OUTLET);
                        world.controls()
                                .edit(
                                        menu(helper, operator),
                                        panel,
                                        world.controls().facts().panel(panel).revision(),
                                        settings[0]);
                        phase[0] = 3;
                        helper.assertTrue(false, "Waiting for the cadence to switch it off");
                    }
                    if (phase[0] == 3) {
                        var shown = latest(operator, panel, flowmeter[0].instance());
                        helper.assertTrue(
                                shown != null && shown.ack() == 2 && shown.status().equals("OFF"),
                                "Waiting for OFF: " + (shown == null ? "no view" : shown.status()));
                        double measured = flow(helper, world, meterId);
                        helper.assertTrue(
                                near(measured, free[0]),
                                "Waiting for the free flow to return: "
                                        + measured
                                        + " of "
                                        + free[0]);
                        operator.player.closeContainer();
                        for (var site : cables) world.controls().removeCable(site);
                        level.removeBlock(controller, false);
                        for (var p : line) level.removeBlock(p, false);
                        phase[0] = 4;
                        helper.assertTrue(false, "Waiting for cleanup");
                    }
                    for (long id : ids)
                        helper.assertTrue(
                                !world.registrations().containsKey(id),
                                "Waiting for cleanup of " + id);
                    helper.assertTrue(
                            world.controls().facts().panel(panel) == null,
                            "The removed controller kept its facts");
                    helper.assertTrue(
                            world.instruments().allSensors().stream()
                                    .noneMatch(s -> s.key().host() == meterId),
                            "The removed pipe kept its flowmeter");
                });
    }

    /** Why a flowmeter request found nothing to fit on {@code pipe}: the aim and the mounts. */
    private static String fitting(ServerLevel level, ServerPlayer fitter, BlockPos pipe) {
        var hit = fitter.pick(fitter.blockInteractionRange(), 0, false);
        var aimed =
                hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK
                        ? "cell " + block.getBlockPos().subtract(pipe).toShortString()
                        : hit.getType().name();
        var host = InstrumentHosts.at(level, pipe);
        if (host == null) return "aimed at " + aimed + ", no instrument host";
        var mounts =
                Sensor.mounts(host, Sensor.Kind.FLOW).stream()
                        .map(
                                m ->
                                        m.id()
                                                + (InstrumentHosts.clear(level, host, m)
                                                        ? ""
                                                        : " blocked")
                                                + (InstrumentHosts.reachable(fitter, host, m)
                                                        ? ""
                                                        : " out of reach"))
                        .toList();
        return "aimed at "
                + aimed
                + ", mounts "
                + mounts
                + ", pipe reach "
                + InstrumentHosts.pipeReachable(fitter, host);
    }

    /** The accepted mass flow through pipe {@code id}; waits while it has no reading. */
    private static double flow(GameTestHelper helper, FluidWorldAuthority world, long id) {
        var reading = world.instrumentPipeReading(id);
        helper.assertTrue(reading != null, "Waiting for a reading of pipe " + id);
        return reading.massFlow();
    }

    private static boolean near(double value, double expected) {
        return Math.abs(value - expected) <= .02 * expected;
    }

    private static long identity(ServerLevel level, BlockPos pos) {
        return ((FluidDeviceBlockEntity) level.getBlockEntity(pos)).fluidIdentity();
    }

    private static DataPort port(ServerLevel level, BlockPos pipe) {
        return ((FluidDeviceBlockEntity) level.getBlockEntity(pipe)).instruments().dataPort();
    }

    private static ControlPanelMenu menu(GameTestHelper helper, ViewCapture operator) {
        helper.assertTrue(
                operator.player.containerMenu instanceof ControlPanelMenu,
                "The controller's menu is not open");
        return (ControlPanelMenu) operator.player.containerMenu;
    }

    /**
     * A menu owner beside {@code controller}. NeoForge's fake player refuses menus, so this is a
     * server player whose connection keeps the views it is sent.
     */
    private static ViewCapture operator(ServerLevel level, BlockPos controller) {
        var player =
                new ServerPlayer(
                        level.getServer(),
                        level,
                        new GameProfile(UUID.randomUUID(), "ControlLoopOperator"),
                        ClientInformation.createDefault());
        var capture = new ViewCapture(player);
        player.setGameMode(GameType.SURVIVAL);
        player.moveTo(controller.getX() + 1.5, controller.getY(), controller.getZ() + .5);
        return capture;
    }

    /** What the latest view of {@code panel} showed: its status, the last answer, one reading. */
    private record Shown(String status, long ack, String reply, double value, String quality) {}

    private static Shown latest(ViewCapture capture, UUID panel, UUID sensor) {
        for (int i = capture.views.size() - 1; i >= 0; i--) {
            var view = capture.views.get(i);
            if (!view.panel().equals(panel)) continue;
            var rows = view.data().getList("controllers", Tag.TAG_COMPOUND);
            if (rows.isEmpty()) return null;
            var row = rows.getCompound(0);
            double value = Double.NaN;
            String quality = "";
            var inputs = row.getList("inputs", Tag.TAG_COMPOUND);
            for (int j = 0; j < inputs.size(); j++) {
                var input = inputs.getCompound(j);
                if (input.hasUUID("id") && input.getUUID("id").equals(sensor)) {
                    value = input.getDouble("value");
                    quality = input.getString("quality");
                }
            }
            return new Shown(
                    row.getString("status"),
                    view.data().getLong("ack"),
                    view.data().getString("reply"),
                    value,
                    quality);
        }
        return null;
    }

    /**
     * Data cable facts through {@code ports} in order, along the shortest path of free cells, as a
     * laid line leaves them: one cable per cell, centred joints between neighbours and each port's
     * socket joint in its cell. The bus reads only these facts. Returns their sites.
     */
    private static List<DataBus.Site> wire(
            ServerLevel level, FluidWorldAuthority world, List<DataPort> ports) {
        var path = new ArrayList<BlockPos>();
        for (int i = 0; i + 1 < ports.size(); i++) {
            var leg = route(level, ports.get(i).cell(), ports.get(i + 1).cell());
            if (!path.isEmpty()) leg.remove(0);
            path.addAll(leg);
        }
        var joints = new LinkedHashMap<BlockPos, ConduitGeometry.Key>();
        for (int i = 0; i < path.size(); i++) {
            var cell = path.get(i);
            var key = joints.getOrDefault(cell, new ConduitGeometry.Key(0, 0));
            for (int j : new int[] {i - 1, i + 1})
                if (j >= 0 && j < path.size() && !path.get(j).equals(cell)) {
                    var step = path.get(j).subtract(cell);
                    var face = Direction.fromDelta(step.getX(), step.getY(), step.getZ());
                    key = key.connect(face.ordinal(), 0, 0);
                }
            joints.put(cell, key);
        }
        var ends = new HashMap<BlockPos, Map<Direction, UUID>>();
        for (var port : ports) {
            joints.put(
                    port.cell(),
                    joints.get(port.cell()).connect(port.face().ordinal(), port.u(), port.v()));
            ends.computeIfAbsent(port.cell(), c -> new EnumMap<>(Direction.class))
                    .put(port.face(), port.id());
        }
        var dimension = level.dimension().location().toString();
        var sites = new ArrayList<DataBus.Site>();
        for (var entry : joints.entrySet()) {
            var site = new DataBus.Site(dimension, entry.getKey(), 0);
            world.controls()
                    .cable(
                            new DataBus.Cable(
                                    site,
                                    entry.getValue(),
                                    0,
                                    ends.getOrDefault(entry.getKey(), Map.of())));
            sites.add(site);
        }
        return sites;
    }

    /** The shortest path of free cells from {@code from} to {@code to}, both included. */
    private static List<BlockPos> route(ServerLevel level, BlockPos from, BlockPos to) {
        var previous = new HashMap<BlockPos, BlockPos>();
        var queue = new ArrayDeque<BlockPos>();
        previous.put(from, from);
        queue.add(from);
        while (!queue.isEmpty()) {
            var cell = queue.removeFirst();
            if (cell.equals(to)) {
                var path = new ArrayList<BlockPos>();
                for (var c = to; !c.equals(from); c = previous.get(c)) path.add(0, c);
                path.add(0, from);
                return path;
            }
            for (var d : Direction.values()) {
                var next = cell.relative(d);
                if (previous.containsKey(next) || next.distManhattan(from) > 24) continue;
                if (!next.equals(to) && !level.getBlockState(next).isAir()) continue;
                previous.put(next, cell);
                queue.addLast(next);
            }
        }
        throw new IllegalStateException("No free cable path from " + from + " to " + to);
    }

    /** A menu owner's connection that keeps the controller views it is sent and drops the rest. */
    private static final class ViewCapture extends ServerGamePacketListenerImpl {
        private final List<ControlNetwork.View> views = new ArrayList<>();

        ViewCapture(ServerPlayer player) {
            super(
                    player.server,
                    new Connection(PacketFlow.SERVERBOUND) {
                        @Override
                        public void setListenerForServerboundHandshake(PacketListener listener) {}
                    },
                    player,
                    CommonListenerCookie.createInitial(player.getGameProfile(), false));
        }

        @Override
        public void tick() {}

        @Override
        public void send(Packet<?> packet) {
            send(packet, null);
        }

        @Override
        public void send(Packet<?> packet, PacketSendListener listener) {
            if (packet instanceof ClientboundCustomPayloadPacket custom
                    && custom.payload() instanceof ControlNetwork.View view) views.add(view);
        }

        @Override
        public void disconnect(Component reason) {}
    }
}
