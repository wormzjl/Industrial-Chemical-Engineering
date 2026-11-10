package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.column.operation.ColumnCycle.Account;
import com.wormzjl.icheme.science.column.operation.Port;
import com.wormzjl.icheme.science.column.operation.Setpoints;
import com.wormzjl.icheme.science.column.operation.SplitTable;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
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
 * not given yet. Both stay while the column is stopped: a station's {@code device} is then 0, and on a running column
 * where its design lost that port. {@code condensers} are the registrations of its water-cooled condenser's water sides,
 * outside the column (C6b: the column is their heat partner), one a shell in parallel (S3b, facts schema 8), or none.
 * {@code leaving}: the column was dismantled, and its unit goes once its ports have left. {@code airSurface}: the bare tube of
 * its air condenser's banks as its Start found them, m², whose fans hold the air's duty (U2, batch 2026-11-02-process-simulator,
 * facts schema 9); 0 where it has none or a save before it, whose air then takes any duty, as before. {@code utilities}: the
 * utility sides of its reboiler and of each pumparound's cooler as its Start found them (W4, batch
 * 2026-11-10-column-failure-modes, facts schema 17), each outside the column with its partner held inside; a duty with none
 * is free, as every reboiler and pumparound duty was before them (a save before the field keeps them free until its next Start).</p>
 */
public record ColumnUnit(long id,PhysicalFluidTopology.Position master,boolean running,Setpoints setpoints,List<Station> stations,Totals totals,List<Long> condensers,State state,boolean leaving,double airSurface,Utilities utilities) {
    /** The most stations a saved unit may hold. A column has at most eleven today (ColumnTrays): a feed, two steams, an off-gas, a sour water, a distillate, four draws, a bottoms. */
    public static final int MAXIMUM_STATIONS=16;
    /** The most condenser water sides: one a shell, as many as a design's condenser count allows. */
    public static final int MAXIMUM_CONDENSERS=com.wormzjl.icheme.science.equipment.ColumnComponents.MAX_UNITS;
    /** Without engine state: one solve before it runs. */
    public ColumnUnit(long id,PhysicalFluidTopology.Position master,boolean running,Setpoints setpoints,List<Station> stations,Totals totals){this(id,master,running,setpoints,stations,totals,List.of(),null,false);}
    /** Without engine state, one condenser water side or none (0). */
    public ColumnUnit(long id,PhysicalFluidTopology.Position master,boolean running,Setpoints setpoints,List<Station> stations,Totals totals,long condenser){this(id,master,running,setpoints,stations,totals,condenser==0?List.of():List.of(condenser),null,false);}
    /** Without engine state. */
    public ColumnUnit(long id,PhysicalFluidTopology.Position master,boolean running,Setpoints setpoints,List<Station> stations,Totals totals,List<Long> condensers){this(id,master,running,setpoints,stations,totals,condensers,null,false);}
    /** Without an air condenser's surface. */
    public ColumnUnit(long id,PhysicalFluidTopology.Position master,boolean running,Setpoints setpoints,List<Station> stations,Totals totals,List<Long> condensers,State state,boolean leaving){this(id,master,running,setpoints,stations,totals,condensers,state,leaving,0);}
    /** Without utility sides: the reboiler's and pumparounds' duties free. */
    public ColumnUnit(long id,PhysicalFluidTopology.Position master,boolean running,Setpoints setpoints,List<Station> stations,Totals totals,List<Long> condensers,State state,boolean leaving,double airSurface){this(id,master,running,setpoints,stations,totals,condensers,state,leaving,airSurface,Utilities.NONE);}
    public ColumnUnit {
        Objects.requireNonNull(master);Objects.requireNonNull(setpoints);Objects.requireNonNull(totals);stations=List.copyOf(stations);
        if(id<=0||master.lane()!=0||stations.size()>MAXIMUM_STATIONS)throw new IllegalArgumentException("Invalid column unit");
        var seen=new HashSet<String>();int width=-1;
        for(var s:stations) {
            if(!seen.add(s.port()+"#"+s.index()))throw new IllegalArgumentException("Duplicate column station "+s.port()+" "+s.index());
            if(!running&&s.device()!=0)throw new IllegalArgumentException("Only a running column's stations have port devices");
            int w=s.account().moles().length;if(width<0)width=w;else if(w!=width)throw new IllegalArgumentException("Column accounts on one axis");
        }
        if(width>=0&&(totals.taken().moles().length!=width||totals.produced().moles().length!=width))throw new IllegalArgumentException("Column totals on the accounts' axis");
        if(state!=null&&!running)throw new IllegalArgumentException("A stopped column has no engine state");
        condensers=List.copyOf(condensers);
        if(condensers.size()>MAXIMUM_CONDENSERS||condensers.stream().anyMatch(c->c<=0)||new HashSet<>(condensers).size()!=condensers.size())throw new IllegalArgumentException("Invalid condenser sides");
        if(!(airSurface>=0)||!Double.isFinite(airSurface))throw new IllegalArgumentException("Invalid air condenser surface");
        Objects.requireNonNull(utilities);
        // A side serves one duty: its transit is owed by one.
        var sides=new HashSet<>(condensers);for(long s:utilities.all())if(!sides.add(s))throw new IllegalArgumentException("A utility side serves one duty");
    }

