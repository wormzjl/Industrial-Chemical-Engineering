package com.wormzjl.icheme.science.fluid.network;
import com.wormzjl.icheme.science.math.DeterministicMath;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.thermo.HydrocarbonModel;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import java.util.Arrays;
import java.util.function.DoubleUnaryOperator;

/**
 * Steady liquid/air contact as Merkel's rating: the air's enthalpy against that of saturated air at the liquid, integrated
 * over the liquid's temperature fall by the four Chebyshev points. A fill runs inside every Newton residual of its island, so
 * its only searches are that fall, the draft and the fan speed ({@link #root}), and nothing iterates along the fill.
 *
 * <p>Any liquid feeds the fill (W3B_NETWORK_PLAN.md section 20). Saturated air at the liquid holds each compound at its
 * partial pressure {@code x_i phi_i P} from the equation of state, read at the inlet and at a second temperature
 * ({@link Liquid}); between them {@code ln(phi_i P)} is linear in {@code 1/T}, and the liquid's heat capacity and each
 * vapour's are constant. The air moves straight towards that state for every compound (Lewis number one); a compound's loss
 * is at most what the stream brings. A compound above its critical temperature (a gas: the air's nitrogen and oxygen, a
 * dissolved methane) leaves the fill in equilibrium with the ambient air at the leaving temperature, {@code x_i = p_i,air /
 * (phi_i P)}: the air's gases saturate the liquid and a gas the air does not hold strips whole. Each component's rate is
 * signed (positive leaves the liquid) and carries its vapour enthalpy on the model's datum.
 */
public final class TowerTransfer {
    private TowerTransfer() {}
    private static final double CP_AIR = 1006, CP_VAPOR = 1860, R_AIR = 287.05, GAS_CONSTANT = 8.314462618;
    private static final double AIR_MOLAR_MASS = GAS_CONSTANT / R_AIR;
    /** Moist air enthalpy counts from dry air and each compound's liquid at this temperature, K. */
    private static final double ZERO = 273.15;
    /** Sensible exchange at the basin surface, W/(m² K). */
    private static final double SURFACE_COEFFICIENT = 5;
    /** The points of the Merkel integral, as shares of the liquid's fall. */
    private static final double[] CHEBYSHEV={.1,.4,.6,.9};
    /** Below this enthalpy difference, J/kg, the air has reached saturation at the liquid: a pinch, not a division by zero. */
    private static final double PINCH = 1;
    /** The dry air's nitrogen and oxygen, mole fractions. */
    private static final double NITROGEN = .7808, OXYGEN = .2095;
    /**
     * Saturated air's vapour over the pressure, above which its content per kilogram of dry air, {@code r / (1 - r)}, is
     * continued with its value and slope (a liquid this close to boiling is no division by zero).
     */
    private static final double NEAR_BOILING = .9;
    /** The ambient air's wet bulb for water, K (25 °C at 50 %): the second temperature a liquid is read at. */
    private static final double AMBIENT_WET_BULB = 291.0;
    /** The fill coefficient, kg dry air/(s m³) at 2 kg dry air/(s m²), the ambient air, and utility water. */
    private record Calibration(FluidThermodynamics model,double coefficient,Liquid water) {
        Calibration with(double fitted){return new Calibration(model,fitted,water);}
    }
    // A single immutable record is safely published. A reload or another package cannot borrow its predecessor's fit.
    private static volatile Calibration calibration;

