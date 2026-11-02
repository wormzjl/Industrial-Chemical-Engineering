package com.wormzjl.icheme.runtime.instrument;

import com.wormzjl.icheme.science.fluid.network.VesselGeometry;
import java.util.Objects;

/** Immutable endpoint contract for the later cable batch; no cable graph or level regulation is enabled here. */
public record ControlBinding(long valve,Sensor.Installed source,long revision,LevelTarget target,Action action) {
    public enum Action { OUTLET, INLET }
    public enum Basis { HEIGHT, HEIGHT_PERCENT }
    public enum Status { REGULATING, FULLY_OPEN, CLOSED, LACKING_COMPONENTS, SIGNAL_UNAVAILABLE, INVALID_CONNECTION, CONTROL_CONFLICT }
    public record LevelTarget(double value,Basis basis){
        public LevelTarget{Objects.requireNonNull(basis);if(!Double.isFinite(value)||value<0||basis==Basis.HEIGHT_PERCENT&&value>100)throw new IllegalArgumentException("Invalid level target");}
        public static LevelTarget defaults(){return new LevelTarget(50,Basis.HEIGHT_PERCENT);}
        public double volume(VesselGeometry geometry,boolean boot){
            double span=boot?geometry.bootDepth():geometry.height();double height=basis==Basis.HEIGHT_PERCENT?value*span/100:value;
            return geometry.volumeAt(height-(boot?geometry.bootDepth():0));
        }
    }
    public ControlBinding{
        Objects.requireNonNull(source);Objects.requireNonNull(target);Objects.requireNonNull(action);
        if(valve<=0||revision<=0||source.kind()!=Sensor.Kind.LEVEL)throw new IllegalArgumentException("Invalid level-control binding");
    }
}
