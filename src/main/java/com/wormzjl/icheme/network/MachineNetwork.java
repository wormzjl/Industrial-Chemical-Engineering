package com.wormzjl.icheme.network;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.MachinePresentation;
import com.wormzjl.icheme.runtime.fluid.MachineView;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.runtime.fluid.ShaftTrains;
import com.wormzjl.icheme.runtime.fluid.ShaftUnit;
import com.wormzjl.icheme.science.machine.Motor;
import com.wormzjl.icheme.science.machine.ShaftNetwork;
import com.wormzjl.icheme.science.machine.ShaftTrain;
import com.wormzjl.icheme.world.inventory.FluidDeviceMenu;
import com.wormzjl.icheme.world.inventory.MachineMenu;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import io.netty.handler.codec.DecoderException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.HandlerThread;
import org.jetbrains.annotations.Nullable;

/**
 * Machine protocol {@value #PROTOCOL} (batch 2026-11-03-rotating-machinery, W7, owner decision D31, readings A85, A87,
 * A89, A100): a motor, generator, gearbox, creative motor or Create coupling (W6b, protocol 2) opens its own page ({@link MachineMenu}); a casing's or a
 * turbine's fluid page opens its driver's ({@link OpenDriverPayload}). An open page is a session of the engine's
 * presentation ({@link MachinePresentation}): it gets its part's view at once and on the buckets after. An edit
 * ({@link EditPayload}) is checked at receipt (the page open on that part, {@code stillValid}, the player may build, the
 * settings in range) and queued as the shaft event {@code /icheme shaft} queues; the next bucket answers it. Wire formats
 * get no compatibility work (AGENTS.md).
 */
public final class MachineNetwork {
    public static final String PROTOCOL = "machine-3";
    /** The highest speed share a variable drive or a turbine's governor takes, and the highest creative motor speed (rpm) and power limit (W). */
    public static final double MAXIMUM_SHARE = 1.1, MAXIMUM_RPM = 1e5, MAXIMUM_LIMIT = 1e9;

    private static final Map<MinecraftServer, MachinePresentation<MachineMenu>> SERVERS = new IdentityHashMap<>();

    private MachineNetwork() {
    }

    /** A view on the wire. */
    public static final StreamCodec<RegistryFriendlyByteBuf, MachineView> VIEW = StreamCodec.of(MachineNetwork::write, MachineNetwork::read);

    private static void write(RegistryFriendlyByteBuf b, MachineView v) {
        b.writeLong(v.unit());
        b.writeEnum(v.kind());
        b.writeUtf(v.status(), MachineView.MAX_TEXT);
        for (double d : new double[]{v.speed(), v.shaftPower(), v.electricPower(), v.energyPerTick()}) b.writeDouble(d);
        writeSettings(b, v.settings());
        b.writeEnum(v.trip());
        b.writeDouble(v.ratedPower());
        b.writeDouble(v.designSpeed());
        b.writeVarInt(v.poles());
        b.writeBoolean(v.variable());
        b.writeDouble(v.storeShare());
        b.writeDouble(v.storeSeconds());
        b.writeUtf(v.governor(), MachineView.MAX_TEXT);
        b.writeDouble(v.ratio());
        for (double d : new double[]{v.steamFlow(), v.inletPressure(), v.exhaustPressure()}) b.writeDouble(d);
        b.writeBoolean(v.coupling() != null);
        if (v.coupling() != null) for (double d : new double[]{v.coupling().createSpeed(), v.coupling().stress(), v.coupling().impact(), v.coupling().trainSpeed(), v.coupling().wattsPerStress()}) b.writeDouble(d);
    }

    private static MachineView read(RegistryFriendlyByteBuf b) {
        try {
            long unit = b.readLong();
            var kind = b.readEnum(ShaftNetwork.Kind.class);
            String status = b.readUtf(MachineView.MAX_TEXT);
            double speed = b.readDouble(), shaft = b.readDouble(), electric = b.readDouble(), perTick = b.readDouble();
            var settings = readSettings(b);
            var trip = b.readEnum(ShaftTrain.Trip.class);
            double rated = b.readDouble(), design = b.readDouble();
            int poles = b.readVarInt();
            boolean variable = b.readBoolean();
            double share = b.readDouble(), seconds = b.readDouble();
            String governor = b.readUtf(MachineView.MAX_TEXT);
            return new MachineView(unit, kind, status, speed, shaft, electric, perTick, settings, trip, rated, design, poles, variable, share, seconds, governor, b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(),
                    b.readBoolean() ? new MachineView.Coupling(b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble()) : null);
        } catch (IllegalArgumentException invalid) {
            throw new DecoderException(invalid.getMessage());
        }
    }

