package com.wormzjl.icheme.science.fluid.network;
import com.wormzjl.icheme.science.math.DeterministicMath;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.function.DoubleUnaryOperator;

/**
 * Steady water/air contact as Merkel's rating: the air's enthalpy against that of saturated air at the water, integrated over
 * the water's temperature fall by the four Chebyshev points. A fill runs inside every Newton residual of its island, so its
 * only searches are that fall, the draft and the fan speed ({@link #root}), and nothing iterates along the fill.
 */
public final class TowerTransfer {
    private TowerTransfer() {}
    private static final double CP_AIR = 1006, CP_VAPOR = 1860, CP_WATER = 4186, R_AIR = 287.05;
    /** Moist air enthalpy counts from dry air and liquid water here, K. */
    private static final double ZERO = 273.15;
    /** Sensible exchange at the basin surface, W/(m² K). */
    private static final double SURFACE_COEFFICIENT = 5;
    /** The points of the Merkel integral, as shares of the water's fall. */
    private static final double[] CHEBYSHEV={.1,.4,.6,.9};
    /** Below this enthalpy difference, J/kg, the air has reached saturation at the water: a pinch, not a division by zero. */
    private static final double PINCH = 1;
    /** The fill coefficient, kg dry air/(s m³) at 2 kg dry air/(s m²), the ambient air, and liquid water at {@link #ZERO}. */
    private record Calibration(FluidThermodynamics model,double coefficient,double wetBulb,double ambientEnthalpy,double ambientHumidity,double zero) {
        Calibration with(double fitted){return new Calibration(model,fitted,wetBulb,ambientEnthalpy,ambientHumidity,zero);}
    }
    // A single immutable record is safely published. A reload or another package cannot borrow its predecessor's fit.
    private static volatile Calibration calibration;

