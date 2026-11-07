package com.wormzjl.icheme.world.level.block;

import com.wormzjl.icheme.network.LineNetwork;
import com.wormzjl.icheme.runtime.control.*;
import com.wormzjl.icheme.science.equipment.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Identical structural contracts for all media; no process solver or player world is involved. */
class LineContractTest {
    private static final UUID ID=new UUID(0,71);
    private static final LineTarget TARGET=new LineTarget(null,BlockPos.ZERO,Direction.EAST,new Vec3(.5,.5,.5),0);
    @Test void everyMediumUsesTheSameBoundedCorrelatedCommandCodec(){
        for(var profile:LineProfile.values())for(var action:LineLaying.Action.values()){
            var path=action==LineLaying.Action.LAY?List.of(BlockPos.ZERO):List.<BlockPos>of();
            var request=new LineLaying.Request(ID,true,profile,action,TARGET,TARGET,path,path.isEmpty()?List.of():List.of(-1));
            var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
            try{LineNetwork.Command.CODEC.encode(buffer,new LineNetwork.Command(request));assertEquals(request,LineNetwork.Command.CODEC.decode(buffer).request());assertEquals(0,buffer.readableBytes());}finally{buffer.release();}
            assertThrows(IllegalArgumentException.class,()->new LineLaying.Request(ID,false,profile,LineLaying.Action.ATTACH,TARGET,new LineTarget(null,new BlockPos(1,0,0),Direction.EAST,new Vec3(.5,.5,.5),0),List.of(),List.of()));
            assertThrows(IllegalArgumentException.class,()->new LineLaying.Request(ID,false,profile,LineLaying.Action.LAY,TARGET,TARGET,List.of(BlockPos.ZERO),List.of(RackGeometry.OPTIONS)));
            assertThrows(IllegalArgumentException.class,()->new LineLaying.Request(ID,false,profile,LineLaying.Action.LAY,TARGET,TARGET,Collections.nCopies(129,BlockPos.ZERO),Collections.nCopies(129,-1)));
        }
        assertThrows(IllegalArgumentException.class,()->new LineTarget(null,BlockPos.ZERO,Direction.UP,new Vec3(Double.NaN,.5,.5),0));
        assertThrows(IllegalArgumentException.class,()->new LineTarget(null,BlockPos.ZERO,Direction.UP,new Vec3(.5,.5,.5),RackGeometry.OPTIONS+1));
    }
    @Test void bothEndsMergeBeforeTheSharedHostLimitIsChecked(){
        for(var profile:LineProfile.values()){
            var host=host(profile,0,48);
            var changes=LineLayout.mergeHosts(List.of(new LineLayout.Connection(host,0,3),new LineLayout.Connection(host,2,29)));
            assertNull(changes.refusal());assertEquals(1,changes.edits().size());var edit=changes.edits().getFirst();
            assertEquals(53,edit.joints());assertEquals(5,edit.changedFaces());assertEquals(3,edit.points()&1023);assertEquals(29,edit.points()>>20&1023);
            var full=host(profile,0,49);
            assertNotNull(LineLayout.mergeHosts(List.of(new LineLayout.Connection(full,2,0),new LineLayout.Connection(full,3,0))).refusal());
            assertEquals(49,full.joints());
            var rack=host(profile,1,16);
            assertEquals(48,LineLayout.mergeHosts(List.of(new LineLayout.Connection(rack,5,0))).edits().getFirst().joints());
            assertNotNull(LineLayout.mergeHosts(List.of(new LineLayout.Connection(rack,5,0),new LineLayout.Connection(rack,1,0))).refusal());
        }
        var automatic=new LineBackend.Host(BlockPos.ZERO,0,1,.05,63,0,0,5,63,true,true,false,true);
        assertEquals(63,LineLayout.mergeHosts(List.of(new LineLayout.Connection(automatic,1,0))).edits().getFirst().joints());
    }
    @Test void physicalLayoutKeepsOffCentreEndsAndRetainedHostPointsInEveryMedium(){
        for(var profile:LineProfile.values()){
            var first=new PipeAnchor(PipeAnchor.Kind.PIPE,BlockPos.ZERO,Direction.WEST,new BlockPos(-1,0,0),0,-1,Direction.EAST,35,PipeAnchor.Fit.ANY,null);
            var last=new PipeAnchor(PipeAnchor.Kind.FREE,new BlockPos(2,0,0),null,null,0,-1,Direction.WEST,-1,PipeAnchor.Fit.ANY,null);
            var host=new LineBackend.Host(new BlockPos(-1,0,0),0,1,.1,48,0,0,5,63,true,false,true,profile==LineProfile.FLUID);
            var route=new PipeRoute.Result(List.of(new PipeRoute.Cell(0,0,0),new PipeRoute.Cell(1,0,0),new PipeRoute.Cell(2,0,0)),List.of(-1,-1,-1),null);
            var layout=LineLayout.build(null,new LineLayout.Anchor(first,host,null,5,35),new LineLayout.Anchor(last,null,null,-1,-1),route,new LineProfile.Settings(profile,.1,.14,0,0));
            assertNull(layout.refusal());assertEquals(3,layout.layout().cells().size());assertEquals(35,layout.layout().cells().getFirst().points()>>40&1023);
            assertEquals(35,layout.layout().hosts().getFirst().points()>>50&1023);assertEquals(48,layout.layout().hosts().getFirst().joints());
            assertEquals(List.of(BlockPos.ZERO,new BlockPos(1,0,0),new BlockPos(2,0,0)),layout.layout().spatial().path());
        }
    }
    @Test void everyMediumReservesLeadCellsDuringBothSearchAndAuthoritativeReconstruction(){
        var lead=new LinePlacement.Lead(BlockPos.ZERO,new BlockPos(2,0,0),new Vec3(.8,.5,.5),.125,List.of(new Vec3(.8,.5,.5),new Vec3(2,.5,.5)));
        for(var profile:LineProfile.values()){
            var world=PipeRoute.Space.of(cell->PipeRoute.Room.FREE,List.of());var space=LinePlacement.reserve(world,List.of(lead));
            assertEquals(PipeRoute.Admit.BLOCKED,space.room(new PipeRoute.Cell(1,0,0)).admit(),profile.name());
            assertEquals(PipeRoute.Admit.FREE,space.room(new PipeRoute.Cell(2,0,0)).admit(),profile.name());
            var start=new PipeRoute.End(new PipeRoute.Cell(0,1,0),-1);var end=new PipeRoute.End(new PipeRoute.Cell(2,1,0),-1);
            var route=PipeRoute.search(start,5,end,.14,space,PipeRoute.Limits.DEFAULT);assertTrue(route.found());
            assertEquals(-1,PipeRoute.blocked(route.cells(),route.options(),start,5,end,.14,space));
        }
    }
    @Test void leadSelectionAndCollisionAreClippedToEveryCrossedCell(){
        var lead=new LinePlacement.Lead(BlockPos.ZERO,new BlockPos(2,0,0),new Vec3(.8,.5,.5),.125,List.of(new Vec3(.8,.5,.5),new Vec3(1.5,.5,.5),new Vec3(1.5,1.2,.5)));
        var cells=LinePieces.slices(lead);
        assertEquals(Set.of(BlockPos.ZERO,new BlockPos(1,0,0),new BlockPos(1,1,0)),cells.keySet());
        for(var part:cells.values())for(var box:part.toAabbs()){
            assertTrue(box.minX>=0&&box.minY>=0&&box.minZ>=0&&box.maxX<=1&&box.maxY<=1&&box.maxZ<=1);
        }
        assertTrue(cells.get(new BlockPos(1,0,0)).bounds().contains(new Vec3(.5,.7,.5)));
    }
    @Test void disposableLeadCopiesRoundTripAndOldCopiesDefaultToEmpty() throws Exception {
        int images=0;
        try(var files=Files.list(Path.of("src/test/resources/compat/line-pieces"))){for(var file:files.sorted().toList())for(var line:Files.readAllLines(file)){
            if(line.isBlank())continue;var image=TagParser.parseTag(line);var saved=image.getCompound("Saved");var pieces=LinePieces.load(saved);
            assertEquals(image.getInt("Expected"),pieces.size());var again=new CompoundTag();again.put("LinePieces",LinePieces.save(pieces));assertEquals(pieces,LinePieces.load(again));images++;
        }}
        assertEquals(3,images);
        var damaged=TagParser.parseTag("{LinePieces:[{Port:{},Lane:0}]}");assertTrue(LinePieces.load(damaged).isEmpty());
    }
    @Test void removingASensorSocketInvalidatesItsLeadWithoutDeletingCableFacts(){
        for(var medium:CableMedium.values()){
            var kind=medium==CableMedium.DATA?DataPort.Kind.SENSOR:DataPort.Kind.POWER_INPUT;
            var p=new DataPort(ID,kind,medium==CableMedium.DATA?7:0,"minecraft:overworld",new BlockPos(-1,0,0),new Vec3(-.1,.5,.5),BlockPos.ZERO,Direction.WEST,0,0);
            var cable=new DataBus.Cable(new DataBus.Site(p.dimension(),BlockPos.ZERO,0),new ConduitGeometry.Key(48,0,medium),0,Map.of(Direction.WEST,ID));
            var facts=new ControlFacts();var changed=new ArrayList<DataBus.Site>();facts.lineChanges(changed::add);facts.port(p);facts.cable(cable);changed.clear();facts.removePort(ID);
            assertEquals(List.of(cable.site()),changed);assertEquals(cable,facts.cable(cable.site()));assertNull(facts.port(ID));
        }
    }
    @Test void domainAdaptersCannotRegainCommandOrRouteOwnership() throws Exception {
        var root=Path.of("src/main/java/com/wormzjl/icheme");
        for(var name:List.of("FluidLineBackend","ConduitLineBackend")){
            var source=Files.readString(root.resolve("world/level/block/"+name+".java"));
            for(var forbidden:List.of("PipeRoute.search(","LinePlacement.commit(","canInteractWithBlock(","getInventory(","destroyBlock("))assertFalse(source.contains(forbidden),name+" must leave admission and geometry to the shared owner");
        }
        for(var name:List.of("PipeLayingClient","CableLayingClient")){
            var source=Files.readString(root.resolve("client/"+name+".java"));assertTrue(source.contains("LineLaying.search("));
            assertFalse(source.contains("PipeRoute.hostJoints("));assertFalse(source.contains("LinePlacement.search("));
        }
        assertFalse(Files.exists(root.resolve("world/level/block/CableLaying.java")));
    }
    private static LineBackend.Host host(LineProfile profile,int lane,int joints){return new LineBackend.Host(BlockPos.ZERO,lane,1,.125,joints,lane>0?48:0,0,5,63,true,false,true,profile==LineProfile.FLUID);}
}
