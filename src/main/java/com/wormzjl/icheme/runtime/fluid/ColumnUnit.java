package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.column.operation.ColumnCycle.Account;
import com.wormzjl.icheme.science.column.operation.Port;
import com.wormzjl.icheme.science.column.operation.Setpoints;
import com.wormzjl.icheme.science.column.operation.SplitTable;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * A designed column's operation, its facts (batch 2026-10-28-column-operation, plan sections 4 and 12): the player's
 * setpoints and run switch, its ports with what each one's account holds, and its totals. Fluid facts schema 6. Its
 * {@link State} (the split table, the fence's windows) is state: the topology unit's state section carries it.
 *
 * <p>{@code id} is a registry identity (from the ledger's counter, never a device's): the devices a running column holds
 * name it ({@link PhysicalFluidTopology.Device#suspendedBy}). {@code master} is the column's origin. An inlet's account holds
 * what its port took from the network and the column has not used yet; an outlet's what the column made and its port has
 * not given yet. Both stay while the column is stopped: a station's {@code device} is then 0.</p>
 */
public record ColumnUnit(long id,PhysicalFluidTopology.Position master,boolean running,Setpoints setpoints,List<Station> stations,Totals totals,State state) {
    /** The most stations a column has: a feed, three steams, an off-gas, a sour water, a distillate, eight draws, a bottoms. */
    public static final int MAXIMUM_STATIONS=16;
    /** Without engine state: one solve before it runs. */
    public ColumnUnit(long id,PhysicalFluidTopology.Position master,boolean running,Setpoints setpoints,List<Station> stations,Totals totals){this(id,master,running,setpoints,stations,totals,null);}
    public ColumnUnit {
        Objects.requireNonNull(master);Objects.requireNonNull(setpoints);Objects.requireNonNull(totals);stations=List.copyOf(stations);
        if(id<=0||master.lane()!=0||stations.size()>MAXIMUM_STATIONS)throw new IllegalArgumentException("Invalid column unit");
        var seen=new HashSet<String>();int width=-1;
        for(var s:stations) {
            if(!seen.add(s.port()+"#"+s.index()))throw new IllegalArgumentException("Duplicate column station "+s.port()+" "+s.index());
            if(running!=(s.device()!=0))throw new IllegalArgumentException("A running column's stations, and only those, have port devices");
            int w=s.account().moles().length;if(width<0)width=w;else if(w!=width)throw new IllegalArgumentException("Column accounts on one axis");
        }
        if(width>=0&&(totals.taken().moles().length!=width||totals.produced().moles().length!=width))throw new IllegalArgumentException("Column totals on the accounts' axis");
        if(state!=null&&!running)throw new IllegalArgumentException("A stopped column has no engine state");
    }

    /**
     * One port of the column: its kind, which one of that kind (from 0), its registered port device (0 while stopped), the
     * boundary valve it stands inside, and its account.
     */
    public record Station(Port port,int index,long device,long valve,Account account) {
        public Station {
            Objects.requireNonNull(port);Objects.requireNonNull(account);
            if(index<0||index>=MAXIMUM_STATIONS||device<0||valve<=0)throw new IllegalArgumentException("Invalid column station");
        }
        public Station withDevice(long value){return new Station(port,index,value,valve,account);}
        public Station withAccount(Account value){return new Station(port,index,device,valve,value);}
    }

    /**
     * What the column took from its inlets and gave to its outlets since it was built, and the duties it booked (J into
     * the column; a condenser's is negative). The accounts hold the difference of the first two, so they audit it.
     */
    public record Totals(Account taken,Account produced,double reboiler,double condenser,double pumparounds) {
        public Totals {
            Objects.requireNonNull(taken);Objects.requireNonNull(produced);
            if(!Double.isFinite(reboiler)||!Double.isFinite(condenser)||!Double.isFinite(pumparounds))throw new IllegalArgumentException("Invalid column duties");
        }
        public static Totals empty(int components){return new Totals(Account.empty(components),Account.empty(components),0,0,0);}
    }

    /** The station of {@code port} number {@code index}, or null. */
    public Station station(Port port,int index) {
        for(var s:stations)if(s.port()==port&&s.index()==index)return s;
        return null;
    }
    /** These stations, running or not; a stopped column forgets its engine state. */
    public ColumnUnit withStations(boolean value,List<Station> next){return new ColumnUnit(id,master,value,setpoints,next,totals,value?state:null);}
    public ColumnUnit withSetpoints(Setpoints value){return new ColumnUnit(id,master,running,value,stations,totals,state);}
    public ColumnUnit withTotals(Totals value){return new ColumnUnit(id,master,running,setpoints,stations,value,state);}
    public ColumnUnit withState(State value){return new ColumnUnit(id,master,running,setpoints,stations,totals,value);}

    /**
     * A running unit's engine state (plan section 12): its split table and the products' specific enthalpies (J/mol), the
     * cycles that asked and applied it, V3's condenser duty there (W), and the inlets' levels and outlets' credits the port
     * plans read through the fence. Rebuilt by the engine: a unit without it solves once before it takes anything.
     */
    public record State(SplitTable table,double[] enthalpy,long tableTick,long applied,double solvedCondenser,List<Level> levels,List<Credit> credits) {
        public State {
            Objects.requireNonNull(table);enthalpy=enthalpy.clone();levels=List.copyOf(levels);credits=List.copyOf(credits);
            if(enthalpy.length!=table.products().size())throw new IllegalArgumentException("One enthalpy per product");
            for(double h:enthalpy)if(!Double.isFinite(h))throw new IllegalArgumentException("Invalid product enthalpy");
            if(!Double.isFinite(solvedCondenser))throw new IllegalArgumentException("Invalid solved condenser duty");
        }
        @Override public double[] enthalpy(){return enthalpy.clone();}
        /** An inlet's level after the cycle at {@code tick}, mol, the rate the column demanded there, mol/s, and the account's molar mass, kg/mol. */
        public record Level(long device,long tick,double held,double rate,double molarMass) {}
        /** A cycle's product credited to an outlet at {@code tick}. */
        public record Credit(long device,long tick,Account product) {public Credit{Objects.requireNonNull(product);}}
    }
}