    /** Positive mass and energy leave the water. {@code dutyWatt} is the heat the water gave up; {@code energyWatt}, what the evaporated water carries out with it. */
    public record Result(double outletTemperature, double evaporation, double dutyWatt, double energyWatt, double dryAirFlow, double fanPowerWatt, double speed) {
        public Result {
            for(double value:new double[]{outletTemperature,evaporation,dutyWatt,energyWatt,dryAirFlow,fanPowerWatt,speed})
                if(!Double.isFinite(value))throw new IllegalArgumentException("Nonfinite tower transfer");
            if(outletTemperature<=0||fanPowerWatt<0||speed<0||speed>1)throw new IllegalArgumentException("Invalid tower transfer");
        }
    }
    /** One cell's contact: its water, and the air the draft reads. */
    private record Contact(double outletTemperature,double evaporation,double dutyWatt,double energyWatt,double airFlow,double airTemperature,double airHumidity) {}
    private static double humidity(FluidThermodynamics model, double temperature, double relativeHumidity) {
        double p = relativeHumidity * model.saturationPressure(temperature);
        if (!(p < CoolingTower.PRESSURE)) throw new IllegalArgumentException("Atmospheric water contact requires liquid water");
        return .621945 * p / (CoolingTower.PRESSURE - p);
    }
    private static double liquid(FluidThermodynamics model, double t) {
        return model.waterLiquid(t, CoolingTower.PRESSURE).molarEnthalpy() / model.waterMolecularWeight;
    }
    private static double vapor(FluidThermodynamics model, double t) {
        return model.vaporWaterEnthalpy(t) / model.waterMolecularWeight;
    }
    private static double density(double t, double w) {
        return CoolingTower.PRESSURE * (1+w) / (R_AIR*t*(1+1.607858*w));
    }
    /** Moist air enthalpy per kg of dry air. */
    private static double enthalpy(FluidThermodynamics model, double zero, double t, double w) {
        return CP_AIR*(t-ZERO)+w*(vapor(model,t)-zero);
    }
    private static double saturated(FluidThermodynamics model, double zero, double t) {
        return enthalpy(model,zero,t,humidity(model,t,1));
    }
    private static double liquidTemperature(FluidThermodynamics model, double h, double around) {
        double t=around;
        for(int i=0;i<8;i++) {
            double f=liquid(model,t)-h;
            if(Math.abs(f)<.001)break;
            double cp=(liquid(model,t+.01)-liquid(model,t-.01))/.02;
            t-=Math.clamp(f/cp,-10,10);
        }
        return t;
    }
    /** The root of {@code f} between ends of opposite sign, by false position with the Illinois step, to a relative step of {@code tolerance}. */
    private static double root(DoubleUnaryOperator f,double a,double fa,double b,double fb,double tolerance) {
        for(int i=0;i<64;i++) {
            double c=b-fb*(b-a)/(fb-fa),fc=f.applyAsDouble(c);
            if(fc==0||Math.abs(c-b)<=tolerance*Math.abs(c))return c;
            if(fc*fb<0){a=b;fa=fb;}else fa/=2;
            b=c;fb=fc;
        }
        return b;
    }
    private static Calibration calibration(FluidThermodynamics model) {
        var cached=calibration;
        if(cached!=null&&cached.model()==model)return cached;
        double zero=liquid(model,ZERO),moist=humidity(model,CoolingTower.AIR_TEMPERATURE,CoolingTower.RELATIVE_HUMIDITY),far=enthalpy(model,zero,CoolingTower.AIR_TEMPERATURE,moist);
        DoubleUnaryOperator approach=t->saturated(model,zero,t)-far;
        double bulb=root(approach,250,approach.applyAsDouble(250),CoolingTower.AIR_TEMPERATURE,approach.applyAsDouble(CoolingTower.AIR_TEMPERATURE),1e-9);
        var base=new Calibration(model,0,bulb,far,moist,zero);
        DoubleUnaryOperator excess=log->contact(base.with(DeterministicMath.exp(log)),2,308.15,2,2,1).outletTemperature()-298.15;
        double lo=DeterministicMath.log(.0001),hi=DeterministicMath.log(100);
        var fitted=base.with(DeterministicMath.exp(root(excess,lo,excess.applyAsDouble(lo),hi,excess.applyAsDouble(hi),1e-10)));
        calibration=fitted;
        return fitted;
    }
    static double referenceOutlet(FluidThermodynamics model){return contact(calibration(model),2,308.15,2,2,1).outletTemperature();}
    /** The calibration point as a fill's result: one square metre, 2 kg/s of water from 35 °C against 2 kg/s of dry air (L/G 1). */
    static Result reference(FluidThermodynamics model){
        var c=contact(calibration(model),2,308.15,2,2,1);
        return new Result(c.outletTemperature(),c.evaporation(),c.dutyWatt(),c.energyWatt(),c.airFlow(),0,0);
    }
    /**
     * The air leaving a fill (batch 2026-11-10-custom-shader-system, W4): its temperature, K, its humidity, kg water per kg
     * dry air, and that humidity over saturation's at its temperature.
     */
    public record Exhaust(double temperature,double humidity,double saturation) {}
    /**
     * The air that {@code fill} leaves (a read, no search of the fill): the ambient air ({@link CoolingTower#AIR_TEMPERATURE},
     * {@link CoolingTower#RELATIVE_HUMIDITY}, {@link CoolingTower#PRESSURE}) gains the evaporated water, w = w_amb + E/G, and the
     * heat the water gave the air, h = h_amb + (Q_energy − E h_liquid(0 °C))/G on the enthalpy reference the fill uses (dry air
     * and liquid water at 0 °C); its temperature solves h(T, w). The ambient air for no air flow.
     */
    public static Exhaust exhaust(FluidThermodynamics model,Result fill){
        double zero=liquid(model,ZERO),ambientW=humidity(model,CoolingTower.AIR_TEMPERATURE,CoolingTower.RELATIVE_HUMIDITY);
        double far=enthalpy(model,zero,CoolingTower.AIR_TEMPERATURE,ambientW),g=Math.abs(fill.dryAirFlow());
        if(!(g>NO_AIR))return new Exhaust(CoolingTower.AIR_TEMPERATURE,ambientW,CoolingTower.RELATIVE_HUMIDITY);
        double w=ambientW+Math.max(0,fill.evaporation())/g,h=far+(fill.energyWatt()-fill.evaporation()*zero)/g,t=CoolingTower.AIR_TEMPERATURE;
        for(int n=0;n<32;n++){
            double step=(enthalpy(model,zero,t,w)-h)/(CP_AIR+w*CP_VAPOR);
            t=Math.clamp(t-step,250,373);
            if(Math.abs(step)<1e-7)break;
        }
        double p=model.saturationPressure(t),saturated=p<CoolingTower.PRESSURE?.621945*p/(CoolingTower.PRESSURE-p):Double.POSITIVE_INFINITY;
        return new Exhaust(t,w,w/saturated);
    }
    /** Below this dry air flow, kg/s, a tower has no exhaust to speak of. */
    public static final double NO_AIR=1e-3;
    /** Merkel's integral over a water fall of {@code fall} from {@code inlet}, in an air that gains {@code gain} J/kg per kelvin of it. */
    private static double merkel(Calibration fit,double inlet,double fall,double gain) {
        double total=0;
        for(double at:CHEBYSHEV) {
            double drive=saturated(fit.model(),fit.zero(),inlet-fall+at*fall)-fit.ambientEnthalpy()-gain*at*fall;
            total+=1/(fall>0?Math.max(drive,PINCH):Math.min(drive,-PINCH));
        }
        return CP_WATER*fall*total/CHEBYSHEV.length;
    }
    /** One cell's contact at a given air flow; no hidden saturation-at-exhaust assumption. Reverse air counts as counterflow. */
    private static Contact contact(Calibration fit, double water, double inlet, double air, double fill, double area) {
        var model=fit.model();double ambientW=fit.ambientHumidity(),far=fit.ambientEnthalpy(),span=inlet-fit.wetBulb();
        if(water<=0||air==0||span==0)return new Contact(inlet,0,0,0,air,CoolingTower.AIR_TEMPERATURE,ambientW);
        double g=Math.abs(air),capacity=water*CP_WATER,gain=capacity/g;
        double units=fit.coefficient()*area*fill*DeterministicMath.pow(g/(2*area),.6)/water;
        // The fall as a share of what the wet bulb allows, and of what the air could take up saturated at the inlet.
        double top=Math.min(1,(saturated(model,fit.zero(),inlet)-far)/(gain*span));
        DoubleUnaryOperator shortfall=share->merkel(fit,inlet,share*span,gain)-units;
        double atTop=shortfall.applyAsDouble(top),share=atTop<=0?top:root(shortfall,0,-units,top,atTop,1e-11);
        double fall=share*span,exchanged=capacity*fall;
        // With a Lewis number of one the air moves straight towards saturation at the water's mean temperature.
        double film=inlet-fall/2,toward=humidity(model,film,1);
        double airHumidity=ambientW+(exchanged/g)*(toward-ambientW)/(enthalpy(model,fit.zero(),film,toward)-far);
        double evaporation=g*(airHumidity-ambientW),energy=exchanged+evaporation*fit.zero(),airEnthalpy=far+exchanged/g;
        double airTemperature=film;
        for(int n=0;n<4;n++)airTemperature-=(enthalpy(model,fit.zero(),airTemperature,airHumidity)-airEnthalpy)/(CP_AIR+airHumidity*CP_VAPOR);
        double in=liquid(model,inlet),outlet=liquidTemperature(model,(water*in-energy)/(water-evaporation),inlet-fall),out=liquid(model,outlet);
        return new Contact(outlet,evaporation,water*(in-out),water*in-(water-evaporation)*out,air,airTemperature,airHumidity);
    }
    /** One representative parallel cell; extensive quantities are multiplied once by its cell count. */
    public static Result fill(FluidThermodynamics model, CoolingTower tower, double water, double inlet, double speed) {
        var fit=calibration(model);double area=tower.cellArea(),rho=density(CoolingTower.AIR_TEMPERATURE,fit.ambientHumidity());
        // The search runs on flow|flow|, in which resistance is a straight line, so false position lands in a few trials.
        DoubleUnaryOperator imbalance=square->draught(fit,tower,water,inlet,speed,signedRoot(square),rho).residual();
        // Warm water can hold both a rising and a falling draft. Still air picks, as it does when a chimney starts to draw.
        double still=imbalance.applyAsDouble(0),end=still<0?144*area*area:-144*area*area,atEnd=imbalance.applyAsDouble(end);
        // Expand the pressure bracket rather than limiting a tower's airflow at a chosen mass loading.
        while(still*atEnd>0){end*=4;atEnd=imbalance.applyAsDouble(end);}
        double square=still==0?0:root(imbalance,0,still,end,atEnd,1e-11);
        var r=draught(fit,tower,water,inlet,speed,signedRoot(square),rho).transfer();
        int n=tower.cells();
        // Fan power depends on the tower's geometry and speed, not on its solved water or air state.
        return new Result(r.outletTemperature(),r.evaporation()*n,r.dutyWatt()*n,r.energyWatt()*n,r.airFlow()*n,tower.fanPower(speed),speed);
    }
    private static double signedRoot(double square){return Math.signum(square)*Math.sqrt(Math.abs(square));}
    /** The fan speed that brings leaving water to the tower's set point; the nearer end stop when no speed does. */
    static double targetSpeed(FluidThermodynamics model,CoolingTower tower,double water,double inlet) {
        DoubleUnaryOperator error=speed->fill(model,tower,water,inlet,speed).outletTemperature()-tower.setPoint();
        double low=error.applyAsDouble(0),high=error.applyAsDouble(1);
        if(low*high>=0)return Math.abs(low)<=Math.abs(high)?0:1;
        return Math.clamp(root(error,0,low,1,high,1e-6),0,1);
    }
    private record Draught(Contact transfer,double residual) {}
    private static Draught draught(Calibration fit,CoolingTower tower,double water,double inlet,double speed,double air,double rho){
        // Evaluate the zero-flow limit from moving air; the exact zero state has no defined exhaust density.
        if(Math.abs(air)<1e-12*tower.cellArea())air=1e-8*tower.cellArea();
        var transfer=contact(fit,water/tower.cells(),inlet,air,tower.fillDepth(),tower.cellArea());
        double buoyancy=9.80665*Math.max(1,tower.height()-tower.distributorHeight())*(rho-density(transfer.airTemperature(),transfer.airHumidity()));
        double velocity=air/(rho*tower.cellArea());
        double resistance=(3+6*tower.fillDepth()/2)*rho*velocity*Math.abs(velocity)/2;
        double fan=tower.draft()==CoolingTower.Draft.INDUCED?CoolingTower.FAN_PRESSURE*speed*speed:0;
        return new Draught(transfer,resistance-fan-buoyancy);
    }
    /** Sensible exchange at a wet basin surface, including at zero spray; the basin itself does not evaporate. */
    static double surfaceWatt(CoolingTower tower,double t) {
        return SURFACE_COEFFICIENT*tower.area()*(t-CoolingTower.AIR_TEMPERATURE);
    }
}
