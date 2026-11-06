package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.equipment.MachineDuty;
import com.wormzjl.icheme.science.machine.ShaftNetwork;
import com.wormzjl.icheme.science.machine.ShaftTrain;
import java.util.List;
import java.util.Objects;

/**
 * One part of a shaft train as the world saves it (batch 2026-11-03-rotating-machinery, W3; fluid facts schema 10, reading
 * A15): a fact of the topology unit, beside the column units, so trains run while their chunks are unloaded, as islands
 * do. A shaft block ({@code SHAFT}: its cell and axis), a creative motor (its cell and its one shaft end), or a structure
 * with shaft ends (its origin, its ends' world cells, its rated values, and for a casing or a turbine its fluid device
 * identity {@code fluid}). {@code settings} and {@code trip} are what the player set and what waits for the player's
 * reset; {@code state} is the engine's (a driver's speed), saved in the topology state section and rebuilt when dropped.
 */
public record ShaftUnit(long id,ShaftNetwork.Kind kind,PhysicalFluidTopology.Position position,int axis,List<End> ends,long fluid,
                        double ratedPower,double designSpeed,int poles,MachineDuty.Drive drive,Settings settings,ShaftTrain.Trip trip,State state) {
    /** The most shaft ends a part has. */
    public static final int MAXIMUM_ENDS=8;
    /** A shaft end: the cell inside the coupling face, the face's outward step, and whether power leaves there. */
    public record End(PhysicalFluidTopology.Position cell,int dx,int dy,int dz,boolean output) {
        public End {
            Objects.requireNonNull(cell);
            if(Math.abs(dx)+Math.abs(dy)+Math.abs(dz)!=1)throw new IllegalArgumentException("A shaft end leaves along one axis");
        }
        ShaftNetwork.End science(){return new ShaftNetwork.End(ShaftUnit.cell(cell),dx,dy,dz,output);}
    }
    /**
     * What the player set: whether it is armed to run (default true; actual speed requires power), its mode (default SPEED),
     * its set point (default 0: its design speed; a creative motor's rpm), a creative motor's power limit (default 0:
     * {@link ShaftTrain#CREATIVE_LIMIT}), a gearbox's ratio (0: auto, reading A20; new placements start at 4); up to
     * {@link ShaftNetwork#CREATE_RATIO} either way, of which a train applies more than {@link ShaftNetwork#MAXIMUM_RATIO} only
     * behind a Create coupling (W6b, reading A145).
     */
    public record Settings(boolean running,ShaftTrain.Mode mode,double setPoint,double powerLimit,double ratio) {
        public static final Settings DEFAULT=new Settings(true,ShaftTrain.Mode.SPEED,0,0,0);
        private static final Settings GEARBOX=new Settings(true,ShaftTrain.Mode.SPEED,0,0,ShaftNetwork.DEFAULT_GEARBOX_RATIO);
        /** New parts only: saved settings, including zero for Auto, retain their existing meaning. */
        public static Settings placed(ShaftNetwork.Kind kind){return kind==ShaftNetwork.Kind.GEARBOX?GEARBOX:DEFAULT;}
        public Settings {
            Objects.requireNonNull(mode);
            if(!(setPoint>=0)||!(powerLimit>=0)||!(ratio>=0)||!Double.isFinite(setPoint+powerLimit+ratio)||ratio>ShaftNetwork.CREATE_RATIO)throw new IllegalArgumentException("Invalid shaft settings");
            if(ratio>0&&ratio<1/ShaftNetwork.CREATE_RATIO)throw new IllegalArgumentException("Invalid gearbox ratio "+ratio);
        }
    }
    /** A driver's speed, rpm, and the slice before's speed and regulated measure (NaN for none): engine state. */
    public record State(double speed,double previousSpeed,double previousMeasured) {
        public State {if(!(speed>=0)||!Double.isFinite(speed))throw new IllegalArgumentException("Invalid shaft speed "+speed);}
    }
    public ShaftUnit {
        Objects.requireNonNull(kind);Objects.requireNonNull(position);Objects.requireNonNull(drive);Objects.requireNonNull(settings);Objects.requireNonNull(trip);
        ends=List.copyOf(ends);
        if(id<=0||fluid<0||ends.size()>MAXIMUM_ENDS)throw new IllegalArgumentException("Invalid shaft unit identity");
        if(kind==ShaftNetwork.Kind.SHAFT?axis<0||axis>2||!ends.isEmpty():ends.isEmpty())throw new IllegalArgumentException("A shaft block has an axis, a machine its shaft ends");
        if(fluid!=0!=(kind==ShaftNetwork.Kind.CASING||kind==ShaftNetwork.Kind.STEAM_TURBINE))throw new IllegalArgumentException("Only a casing or a turbine names a fluid device");
        for(var e:ends)if(!e.cell().dimension().equals(position.dimension()))throw new IllegalArgumentException("A shaft end in another dimension");
        if(!(ratedPower>=0)||!(designSpeed>=0)||!Double.isFinite(ratedPower+designSpeed)||poles!=0&&!MachineDuty.POLES.contains(poles))throw new IllegalArgumentException("Invalid shaft unit rating");
    }
    /** A shaft block. */
    public static ShaftUnit shaft(long id,PhysicalFluidTopology.Position cell,int axis) {
        return new ShaftUnit(id,ShaftNetwork.Kind.SHAFT,cell,axis,List.of(),0,0,0,0,MachineDuty.Drive.FIXED,Settings.DEFAULT,ShaftTrain.Trip.NONE,null);
    }
    public ShaftUnit withSettings(Settings value){return new ShaftUnit(id,kind,position,axis,ends,fluid,ratedPower,designSpeed,poles,drive,value,trip,state);}
    public ShaftUnit withTrip(ShaftTrain.Trip value){return new ShaftUnit(id,kind,position,axis,ends,fluid,ratedPower,designSpeed,poles,drive,settings,value,state);}
    public ShaftUnit withState(State value){return new ShaftUnit(id,kind,position,axis,ends,fluid,ratedPower,designSpeed,poles,drive,settings,trip,value);}
    /** These facts without the engine's state: what a facts comparison reads. */
    public ShaftUnit facts(){return state==null?this:withState(null);}
    /** The part the train compiler reads. */
    public ShaftNetwork.Part part() {
        if(kind==ShaftNetwork.Kind.SHAFT)return ShaftNetwork.Part.shaft(id,cell(position),axis);
        return new ShaftNetwork.Part(id,kind,null,-1,ends.stream().map(End::science).toList(),kind==ShaftNetwork.Kind.GEARBOX?settings.ratio():0,designSpeed);
    }
    /** The driver this unit is in the step's terms, or null for a part that drives nothing. */
    public ShaftTrain.Drive drive(boolean variable) {
        return switch(kind){case MOTOR->variable?ShaftTrain.Drive.VARIABLE_MOTOR:ShaftTrain.Drive.FIXED_MOTOR;case STEAM_TURBINE->ShaftTrain.Drive.TURBINE;case CREATIVE_MOTOR->ShaftTrain.Drive.CREATIVE;case CREATE_COUPLING->ShaftTrain.Drive.CREATE;default->null;};
    }
    static ShaftNetwork.Cell cell(PhysicalFluidTopology.Position p){return new ShaftNetwork.Cell(p.dimension(),p.x(),p.y(),p.z());}
}