    /**
     * Positive mass and energy leave the liquid. {@code losses}: kg/s per component on the conserved basis (water last), a
     * gain negative. {@code dutyWatt} is the heat the liquid gave up; {@code energyWatt}, what the components that left carry
     * out with them (each at its vapour enthalpy on the model's datum, a gain at the ambient air's) and the heat to the air.
     * {@code airTemperature} and {@code airHumidity} (kg water per kg dry air) are the air leaving the fill.
     */
    public record Result(double outletTemperature, double[] losses, double dutyWatt, double energyWatt, double dryAirFlow, double fanPowerWatt, double speed,
                         double airTemperature, double airHumidity) {
        public Result {
            losses=losses.clone();
            for(double value:losses)if(!Double.isFinite(value))throw new IllegalArgumentException("Nonfinite tower transfer");
            for(double value:new double[]{outletTemperature,dutyWatt,energyWatt,dryAirFlow,fanPowerWatt,speed,airTemperature,airHumidity})
                if(!Double.isFinite(value))throw new IllegalArgumentException("Nonfinite tower transfer");
            if(outletTemperature<=0||airTemperature<=0||airHumidity<0||fanPowerWatt<0||speed<0||speed>1)throw new IllegalArgumentException("Invalid tower transfer");
        }
        @Override public double[] losses(){return losses.clone();}
        /** Component {@code c}'s loss, kg/s. */
        public double loss(int c){return losses[c];}
        /** The net mass that leaves the liquid, kg/s. */
        public double evaporation(){double total=0;for(double value:losses)total+=value;return total;}
        @Override public boolean equals(Object o){
            return o instanceof Result r&&Double.compare(outletTemperature,r.outletTemperature)==0&&Arrays.equals(losses,r.losses)
                &&Double.compare(dutyWatt,r.dutyWatt)==0&&Double.compare(energyWatt,r.energyWatt)==0&&Double.compare(dryAirFlow,r.dryAirFlow)==0
                &&Double.compare(fanPowerWatt,r.fanPowerWatt)==0&&Double.compare(speed,r.speed)==0
                &&Double.compare(airTemperature,r.airTemperature)==0&&Double.compare(airHumidity,r.airHumidity)==0;
        }
        @Override public int hashCode(){return 31*Arrays.hashCode(losses)+Double.hashCode(outletTemperature)+7*Double.hashCode(energyWatt);}
        @Override public String toString(){return "Result[outlet="+outletTemperature+", losses="+Arrays.toString(losses)+", duty="+dutyWatt+", energy="+energyWatt
            +", air="+dryAirFlow+", fan="+fanPowerWatt+", speed="+speed+", airT="+airTemperature+", airW="+airHumidity+"]";}
    }

