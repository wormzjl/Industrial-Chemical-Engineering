package com.wormzjl.icheme.runtime.power;

import net.neoforged.neoforge.energy.EnergyStorage;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EnergyTransferTest {
    @Test void receivingOnlyDebitsWhatTheLoadAccepts(){
        var source=new EnergyStorage(1000,0,120,500);var sink=new EnergyStorage(200,30,0,180);
        assertEquals(20,EnergyTransfer.move(source,sink,Integer.MAX_VALUE));
        assertEquals(480,source.getEnergyStored());assertEquals(200,sink.getEnergyStored());
        assertEquals(0,EnergyTransfer.move(source,sink,Integer.MAX_VALUE));assertEquals(480,source.getEnergyStored());
    }
    @Test void simulationAcrossMultipleStoresIsReadOnlyAndHonoursPortLimits(){
        var a=new EnergyStorage(100,25,0,10);var b=new EnergyStorage(100,40,0,95);
        assertEquals(30,EnergyTransfer.receive(List.of(a,b),100,true));
        assertEquals(10,a.getEnergyStored());assertEquals(95,b.getEnergyStored());
        assertEquals(30,EnergyTransfer.receive(List.of(a,b),100,false));
        assertEquals(35,a.getEnergyStored());assertEquals(100,b.getEnergyStored());
        var source=new EnergyStorage(100,0,15,60);
        assertEquals(15,EnergyTransfer.extract(List.of(source),100,true));assertEquals(60,source.getEnergyStored());
        assertEquals(15,EnergyTransfer.extract(List.of(source),100,false));assertEquals(45,source.getEnergyStored());
        assertEquals(0,EnergyTransfer.receive(List.of(source),100,false));assertEquals(0,EnergyTransfer.extract(List.of(a,b),100,false));
        assertEquals(0,EnergyTransfer.receive(List.of(a,b),-1,false));
    }
}
