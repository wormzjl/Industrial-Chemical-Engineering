package com.wormzjl.icheme.network;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.runtime.control.ColumnCommands;
import com.wormzjl.icheme.runtime.control.ConverterCommands;
import com.wormzjl.icheme.runtime.control.ControlSettings;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.world.inventory.AlarmBeaconMenu;
import com.wormzjl.icheme.world.inventory.ControlPanelMenu;

import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

import java.util.*;

/**
 * Structural cable actions are checked atomically. Loop edits are queued; readings only serve
 * subscribed menus.
 */
public final class ControlNetwork {
    private ControlNetwork() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(
                ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, path));
    }

    public record Edit(int menu, UUID panel, long revision, ControlSettings settings)
            implements CustomPacketPayload {
        public static final Type<Edit> TYPE = ControlNetwork.type("control_edit");
        public static final StreamCodec<RegistryFriendlyByteBuf, Edit> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeVarInt(p.menu);
                            b.writeUUID(p.panel);
                            b.writeLong(p.revision);
                            b.writeNbt(p.settings.save());
                        },
                        b ->
                                new Edit(
                                        b.readVarInt(),
                                        b.readUUID(),
                                        b.readLong(),
                                        ControlSettings.load(Objects.requireNonNull(b.readNbt()))));

        @Override
        public Type<Edit> type() {
            return TYPE;
        }
    }

    public record View(int menu, UUID panel, CompoundTag data) implements CustomPacketPayload {
        public static final Type<View> TYPE = ControlNetwork.type("control_view");
        public static final StreamCodec<RegistryFriendlyByteBuf, View> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeVarInt(p.menu);
                            b.writeUUID(p.panel);
                            b.writeNbt(p.data);
                        },
                        b ->
                                new View(
                                        b.readVarInt(),
                                        b.readUUID(),
                                        Objects.requireNonNull(b.readNbt())));

        @Override
        public Type<View> type() {
            return TYPE;
        }
    }

    public record Browse(int menu, int controllers, int devices) implements CustomPacketPayload {
        public static final Type<Browse> TYPE = ControlNetwork.type("control_browse");
        public static final StreamCodec<RegistryFriendlyByteBuf, Browse> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeVarInt(p.menu);
                            b.writeVarInt(p.controllers);
                            b.writeVarInt(p.devices);
                        },
                        b -> new Browse(b.readVarInt(), b.readVarInt(), b.readVarInt()));

        @Override
        public Type<Browse> type() {
            return TYPE;
        }
    }

    /** A DCS menu's choice of column page (W6.2): the publication then carries that column's Operation view. */
    public record ColumnSelect(int menu, long host) implements CustomPacketPayload {
        public static final Type<ColumnSelect> TYPE = ControlNetwork.type("control_column_select");
        public static final StreamCodec<RegistryFriendlyByteBuf, ColumnSelect> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeVarInt(p.menu);
                            b.writeVarLong(p.host);
                        },
                        b -> new ColumnSelect(b.readVarInt(), b.readVarLong()));

        @Override
        public Type<ColumnSelect> type() {
            return TYPE;
        }
    }

    /** A DCS's column command (W6.2): Start, Stop or the set points, queued like a controller edit. */
    public record ColumnCommand(int menu, ColumnCommands.Command command) implements CustomPacketPayload {
        public static final Type<ColumnCommand> TYPE = ControlNetwork.type("control_column_command");
        public static final StreamCodec<RegistryFriendlyByteBuf, ColumnCommand> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeVarInt(p.menu);
                            b.writeVarLong(p.command.host());
                            b.writeEnum(p.command.action());
                            b.writeVarInt(p.command.values().size());
                            for (double v : p.command.values()) b.writeDouble(v);
                        },
                        b -> {
                            int menu = b.readVarInt();
                            long host = b.readVarLong();
                            var action = b.readEnum(ColumnCommands.Action.class);
                            int n = Wire.count(b, ColumnCommands.Command.MAX_VALUES, Double.BYTES, "set point value");
                            var values = new ArrayList<Double>();
                            for (int i = 0; i < n; i++) values.add(b.readDouble());
                            return new ColumnCommand(menu, new ColumnCommands.Command(host, action, values));
                        });

        @Override
        public Type<ColumnCommand> type() {
            return TYPE;
        }
    }

    /** A DCS's converter command (batch 2026-11-12-fluid-conversion, W4): a mode, a rate, a deadband or the thresholds, queued like a controller edit. */
    public record ConverterCommand(int menu, ConverterCommands.Command command) implements CustomPacketPayload {
        public static final Type<ConverterCommand> TYPE = ControlNetwork.type("control_converter_command");
        public static final StreamCodec<RegistryFriendlyByteBuf, ConverterCommand> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeVarInt(p.menu);
                            b.writeVarLong(p.command.host());
                            b.writeEnum(p.command.action());
                            b.writeEnum(p.command.mode() == null ? com.wormzjl.icheme.runtime.fluid.ConverterUnit.Mode.OFF : p.command.mode());
                            for (double v : p.command.values()) b.writeDouble(v);
                        },
                        b -> {
                            int menu = b.readVarInt();
                            long host = b.readVarLong();
                            var action = b.readEnum(ConverterCommands.Action.class);
                            var mode = b.readEnum(com.wormzjl.icheme.runtime.fluid.ConverterUnit.Mode.class);
                            var values = new ArrayList<Double>();
                            for (int i = 0; i < ConverterCommands.Command.count(action); i++) values.add(b.readDouble());
                            return new ConverterCommand(menu, new ConverterCommands.Command(host, action, mode, values));
                        });

        @Override
        public Type<ConverterCommand> type() {
            return TYPE;
        }
    }

    // The alarm beacon (batch 2026-11-15-fluid-interfaces, W9): its settings edit and its view.
    public record AlarmEdit(int menu, UUID alarm, long revision, com.wormzjl.icheme.runtime.control.AlarmSettings settings)
            implements CustomPacketPayload {
        public static final Type<AlarmEdit> TYPE = ControlNetwork.type("alarm_edit");
        public static final StreamCodec<RegistryFriendlyByteBuf, AlarmEdit> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeVarInt(p.menu);
                            b.writeUUID(p.alarm);
                            b.writeLong(p.revision);
                            b.writeNbt(p.settings.save());
                        },
                        b ->
                                new AlarmEdit(
                                        b.readVarInt(),
                                        b.readUUID(),
                                        b.readLong(),
                                        com.wormzjl.icheme.runtime.control.AlarmSettings.load(Objects.requireNonNull(b.readNbt()))));

        @Override
        public Type<AlarmEdit> type() {
            return TYPE;
        }
    }

    public record AlarmView(int menu, UUID alarm, CompoundTag data) implements CustomPacketPayload {
        public static final Type<AlarmView> TYPE = ControlNetwork.type("alarm_view");
        public static final StreamCodec<RegistryFriendlyByteBuf, AlarmView> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeVarInt(p.menu);
                            b.writeUUID(p.alarm);
                            b.writeNbt(p.data);
                        },
                        b -> new AlarmView(b.readVarInt(), b.readUUID(), Objects.requireNonNull(b.readNbt())));

        @Override
        public Type<AlarmView> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        // control-5: the converter command (batch 2026-11-12-fluid-conversion, W4).
        var r = event.registrar(NetworkProtocol.VERSION).executesOn(HandlerThread.MAIN);
        r.playToServer(
                Edit.TYPE,
                Edit.CODEC,
                (p, c) -> {
                    if (c.player() instanceof ServerPlayer player
                            && player.containerMenu instanceof ControlPanelMenu menu
                            && menu.containerId == p.menu
                            && menu.stillValid(player))
                        FluidWorldAuthority.find(player.server)
                                .ifPresent(
                                        w ->
                                                w.controls()
                                                        .edit(
                                                                menu,
                                                                p.panel,
                                                                p.revision,
                                                                p.settings));
                });
        r.playToServer(
                Browse.TYPE,
                Browse.CODEC,
                (p, c) -> {
                    if (c.player() instanceof ServerPlayer player
                            && player.containerMenu instanceof ControlPanelMenu menu
                            && menu.containerId == p.menu
                            && menu.stillValid(player)) menu.browse(p.controllers, p.devices);
                    else if (c.player() instanceof ServerPlayer player
                            && player.containerMenu instanceof AlarmBeaconMenu menu
                            && menu.containerId == p.menu
                            && menu.stillValid(player)) menu.browse(p.devices);
                });
        r.playToServer(
                ColumnSelect.TYPE,
                ColumnSelect.CODEC,
                (p, c) -> {
                    if (c.player() instanceof ServerPlayer player
                            && player.containerMenu instanceof ControlPanelMenu menu
                            && menu.containerId == p.menu
                            && menu.dcs()
                            && menu.stillValid(player))
                        FluidWorldAuthority.find(player.server).ifPresent(w -> w.controls().selectColumn(menu, p.host));
                });
        r.playToServer(
                ColumnCommand.TYPE,
                ColumnCommand.CODEC,
                (p, c) -> {
                    if (c.player() instanceof ServerPlayer player
                            && player.containerMenu instanceof ControlPanelMenu menu
                            && menu.containerId == p.menu
                            && menu.stillValid(player))
                        FluidWorldAuthority.find(player.server).ifPresent(w -> w.controls().column(menu, p.command));
                });
        r.playToServer(
                ConverterCommand.TYPE,
                ConverterCommand.CODEC,
                (p, c) -> {
                    if (c.player() instanceof ServerPlayer player
                            && player.containerMenu instanceof ControlPanelMenu menu
                            && menu.containerId == p.menu
                            && menu.stillValid(player))
                        FluidWorldAuthority.find(player.server).ifPresent(w -> w.controls().converter(menu, p.command));
                });
        // The alarm beacon (W9): its edit, its page of input choices (Browse: devices is the page) and its view.
        r.playToServer(
                AlarmEdit.TYPE,
                AlarmEdit.CODEC,
                (p, c) -> {
                    if (c.player() instanceof ServerPlayer player
                            && player.containerMenu instanceof AlarmBeaconMenu menu
                            && menu.containerId == p.menu
                            && menu.stillValid(player))
                        FluidWorldAuthority.find(player.server).ifPresent(w -> w.controls().edit(menu, p.alarm, p.revision, p.settings));
                });
        r.playToClient(
                AlarmView.TYPE,
                AlarmView.CODEC,
                (p, c) -> {
                    if (c.player().containerMenu instanceof AlarmBeaconMenu menu
                            && menu.containerId == p.menu
                            && menu.alarm().equals(p.alarm)) menu.receive(p.data);
                });
        r.playToClient(
                View.TYPE,
                View.CODEC,
                (p, c) -> {
                    var player = c.player();
                    if (player.containerMenu instanceof ControlPanelMenu menu
                            && menu.containerId == p.menu
                            && menu.panel().equals(p.panel)) menu.receive(p.data);
                });
    }

    public static void send(ServerPlayer p, int menu, UUID panel, CompoundTag data) {
        PacketDistributor.sendToPlayer(p, new View(menu, panel, data));
    }

    public static void edit(int menu, UUID panel, long revision, ControlSettings settings) {
        PacketDistributor.sendToServer(new Edit(menu, panel, revision, settings));
    }

    public static void selectColumn(int menu, long host) {
        PacketDistributor.sendToServer(new ColumnSelect(menu, host));
    }

    public static void column(int menu, ColumnCommands.Command command) {
        PacketDistributor.sendToServer(new ColumnCommand(menu, command));
    }

    public static void converter(int menu, ConverterCommands.Command command) {
        PacketDistributor.sendToServer(new ConverterCommand(menu, command));
    }

    public static void browse(int menu, int controllers, int devices) {
        PacketDistributor.sendToServer(new Browse(menu, controllers, devices));
    }

    public static void sendAlarm(ServerPlayer p, int menu, UUID alarm, CompoundTag data) {
        PacketDistributor.sendToPlayer(p, new AlarmView(menu, alarm, data));
    }

    public static void editAlarm(int menu, UUID alarm, long revision, com.wormzjl.icheme.runtime.control.AlarmSettings settings) {
        PacketDistributor.sendToServer(new AlarmEdit(menu, alarm, revision, settings));
    }
}
