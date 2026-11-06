package com.wormzjl.icheme.network;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.runtime.control.ControlSettings;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.world.inventory.ControlPanelMenu;
import com.wormzjl.icheme.world.level.block.DataCableLaying;
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
    public record Cable(UUID request,boolean offhand,boolean remove,DataCableLaying.End first,DataCableLaying.End last,List<BlockPos> path,List<Integer> options) implements CustomPacketPayload {
        public static final Type<Cable> TYPE=ControlNetwork.type("data_cable");
        public static final StreamCodec<RegistryFriendlyByteBuf,Cable> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.request);b.writeBoolean(p.offhand);b.writeBoolean(p.remove);writeEnd(b,p.first);writeEnd(b,p.last);b.writeVarInt(p.path.size());for(int i=0;i<p.path.size();i++){b.writeBlockPos(p.path.get(i));b.writeVarInt(p.options.get(i));}},b->{var request=b.readUUID();boolean hand=b.readBoolean(),remove=b.readBoolean();var a=readEnd(b);var z=readEnd(b);int count=b.readVarInt();if(count<0||count>128)throw new IllegalArgumentException("Cable route too long");var path=new ArrayList<BlockPos>();var options=new ArrayList<Integer>();for(int i=0;i<count;i++){path.add(b.readBlockPos());options.add(b.readVarInt());}return new Cable(request,hand,remove,a,z,List.copyOf(path),List.copyOf(options));});
        @Override public Type<Cable> type(){return TYPE;}
    }
    public record Answer(UUID request,boolean success,String detail) implements CustomPacketPayload {
        public static final Type<Answer> TYPE=ControlNetwork.type("data_answer");public static final StreamCodec<RegistryFriendlyByteBuf,Answer> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.request);b.writeBoolean(p.success);b.writeUtf(p.detail,1024);},b->new Answer(b.readUUID(),b.readBoolean(),b.readUtf(1024)));@Override public Type<Answer> type(){return TYPE;}
    }
    public record Edit(int menu,UUID panel,long revision,ControlSettings settings) implements CustomPacketPayload {
        public static final Type<Edit> TYPE=ControlNetwork.type("control_edit");public static final StreamCodec<RegistryFriendlyByteBuf,Edit> CODEC=StreamCodec.of((b,p)->{b.writeVarInt(p.menu);b.writeUUID(p.panel);b.writeLong(p.revision);b.writeNbt(p.settings.save());},b->new Edit(b.readVarInt(),b.readUUID(),b.readLong(),ControlSettings.load(Objects.requireNonNull(b.readNbt()))));@Override public Type<Edit> type(){return TYPE;}
    }
    public record View(int menu,UUID panel,CompoundTag data) implements CustomPacketPayload {
        public static final Type<View> TYPE=ControlNetwork.type("control_view");public static final StreamCodec<RegistryFriendlyByteBuf,View> CODEC=StreamCodec.of((b,p)->{b.writeVarInt(p.menu);b.writeUUID(p.panel);b.writeNbt(p.data);},b->new View(b.readVarInt(),b.readUUID(),Objects.requireNonNull(b.readNbt())));@Override public Type<View> type(){return TYPE;}
    }
    public record Browse(int menu,int controllers,int devices) implements CustomPacketPayload {
        public static final Type<Browse> TYPE=ControlNetwork.type("control_browse");public static final StreamCodec<RegistryFriendlyByteBuf,Browse> CODEC=StreamCodec.of((b,p)->{b.writeVarInt(p.menu);b.writeVarInt(p.controllers);b.writeVarInt(p.devices);},b->new Browse(b.readVarInt(),b.readVarInt(),b.readVarInt()));@Override public Type<Browse> type(){return TYPE;}
    }
    private static void writeEnd(RegistryFriendlyByteBuf b,DataCableLaying.End e){b.writeBoolean(e.port()!=null);if(e.port()!=null)b.writeUUID(e.port());b.writeBlockPos(e.clicked());b.writeEnum(e.face());b.writeDouble(e.local().x);b.writeDouble(e.local().y);b.writeDouble(e.local().z);b.writeVarInt(e.lane());}
    private static DataCableLaying.End readEnd(RegistryFriendlyByteBuf b){var id=b.readBoolean()?b.readUUID():null;return new DataCableLaying.End(id,b.readBlockPos(),b.readEnum(Direction.class),new Vec3(b.readDouble(),b.readDouble(),b.readDouble()),b.readVarInt());}
    public static void register(RegisterPayloadHandlersEvent event){var r=event.registrar("control-1").executesOn(HandlerThread.MAIN);
        r.playToServer(Cable.TYPE,Cable.CODEC,(p,c)->{if(!(c.player() instanceof ServerPlayer player))return;var hand=p.offhand?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND;var refusal=p.remove?DataCableLaying.dismantle(player,hand,p.first):DataCableLaying.lay(player,hand,p.first,p.last,p.path,p.options);PacketDistributor.sendToPlayer(player,new Answer(p.request,refusal==null,refusal==null?"":refusal.getString()));});
        r.playToServer(Edit.TYPE,Edit.CODEC,(p,c)->{if(c.player() instanceof ServerPlayer player&&player.containerMenu instanceof ControlPanelMenu menu&&menu.containerId==p.menu&&menu.stillValid(player))FluidWorldAuthority.find(player.server).ifPresent(w->w.controls().edit(menu,p.panel,p.revision,p.settings));});
        r.playToServer(Browse.TYPE,Browse.CODEC,(p,c)->{if(c.player() instanceof ServerPlayer player&&player.containerMenu instanceof ControlPanelMenu menu&&menu.containerId==p.menu&&menu.stillValid(player))menu.browse(p.controllers,p.devices);});
        r.playToClient(Answer.TYPE,Answer.CODEC,(p,c)->com.wormzjl.icheme.client.DataCableLayingClient.answered(p.request,p.success,p.detail));
        r.playToClient(View.TYPE,View.CODEC,(p,c)->{var player=c.player();if(player.containerMenu instanceof ControlPanelMenu menu&&menu.containerId==p.menu&&menu.panel().equals(p.panel))menu.receive(p.data);});
    }
    public static void send(ServerPlayer p,int menu,UUID panel,CompoundTag data){PacketDistributor.sendToPlayer(p,new View(menu,panel,data));}
    public static void edit(int menu,UUID panel,long revision,ControlSettings settings){PacketDistributor.sendToServer(new Edit(menu,panel,revision,settings));}
    public static void browse(int menu,int controllers,int devices){PacketDistributor.sendToServer(new Browse(menu,controllers,devices));}
    public static UUID cable(boolean off,boolean remove,DataCableLaying.End a,DataCableLaying.End b,List<BlockPos> cells,List<Integer> options){var id=UUID.randomUUID();PacketDistributor.sendToServer(new Cable(id,off,remove,a,b,cells,options));return id;}
}