    /** The first condenser water side, or 0 for none. */
    public long condenser(){return condensers.isEmpty()?0:condensers.getFirst();}

    /**
     * The utility sides of a column's heated and cooled members (W4, facts schema 17): its reboiler's (a kettle's tubes, one a
     * unit in parallel) and each pumparound's cooler's, in the pumparounds' order (each one's cooling side, one a unit), at most
     * {@link #MAXIMUM_CONDENSERS} a duty. A pumparound past the list, or with an empty list, has none.
     */
    public record Utilities(List<Long> reboiler,List<List<Long>> pumparounds) {
        /** None: every duty free. */
        public static final Utilities NONE=new Utilities(List.of(),List.of());
        public Utilities {
            reboiler=sides(reboiler);var copy=new java.util.ArrayList<List<Long>>();for(var p:pumparounds)copy.add(sides(p));pumparounds=List.copyOf(copy);
            if(pumparounds.size()>MAXIMUM_STATIONS)throw new IllegalArgumentException("Invalid pumparound utilities");
        }
        private static List<Long> sides(List<Long> sides) {
            var copy=List.copyOf(sides);
            if(copy.size()>MAXIMUM_CONDENSERS||copy.stream().anyMatch(c->c<=0)||new HashSet<>(copy).size()!=copy.size())throw new IllegalArgumentException("Invalid utility sides");
            return copy;
        }
        /** Pumparound {@code j}'s sides, or none. */
        public List<Long> pumparound(int j){return j>=0&&j<pumparounds.size()?pumparounds.get(j):List.of();}
        /** Every side, the reboiler's first. */
        public List<Long> all(){var out=new java.util.ArrayList<>(reboiler);for(var p:pumparounds)out.addAll(p);return out;}
        /** Whether no duty has a side. */
        public boolean none(){return all().isEmpty();}
    }

