package com.wormzjl.icheme.runtime.fluid;

import com.google.gson.Gson;
import com.wormzjl.icheme.network.FluidNetwork;
import com.wormzjl.icheme.science.fluid.network.PipeTransfer;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * One-off probe of batch 2026-11-04-refinery-compounds (V2 stage 5): the largest world list the fluid-17 menu wire
 * carries under MAX_JSON, every value at the gate's worst-case width, with a view holding a pipe's 12 history pairs, its
 * contents and a heat unit's 8 ports (34 three-phase vectors). Prints the JSON lengths. Not a gate; uncommitted.
 */
class WireWidthProbe {
    @Test void widths() {
        for(int count:new int[]{65,100,103,104,105,106,107,110,119,128}) {
            double[][] n=new double[3][count];for(var phase:n)Arrays.fill(phase,1.2345678901234567e-123);
            var history=new ArrayList<PipeTransfer>();for(int i=0;i<12;i++)history.add(new PipeTransfer(i+1,new PipeTransfer.Stream(1,n,new double[]{1,2,3}),new PipeTransfer.Stream(1,n,new double[]{1,2,3})));
            var ids=new ArrayList<String>();var descriptors=new HashMap<String,com.wormzjl.icheme.science.material.MaterialName>();
            for(int i=0;i<count;i++){String id="server_"+i;ids.add(id);descriptors.put(id,new com.wormzjl.icheme.science.material.MaterialName(id,"t".repeat(128),"l".repeat(128),"lump",null,null,false));}
            double[] amounts=new double[count];Arrays.fill(amounts,1.0/count);var presets=new ArrayList<FluidPresetCatalog.Preset>();
            for(int i=0;i<32;i++)presets.add(new FluidPresetCatalog.Preset("p".repeat(120)+i,"n".repeat(128),amounts));
            var ports=new ArrayList<FluidView.Port>();for(int i=0;i<FluidView.Heat.MAXIMUM_PORTS;i++)ports.add(new FluidView.Port("PORT_"+i,300.,1e5,1,n,new double[]{1,2,3}));
            var heat=new FluidView.Heat("unit","side",true,300,1,null,null,null,null,null,null).with("none",null,"NONE",ports,null);
            var view=new FluidView(1,0,0,0,"READY",new FluidView.State(101325,298.15,1,1,new double[]{1,2,3},n,SolidInventory.EMPTY,1),0,history);
            view=view.withPipeInfo(new FluidView.PipeInfo(new PipeTransfer.Stream(1,n,new double[]{1,2,3}),List.of(),false,false,null)).withHeat(heat);
            var weights=java.util.stream.IntStream.range(0,count).mapToObj(i->0.01+i*0.002).toList();
            var data=new FluidNetwork.MenuData(TopologyCompiler.Kind.PIPE,view,new FluidNetwork.Controls(298.15,101325,.05,.000045,.01,500000,amounts),ids,presets,"",descriptors,weights);
            String fixed=new Gson().toJson(data.staticData(7)),live=new Gson().toJson(new FluidNetwork.LiveData(data.view(),data.controls(),data.message()));
            System.out.println("WIRE count="+count+" static="+fixed.length()+" live="+live.length()+" max="+FluidNetwork.MAX_JSON+(live.length()<FluidNetwork.MAX_JSON&&fixed.length()<FluidNetwork.MAX_JSON?" fits":" OVER"));
        }
    }
}
