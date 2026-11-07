package com.wormzjl.icheme.runtime.power;

import com.wormzjl.icheme.runtime.control.*;
import com.wormzjl.icheme.science.equipment.*;
import net.minecraft.core.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.energy.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PowerGridTest {
    private static final String DIM="minecraft:overworld";
    private record Rig(PowerGrid grid,DataBus.Site site,Map<PowerGrid.Address,IEnergyStorage> stores,List<PowerGrid.Address> addresses){}
    private static Rig rig(IEnergyStorage source,IEnergyStorage first,IEnergyStorage second){
        var ports=new HashMap<UUID,DataPort>();var wires=new ArrayList<DataBus.Cable>();var stores=new HashMap<PowerGrid.Address,IEnergyStorage>();var addresses=new ArrayList<PowerGrid.Address>();
        for(int i=0;i<3;i++){
            var cell=new BlockPos(i,0,0);var face=i==0?Direction.WEST:Direction.EAST;var id=new UUID(0,i+1);
            var p=new DataPort(id,i==0?DataPort.Kind.POWER_OUTPUT:DataPort.Kind.POWER_INPUT,0,DIM,cell.relative(face),cell.getCenter(),cell,face,0,0);ports.put(id,p);
            wires.add(new DataBus.Cable(new DataBus.Site(DIM,cell,0),new ConduitGeometry.Key(48,0,CableMedium.POWER),0,Map.of(face,id)));
            var address=new PowerGrid.Address(DIM,p.owner(),face.getOpposite());addresses.add(address);stores.put(address,i==0?source:i==1?first:second);
        }
        return new Rig(new PowerGrid(new DataBus(wires,ports,CableMedium.POWER),ports,stores::get),wires.getFirst().site(),stores,addresses);
    }
    @Test void aFiniteSourceConservesFeAcrossMultipleLoadsAndPortDirections(){
        var source=new EnergyStorage(1000,0,1000,500);var first=new EnergyStorage(300,1000,0);var second=new EnergyStorage(300,1000,0);var r=rig(source,first,second);
        r.grid().tick();assertEquals(500,first.getEnergyStored()+second.getEnergyStored());assertEquals(0,source.getEnergyStored());
        assertTrue(r.grid().available(r.site(),true));assertTrue(r.grid().available(r.site(),false));
        assertEquals(500,r.grid().total(r.site(),false));assertEquals(1600,r.grid().total(r.site(),true));
        assertEquals(0,r.grid().transfer(r.site(),100,false,false),"receive-only loads cannot feed the cable");
    }
    @Test void simulationIsReadOnlyAndRemovedOrReplacedCapabilitiesAreObservedImmediately(){
        var source=new EnergyStorage(1000,0,1000,400);var a=new EnergyStorage(200,1000,0);var b=new EnergyStorage(200,1000,0);var r=rig(source,a,b);
        assertEquals(300,r.grid().transfer(r.site(),300,true,true));assertEquals(400,r.grid().total(r.site(),false));
        assertEquals(300,r.grid().transfer(r.site(),300,true,false));assertEquals(400,source.getEnergyStored());
        r.stores().remove(r.addresses().get(1));r.grid().tick();assertEquals(0,a.getEnergyStored());assertEquals(200,b.getEnergyStored());assertEquals(200,source.getEnergyStored());
        var replacement=new EnergyStorage(200,1000,0);r.stores().put(r.addresses().get(1),replacement);r.grid().tick();
        assertEquals(0,a.getEnergyStored());assertEquals(200,replacement.getEnergyStored());assertEquals(0,source.getEnergyStored());
    }
    @Test void aFullConsumerStillSeesItsLiveSourceWithoutAFeTransfer(){
        var source=new EnergyStorage(1000,0,1000,400);var a=new EnergyStorage(200,1000,0,200);var b=new EnergyStorage(200,1000,0,200);var r=rig(source,a,b);
        var consumer=new UUID(0,2);var producer=new UUID(0,1);
        assertTrue(r.grid().supplied(consumer));assertFalse(r.grid().supplied(producer),"A terminal cannot count itself as its own feed");
        assertFalse(r.grid().supplied(new UUID(0,99)));
        r.grid().tick();assertEquals(400,source.getEnergyStored());assertEquals(200,a.getEnergyStored());
        assertTrue(r.grid().supplied(consumer),"A full idle load still has a live circuit");
        assertEquals(400,source.getEnergyStored(),"Checking availability never extracts energy");
        r.stores().remove(r.addresses().getFirst());assertFalse(r.grid().supplied(consumer),"Source removal is observed without waiting for a failed transfer");
        r.stores().put(r.addresses().getFirst(),new EnergyStorage(1000,0,1000));assertFalse(r.grid().supplied(consumer),"An empty source cannot power the drive");
        r.stores().put(r.addresses().getFirst(),source);assertTrue(r.grid().supplied(consumer));
    }
    @Test void aCapabilityCallingBackIntoTheGridCannotRecurseOrDuplicateEnergy(){
        var grid=new PowerGrid[1];var backing=new EnergyStorage(100,100,0);
        IEnergyStorage callback=new IEnergyStorage(){
            @Override public int receiveEnergy(int amount,boolean simulate){assertFalse(grid[0].available(new DataBus.Site(DIM,BlockPos.ZERO,0),true));return backing.receiveEnergy(amount,simulate);}
            @Override public int extractEnergy(int amount,boolean simulate){return 0;}
            @Override public int getEnergyStored(){return backing.getEnergyStored();}
            @Override public int getMaxEnergyStored(){return 100;}
            @Override public boolean canExtract(){return false;}
            @Override public boolean canReceive(){return true;}
        };
        var source=new EnergyStorage(100,0,100,100);var r=rig(source,callback,new EnergyStorage(0));grid[0]=r.grid();r.grid().tick();
        assertEquals(100,backing.getEnergyStored());assertEquals(0,source.getEnergyStored());
    }

    @Test void anUnpoweredBusDoesNotProbeEveryLoadForEveryGenerator(){
        int generators=32,loads=64;var ports=new HashMap<UUID,DataPort>();var wires=new ArrayList<DataBus.Cable>();
        var stores=new HashMap<PowerGrid.Address,IEnergyStorage>();var probes=new int[2];
        for(int i=0;i<generators+loads;i++){
            boolean generator=i<generators;var cell=new BlockPos(i,0,0);var face=generator?Direction.WEST:Direction.EAST;var id=new UUID(0,i+1);
            var p=new DataPort(id,generator?DataPort.Kind.POWER_OUTPUT:DataPort.Kind.POWER_INPUT,0,DIM,cell.relative(face),cell.getCenter(),cell,face,0,0);ports.put(id,p);
            wires.add(new DataBus.Cable(new DataBus.Site(DIM,cell,0),new ConduitGeometry.Key(48,0,CableMedium.POWER),0,Map.of(face,id)));
            stores.put(new PowerGrid.Address(DIM,p.owner(),face.getOpposite()),generator?new EnergyStorage(100,0,100){
                @Override public int extractEnergy(int amount,boolean simulate){probes[0]++;return super.extractEnergy(amount,simulate);}
            }:new EnergyStorage(100,100,0){@Override public boolean canReceive(){probes[1]++;return true;}});
        }
        var grid=new PowerGrid(new DataBus(wires,ports,CableMedium.POWER),ports,stores::get);grid.tick();
        assertEquals(generators,probes[0]);assertEquals(0,probes[1],"no supply means no demand-side FE probes");
    }
}