    /**
     * One port of the column: its kind, which one of that kind (from 0), its registered port device (0 while stopped), the
     * boundary valve it stands inside, and its account.
     */
    public record Station(Port port,int index,long device,long valve,Account account,SolidInventory solids) {
        public Station(Port port,int index,long device,long valve,Account account){this(port,index,device,valve,account,SolidInventory.EMPTY);}
        public Station {
            Objects.requireNonNull(port);Objects.requireNonNull(account);Objects.requireNonNull(solids);
            if(index<0||index>=MAXIMUM_STATIONS||device<0||valve<=0)throw new IllegalArgumentException("Invalid column station");
        }
        public Station withDevice(long value){return new Station(port,index,value,valve,account,solids);}
        public Station withAccount(Account value){return new Station(port,index,device,valve,value,solids);}
        public Station withSolids(SolidInventory value){return new Station(port,index,device,valve,account,value);}
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
    /** The stations of this unit a start with {@code next} no longer has (its design lost the port): they keep their accounts, with no port. */
    public List<Station> lost(List<Station> next) {
        var kept=new java.util.ArrayList<Station>();
        for(var s:stations)if(next.stream().noneMatch(t->t.port()==s.port()&&t.index()==s.index()))kept.add(s.withDevice(0));
        return kept;
    }
    /** These stations, running or not; a stopped column forgets its engine state. */
    public ColumnUnit withStations(boolean value,List<Station> next){return new ColumnUnit(id,master,value,setpoints,next,totals,condensers,value?state:null,leaving,airSurface,utilities);}
    public ColumnUnit withSetpoints(Setpoints value){return new ColumnUnit(id,master,running,value,stations,totals,condensers,state,leaving,airSurface,utilities);}
    public ColumnUnit withTotals(Totals value){return new ColumnUnit(id,master,running,setpoints,stations,value,condensers,state,leaving,airSurface,utilities);}
    public ColumnUnit withState(State value){return new ColumnUnit(id,master,running,setpoints,stations,totals,condensers,value,leaving,airSurface,utilities);}
    public ColumnUnit leave(){return new ColumnUnit(id,master,running,setpoints,stations,totals,condensers,state,true,airSurface,utilities);}

    /**
     * A running unit's engine state (plan section 12): its split table and the products' partial molar enthalpies (J/mol), the
     * cycles that asked and applied it, V3's condenser duty there (W), and the inlets' levels and outlets' credits the port
     * plans read through the fence. Rebuilt by the engine: a unit without it solves once before it takes anything.
     */
    public record State(SplitTable table,double[][] enthalpy,long tableTick,long applied,double solvedCondenser,List<Level> levels,List<Credit> credits,Probes probes) {
        public State(SplitTable table,double[][] enthalpy,long tableTick,long applied,double solvedCondenser,List<Level> levels,List<Credit> credits){this(table,enthalpy,tableTick,applied,solvedCondenser,levels,credits,null);}
        public State {
            Objects.requireNonNull(table);enthalpy=com.wormzjl.icheme.science.column.operation.ColumnSolve.copy(enthalpy);levels=List.copyOf(levels);credits=List.copyOf(credits);
            if(enthalpy.length!=table.products().size())throw new IllegalArgumentException("One enthalpy row per product");
            for(double[] row:enthalpy){if(row.length!=table.axis().size())throw new IllegalArgumentException("A partial enthalpy per component");for(double h:row)if(!Double.isFinite(h))throw new IllegalArgumentException("Invalid product enthalpy");}
            if(!Double.isFinite(solvedCondenser))throw new IllegalArgumentException("Invalid solved condenser duty");
        }
        @Override public double[][] enthalpy(){return com.wormzjl.icheme.science.column.operation.ColumnSolve.copy(enthalpy);}
        /** An inlet's level after the cycle at {@code tick}, mol, the rate the column demanded there, mol/s, and the account's molar mass, kg/mol. */
        public record Level(long device,long tick,double held,double rate,double molarMass) {}
        /** A cycle's product credited to an outlet at {@code tick}. */
        public record Credit(long device,long tick,Account product) {public Credit{Objects.requireNonNull(product);}}
    }
    /** Accepted solve readings: Pa, K, and kg of internal stream per mol of total feed. Rebuildable state. */
    public record Probes(double topPressure,double topTemperature,double bottomPressure,double bottomTemperature,
                         double refluxPressure,double refluxTemperature,double refluxPerFeed,double vapourPerFeed) {
        public Probes {
            for(double value:new double[]{topPressure,topTemperature,bottomPressure,bottomTemperature,refluxPressure,refluxTemperature})
                if(!(value>0)||!Double.isFinite(value))throw new IllegalArgumentException("Invalid accepted column reading");
            if(!(refluxPerFeed>=0)||!Double.isFinite(refluxPerFeed)||!(vapourPerFeed>=0)||!Double.isFinite(vapourPerFeed))throw new IllegalArgumentException("Invalid internal stream ratio");
        }
    }
}
