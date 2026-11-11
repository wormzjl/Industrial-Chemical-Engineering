package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.PipeTransfer;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.network.InlineFilter;

/** Read-only presentation of a committed interval; a null state denotes a zero-holdup pipe segment. */
public record FluidView(long identity,long inputRevision,long committedTick,long onlineTick,String status,State state,
                        double massFlow,List<PipeTransfer> pipeHistory,Double devicePressureChange,
                        double intervalSeconds,List<PipeRoute> pipeRoutes,InlineFilter filter,PipeInfo pipeInfo,
                        List<Outlet> outlets,Tank tank,Heat heat,Tower tower,Drive drive,Power power,Pump pump,Flare flare,Still still,Chimney chimney) {
    /** Every field but a chimney's. */
    public FluidView(long identity,long inputRevision,long committedTick,long onlineTick,String status,State state,double massFlow,List<PipeTransfer> pipeHistory,Double devicePressureChange,
                     double intervalSeconds,List<PipeRoute> pipeRoutes,InlineFilter filter,PipeInfo pipeInfo,List<Outlet> outlets,Tank tank,Heat heat,Tower tower,Drive drive,Power power,Pump pump,Flare flare,Still still) {
        this(identity,inputRevision,committedTick,onlineTick,status,state,massFlow,pipeHistory,devicePressureChange,intervalSeconds,pipeRoutes,filter,pipeInfo,outlets,tank,heat,tower,drive,power,pump,flare,still,null);
    }
    public FluidView withChimney(Chimney value){return new FluidView(identity,inputRevision,committedTick,onlineTick,status,state,massFlow,pipeHistory,devicePressureChange,intervalSeconds,pipeRoutes,filter,pipeInfo,outlets,tank,heat,tower,drive,power,pump,flare,still,value);}
    /**
     * A chimney (batch 2026-11-10-chimney): the mean mass flow it released (kg/s), the solids it carried (kg/s), the gas's
     * temperature (K), its exit velocity (m/s), the smoke's opacity (0 to 1), the draft of its stack (Pa), its exit diameter
     * (m) and its status by name ({@code Chimney.Status}). Null for a device that is no chimney.
     */
    public record Chimney(double massFlow,double solids,double temperature,double exitVelocity,double opacity,double draft,double diameter,String status) {
        public Chimney {
            Objects.requireNonNull(status);
            if(!(massFlow>=0)||!(solids>=0)||!(temperature>0)||!(exitVelocity>=0)||!(opacity>=0&&opacity<=1)||!(diameter>0)||!Double.isFinite(massFlow+solids+temperature+exitVelocity+draft+diameter)||status.length()>32)
                throw new IllegalArgumentException("Invalid chimney presentation");
        }
    }
    /**
     * A pump's readings (batch 2026-11-03-rotating-machinery W10d, owner decision D37), from its last committed suction read
     * and its health: NPSH available and NPSH required (NPSH3 at the accepted flow and speed; an in-line pump's at its D21
     * duty speed), m, and the suction's vapour volume share, 0 to 1, each null where the slice made no read; its health, 0
     * to 1; a constructed centrifugal pump's design for its curve, null for an in-line pump; its life, the play seconds until
     * it fails at its present wear (batch 2026-11-11-minecraft-time-delta, W5), null while it does not wear. Null for a device
     * that is no pump.
     */
    public record Pump(Double npshAvailable,Double npshRequired,Double gasShare,double health,Curve curve,Double lifePlaySeconds) {
        public Pump {
            if(npshAvailable!=null&&!Double.isFinite(npshAvailable)||npshRequired!=null&&!(npshRequired>=0&&Double.isFinite(npshRequired))
                    ||gasShare!=null&&!(gasShare>=0&&gasShare<=1)||!(health>=0&&health<=1)||lifePlaySeconds!=null&&!(lifePlaySeconds>=0&&Double.isFinite(lifePlaySeconds)))
                throw new IllegalArgumentException("Invalid pump presentation");
        }
        public Pump(Double npshAvailable,Double npshRequired,Double gasShare,double health,Curve curve){this(npshAvailable,npshRequired,gasShare,health,curve,null);}
    }
    /** A constructed centrifugal pump's design ({@code CentrifugalPump}): rated suction flow (m³/s), head (m), efficiency and speed (rpm). Static: its casing's duty. */
    public record Curve(double ratedFlow,double ratedHead,double efficiency,double ratedSpeed) {
        public Curve {
            if(!(ratedFlow>0)||!(ratedHead>0)||!(efficiency>0&&efficiency<=1)||!(ratedSpeed>0)||!Double.isFinite(ratedFlow+ratedHead+ratedSpeed))throw new IllegalArgumentException("Invalid pump curve presentation");
        }
    }
    public record Tower(com.wormzjl.icheme.science.fluid.network.CoolingTower specification,com.wormzjl.icheme.science.fluid.network.TowerExchange.Rates rates) {}
    /**
     * A casing's driver (batch 2026-11-03-rotating-machinery, W7, option A's Drive group): the driver's kind by name
     * ({@code MOTOR}, {@code STEAM_TURBINE}, {@code CREATIVE_MOTOR}; empty: {@code NO DRIVER}), its rating (W) and a motor's
     * poles, its status word and speed (rpm), whether it runs at a fixed speed (a fixed motor: the flow follows the line), the
     * casing's speed share and the shaft power it takes (W). Null for a device that is not a casing.
     */
    public record Drive(String driver,double ratedPower,int poles,String status,double speed,boolean fixed,double share,double shaftPower) {
        public Drive {
            Objects.requireNonNull(driver);Objects.requireNonNull(status);
            if(driver.length()>32||status.length()>256||!(ratedPower>=0)||!(speed>=0)||!(share>=0)||!Double.isFinite(ratedPower+speed+share+shaftPower)||poles<0)
                throw new IllegalArgumentException("Invalid drive presentation");
        }
    }
    /**
     * A fan unit's power (W5, reading A68; W7, reading A86): its electric draw (W) and FE per tick, its store's share full
     * (null for none), and its status word on the display ({@code NO POWER}, the notice {@code UNMETERED}, or empty). Null
     * for a device without fans.
     */
    /**
     * A fired heater's burners as its coils' pages show them (batch 2026-11-09-furnace-fuel-power): the fuel's mass flow
     * into the firebox (kg/s) and its lower heating value (J/kg), the heat fired and asked for (W), the combustion-air fans'
     * electric draw (W), and the alarms raised, by their status words.
     */
    public record Fuel(double massFlow,double heatingValue,double firedWatt,double askedWatt,double fanWatt,List<String> alarms) {
        public Fuel {
            alarms=List.copyOf(alarms);
            if(!(massFlow>=0)||!(heatingValue>=0)||!(firedWatt>=0)||!(askedWatt>=0)||!(fanWatt>=0)||!Double.isFinite(massFlow+heatingValue+firedWatt+askedWatt+fanWatt)||alarms.size()>4)
                throw new IllegalArgumentException("Invalid fuel presentation");
        }
    }
    public record Power(double draw,double perTick,Double store,String status) {
        public Power {
            Objects.requireNonNull(status);
            if(!(draw>=0)||!(perTick>=0)||!Double.isFinite(draw+perTick)||store!=null&&!(store>=0&&store<=1)||status.length()>32)throw new IllegalArgumentException("Invalid power presentation");
        }
    }
    /** Every field but a casing's drive and a fan unit's power. */
    public FluidView(long identity,long inputRevision,long committedTick,long onlineTick,String status,State state,double massFlow,List<PipeTransfer> pipeHistory,Double devicePressureChange,
                     double intervalSeconds,List<PipeRoute> pipeRoutes,InlineFilter filter,PipeInfo pipeInfo,List<Outlet> outlets,Tank tank,Heat heat,Tower tower) {
        this(identity,inputRevision,committedTick,onlineTick,status,state,massFlow,pipeHistory,devicePressureChange,intervalSeconds,pipeRoutes,filter,pipeInfo,outlets,tank,heat,tower,null,null,null,null,null);
    }
    public FluidView withDrive(Drive value){return new FluidView(identity,inputRevision,committedTick,onlineTick,status,state,massFlow,pipeHistory,devicePressureChange,intervalSeconds,pipeRoutes,filter,pipeInfo,outlets,tank,heat,tower,value,power,pump,flare,still,chimney);}
    public FluidView withPower(Power value){return new FluidView(identity,inputRevision,committedTick,onlineTick,status,state,massFlow,pipeHistory,devicePressureChange,intervalSeconds,pipeRoutes,filter,pipeInfo,outlets,tank,heat,tower,drive,value,pump,flare,still,chimney);}
    public FluidView withTower(Tower value){return new FluidView(identity,inputRevision,committedTick,onlineTick,status,state,massFlow,pipeHistory,devicePressureChange,intervalSeconds,pipeRoutes,filter,pipeInfo,outlets,tank,heat,value,drive,power,pump,flare,still,chimney);}
    public FluidView withPump(Pump value){return new FluidView(identity,inputRevision,committedTick,onlineTick,status,state,massFlow,pipeHistory,devicePressureChange,intervalSeconds,pipeRoutes,filter,pipeInfo,outlets,tank,heat,tower,drive,power,value,flare);}
    public FluidView withStatus(String value){return new FluidView(identity,inputRevision,committedTick,onlineTick,value,state,massFlow,pipeHistory,devicePressureChange,intervalSeconds,pipeRoutes,filter,pipeInfo,outlets,tank,heat,tower,drive,power,pump,flare,still,chimney);}
    /** Every field but a flare's. */
    public FluidView(long identity,long inputRevision,long committedTick,long onlineTick,String status,State state,double massFlow,List<PipeTransfer> pipeHistory,Double devicePressureChange,
                     double intervalSeconds,List<PipeRoute> pipeRoutes,InlineFilter filter,PipeInfo pipeInfo,List<Outlet> outlets,Tank tank,Heat heat,Tower tower,Drive drive,Power power,Pump pump) {
        this(identity,inputRevision,committedTick,onlineTick,status,state,massFlow,pipeHistory,devicePressureChange,intervalSeconds,pipeRoutes,filter,pipeInfo,outlets,tank,heat,tower,drive,power,pump,null,null);
    }
    /** Every field but a batch still's. */
    public FluidView(long identity,long inputRevision,long committedTick,long onlineTick,String status,State state,double massFlow,List<PipeTransfer> pipeHistory,Double devicePressureChange,
                     double intervalSeconds,List<PipeRoute> pipeRoutes,InlineFilter filter,PipeInfo pipeInfo,List<Outlet> outlets,Tank tank,Heat heat,Tower tower,Drive drive,Power power,Pump pump,Flare flare) {
        this(identity,inputRevision,committedTick,onlineTick,status,state,massFlow,pipeHistory,devicePressureChange,intervalSeconds,pipeRoutes,filter,pipeInfo,outlets,tank,heat,tower,drive,power,pump,flare,null);
    }
    public FluidView withStill(Still value){return new FluidView(identity,inputRevision,committedTick,onlineTick,status,state,massFlow,pipeHistory,devicePressureChange,intervalSeconds,pipeRoutes,filter,pipeInfo,outlets,tank,heat,tower,drive,power,pump,flare,value,chimney);}
    /**
     * A batch still's pot (batch 2026-11-09-batch-distiller, W6), as its page shows it, from the engine's cached view
     * ({@code Stills.view}) and the still's facts: its heater by name ({@code SOLID}, {@code LIQUID}, {@code ELECTRIC}), its
     * column's stages (0 for none), whether it has its built-in condenser, its rating (heat into the pot at full firing, W);
     * the player's settings, the firing (0 to 1) and the reflux valve (its ratio, or closed); what a data-network controller
     * holds instead (null where the setting rules; {@code heldClosed} for a controller that closes the valve); the readings:
     * the pot's and the head's temperatures (K, null for none yet), the liquid level (m) and its share of the pot, the heat
     * into the pot (W), the distillate and vent flows (kg/s), the condenser's share, the reflux the column runs at (null for
     * no column, or total reflux); the status word and the alarms' words; a solid still's fuel slot: the item's id (empty for
     * none), its count, one item's heat (J), and the heat left in the burning item (J). No process state is inferred from it.
     */
    public record Still(String heater,int stages,boolean condenser,double rating,double firing,double reflux,boolean closed,
                        Double heldFiring,Double heldReflux,boolean heldClosed,
                        Double potTemperature,Double headTemperature,double level,double filled,double heat,double distillate,double vent,double condensed,Double effectiveReflux,
                        String status,List<String> alarms,String fuel,int fuelCount,double fuelEnergy,double burning) {
        public Still {
            Objects.requireNonNull(heater);Objects.requireNonNull(status);Objects.requireNonNull(fuel);alarms=List.copyOf(alarms);
            if(heater.length()>16||status.length()>32||fuel.length()>128||alarms.size()>8||stages<0||stages>16||fuelCount<0||fuelCount>64
                    ||!(rating>0)||!(firing>=0&&firing<=1)||!(reflux>=0)||!(level>=0)||!(filled>=0)||!(heat>=0)||!(distillate>=0)||!(vent>=0)||!(condensed>=0&&condensed<=1)||!(fuelEnergy>=0)||!(burning>=0)
                    ||!Double.isFinite(rating+reflux+level+filled+heat+distillate+vent+fuelEnergy+burning)
                    ||heldFiring!=null&&!(heldFiring>=0&&heldFiring<=1)||heldReflux!=null&&!(heldReflux>=0&&Double.isFinite(heldReflux))
                    ||potTemperature!=null&&!(potTemperature>0&&Double.isFinite(potTemperature))||headTemperature!=null&&!(headTemperature>0&&Double.isFinite(headTemperature))
                    ||effectiveReflux!=null&&!(effectiveReflux>=0&&Double.isFinite(effectiveReflux)))
                throw new IllegalArgumentException("Invalid still presentation");
            for(var a:alarms)if(a.length()>32)throw new IllegalArgumentException("Invalid still alarm");
        }
        /** Whether a data-network controller holds the firing or the reflux valve. */
        public boolean held(){return heldFiring!=null||heldReflux!=null||heldClosed;}
    }
    public FluidView withFlare(Flare value){return new FluidView(identity,inputRevision,committedTick,onlineTick,status,state,massFlow,pipeHistory,devicePressureChange,intervalSeconds,pipeRoutes,filter,pipeInfo,outlets,tank,heat,tower,drive,power,pump,value,still,chimney);}
    /**
     * A flare stack (batch 2026-11-09-flare-stack): the mean mass flow it took (kg/s), the heat it released (W), the gas's
     * net heating value (J per standard m³) and stoichiometric flame temperature (K), the most it passes of that gas now
     * (kg/s), its rated heat release (W), its tip diameter (m) and its status by name ({@code Flares.Status}). Null for a
     * device that is no flare.
     */
    public record Flare(double massFlow,double heat,double heatingValue,double flameTemperature,double limit,double rated,double tip,String status) {
        public Flare {
            Objects.requireNonNull(status);
            if(!(massFlow>=0)||!(heat>=0)||!(heatingValue>=0)||!(flameTemperature>0)||!(limit>=0)||!(rated>0)||!(tip>0)||!Double.isFinite(massFlow+heat+heatingValue+flameTemperature+limit+rated+tip)||status.length()>32)
                throw new IllegalArgumentException("Invalid flare presentation");
        }
    }
    /** A tank has six faces and each face one neighbour, so it has at most six connections. */
    public static final int MAXIMUM_OUTLETS=6;
    /** Source-side convenience for devices without pipe inspection, not a wire-format fallback. */
    public FluidView(long identity,long inputRevision,long committedTick,long onlineTick,String status,State state,double massFlow,List<PipeTransfer> pipeHistory,Double devicePressureChange,double intervalSeconds,List<PipeRoute> pipeRoutes,InlineFilter filter){
        this(identity,inputRevision,committedTick,onlineTick,status,state,massFlow,pipeHistory,devicePressureChange,intervalSeconds,pipeRoutes,filter,null,List.of(),null,null,null,null,null,null,null,null);
    }
    public FluidView withPipeInfo(PipeInfo info){
        return withPipeInfo(info,intervalSeconds);
    }
    public FluidView withPipeInfo(PipeInfo info,double seconds){
        return new FluidView(identity,inputRevision,committedTick,onlineTick,status,state,massFlow,pipeHistory,devicePressureChange,seconds,pipeRoutes,filter,info,outlets,tank,heat,tower,drive,power,pump,flare,still,chimney);
    }
    public FluidView withOutlets(List<Outlet> list){
        return new FluidView(identity,inputRevision,committedTick,onlineTick,status,state,massFlow,pipeHistory,devicePressureChange,intervalSeconds,pipeRoutes,filter,pipeInfo,list,tank,heat,tower,drive,power,pump,flare,still,chimney);
    }
    public FluidView withTank(Tank value){
        return new FluidView(identity,inputRevision,committedTick,onlineTick,status,state,massFlow,pipeHistory,devicePressureChange,intervalSeconds,pipeRoutes,filter,pipeInfo,outlets,value,heat,tower,drive,power,pump,flare,still,chimney);
    }
    /**
     * A vessel's own geometry and what it gave the atmosphere over the last completed interval (batch
     * 2026-10-21-floating-roof-tanks, {@code fluid-11}): the height its level head stands over (its fill height, for an
     * open tank), whether it is open under a floating roof, and its vent and spill (kg/s, from its VENT and SPILL boundary
     * transfers). fluid-14 adds capacity and top/bottom absolute pressure. Both pressures are null only while empty;
     * capacity/geometry are still known. Null Tank for a node that is not a vessel.
     */
    public record Tank(double levelHeight,boolean open,double ventKgPerSecond,double spillKgPerSecond,
                       double capacity,Double topPressure,Double bottomPressure,com.wormzjl.icheme.science.fluid.network.VesselGeometry geometry) {
        public Tank(double levelHeight,boolean open,double ventKgPerSecond,double spillKgPerSecond,double capacity,Double topPressure,Double bottomPressure){this(levelHeight,open,ventKgPerSecond,spillKgPerSecond,capacity,topPressure,bottomPressure,com.wormzjl.icheme.science.fluid.network.VesselGeometry.prism(capacity,levelHeight));}
        public Tank {
            java.util.Objects.requireNonNull(geometry,"Invalid tank presentation: missing geometry");if(Math.abs(geometry.capacity()-capacity)>capacity*1e-8||Math.abs(geometry.height()-levelHeight)>levelHeight*1e-8)throw new IllegalArgumentException("Invalid tank presentation: geometry mismatch");
            if(!(levelHeight>0)||!Double.isFinite(levelHeight)||!(ventKgPerSecond>=0)||!Double.isFinite(ventKgPerSecond)||!(spillKgPerSecond>=0)||!Double.isFinite(spillKgPerSecond)||!(capacity>0)||!Double.isFinite(capacity))
                throw new IllegalArgumentException("Invalid tank presentation");
            if((topPressure==null)!=(bottomPressure==null)||topPressure!=null&&(!Double.isFinite(topPressure)||!(topPressure>0)||!Double.isFinite(bottomPressure)||bottomPressure<topPressure))
                throw new IllegalArgumentException("Invalid tank pressures");
        }
        /** The liquid's height over the bottom, m: its condensed volume (both liquids and the solids) over the footprint
         * {@code capacity / levelHeight}. */
        public double level(State state){return state==null?0:geometry.heightAt(state.phaseVolumes()[0]+state.phaseVolumes()[1]+state.solids().volume());}
    }
    /**
     * One connection of a tank, as its page lists it (plan 4.3 under decision D11): the port the connection's end at the
     * tank compiled to ({@code VAPOR} top, {@code BULK} side, {@code LIQUID} bottom), the net flow out of the tank through
     * it over the last completed interval (kg/s and mol/s; negative is inflow), the phase that led what it drew ({@link
     * #NONE} when it drew nothing, else the phase index of {@link PipeTransfer.Stream}: 0 hydrocarbon liquid, 1 free
     * water, 2 vapour), and, at a bottom port, the level head of the committed state (Pa; 0 elsewhere). {@code face} is
     * the face of the tank the connection leaves by, as the compile that made the graph recorded it (the page names each
     * row with it, so two side connections can be told apart); required on the wire since fluid-8. A one-block tank's
     * port is its face's (top VAPOR, bottom LIQUID, sides BULK); a vessel's nozzle states its own class whichever way it
     * faces (a drum's, a floating-roof tank's bottom LIQUID and top OVERFLOW nozzles on its front, a sphere's bottom
     * outlet routed to its front).
     */
    public record Outlet(long pipeId,PhysicalFluidTopology.Direction face,com.wormzjl.icheme.science.fluid.network.PassiveNetwork.PhasePort port,double massFlow,double moleFlow,int drawn,double head) {
        public static final int NONE=-1;
        public Outlet {
            Objects.requireNonNull(face,"Tank outlet without its face");Objects.requireNonNull(port);
            if(!Double.isFinite(massFlow)||!Double.isFinite(moleFlow)||drawn<NONE||drawn>2||!Double.isFinite(head)||head<0)throw new IllegalArgumentException("Invalid tank outlet");
        }
    }
    /** Nonnegative interval transport; a junction counts outgoing material once. A void aggregates incoming rates
     * from independent islands as one second of transport (intervalSeconds = 1), never its dummy boundary inventory. */
    public record PipeInfo(PipeTransfer.Stream contents,List<PipeConnection> connections,boolean junction,boolean changedDirection,Reading reading){
        public PipeInfo(PipeTransfer.Stream contents,List<PipeConnection> connections,boolean junction,boolean changedDirection){this(contents,connections,junction,changedDirection,null);}
        public PipeInfo {
            Objects.requireNonNull(contents);connections=List.copyOf(connections);
            if(connections.size()>12||connections.stream().map(PipeConnection::pipeId).distinct().count()!=connections.size())
                throw new IllegalArgumentException("Invalid pipe inspection connections");
        }
        public static PipeInfo empty(int components){return new PipeInfo(new PipeTransfer.Stream(0,new double[3][components],new double[3]),List.of(),false,false);}
        public PipeInfo withReading(Reading value){return new PipeInfo(contents,connections,junction,changedDirection,value);}
    }
    /**
     * The stream a plain pipe carries, as its line gauge reads it (fluid-16, batch 2026-10-27-heat-exchanger-design,
     * OWNER_TEST_REVIEW.md section 6): the temperature (K) of what flows and the pressure (Pa) at the block. A still pipe
     * ({@code flowing} false) shows the last flowing temperature, or what lies in it at rest.
     */
    public record Reading(double temperature,double pressure,boolean flowing) {
        public Reading {if(!(temperature>0)||!Double.isFinite(temperature)||!Double.isFinite(pressure))throw new IllegalArgumentException("Invalid line reading");}
        public Reading still(){return new Reading(temperature,pressure,false);}
    }
    /** Speed is the previous-five-online-seconds bulk speed at this block's bore; gradient is endpoint pressure difference / path length. */
    public record PipeConnection(long pipeId,double massRateKgPerSecond,double velocityMetresPerSecond,double pressureDropPascalPerMetre,boolean reverse){
        public PipeConnection {
            for(double value:new double[]{massRateKgPerSecond,velocityMetresPerSecond,pressureDropPascalPerMetre})
                if(!Double.isFinite(value)||value<0)throw new IllegalArgumentException("Invalid pipe inspection metric");
        }
    }
    public record PipeRoute(long pipeId,String first,String second) {}
    /**
     * A unit's heat side over the last completed interval (batch 2026-10-27-heat-exchanger-design): which unit and side it
     * is, whether it has a partner side (whose screen it opens), the temperature at its outlet nozzle's junction (K), the
     * heat its stream took up (W, negative where it was cooled, from the interval's {@code HEAT} boundaries), and an air
     * cooler's fans: their share of full speed as they turn (its controller's where it has one) and their power (W), null for
     * a side without fans; an exchanger side's bypass share as it stands (W4), null for another side; a fired heater's coil:
     * its burners' share of full firing, the set point (K) and the coil's most heat (W) with the burners as they are, null for
     * a side without burners.
     *
     * <p>fluid-16 (the heat GUI, HEAT_GUI_PLAN.md section 1): the {@code actuator} the side's control moves, as a key the
     * screen names ({@link #ACTUATORS}), and its {@code effort}, 0 to 1 (the bypassed share, the fans' speed, the burners'
     * share of the coil's duty), or null; the controller's planned {@code limit} by its name; every inlet and outlet of the
     * unit, both sides ({@link Port}); and an exchanger's control lines ({@link Lines}), null for a unit without them.</p>
     */
    public record Heat(String unit,String side,boolean paired,double outletTemperature,double heatWatt,Double fans,Double fanPowerWatt,
                       Double burners,Double setPoint,Double maximumWatt,Double bypass,
                       String actuator,Double effort,String limit,List<Port> ports,Lines lines,Fuel fuel) {
        public Heat(String unit,String side,boolean paired,double outletTemperature,double heatWatt,Double fans,Double fanPowerWatt,Double burners,Double setPoint,Double maximumWatt,Double bypass,
                    String actuator,Double effort,String limit,List<Port> ports,Lines lines) {
            this(unit,side,paired,outletTemperature,heatWatt,fans,fanPowerWatt,burners,setPoint,maximumWatt,bypass,actuator,effort,limit,ports,lines,null);
        }
        /** The actuators the screen names: an internal bypass, the real bypass valve, a ratio's feed valve, fans, burners, none. */
        public static final Set<String> ACTUATORS=Set.of("none","bypass","bypass_valve","feed_valve","fans","louvers","burners");
        /** An exchanger's four nozzles, a kettle's five; a little room for another unit's. */
        public static final int MAXIMUM_PORTS=8;
        public Heat(String unit,String side,boolean paired,double outletTemperature,double heatWatt,Double fans,Double fanPowerWatt,Double burners,Double setPoint,Double maximumWatt,Double bypass) {
            this(unit,side,paired,outletTemperature,heatWatt,fans,fanPowerWatt,burners,setPoint,maximumWatt,bypass,"none",null,"NONE",List.of(),null);
        }
        public Heat {
            Objects.requireNonNull(unit);Objects.requireNonNull(side);
            actuator=actuator==null?"none":actuator;limit=limit==null?"NONE":limit;ports=ports==null?List.of():List.copyOf(ports);
            if(!(outletTemperature>0)||!Double.isFinite(outletTemperature)||!Double.isFinite(heatWatt)||(fans==null)!=(fanPowerWatt==null)
                    ||fans!=null&&!(fans>=0&&fans<=1&&fanPowerWatt>=0&&Double.isFinite(fanPowerWatt))||bypass!=null&&!(bypass>=0&&bypass<=1)
                    ||(burners==null)!=(setPoint==null)||burners!=null&&!(burners>=0&&burners<=1&&setPoint>0&&Double.isFinite(setPoint))
                    ||maximumWatt!=null&&!(maximumWatt>=0&&Double.isFinite(maximumWatt)))throw new IllegalArgumentException("Invalid heat side presentation");
            if(!ACTUATORS.contains(actuator)||effort!=null&&!(effort>=0&&effort<=1)||limit.length()>32||ports.size()>MAXIMUM_PORTS
                    ||ports.stream().map(Port::role).distinct().count()!=ports.size())throw new IllegalArgumentException("Invalid heat side control presentation");
        }
        /** This side with its control's actuator and effort, its limit, the unit's ports and lines. */
        public Heat with(String actuator,Double effort,String limit,List<Port> ports,Lines lines) {
            return new Heat(unit,side,paired,outletTemperature,heatWatt,fans,fanPowerWatt,burners,setPoint,maximumWatt,bypass,actuator,effort,limit,ports,lines,fuel);
        }
        /** A fired heater's side with its burners' readings. */
        public Heat withFuel(Fuel value){return new Heat(unit,side,paired,outletTemperature,heatWatt,fans,fanPowerWatt,burners,setPoint,maximumWatt,bypass,actuator,effort,limit,ports,lines,value);}
        /** The port of nozzle role {@code role}, or null. */
        public Port port(String role){for(var p:ports)if(p.role().equals(role))return p;return null;}
    }
    /**
     * The stream at one of a heat unit's nozzles over the last completed interval (fluid-16): the nozzle's role by name
     * ({@code TUBE_INLET}, a kettle shell's {@code FEED}), its temperature (K) and pressure (Pa), null while the node holds
     * nothing, its mass flow (kg/s, negative against the nozzle's direction), and what it carries per second by phase:
     * mol/s on the menu's axis and m³/s, in the order of {@link PipeTransfer.Stream} (hydrocarbon liquid, water, vapour).
     */
    public record Port(String role,Double temperature,Double pressure,double massFlow,double[][] phaseMoles,double[] phaseVolumes) {
        public Port {
            Objects.requireNonNull(role);Objects.requireNonNull(phaseMoles);Objects.requireNonNull(phaseVolumes);
            phaseMoles=Arrays.stream(phaseMoles).map(double[]::clone).toArray(double[][]::new);phaseVolumes=phaseVolumes.clone();
            if(role.length()>32||temperature!=null&&!(temperature>0&&Double.isFinite(temperature))||pressure!=null&&!Double.isFinite(pressure)||!Double.isFinite(massFlow)
                    ||phaseMoles.length!=3||phaseVolumes.length!=3)throw new IllegalArgumentException("Invalid heat port presentation");
            for(int p=0;p<3;p++){if(!(phaseVolumes[p]>=0)||!Double.isFinite(phaseVolumes[p]))throw new IllegalArgumentException("Invalid heat port phase");for(double n:phaseMoles[p])if(!(n>=0)||!Double.isFinite(n))throw new IllegalArgumentException("Invalid heat port amount");}
        }
        @Override public double[][] phaseMoles(){return Arrays.stream(phaseMoles).map(double[]::clone).toArray(double[][]::new);}
        @Override public double[] phaseVolumes(){return phaseVolumes.clone();}
    }
    /**
     * An exchanger's control lines as the side's screen draws them (fluid-16, W8): this side's lines {@code status} by
     * {@link FluidWorldAuthority.Lines.Status} name, or {@code NONE} where only the other side asks for lines; how many parts
     * are not in place; and every part the exchanger's design asks for ({@link Part}).
     */
    public record Lines(String status,int missing,List<Part> parts) {
        public static final int MAXIMUM_PARTS=12;
        public Lines {
            Objects.requireNonNull(status);parts=List.copyOf(parts);
            if(status.length()>32||missing<0||parts.size()>MAXIMUM_PARTS||parts.stream().map(Part::slot).distinct().count()!=parts.size())throw new IllegalArgumentException("Invalid exchanger lines presentation");
        }
        public Part part(String slot){for(var p:parts)if(p.slot().equals(slot))return p;return null;}
    }
    /**
     * One part of an exchanger's lines: its slot ({@code shell_inlet_tee}), its state by {@code AssemblyReport.State} name
     * ({@code NOT_VALIDATED} before a check), the report's detail and the place of its fault (a block position, or
     * {@code Long.MIN_VALUE} for none), and the stream at the part when one is registered there: a tee's temperature (K), a
     * valve's or a metered line's mass flow (kg/s).
     */
    public record Part(String slot,String state,String detail,long at,Double temperature,Double massFlow) {
        public Part {
            Objects.requireNonNull(slot);Objects.requireNonNull(state);detail=detail==null?"":detail;
            if(slot.length()>48||state.length()>32||detail.length()>64||temperature!=null&&!(temperature>0&&Double.isFinite(temperature))||massFlow!=null&&!Double.isFinite(massFlow))
                throw new IllegalArgumentException("Invalid exchanger part presentation");
        }
    }
    /** Every field but the heat side's: a view of a device that is not one. */
    public FluidView(long identity,long inputRevision,long committedTick,long onlineTick,String status,State state,double massFlow,List<PipeTransfer> pipeHistory,Double devicePressureChange,
                     double intervalSeconds,List<PipeRoute> pipeRoutes,InlineFilter filter,PipeInfo pipeInfo,List<Outlet> outlets,Tank tank) {
        this(identity,inputRevision,committedTick,onlineTick,status,state,massFlow,pipeHistory,devicePressureChange,intervalSeconds,pipeRoutes,filter,pipeInfo,outlets,tank,null,null,null,null,null,null,null);
    }
    public FluidView withHeat(Heat value){
        return new FluidView(identity,inputRevision,committedTick,onlineTick,status,state,massFlow,pipeHistory,devicePressureChange,intervalSeconds,pipeRoutes,filter,pipeInfo,outlets,tank,value,tower,drive,power,pump,flare,still,chimney);
    }
    public FluidView(long identity,long inputRevision,long committedTick,long onlineTick,String status,State state,double massFlow,List<PipeTransfer> pipeHistory) {
        this(identity,inputRevision,committedTick,onlineTick,status,state,massFlow,pipeHistory,null,0,List.of(),null);
    }
    public FluidView {
        Objects.requireNonNull(status);pipeHistory=List.copyOf(pipeHistory);pipeRoutes=List.copyOf(pipeRoutes);outlets=List.copyOf(outlets);
        if(outlets.size()>MAXIMUM_OUTLETS)throw new IllegalArgumentException("Too many tank outlets");
    }
    /** {@code volume} is what the node holds; {@code capacity} the volume its wall holds (decision D54: a vessel is a bag
     * whose contents may be smaller than its capacity), the contents' own volume for a node with no wall. */
    public record State(double pressure,double temperature,double volume,double mass,double[] phaseVolumes,double[][] phaseMoles,SolidInventory solids,double capacity) {
        public State {Objects.requireNonNull(solids);phaseVolumes=phaseVolumes.clone();phaseMoles=Arrays.stream(phaseMoles).map(double[]::clone).toArray(double[][]::new);
            if(!(capacity>0)||!Double.isFinite(capacity))throw new IllegalArgumentException("Invalid capacity");}
        @Override public double[] phaseVolumes(){return phaseVolumes.clone();}
        @Override public double[][] phaseMoles(){return Arrays.stream(phaseMoles).map(double[]::clone).toArray(double[][]::new);}
        /** The share of the capacity the contents take, 1 for a full (taut) vessel. */
        public double fill(){return Math.min(1,volume/capacity);}
        static State from(FluidThermodynamics.State s,double capacity) {
            double[][] n=new double[3][s.liquid().length+1];var l=s.liquid();var v=s.vapor();
            for(int c=0;c<l.length;c++){n[0][c]=l[c];n[2][c]=v[c];}n[1][l.length]=s.waterLiquid();n[2][l.length]=s.waterVapor();
            return new State(s.pressure(),s.temperature(),s.volume(),s.mass(),new double[]{s.liquidVolume(),s.waterVolume(),s.vaporVolume()},n,s.solids(),capacity);
        }
    }
    public interface Receiver {
        long fluidIdentity();
        void acceptFluidView(FluidView view);
    }
}