    /**
     * The liquid a fill cools, read off the engine at its inlet temperature and a second one. Per kilogram of liquid:
     * {@code perKg}, mol of each component. Each component that can exchange (held by the liquid, or a gas of the air the
     * package holds) carries {@code ln(phi P) = a - b/T} of the liquid phase it is most concentrated in, its molar mass, its
     * vapour enthalpy at the inlet and the vapour's heat capacity, J/mol and J/(mol K), and whether it is a gas there.
     */
    static final class Liquid {
        final int components;final double inlet,heatCapacity,molesPerKg;final double[] perKg;
        final int[] index;final boolean[] gas;final double[] molarMass,fraction,a,b,vapor,vaporCp,ambient,reference,ambientHumidity;
        final double inertHumidity,ambientEnthalpy,wetBulb,ambientWater,waterMolarMass;final boolean boiling;final int water;
        private Liquid(FluidThermodynamics model,double inlet,double heatCapacity,double[] perKg,int[] index,boolean[] gas,double[] molarMass,
                       double[] fraction,double[] a,double[] b,double[] vapor,double[] vaporCp,double[] ambient,boolean boiling) {
            int components=model.componentCount(),water=components-1;ambientWater=TowerTransfer.ambientWater(model);waterMolarMass=model.waterMolecularWeight;
            this.components=components;this.inlet=inlet;this.heatCapacity=heatCapacity;this.perKg=perKg;this.index=index;this.gas=gas;
            this.molarMass=molarMass;this.fraction=fraction;this.a=a;this.b=b;this.vapor=vapor;this.vaporCp=vaporCp;this.ambient=ambient;this.water=water;
            double total=0;for(double n:perKg)total+=n;molesPerKg=total;
            int k=index.length;reference=new double[k];ambientHumidity=new double[k];
            double pw=ambientWaterPressure(model),inert=0;
            for(int i=0;i<k;i++){
                // Each compound's liquid at ZERO under ln(phi P) linear in 1/T: its vapour less R b (the latent heat out of this
                // liquid), less the liquid's sensible heat from ZERO; for water alone, about utility water's liquid at 0 °C.
                reference[i]=(vapor[i]-GAS_CONSTANT*b[i])/molarMass[i]-heatCapacity*(inlet-ZERO);
                if(index[i]==water&&!gas[i])ambientHumidity[i]=molarMass[i]/AIR_MOLAR_MASS*pw/(CoolingTower.PRESSURE-pw);
            }
            // The air's water passes as an inert vapour where the liquid's package holds no water.
            boolean exchanges=false;for(int i=0;i<k;i++)exchanges|=index[i]==water&&!gas[i];
            if(!exchanges)inert=ambientWater;
            inertHumidity=inert;this.boiling=boiling;
            double far=airEnthalpy(CoolingTower.AIR_TEMPERATURE,ambientHumidity);ambientEnthalpy=far;
            wetBulb=boiling?inlet:bulb(far);
        }
        double vapor(int i,double t){return vapor[i]+vaporCp[i]*(t-inlet);}
        /** Component {@code i}'s partial pressure over the liquid at {@code t}, Pa. */
        double partial(int i,double t){return fraction[i]*DeterministicMath.exp(a[i]-b[i]/t);}
        /** Saturated air's content at the liquid at {@code t}, kg per kg dry air, of each exchanging compound (a gas: none). */
        double[] saturated(double t) {
            int k=index.length;double[] w=new double[k];double sum=0;
            for(int i=0;i<k;i++)if(!gas[i]){w[i]=partial(i,t);sum+=w[i];}
            double r=sum/CoolingTower.PRESSURE,g=r<=NEAR_BOILING?1/(1-r):1/(1-NEAR_BOILING)+(r-NEAR_BOILING)/((1-NEAR_BOILING)*(1-NEAR_BOILING));
            for(int i=0;i<k;i++)if(!gas[i])w[i]=molarMass[i]/AIR_MOLAR_MASS*w[i]/CoolingTower.PRESSURE*g;
            return w;
        }
        /** Moist air enthalpy per kg of dry air at {@code t}, with content {@code w} of the exchanging compounds. */
        double airEnthalpy(double t,double[] w) {
            double h=CP_AIR*(t-ZERO)+inertHumidity*CP_VAPOR*(t-ZERO);
            for(int i=0;i<w.length;i++)if(w[i]!=0)h+=w[i]*(vapor(i,t)/molarMass[i]-reference[i]);
            return h;
        }
        double airHeatCapacity(double[] w){double c=CP_AIR+inertHumidity*CP_VAPOR;for(int i=0;i<w.length;i++)c+=w[i]*vaporCp[i]/molarMass[i];return c;}
        double saturatedEnthalpy(double t){return airEnthalpy(t,saturated(t));}
        double waterHumidity(double[] w){for(int i=0;i<index.length;i++)if(index[i]==water&&!gas[i])return w[i];return inertHumidity;}
        /** The liquid's wet bulb in the ambient air: saturated air at it has the ambient air's enthalpy. */
        private double bulb(double far) {
            DoubleUnaryOperator approach=t->saturatedEnthalpy(t)-far;
            double lo=Math.min(220,inlet-1),flo=approach.applyAsDouble(lo),hi=inlet,fhi=approach.applyAsDouble(hi);
            for(int n=0;n<8&&fhi<0;n++){hi+=25;fhi=approach.applyAsDouble(hi);}
            if(flo>=0)return lo;if(fhi<=0)return hi;
            return root(approach,lo,flo,hi,fhi,1e-10);
        }
    }

