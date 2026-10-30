package com.wormzjl.icheme.science.fluid.network;
import com.wormzjl.icheme.science.math.DeterministicMath;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;

/** Steady water/air contact, with a common water enthalpy reference on both sides of evaporation. */
public final class TowerTransfer {
    private TowerTransfer() {}
    public static final int SEGMENTS = 8;
    private static final double CP_AIR = 1006, CP_VAPOR = 1860, R_AIR = 287.05;
    /** Calibrated by {@link #calibrate}; kg dry air/(s m³), at 2 kg dry air/(s m²). */
    private record Calibration(FluidThermodynamics model,double coefficient) {}
    // A single immutable pair is safely published. A reload or another package cannot borrow its predecessor's fit.
    private static volatile Calibration calibration;

    /** Positive mass and energy leave the water. Latent duty is diagnostic, not an additional sink. */
    public record Result(double outletTemperature, double evaporation, double sensibleWatt,
                         double latentWatt, double energyWatt, double dryAirFlow, double airTemperature,
                         double airHumidity, double fanPowerWatt, double speed) {
        public Result {
            for(double value:new double[]{outletTemperature,evaporation,sensibleWatt,latentWatt,energyWatt,dryAirFlow,airTemperature,airHumidity,fanPowerWatt,speed})
                if(!Double.isFinite(value))throw new IllegalArgumentException("Nonfinite tower transfer");
            if(outletTemperature<=0||airTemperature<=0||airHumidity<0||fanPowerWatt<0||speed<0||speed>1)throw new IllegalArgumentException("Invalid tower transfer");
        }
        public double waterLoss() { return evaporation; }
        public Result scale(double n) {
            return new Result(outletTemperature, evaporation*n, sensibleWatt*n, latentWatt*n, energyWatt*n,
                    dryAirFlow*n, airTemperature, airHumidity, fanPowerWatt*n, speed);
        }
    }
    public static double humidity(FluidThermodynamics model, double temperature, double relativeHumidity) {
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
    public static double liquidTemperature(FluidThermodynamics model, double h, double around) {
        double t=around;
        for(int i=0;i<8;i++) {
            double f=liquid(model,t)-h;
            if(Math.abs(f)<.001)break;
            double cp=(liquid(model,t+.01)-liquid(model,t-.01))/.02;
            t-=Math.clamp(f/cp,-10,10);
        }
        return t;
    }
    private static double coefficient(FluidThermodynamics model) {
        var cached=calibration;
        if(cached!=null&&cached.model()==model)return cached.coefficient();
        double fitted=calibrate(model);
        calibration=new Calibration(model,fitted);
        return fitted;
    }
    private static double calibrate(FluidThermodynamics model) {
        double lo=.0001,hi=100;
        for(int i=0;i<48;i++) {
            double mid=Math.sqrt(lo*hi);
            double out=contact(model,2,308.15,2,2,1,mid,0).outletTemperature();
            if(out>298.15)lo=mid;else hi=mid;
        }
        return Math.sqrt(lo*hi);
    }
    static Result reference(FluidThermodynamics model){return contact(model,2,308.15,2,2,1,coefficient(model),1);}
    private record Cell(double temperature,double evaporation,double sensible,double energy,double latent,double residual) {}
    private static Cell cellAt(FluidThermodynamics model,double t,double incomingH,double water,double airT,double airW,double conductance) {
        double dw=humidity(model,t,1)-airW;
        // A dryout shortens the wet portion of the cell; it cannot evaporate unavailable water.
        double ke=dw>0?Math.min(conductance,water/dw):conductance;
        double dm=ke*dw,hv=vapor(model,t),hl=liquid(model,t),q=ke*(CP_AIR+airW*CP_VAPOR)*(t-airT);
        double energy=dm*hv+q;
        return new Cell(t,dm,q,energy,dm*(hv-hl),(water-dm)*hl-water*incomingH+energy);
    }
    private static Cell cell(FluidThermodynamics model,double incoming,double water,double airT,double airW,double conductance) {
        double h=liquid(model,incoming),lo=Math.max(1,Math.min(incoming,airT)-60),hi=Math.max(incoming,airT);
        double t=(lo+hi)/2;Cell result=null;
        for(int i=0;i<40;i++) {
            result=cellAt(model,t,h,water,airT,airW,conductance);
            if(Math.abs(result.residual())<Math.max(1e-7,water*1e-6))break;
            if(result.residual()>0)hi=t;else lo=t;
            double offset=Math.min(.001,t/2);
            double derivative=(result.residual()-cellAt(model,t-offset,h,water,airT,airW,conductance).residual())/offset;
            double next=t-result.residual()/derivative;
            t=Double.isFinite(next)&&next>lo&&next<hi?next:(lo+hi)/2;
        }
        // Use the solved water balance for booking, retaining its independently calculated heat split.
        double energy=water*h-(water-result.evaporation())*liquid(model,result.temperature());
        return new Cell(result.temperature(),result.evaporation(),energy-result.evaporation()*vapor(model,result.temperature()),energy,result.latent(),0);
    }
    /** Explicit-air test/calibration entry point; no hidden saturation-at-exhaust assumption. */
    public static Result contact(FluidThermodynamics model, double water, double inlet, double air, double fill,
                                 double area, double transferCoefficient, double speed) {
        double ambientW=humidity(model,CoolingTower.AIR_TEMPERATURE,CoolingTower.RELATIVE_HUMIDITY);
        if(water<=0||air==0)return new Result(inlet,0,0,0,0,air,CoolingTower.AIR_TEMPERATURE,ambientW,0,speed);
        double g=Math.abs(air), dz=fill/SEGMENTS;
        double[] ta=new double[SEGMENTS+1],wa=new double[SEGMENTS+1];
        java.util.Arrays.fill(ta,CoolingTower.AIR_TEMPERATURE);java.util.Arrays.fill(wa,ambientW);
        double tw=inlet,left=water,evap=0,conv=0,latent=0,energy=0;
        // Counterflow boundary value solved by under-relaxed air sweeps; reverse air runs co-current.
        for(int sweep=0;sweep<100;sweep++) {
            tw=inlet;left=water;evap=conv=latent=energy=0;
            double[] de=new double[SEGMENTS],dq=new double[SEGMENTS],dh=new double[SEGMENTS];
            for(int j=0;j<SEGMENTS;j++) {
                if(left<=0)break;
                int k=air>0?SEGMENTS-1-j:j;
                double conductance=transferCoefficient*area*dz*DeterministicMath.pow(g/(2*area),.6);
                // Bounded effectiveness prevents overshooting either stream in a finite segment.
                double ke=g*(-DeterministicMath.expm1(-conductance/g));
                var cell=cell(model,tw,left,ta[k],wa[k],ke);
                double dm=cell.evaporation(),q=cell.sensible();
                de[k]=dm;dq[k]=q;dh[k]=cell.energy();
                tw=cell.temperature();left-=dm;
                evap+=dm;conv+=q;latent+=cell.latent();energy+=cell.energy();
            }
            double at=CoolingTower.AIR_TEMPERATURE,aw=ambientW,error=0;
            for(int k=0;k<SEGMENTS;k++) {
                double nw=aw+de[k]/g;
                // Vapor enters at the liquid interface temperature; recover air T from the same enthalpy balance.
                double nt=at+(dq[k]/g)/(CP_AIR+aw*CP_VAPOR);
                // Absolute water reference cancels through the vapor enthalpy difference.
                for(int n=0;n<4;n++) {
                    double f=CP_AIR*(nt-at)+nw*vapor(model,nt)-aw*vapor(model,at)-dh[k]/g;
                    nt-=f/(CP_AIR+nw*CP_VAPOR);
                }
                error=Math.max(error,Math.abs(ta[k+1]-nt));
                ta[k+1]=.5*ta[k+1]+.5*nt;wa[k+1]=.5*wa[k+1]+.5*nw;
                at=ta[k+1];aw=wa[k+1];
            }
            if(error<1e-7)break;
        }
        return new Result(tw,evap,conv,latent,energy,air,ta[SEGMENTS],wa[SEGMENTS],0,speed);
    }
    /** One representative parallel cell; extensive quantities are multiplied once by its cell count. */
    public static Result fill(FluidThermodynamics model, CoolingTower tower, double water, double inlet, double speed) {
        double area=tower.cellArea(),w=humidity(model,CoolingTower.AIR_TEMPERATURE,CoolingTower.RELATIVE_HUMIDITY);
        double rho=density(CoolingTower.AIR_TEMPERATURE,w),lo=-12*area,hi=12*area;
        // Expand the pressure bracket rather than limiting a tower's airflow at a chosen mass loading.
        while(draught(model,tower,water,inlet,speed,lo,rho).residual()>0)lo*=2;
        while(draught(model,tower,water,inlet,speed,hi,rho).residual()<0)hi*=2;
        Result result=null;
        double pressure=tower.draft()==CoolingTower.Draft.INDUCED?20*speed*speed:0;
        for(int i=0;i<35;i++) {
            double air=(lo+hi)/2;
            // Evaluate the zero-flow limit from moving air; the exact zero state has no defined exhaust density.
            if(Math.abs(air)<1e-12*area)air=1e-8*area;
            var balance=draught(model,tower,water,inlet,speed,air,rho);result=balance.transfer();
            if(balance.residual()>0)hi=air;else lo=air;
        }
        double power=tower.draft()==CoolingTower.Draft.INDUCED?Math.max(0,pressure*result.dryAirFlow()/rho/.6):0;
        return new Result(result.outletTemperature(),result.evaporation(),result.sensibleWatt(),result.latentWatt(),result.energyWatt(),result.dryAirFlow(),result.airTemperature(),result.airHumidity(),power,speed).scale(tower.cells());
    }
    private record Draught(Result transfer,double residual) {}
    private static Draught draught(FluidThermodynamics model,CoolingTower tower,double water,double inlet,double speed,double air,double rho){
        var transfer=contact(model,water/tower.cells(),inlet,air,tower.fillDepth(),tower.cellArea(),water>0?coefficient(model):0,speed);
        double buoyancy=9.80665*Math.max(1,tower.height()-tower.distributorHeight())*(rho-density(transfer.airTemperature(),transfer.airHumidity()));
        double velocity=air/(rho*tower.cellArea());
        double resistance=(3+6*tower.fillDepth()/2)*rho*velocity*Math.abs(velocity)/2;
        double fan=tower.draft()==CoolingTower.Draft.INDUCED?20*speed*speed:0;
        return new Draught(transfer,resistance-fan-buoyancy);
    }
    /** Wet basin surface, including at zero spray flow. Positive evaporation may be limited to available inventory. */
    public static Result surface(FluidThermodynamics model,CoolingTower tower,double t,double available,double dt) {
        if(available<=0)return new Result(t,0,0,0,0,0,CoolingTower.AIR_TEMPERATURE,0,0,0);
        double h=5*tower.area(),ke=h/(CP_AIR+humidity(model,CoolingTower.AIR_TEMPERATURE,.5)*CP_VAPOR);
        double dm=ke*(humidity(model,t,1)-humidity(model,CoolingTower.AIR_TEMPERATURE,.5));
        dm=Math.min(dm,.1*available/Math.max(dt,1e-9));
        double q=h*(t-CoolingTower.AIR_TEMPERATURE),hv=vapor(model,t),hl=liquid(model,t);
        return new Result(t,dm,q,dm*(hv-hl),q+dm*hv,0,CoolingTower.AIR_TEMPERATURE,0,0,0);
    }
}
