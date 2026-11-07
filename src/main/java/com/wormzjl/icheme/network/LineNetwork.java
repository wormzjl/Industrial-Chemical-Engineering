package com.wormzjl.icheme.network;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.world.level.block.*;
import com.wormzjl.icheme.science.equipment.PipeRoute;
import net.minecraft.core.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;
import java.util.*;

/** One bounded structural action protocol for every line medium. No process state is read or advanced. */
public final class LineNetwork {
    private LineNetwork(){}
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String name){return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,name));}
    public record Command(LineLaying.Request request) implements CustomPacketPayload {
        public static final Type<Command> TYPE=LineNetwork.type("line_command");
        public static final StreamCodec<RegistryFriendlyByteBuf,Command> CODEC=StreamCodec.of((b,p)->{
            var r=p.request();b.writeUUID(r.id());b.writeBoolean(r.offHand());b.writeEnum(r.profile());b.writeEnum(r.action());writeTarget(b,r.first());writeTarget(b,r.last());
            b.writeVarInt(r.path().size());for(int i=0;i<r.path().size();i++){b.writeBlockPos(r.path().get(i));b.writeVarInt(r.options().get(i));}
        },b->{
            var id=b.readUUID();boolean off=b.readBoolean();var profile=b.readEnum(LineProfile.class);var action=b.readEnum(LineLaying.Action.class);var a=readTarget(b);var z=readTarget(b);
            int count=b.readVarInt();if(count<0||count>PipeRoute.Limits.DEFAULT.cells())throw new IllegalArgumentException("Line route too long");
            var cells=new ArrayList<BlockPos>(count);var options=new ArrayList<Integer>(count);for(int i=0;i<count;i++){cells.add(b.readBlockPos());options.add(b.readVarInt());}
            return new Command(new LineLaying.Request(id,off,profile,action,a,z,cells,options));
        });
        @Override public Type<Command> type(){return TYPE;}
    }
    public record Answer(LineLaying.Result result) implements CustomPacketPayload {
        public static final Type<Answer> TYPE=LineNetwork.type("line_answer");
        public static final StreamCodec<RegistryFriendlyByteBuf,Answer> CODEC=StreamCodec.of((b,p)->{var r=p.result();b.writeUUID(r.id());b.writeBoolean(r.success());b.writeUtf(net.minecraft.network.chat.Component.Serializer.toJson(r.message(),b.registryAccess()),8192);b.writeVarInt(r.cells());},b->new Answer(new LineLaying.Result(b.readUUID(),b.readBoolean(),Objects.requireNonNull(net.minecraft.network.chat.Component.Serializer.fromJson(b.readUtf(8192),b.registryAccess())),b.readVarInt())));
        @Override public Type<Answer> type(){return TYPE;}
    }
    private static void writeTarget(RegistryFriendlyByteBuf b,LineTarget t){b.writeBoolean(t.port()!=null);if(t.port()!=null)b.writeUUID(t.port());b.writeBlockPos(t.clicked());b.writeEnum(t.face());b.writeDouble(t.local().x);b.writeDouble(t.local().y);b.writeDouble(t.local().z);b.writeVarInt(t.lane());}
    private static LineTarget readTarget(RegistryFriendlyByteBuf b){return new LineTarget(b.readBoolean()?b.readUUID():null,b.readBlockPos(),b.readEnum(Direction.class),new Vec3(b.readDouble(),b.readDouble(),b.readDouble()),b.readVarInt());}
    public static void register(RegisterPayloadHandlersEvent event){var r=event.registrar("lines-1").executesOn(HandlerThread.MAIN);
        r.playToServer(Command.TYPE,Command.CODEC,(p,c)->{if(c.player() instanceof ServerPlayer player)PacketDistributor.sendToPlayer(player,new Answer(LineLaying.execute(player,p.request())));});
        r.playToClient(Answer.TYPE,Answer.CODEC,(p,c)->{var a=p.result();com.wormzjl.icheme.client.LineToolSession.answered(a.id(),a.message(),a.success());});
    }
    public static void send(LineLaying.Request request){PacketDistributor.sendToServer(new Command(request));}
}