    private static void writeSettings(RegistryFriendlyByteBuf b, ShaftUnit.Settings s) {
        b.writeBoolean(s.running());
        b.writeEnum(s.mode());
        b.writeDouble(s.setPoint());
        b.writeDouble(s.powerLimit());
        b.writeDouble(s.ratio());
    }

    private static ShaftUnit.Settings readSettings(RegistryFriendlyByteBuf b) {
        return new ShaftUnit.Settings(b.readBoolean(), b.readEnum(ShaftTrain.Mode.class), b.readDouble(), b.readDouble(), b.readDouble());
    }

    /** Server to client: the view of the part whose page is open in container {@code container}, the last edit it answers and that edit's refusal. */
    public record StatePayload(int container, MachineView view, int answered, String refusal) implements CustomPacketPayload {
        public static final Type<StatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "machine_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StatePayload> STREAM_CODEC = StreamCodec.of((b, p) -> {
            b.writeVarInt(p.container);
            VIEW.encode(b, p.view);
            b.writeVarInt(p.answered);
            b.writeUtf(p.refusal, MachineView.MAX_TEXT);
        }, b -> new StatePayload(b.readVarInt(), VIEW.decode(b), b.readVarInt(), b.readUtf(MachineView.MAX_TEXT)));

        public StatePayload {
            Objects.requireNonNull(view);
            Objects.requireNonNull(refusal);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** What an edit asks: new settings, or a reset of a latched trip. */
    public enum Action {
        APPLY, RESET
    }

    /**
     * Client to server: edit {@code sequence} of the page in container {@code container}: the settings in the engine's terms
     * (whether it runs, its mode, its set point: a motor's or a turbine's speed share, a creative motor's rpm; a power limit,
     * W; a gearbox's ratio, 0 for auto), or a reset.
     */
    public record EditPayload(int container, int sequence, Action action, ShaftUnit.Settings settings) implements CustomPacketPayload {
        public static final Type<EditPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "machine_edit"));
        public static final StreamCodec<RegistryFriendlyByteBuf, EditPayload> STREAM_CODEC = StreamCodec.of((b, p) -> {
            b.writeVarInt(p.container);
            b.writeVarInt(p.sequence);
            b.writeEnum(p.action);
            writeSettings(b, p.settings);
        }, b -> {
            try {
                return new EditPayload(b.readVarInt(), b.readVarInt(), b.readEnum(Action.class), readSettings(b));
            } catch (IllegalArgumentException invalid) {
                throw new DecoderException(invalid.getMessage());
            }
        });

        public EditPayload {
            Objects.requireNonNull(action);
            Objects.requireNonNull(settings);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client to server: the fluid page in container {@code container} (a casing's or a turbine's, at {@code position}) opens its driver's page. */
    public record OpenDriverPayload(int container, BlockPos position, long identity) implements CustomPacketPayload {
        public static final Type<OpenDriverPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "machine_open_driver"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenDriverPayload> STREAM_CODEC = StreamCodec.of((b, p) -> {
            b.writeVarInt(p.container);
            b.writeBlockPos(p.position);
            b.writeLong(p.identity);
        }, b -> new OpenDriverPayload(b.readVarInt(), b.readBlockPos(), b.readLong()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client to server: the page in container {@code container} opens its structure's datasheet. */
    public record DatasheetPayload(int container) implements CustomPacketPayload {
        public static final Type<DatasheetPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "machine_datasheet"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DatasheetPayload> STREAM_CODEC = StreamCodec.of((b, p) -> b.writeVarInt(p.container),
                b -> new DatasheetPayload(b.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL).executesOn(HandlerThread.MAIN);
        registrar.playToServer(EditPayload.TYPE, EditPayload.STREAM_CODEC, MachineNetwork::edit);
        registrar.playToServer(OpenDriverPayload.TYPE, OpenDriverPayload.STREAM_CODEC, MachineNetwork::openDriver);
        registrar.playToServer(DatasheetPayload.TYPE, DatasheetPayload.STREAM_CODEC, MachineNetwork::datasheet);
        registrar.playToClient(StatePayload.TYPE, StatePayload.STREAM_CODEC, (payload, context) -> {
            if (context.player().containerMenu instanceof MachineMenu menu && menu.containerId == payload.container())
                menu.accept(payload.view(), payload.answered(), payload.refusal());
        });
    }

    // ---------------- the server's side ----------------

    private static final MachinePresentation.Host<MachineMenu> HOST = new MachinePresentation.Host<>() {
        @Override
        public boolean valid(MachineMenu menu) {
            var player = menu.serverPlayer();
            return player != null && !player.hasDisconnected() && player.containerMenu == menu && menu.stillValid(player);
        }

        @Override
        public @Nullable MachineView view(MachineMenu menu) {
            var player = Objects.requireNonNull(menu.serverPlayer());
            return FluidWorldAuthority.find(player.server).map(world -> machineView(world, player.serverLevel(), menu.unit())).orElse(null);
        }

        @Override
        public void send(MachineMenu menu, MachineView view, int answered, String refusal) {
            PacketDistributor.sendToPlayer(Objects.requireNonNull(menu.serverPlayer()), new StatePayload(menu.containerId, view, answered, refusal));
        }
    };

    /** Part {@code unit}'s view, its governor valve named by the valve's device name or, without one, its position (reading A95). */
    public static @Nullable MachineView machineView(FluidWorldAuthority world, ServerLevel level, long unit) {
        var trains = world.shafts();
        long valve = trains.governorOf(unit);
        String governor = "";
        if (valve != 0) {
            var registration = world.registrations().get(valve);
            if (registration != null) {
                var p = registration.device().position();
                var at = new BlockPos(p.x(), p.y(), p.z());
                String name = level.isLoaded(at) ? EquipmentStructure.name(level, at) : "";
                governor = name.isEmpty() ? at.toShortString() : name;
            }
        }
        return MachineView.of(trains, unit, governor);
    }

    private static MachinePresentation<MachineMenu> presentation(MinecraftServer server) {
        return SERVERS.computeIfAbsent(server, s -> new MachinePresentation<>(HOST));
    }

    /**
     * Opens the page of the shaft part at {@code origin} (a structure's origin, or a creative motor's block), reachable from
     * {@code reach} blocks; false when the trains hold no part there with a page. A deployer's fake player opens nothing.
     */
    public static boolean open(ServerPlayer player, BlockPos origin, double reach, boolean datasheet) {
        var world = FluidWorldAuthority.find(player.server).orElse(null);
        if (world == null) return false;
        var level = player.serverLevel();
        var unit = world.shafts().unitAt(new PhysicalFluidTopology.Position(level.dimension().location().toString(), origin.getX(), origin.getY(), origin.getZ()));
        if (unit == null || !hasPage(world.shafts().kindOf(unit.id()))) return false;
        if (player instanceof FakePlayer) return true;
        long id = unit.id();
        var kind = world.shafts().kindOf(unit.id());
        player.openMenu(new SimpleMenuProvider((container, inventory, p) -> new MachineMenu(container, inventory, origin, reach, id, kind, datasheet),
                Component.translatable("gui.icheme.machine.kind." + kind.name().toLowerCase(java.util.Locale.ROOT))), buffer -> MachineMenu.write(buffer, origin, reach, id, kind, datasheet));
        // Subscribe after the opening packet, so the replay follows it.
        if (player.containerMenu instanceof MachineMenu menu && menu.unit() == id) subscribe(menu);
        return true;
    }

    /** A page's session starts: its part's view now, its buckets after (the menu's player's server). */
    public static void subscribe(MachineMenu menu) {
        var player = Objects.requireNonNull(menu.serverPlayer());
        FluidWorldAuthority.find(player.server).ifPresent(world -> presentation(player.server).open(menu, world.onlineTick()));
    }

    /** The open sessions of {@code server}'s machine pages. */
    public static int sessions(MinecraftServer server) {
        var presentation = SERVERS.get(server);
        return presentation == null ? 0 : presentation.sessions();
    }

    /** Whether a shaft part of {@code kind} has a page of its own: every part but a shaft block and a casing (its fluid page). */
    public static boolean hasPage(ShaftNetwork.Kind kind) {
        return kind != ShaftNetwork.Kind.SHAFT && kind != ShaftNetwork.Kind.CASING;
    }

    /** A machine page closed: its session ends. */
    public static void closed(MachineMenu menu) {
        var player = menu.serverPlayer();
        var presentation = player == null ? null : SERVERS.get(player.server);
        if (presentation != null) presentation.close(menu);
    }

    private static @Nullable MachineMenu menu(IPayloadContext context, int container) {
        return context.player() instanceof ServerPlayer player && player.containerMenu instanceof MachineMenu menu && menu.containerId == container
                && menu.stillValid(player) ? menu : null;
    }

    /** An edit: checked here, queued as a shaft event; the session's next bucket answers it. */
    private static void edit(EditPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) receive(player, payload);
    }

    /** An edit as {@code player} sent it: the page open in its container, still valid, a player who may build; then queued. */
    public static void receive(ServerPlayer player, EditPayload payload) {
        var menu = player.containerMenu instanceof MachineMenu m && m.containerId == payload.container() && m.stillValid(player) ? m : null;
        if (menu == null) return;
        if (!player.mayBuild() || player.isSpectator()) return;
        var world = FluidWorldAuthority.find(player.server).orElse(null);
        if (world == null) return;
        presentation(player.server).edit(menu, world.onlineTick(), payload.sequence(), () -> queue(world.shafts(), menu.unit(), payload.action(), payload.settings()));
    }

    /**
     * Checks an edit of part {@code unit} and queues its shaft event; returns the refusal's reason, or null. Only what the
     * part's page sets moves: a motor's run, mode and speed share; a turbine's run, mode and speed share; a creative motor's
     * run, mode, rpm and power limit; a generator's run and output limit; a gearbox's ratio.
     */
    public static @Nullable String queue(ShaftTrains trains, long unit, Action action, ShaftUnit.Settings asked) {
        var u = trains.unit(unit);
        if (u == null) return "the machine is gone";
        if (action == Action.RESET) {
            if (u.trip() == ShaftTrain.Trip.NONE) return "nothing to reset";
            trains.queue(new ShaftTrains.Reset(unit));
            return null;
        }
        var now = u.settings();
        ShaftUnit.Settings next;
        try {
            next = switch (u.kind()) {
                case MOTOR, STEAM_TURBINE -> {
                    if(asked.mode()==ShaftTrain.Mode.SPEED) {
                        if(u.kind()==ShaftNetwork.Kind.MOTOR&&(asked.setPoint()<Motor.LOWEST_SPEED||asked.setPoint()*u.designSpeed()>Motor.MAXIMUM_RPM))
                            yield refuse(String.format(java.util.Locale.ROOT,"the speed lies between %.0f and %.0f rpm",Motor.LOWEST_SPEED*u.designSpeed(),Motor.MAXIMUM_RPM));
                        if(u.kind()!=ShaftNetwork.Kind.MOTOR&&(asked.setPoint()<Motor.LOWEST_SPEED||asked.setPoint()>MAXIMUM_SHARE))
                            yield refuse(String.format(java.util.Locale.ROOT,"the speed lies between %.0f %% and %.0f %%",100*Motor.LOWEST_SPEED,100*MAXIMUM_SHARE));
                    }
                    yield new ShaftUnit.Settings(asked.running(), asked.mode(), asked.mode() == ShaftTrain.Mode.SPEED ? asked.setPoint() : now.setPoint(), u.kind()==ShaftNetwork.Kind.MOTOR?Math.min(MAXIMUM_LIMIT,asked.powerLimit()):now.powerLimit(), now.ratio());
                }
                case CREATIVE_MOTOR -> {
                    if (asked.mode() == ShaftTrain.Mode.SPEED && (asked.setPoint() <= 0 || asked.setPoint() > MAXIMUM_RPM)) yield refuse("the speed lies between 0 and 100000 rpm");
                    if (asked.powerLimit() > MAXIMUM_LIMIT) yield refuse("the power limit is at most 1000000 kW");
                    yield new ShaftUnit.Settings(asked.running(), asked.mode(), asked.mode() == ShaftTrain.Mode.SPEED ? asked.setPoint() : now.setPoint(), asked.powerLimit(), now.ratio());
                }
                case GENERATOR -> {
                    if (asked.powerLimit() > MAXIMUM_LIMIT) yield refuse("the output limit is at most 1000000 kW");
                    yield new ShaftUnit.Settings(asked.running(), now.mode(), now.setPoint(), asked.powerLimit(), now.ratio());
                }
                case GEARBOX -> new ShaftUnit.Settings(now.running(), now.mode(), now.setPoint(), now.powerLimit(), asked.ratio());
                default -> refuse("this part has no settings");
            };
        } catch (IllegalArgumentException invalid) {
            return invalid.getMessage();
        }
        trains.queue(new ShaftTrains.Configure(unit, next));
        return null;
    }

    private static ShaftUnit.Settings refuse(String reason) {
        throw new IllegalArgumentException(reason);
    }

    /** A casing's or a turbine's fluid page asks for its driver's page (reading A100). */
    private static void openDriver(OpenDriverPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.containerMenu instanceof FluidDeviceMenu fluid) || fluid.containerId != payload.container()
                || !fluid.position().equals(payload.position()) || fluid.identity() != payload.identity() || !fluid.stillValid(player)) return;
        var world = FluidWorldAuthority.find(player.server).orElse(null);
        if (world == null) return;
        var own = world.shafts().unitOfDevice(fluid.identity());
        var driver = own == null ? null : own.kind() == ShaftNetwork.Kind.STEAM_TURBINE ? own : world.shafts().driverOf(fluid.identity());
        if (driver == null) return;
        var p = driver.position();
        var origin = new BlockPos(p.x(), p.y(), p.z());
        var level = player.serverLevel();
        if (!level.dimension().location().toString().equals(p.dimension()) || !level.isLoaded(origin)) return;
        var vessel = EquipmentStructure.find(level, origin);
        double reach = MachineMenu.REACH + (vessel == null ? 0 : Math.max(vessel.shape().sizeX(), Math.max(vessel.shape().sizeY(), vessel.shape().sizeZ())));
        // The driver may stand away from the casing: its page is reachable from where the player stands.
        reach = Math.max(reach, Math.sqrt(player.distanceToSqr(origin.getCenter())) + MachineMenu.REACH);
        player.closeContainer();
        open(player, origin, reach, vessel != null);
    }

    /** The page's Datasheet push button: the structure's datasheet replaces the page. */
    private static void datasheet(DatasheetPayload payload, IPayloadContext context) {
        var menu = menu(context, payload.container());
        if (menu == null || !menu.datasheet()) return;
        AssemblyNetwork.datasheet(Objects.requireNonNull(menu.serverPlayer()), menu.origin());
    }

    /** The engine's tick: the machine pages' buckets, when one is due. */
    public static void presentationTick(MinecraftServer server, long onlineTick) {
        var presentation = SERVERS.get(server);
        if (presentation != null) presentation.tick(onlineTick);
    }

    public static void forget(MinecraftServer server) {
        SERVERS.remove(server);
    }

    // ---------------- the client's side ----------------

    /** Client: sends edit {@code sequence} of the page in container {@code container}. */
    public static void edit(int container, int sequence, Action action, ShaftUnit.Settings settings) {
        PacketDistributor.sendToServer(new EditPayload(container, sequence, action, settings));
    }

    /** Client: the fluid page in container {@code container} opens its driver's page. */
    public static void openDriver(int container, BlockPos position, long identity) {
        PacketDistributor.sendToServer(new OpenDriverPayload(container, position, identity));
    }

    /** Client: the page in container {@code container} opens its datasheet. */
    public static void datasheet(int container) {
        PacketDistributor.sendToServer(new DatasheetPayload(container));
    }
}