    /** The ambient air's water partial pressure, Pa, on the package's water record. */
    private static double ambientWaterPressure(FluidThermodynamics model){return CoolingTower.RELATIVE_HUMIDITY*model.saturationPressure(CoolingTower.AIR_TEMPERATURE);}
    /** The ambient air's humidity, kg water per kg dry air. */
    private static double ambientWater(FluidThermodynamics model){double pw=ambientWaterPressure(model);return model.waterMolecularWeight/AIR_MOLAR_MASS*pw/(CoolingTower.PRESSURE-pw);}

    private static double second(double inlet){return Math.abs(inlet-AMBIENT_WET_BULB)>=5?AMBIENT_WET_BULB:inlet-10;}
    /**
     * The liquid of {@code oil} and {@code aqueous} (amounts of the two liquid slots, any scale, conserved basis) at
     * {@code inlet}, at the atmosphere. A liquid whose compounds' partial pressures reach the atmosphere at the inlet boils
     * there ({@link Liquid#boiling}): it has no air contact.
     */
    static Liquid of(FluidThermodynamics model,double[] oil,double[] aqueous,double inlet) {
        int n=model.componentCount(),water=n-1;double p=CoolingTower.PRESSURE,t2=second(inlet);
        double[][] slots={oil,aqueous};double[] slotMoles=new double[2];double mass=0;
        for(int s=0;s<2;s++)for(int c=0;c<n;c++){slotMoles[s]+=slots[s][c];mass+=slots[s][c]*model.molecularWeight(c);}
        if(!(mass>0))throw new IllegalArgumentException("A fill's liquid has no mass");
        double[] perKg=new double[n];for(int c=0;c<n;c++)perKg[c]=(oil[c]+aqueous[c])/mass;
        HydrocarbonModel.Phase[][] at=new HydrocarbonModel.Phase[2][2];double h1=0,h2=0;
        try{
            for(int s=0;s<2;s++)if(slotMoles[s]>0){
                at[s][0]=model.hydrocarbon.phase(inlet,p,slots[s],PengRobinsonKernel.Root.LIQUID);
                at[s][1]=model.hydrocarbon.phase(t2,p,slots[s],PengRobinsonKernel.Root.LIQUID);
                h1+=slotMoles[s]*at[s][0].molarEnthalpy();h2+=slotMoles[s]*at[s][1].molarEnthalpy();
            }
        }catch(IllegalArgumentException noLiquid){
            return new Liquid(model,inlet,4186,perKg,new int[0],new boolean[0],new double[0],new double[0],new double[0],new double[0],new double[0],new double[0],new double[0],true);
        }
        int nitrogen=model.components().indexOf("Nitrogen"),oxygen=model.components().indexOf("Oxygen");
        double pw=ambientWaterPressure(model),dry=p-pw;var air=airComponents(model);
        int[] index=new int[n];int k=0;
        for(int c=0;c<n;c++){final int each=c;if(perKg[c]>0||java.util.Arrays.stream(air).anyMatch(q->q==each))index[k++]=c;}
        index=Arrays.copyOf(index,k);
        boolean[] gas=new boolean[k];double[] molarMass=new double[k],fraction=new double[k],a=new double[k],b=new double[k],vapor=new double[k],vaporCp=new double[k],ambient=new double[k];
        double bubble=0;
        for(int i=0;i<k;i++){
            int c=index[i];molarMass[i]=model.molecularWeight(c);gas[i]=model.hydrocarbon.criticalTemperature(c)<=inlet;
            // The slot it is most concentrated in (at equilibrium both give the same fugacity); the larger liquid for a trace.
            int s=-1;double best=-1;
            for(int q=0;q<2;q++)if(slotMoles[q]>0){double x=slots[q][c]/slotMoles[q];if(x>best||x==best&&slotMoles[q]>slotMoles[s]){best=x;s=q;}}
            fraction[i]=best;
            double l1=at[s][0].logFugacityView()[c]+DeterministicMath.log(p),l2=at[s][1].logFugacityView()[c]+DeterministicMath.log(p);
            b[i]=(l2-l1)/(1/inlet-1/t2);a[i]=l1+b[i]/inlet;
            double[] unit=new double[n];unit[c]=1;
            double v1=model.hydrocarbon.phase(inlet,1,unit,PengRobinsonKernel.Root.VAPOR).molarEnthalpy(),v2=model.hydrocarbon.phase(t2,1,unit,PengRobinsonKernel.Root.VAPOR).molarEnthalpy();
            vapor[i]=v1;vaporCp[i]=(v1-v2)/(inlet-t2);
            ambient[i]=c==nitrogen?NITROGEN*dry:c==oxygen?OXYGEN*dry:c==water?pw:0;
            if(!gas[i])bubble+=fraction[i]*DeterministicMath.exp(l1);
        }
        return new Liquid(model,inlet,(h1-h2)/(mass*(inlet-t2)),perKg,index,gas,molarMass,fraction,a,b,vapor,vaporCp,ambient,bubble>=p);
    }
    /** The components the ambient air brings to a fill: nitrogen, oxygen and water, those the package holds. */
    static int[] airComponents(FluidThermodynamics model) {
        int nitrogen=model.components().indexOf("Nitrogen"),oxygen=model.components().indexOf("Oxygen");
        return java.util.stream.IntStream.of(nitrogen,oxygen,model.wet()?model.componentCount()-1:-1).filter(c->c>=0).toArray();
    }
    /** Pure liquid water on the engine at {@code inlet}. */
    static Liquid water(FluidThermodynamics model,double inlet) {
        double[] none=new double[model.componentCount()],water=none.clone();water[water.length-1]=1;
        return of(model,none,water,inlet);
    }
    /** Utility water (the package's water record: Wagner-Pruss, Region 1, its steam) at {@code inlet}: the calibration's liquid. */
    private static Liquid utilityWater(FluidThermodynamics model,double inlet) {
        int n=model.componentCount();double t2=second(inlet),m=model.waterMolecularWeight;
        double l1=DeterministicMath.log(model.saturationPressure(inlet)),l2=DeterministicMath.log(model.saturationPressure(t2));
        double b=(l2-l1)/(1/inlet-1/t2),a=l1+b/inlet,h1=model.waterLiquid(inlet,CoolingTower.PRESSURE).molarEnthalpy(),h2=model.waterLiquid(t2,CoolingTower.PRESSURE).molarEnthalpy();
        double v1=model.vaporWaterEnthalpy(inlet),v2=model.vaporWaterEnthalpy(t2);
        double[] perKg=new double[n];perKg[n-1]=1/m;
        return new Liquid(model,inlet,(h1-h2)/(m*(inlet-t2)),perKg,new int[]{n-1},new boolean[]{false},new double[]{m},new double[]{1},new double[]{a},new double[]{b},
            new double[]{v1},new double[]{(v1-v2)/(inlet-t2)},new double[]{ambientWaterPressure(model)},false);
    }

