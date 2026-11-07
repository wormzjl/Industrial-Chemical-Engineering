package com.wormzjl.icheme.runtime.power;

import net.neoforged.neoforge.energy.IEnergyStorage;
import java.util.List;

/** FE storage I/O only. Consumption and machine physics stay with ElectricStores and the process engine. */
public final class EnergyTransfer {
    private EnergyTransfer(){}
    public static int receive(List<IEnergyStorage> sinks,int amount,boolean simulate){
        int remaining=Math.max(0,amount);
        for(var sink:sinks)if(remaining>0&&sink.canReceive())remaining-=Math.clamp(sink.receiveEnergy(remaining,simulate),0,remaining);
        return Math.max(0,amount)-remaining;
    }
    public static int extract(List<IEnergyStorage> sources,int amount,boolean simulate){
        int remaining=Math.max(0,amount);
        for(var source:sources)if(remaining>0&&source.canExtract())remaining-=Math.clamp(source.extractEnergy(remaining,simulate),0,remaining);
        return Math.max(0,amount)-remaining;
    }
    public static int move(IEnergyStorage source,IEnergyStorage sink,int limit){
        if(source==sink||!source.canExtract()||!sink.canReceive())return 0;
        int offered=Math.clamp(source.extractEnergy(Math.max(0,limit),true),0,Math.max(0,limit));
        int accepted=Math.clamp(sink.receiveEnergy(offered,false),0,offered);
        if(accepted>0)source.extractEnergy(accepted,false);
        return accepted;
    }
}
