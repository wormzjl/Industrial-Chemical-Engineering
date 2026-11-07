package com.wormzjl.icheme.network;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.runtime.control.ControlSettings;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.world.inventory.ControlPanelMenu;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;
import java.util.*;

/** Structural cable actions are checked atomically. Loop edits are queued; readings only serve subscribed menus. */
public final class ControlNetwork {
    private ControlNetwork(){}
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path){return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,path));}
    public record Edit(int menu,UUID panel,long revision,ControlSettings settings) implements CustomPacketPayload {
        public static final Type<Edit> TYPE=ControlNetwork.type("control_edit");public static final StreamCodec<RegistryFriendlyByteBuf,Edit> CODEC=StreamCodec.of((b,p)->{b.writeVarInt(p.menu);b.writeUUID(p.panel);b.writeLong(p.revision);b.writeNbt(p.settings.save());},b->new Edit(b.readVarInt(),b.readUUID(),b.readLong(),ControlSettings.load(Objects.requireNonNull(b.readNbt()))));@Override public Type<Edit> type(){return TYPE;}
    }
    public record View(int menu,UUID panel,CompoundTag data) implements CustomPacketPayload {
        public static final Type<View> TYPE=ControlNetwork.type("control_view");public static final StreamCodec<RegistryFriendlyByteBuf,View> CODEC=StreamCodec.of((b,p)->{b.writeVarInt(p.menu);b.writeUUID(p.panel);b.writeNbt(p.data);},b->new View(b.readVarInt(),b.readUUID(),Objects.requireNonNull(b.readNbt())));@Override public Type<View> type(){return TYPE;}
    }
    public record Browse(int menu,int controllers,int devices) implements CustomPacketPayload {
        public static final Type<Browse> TYPE=ControlNetwork.type("control_browse");public static final StreamCodec<RegistryFriendlyByteBuf,Browse> CODEC=StreamCodec.of((b,p)->{b.writeVarInt(p.menu);b.writeVarInt(p.controllers);b.writeVarInt(p.devices);},b->new Browse(b.readVarInt(),b.readVarInt(),b.readVarInt()));@Override public Type<Browse> type(){return TYPE;}
    }
    public static void register(RegisterPayloadHandlersEvent event){var r=event.registrar("control-3").executesOn(HandlerThread.MAIN);
        r.playToServer(Edit.TYPE,Edit.CODEC,(p,c)->{if(c.player() instanceof ServerPlayer player&&player.containerMenu instanceof ControlPanelMenu menu&&menu.containerId==p.menu&&menu.stillValid(player))FluidWorldAuthority.find(player.server).ifPresent(w->w.controls().edit(menu,p.panel,p.revision,p.settings));});
        r.playToServer(Browse.TYPE,Browse.CODEC,(p,c)->{if(c.player() instanceof ServerPlayer player&&player.containerMenu instanceof ControlPanelMenu menu&&menu.containerId==p.menu&&menu.stillValid(player))menu.browse(p.controllers,p.devices);});
        r.playToClient(View.TYPE,View.CODEC,(p,c)->{var player=c.player();if(player.containerMenu instanceof ControlPanelMenu menu&&menu.containerId==p.menu&&menu.panel().equals(p.panel))menu.receive(p.data);});
    }
    public static void send(ServerPlayer p,int menu,UUID panel,CompoundTag data){PacketDistributor.sendToPlayer(p,new View(menu,panel,data));}
    public static void edit(int menu,UUID panel,long revision,ControlSettings settings){PacketDistributor.sendToServer(new Edit(menu,panel,revision,settings));}
    public static void browse(int menu,int controllers,int devices){PacketDistributor.sendToServer(new Browse(menu,controllers,devices));}
}