    private static double density(double t,double vapourMass,double vapourMoles) {
        return CoolingTower.PRESSURE*(1+vapourMass)/(GAS_CONSTANT*t*(1/AIR_MOLAR_MASS+vapourMoles));
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
        var base=new Calibration(model,0,utilityWater(model,308.15));
        DoubleUnaryOperator excess=log->contact(base.with(DeterministicMath.exp(log)),base.water(),2,2,2,1).outletTemperature()-298.15;
        double lo=DeterministicMath.log(.0001),hi=DeterministicMath.log(100);
        var fitted=base.with(DeterministicMath.exp(root(excess,lo,excess.applyAsDouble(lo),hi,excess.applyAsDouble(hi),1e-10)));
        calibration=fitted;
        return fitted;
    }
    static double referenceOutlet(FluidThermodynamics model){var fit=calibration(model);return contact(fit,fit.water(),2,2,2,1).outletTemperature();}
    /** The calibration point as a fill's result: one square metre, 2 kg/s of utility water from 35 °C against 2 kg/s of dry air (L/G 1). */
    static Result reference(FluidThermodynamics model){
        var fit=calibration(model);var c=contact(fit,fit.water(),2,2,2,1);
        return new Result(c.outletTemperature(),c.losses(),c.dutyWatt(),c.energyWatt(),c.airFlow(),0,0,c.airTemperature(),c.airWater());
    }
    /**
     * The air leaving a fill (batch 2026-11-10-custom-shader-system, W4): its temperature, K, its humidity, kg water per kg
     * dry air, and that humidity over saturation's at its temperature.
     */
    public record Exhaust(double temperature,double humidity,double saturation) {}
    /** The air that {@code fill} leaves (a read, no search of the fill); the ambient air for no air flow. */
    public static Exhaust exhaust(FluidThermodynamics model,Result fill){
        double ambientW=ambientWater(model);
        if(!(Math.abs(fill.dryAirFlow())>NO_AIR))return new Exhaust(CoolingTower.AIR_TEMPERATURE,ambientW,CoolingTower.RELATIVE_HUMIDITY);
        double t=fill.airTemperature(),w=fill.airHumidity(),p=model.saturationPressure(t);
        double saturated=p<CoolingTower.PRESSURE?model.waterMolecularWeight/AIR_MOLAR_MASS*p/(CoolingTower.PRESSURE-p):Double.POSITIVE_INFINITY;
        return new Exhaust(t,w,w/saturated);
    }
    /** Below this dry air flow, kg/s, a tower has no exhaust to speak of. */
    public static final double NO_AIR=1e-3;
    /** Merkel's integral over a fall of {@code fall} from {@code inlet}, in an air that gains {@code gain} J/kg per kelvin of it. */
    private static double merkel(Liquid liquid,double inlet,double fall,double gain) {
        double total=0;
        for(double at:CHEBYSHEV) {
            double drive=liquid.saturatedEnthalpy(inlet-fall+at*fall)-liquid.ambientEnthalpy-gain*at*fall;
            total+=1/(fall>0?Math.max(drive,PINCH):Math.min(drive,-PINCH));
        }
        return liquid.heatCapacity*fall*total/CHEBYSHEV.length;
    }
    /** One cell's contact: its liquid, and the air the draft reads (its vapour per kg dry air, by mass and by moles). */
    private record Contact(double outletTemperature,double[] losses,double dutyWatt,double energyWatt,double airFlow,double airTemperature,
                           double airWater,double vapourMass,double vapourMoles) {}
    private static Contact still(Liquid liquid,double air) {
        double w=liquid.ambientWater;
        return new Contact(liquid.inlet,new double[liquid.components],0,0,air,CoolingTower.AIR_TEMPERATURE,w,w,w/liquid.waterMolarMass);
    }
    /** One cell's contact at a given air flow; no hidden saturation-at-exhaust assumption. Reverse air counts as counterflow. */
    private static Contact contact(Calibration fit,Liquid liquid,double water,double air,double fill,double area) {
        double inlet=liquid.inlet,far=liquid.ambientEnthalpy,span=inlet-liquid.wetBulb;
        if(water<=0||air==0||span==0||liquid.boiling)return still(liquid,air);
        int k=liquid.index.length;double g=Math.abs(air),capacity=water*liquid.heatCapacity,gain=capacity/g;
        double units=fit.coefficient()*area*fill*DeterministicMath.pow(g/(2*area),.6)/water;
        // The fall as a share of what the wet bulb allows, and of what the air could take up saturated at the inlet.
        double top=Math.min(1,(liquid.saturatedEnthalpy(inlet)-far)/(gain*span));
        DoubleUnaryOperator shortfall=share->merkel(liquid,inlet,share*span,gain)-units;
        double atTop=shortfall.applyAsDouble(top),share=atTop<=0?top:root(shortfall,0,-units,top,atTop,1e-11);
        double fall=share*span,exchanged=capacity*fall;
        // With a Lewis number of one the air moves straight towards saturation at the liquid's mean temperature, for every
        // compound. A compound's loss is at most what the stream brings, and its gain at most what brings the leaving liquid to
        // equilibrium with the ambient air (the air's water into a liquid that holds little of it).
        double film=inlet-fall/2,leaving=inlet-fall;double[] toward=liquid.saturated(film),out=new double[k],losses=new double[liquid.components];
        double drive=liquid.airEnthalpy(film,toward)-far,approach=(exchanged/g)/(drive>=0?Math.max(drive,PINCH):Math.min(drive,-PINCH)),excess=0;
        for(int i=0;i<k;i++)if(!liquid.gas[i]) {
            int c=liquid.index[i];double brought=water*liquid.perKg[c]*liquid.molarMass[i],equilibrium=liquid.ambient[i]/DeterministicMath.exp(liquid.a[i]-liquid.b[i]/leaving);
            double gainable=equilibrium<1?Math.max(0,(equilibrium*liquid.molesPerKg-liquid.perKg[c])/(1-equilibrium))*water*liquid.molarMass[i]:Double.POSITIVE_INFINITY;
            out[i]=liquid.ambientHumidity[i]+approach*(toward[i]-liquid.ambientHumidity[i]);
            double loss=g*(out[i]-liquid.ambientHumidity[i]),held=Math.clamp(loss,-gainable,brought);
            if(held!=loss){excess+=(loss-held)*(liquid.vapor(i,film)/liquid.molarMass[i]-liquid.reference[i]);loss=held;out[i]=liquid.ambientHumidity[i]+held/g;}
            losses[c]=loss;
        }
        double airEnthalpy=far+(exchanged-excess)/g,airTemperature=film;
        for(int n=0;n<4;n++)airTemperature-=(liquid.airEnthalpy(airTemperature,out)-airEnthalpy)/liquid.airHeatCapacity(out);
        double energy=(airEnthalpy-far)*g,evaporated=0;
        for(int i=0;i<k;i++)if(!liquid.gas[i]){energy+=losses[liquid.index[i]]*liquid.reference[i];evaporated+=losses[liquid.index[i]];}
        // The gases leave in equilibrium with the ambient air at the leaving temperature; a gain arrives at the air's.
        for(int i=0;i<k;i++)if(liquid.gas[i]) {
            int c=liquid.index[i];double equilibrium=liquid.ambient[i]/DeterministicMath.exp(liquid.a[i]-liquid.b[i]/leaving);
            double loss=liquid.molarMass[i]*water*(liquid.perKg[c]-equilibrium*liquid.molesPerKg);
            losses[c]=loss;evaporated+=loss;energy+=loss/liquid.molarMass[i]*liquid.vapor(i,loss>0?airTemperature:CoolingTower.AIR_TEMPERATURE);
        }
        // The liquid's balance: each component leaves at its partial molar enthalpy in the liquid, its vapour's less R b
        // (exact under ln(phi P) linear in 1/T), not at the mixture's specific enthalpy (a compound's datum, a formation
        // enthalpy, would then price the whole stream); so the fall is the latent and sensible heat that left, on no datum.
        double partial=0,partialCp=0;
        for(int i=0;i<k;i++){double moles=losses[liquid.index[i]]/liquid.molarMass[i];partial+=moles*(liquid.vapor[i]-GAS_CONSTANT*liquid.b[i]);partialCp+=moles*liquid.vaporCp[i];}
        double outlet=inlet-(energy-partial)/(capacity-partialCp);
        double vapourMass=liquid.inertHumidity,vapourMoles=liquid.inertHumidity/liquid.waterMolarMass;
        for(int i=0;i<k;i++)if(!liquid.gas[i]){vapourMass+=out[i];vapourMoles+=out[i]/liquid.molarMass[i];}
        return new Contact(outlet,losses,capacity*(inlet-outlet),energy,air,airTemperature,liquid.waterHumidity(out),vapourMass,vapourMoles);
    }
    /** One representative parallel cell; extensive quantities are multiplied once by its cell count. */
    static Result fill(FluidThermodynamics model,CoolingTower tower,Liquid liquid,double mass,double speed) {
        var fit=calibration(model);double area=tower.cellArea(),w=ambientWater(model),rho=density(CoolingTower.AIR_TEMPERATURE,w,w/model.waterMolecularWeight);
        // The search runs on flow|flow|, in which resistance is a straight line, so false position lands in a few trials.
        DoubleUnaryOperator imbalance=square->draught(fit,tower,liquid,mass,speed,signedRoot(square),rho).residual();
        // Warm liquid can hold both a rising and a falling draft. Still air picks, as it does when a chimney starts to draw.
        double still=imbalance.applyAsDouble(0),end=still<0?144*area*area:-144*area*area,atEnd=imbalance.applyAsDouble(end);
        // Expand the pressure bracket rather than limiting a tower's airflow at a chosen mass loading.
        while(still*atEnd>0){end*=4;atEnd=imbalance.applyAsDouble(end);}
        double square=still==0?0:root(imbalance,0,still,end,atEnd,1e-11);
        var r=draught(fit,tower,liquid,mass,speed,signedRoot(square),rho).transfer();
        int n=tower.cells();double[] losses=r.losses().clone();for(int c=0;c<losses.length;c++)losses[c]*=n;
        // Fan power depends on the tower's geometry and speed, not on its solved liquid or air state.
        return new Result(r.outletTemperature(),losses,r.dutyWatt()*n,r.energyWatt()*n,r.airFlow()*n,tower.fanPower(speed),speed,r.airTemperature(),r.airWater());
    }
    /** Pure water on the engine: {@link #fill(FluidThermodynamics, CoolingTower, Liquid, double, double)} of {@link #water}. */
    public static Result fill(FluidThermodynamics model,CoolingTower tower,double water,double inlet,double speed) {
        if(water>0)return fill(model,tower,water(model,inlet),water,speed);
        // No spray: the draught alone, on any liquid (none touches the air); the leaving temperature is the one given.
        var r=fill(model,tower,calibration(model).water(),0,speed);
        return new Result(inlet,r.losses(),0,0,r.dryAirFlow(),r.fanPowerWatt(),speed,r.airTemperature(),r.airHumidity());
    }
    private static double signedRoot(double square){return Math.signum(square)*Math.sqrt(Math.abs(square));}
    /** The fan speed that brings the leaving liquid to the tower's set point; the nearer end stop when no speed does. */
    static double targetSpeed(FluidThermodynamics model,CoolingTower tower,Liquid liquid,double mass) {
        DoubleUnaryOperator error=speed->fill(model,tower,liquid,mass,speed).outletTemperature()-tower.setPoint();
        double low=error.applyAsDouble(0),high=error.applyAsDouble(1);
        if(low*high>=0)return Math.abs(low)<=Math.abs(high)?0:1;
        return Math.clamp(root(error,0,low,1,high,1e-6),0,1);
    }
    private record Draught(Contact transfer,double residual) {}
    private static Draught draught(Calibration fit,CoolingTower tower,Liquid liquid,double mass,double speed,double air,double rho){
        // Evaluate the zero-flow limit from moving air; the exact zero state has no defined exhaust density.
        if(Math.abs(air)<1e-12*tower.cellArea())air=1e-8*tower.cellArea();
        var transfer=contact(fit,liquid,mass/tower.cells(),air,tower.fillDepth(),tower.cellArea());
        double buoyancy=9.80665*Math.max(1,tower.height()-tower.distributorHeight())*(rho-density(transfer.airTemperature(),transfer.vapourMass(),transfer.vapourMoles()));
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
