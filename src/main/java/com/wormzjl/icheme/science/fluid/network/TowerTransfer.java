package com.wormzjl.icheme.science.fluid.network;
import com.wormzjl.icheme.science.math.DeterministicMath;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.thermo.HydrocarbonModel;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import java.util.Arrays;
import java.util.function.DoubleUnaryOperator;

/**
 * A cooling tower as one atmospheric contact (COOLING_TOWER_PLAN.md): the tower brings what its spray delivers to
 * atmospheric pressure in the air stream. What is gas there leaves with the air ({@link Stream}: the arriving gas, and the
 * vapour a liquid above its bubble point flashes at the nozzle, {@link Liquid#nozzle}); the liquid exchanges heat and every
 * compound with the air in the fill; the liquid that remains falls to the basin.
 *
 * <p>The fill is Merkel's rating: the air's enthalpy against that of saturated air at the liquid, integrated over the
 * liquid's temperature fall by the four Chebyshev points. A fill runs inside every Newton residual of its island, so its
 * only searches are the nozzle's flash, that fall, the draft and the fan speed ({@link #root}), and nothing iterates along
 * the fill. Saturated air at the liquid holds each compound at its partial pressure {@code x_i phi_i P} from the equation
 * of state, read at the inlet and at a second temperature ({@link Liquid}): between them {@code ln(phi_i P)} is linear in
 * {@code 1/T} and the liquid's heat capacity constant; vapour enthalpies are the model's ideal-gas functions. The air moves
 * straight towards that state for every compound (Lewis number one). The air's nitrogen and oxygen leave the liquid
 * saturated at its leaving temperature.
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
    /** The lowest temperature the nozzle's flash searches, K. */
    private static final double COLDEST = 150;
    /** The fill coefficient, kg dry air/(s m³) at 2 kg dry air/(s m²), the ambient air, and utility water. */
    private record Calibration(FluidThermodynamics model,double coefficient,Liquid water) {
        Calibration with(double fitted){return new Calibration(model,fitted,water);}
    }
    // A single immutable record is safely published. A reload or another package cannot borrow its predecessor's fit.
    private static volatile Calibration calibration;

    /**
     * What a tower's sprays deliver, per second (COOLING_TOWER_PLAN.md 3.1): mol/s per component on the conserved basis of
     * its gas and of its two liquid slots, the gas's and the liquid's enthalpies as transported (W), the liquid's mass
     * (kg/s, without solids) and its temperature.
     */
    public record Stream(double[] gas,double[] oil,double[] aqueous,double gasEnthalpy,double liquidEnthalpy,double liquidMass,double temperature) {
        static Stream none(int components){return new Stream(new double[components],new double[components],new double[components],0,0,0,0);}
        Stream plus(Stream other) {
            int n=gas.length;double[] g=gas.clone(),o=oil.clone(),a=aqueous.clone();
            for(int c=0;c<n;c++){g[c]+=other.gas[c];o[c]+=other.oil[c];a[c]+=other.aqueous[c];}
            double mass=liquidMass+other.liquidMass;
            return new Stream(g,o,a,gasEnthalpy+other.gasEnthalpy,liquidEnthalpy+other.liquidEnthalpy,mass,
                mass>0?(liquidMass*temperature+other.liquidMass*other.temperature)/mass:Math.max(temperature,other.temperature));
        }
        /**
         * The stream {@code q} kg/s of a donor delivers: its slots in the flow's proportion at a bulk end ({@code rates}
         * null), and otherwise the phases a phase draw takes ({@code rates}: {@link PhaseDraw.Segment#rates}' six mass flows,
         * by phase on {@code end}, then on {@code start}).
         */
        static Stream of(FluidThermodynamics model,double q,double[] rates,FluidThermodynamics.State end,FluidThermodynamics.State start) {
            int n=model.componentCount();var result=none(n);
            if(rates==null)return result.plus(bulk(model,end,q/end.mass()));
            for(int k=0;k<6;k++)if(rates[k]>0){
                var state=k<3?end:start;int phase=k%3;double mass=model.phaseMass(state,phase);
                if(!(mass>0))continue;
                double share=rates[k]/mass;
                double[] moles=FluidThermodynamics.phaseMoles(state,phase);for(int c=0;c<n;c++)moles[c]*=share;
                double heat=rates[k]*model.phaseSpecificEnthalpy(state,phase,null),fluid=0;for(int c=0;c<n;c++)fluid+=moles[c]*model.molecularWeight(c);
                var none=new double[n];
                result=result.plus(phase==FluidThermodynamics.GAS?new Stream(moles,none,none.clone(),heat,0,0,state.temperature())
                    :new Stream(none,phase==FluidThermodynamics.OIL?moles:none.clone(),phase==FluidThermodynamics.AQUEOUS?moles:none.clone(),0,heat,fluid,state.temperature()));
            }
            return result;
        }
        private static Stream bulk(FluidThermodynamics model,FluidThermodynamics.State s,double share) {
            int n=model.componentCount();double[] g=new double[n],o=new double[n],a=new double[n];
            double[] vapour=s.vaporAmountsView(),oil=s.liquidAmountsView(),water=s.aqueousAmountsView();double mass=0;
            for(int c=0;c<n;c++){g[c]=share*vapour[c];o[c]=share*oil[c];a[c]=share*water[c];mass+=(o[c]+a[c])*model.molecularWeight(c);}
            double gasHeat=FluidThermodynamics.holdsVapor(s)?share*model.phaseMass(s,FluidThermodynamics.GAS)*model.phaseSpecificEnthalpy(s,FluidThermodynamics.GAS,null):0;
            // The fluid's enthalpy: the solids fall to the basin with theirs, so none of it leaves with the air.
            return new Stream(g,o,a,gasHeat,share*s.fluidEnthalpy()-gasHeat,mass,s.temperature());
        }
    }

    /**
     * Positive mass and energy leave the stream for the air. {@code losses}: kg/s per component on the conserved basis
     * (water last), a gain negative: the arriving gas, the nozzle's vapour and the fill's exchange. {@code dutyWatt} is the
     * heat the liquid gave up, nozzle included; {@code energyWatt}, what the components that left carry (the arriving gas
     * as transported, every vapour at its ideal-gas enthalpy on the model's datum, a gain at the ambient air's) and the heat
     * to the air. {@code airTemperature} and {@code airHumidity} (kg water per kg dry air) are the air leaving the fill.
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
        /** The net mass that leaves for the air, kg/s. */
        public double evaporation(){double total=0;for(double value:losses)total+=value;return total;}
        /** Water's loss, kg/s (water last on the conserved basis). */
        public double waterLoss(){return losses[losses.length-1];}
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

    /** A vapour's enthalpy, J/mol, and heat capacity, J/(mol K), of conserved component {@code c}. */
    private interface Vapour { double enthalpy(int c,double t); double heatCapacity(int c,double t); }

    /**
     * The liquid a fill cools: per kilogram, {@code perKg} mol of each component. Each component that can exchange (held by
     * the liquid, or a gas of the air the package holds) carries {@code ln(phi P) = a - b/T} of the liquid slot it is most
     * concentrated in ({@code fraction}: its mole fraction there; {@code pooled}: that fraction to the whole liquid's), its
     * molar mass and the ambient air's partial pressure of it; the air's nitrogen and oxygen also carry that line slot by
     * slot. {@code R b} is the latent heat out of this liquid.
     */
    static final class Liquid {
        final int components;final double inlet,heatCapacity,molesPerKg,ambientWater,waterMolarMass,inertHumidity,ambientEnthalpy;
        final double[] perKg,slotShare;final int[] index;final boolean[] air;
        final double[] molarMass,fraction,pooled,a,b,ambient,reference,ambientHumidity;final double[][] slotA,slotB;final Vapour vapour;
        private Liquid(FluidThermodynamics model,Vapour vapour,double inlet,double heatCapacity,double[] perKg,double[] slotShare,int[] index,boolean[] air,
                       double[] molarMass,double[] fraction,double[] pooled,double[] a,double[] b,double[][] slotA,double[][] slotB,double[] ambient) {
            components=model.componentCount();ambientWater=TowerTransfer.ambientWater(model);waterMolarMass=model.waterMolecularWeight;
            this.vapour=vapour;this.inlet=inlet;this.heatCapacity=heatCapacity;this.perKg=perKg;this.slotShare=slotShare;this.index=index;this.air=air;
            this.molarMass=molarMass;this.fraction=fraction;this.pooled=pooled;this.a=a;this.b=b;this.slotA=slotA;this.slotB=slotB;this.ambient=ambient;
            double total=0;for(double n:perKg)total+=n;molesPerKg=total;
            int k=index.length,water=components-1;reference=new double[k];ambientHumidity=new double[k];
            double pw=ambientWaterPressure(model);boolean exchanges=false;
            for(int i=0;i<k;i++){
                // Each compound's liquid at ZERO under ln(phi P) linear in 1/T: its vapour less R b (the latent heat out of this
                // liquid), less the liquid's sensible heat from ZERO; for water alone, about utility water's liquid at 0 °C.
                reference[i]=(vapor(i,inlet)-GAS_CONSTANT*b[i])/molarMass[i]-heatCapacity*(inlet-ZERO);
                if(index[i]==water){ambientHumidity[i]=molarMass[i]/AIR_MOLAR_MASS*pw/(CoolingTower.PRESSURE-pw);exchanges=true;}
            }
            // The air's water passes as an inert vapour where the liquid's package holds no water.
            inertHumidity=exchanges?0:ambientWater;
            ambientEnthalpy=airEnthalpy(CoolingTower.AIR_TEMPERATURE,ambientHumidity);
        }
        /** The same liquid at {@code temperature} with {@code perKg} instead: the table stands, each fraction moved with the pooled mole fraction. */
        private Liquid after(FluidThermodynamics model,double temperature,double[] perKg) {
            double total=0;for(double n:perKg)total+=n;double[] fraction=this.fraction.clone();
            for(int i=0;i<index.length;i++){int c=index[i];double before=this.perKg[c]/molesPerKg,now=perKg[c]/total;if(before>0)fraction[i]*=now/before;}
            return new Liquid(model,vapour,temperature,heatCapacity,perKg,slotShare,index,air,molarMass,fraction,pooled,a,b,slotA,slotB,ambient);
        }
        double vapor(int i,double t){return vapour.enthalpy(index[i],t);}
        /** Component {@code i}'s partial pressure over the liquid at {@code t}, Pa. */
        double partial(int i,double t){return fraction[i]*DeterministicMath.exp(a[i]-b[i]/t);}
        /**
         * Saturated air's content at the liquid at {@code t}, kg per kg dry air, of each exchanging compound but the air's
         * nitrogen and oxygen. A compound's {@code p/P} is limited, as the smooth lesser of the two (the 4-norm, a bulk
         * compound unchanged to 1e-6), by {@code cap}: what the stream brings and the ambient air holds, as a mole fraction.
         */
        double[] saturated(double t,double[] cap) {
            int k=index.length;double[] w=new double[k];double sum=0;
            for(int i=0;i<k;i++)if(!air[i]){w[i]=lesser(partial(i,t)/CoolingTower.PRESSURE,cap[i]);sum+=w[i];}
            double g=sum<=NEAR_BOILING?1/(1-sum):1/(1-NEAR_BOILING)+(sum-NEAR_BOILING)/((1-NEAR_BOILING)*(1-NEAR_BOILING));
            for(int i=0;i<k;i++)if(!air[i])w[i]*=molarMass[i]/AIR_MOLAR_MASS*g;
            return w;
        }
        /** Moist air enthalpy per kg of dry air at {@code t}, with content {@code w} of the exchanging compounds. */
        double airEnthalpy(double t,double[] w) {
            double h=CP_AIR*(t-ZERO)+inertHumidity*CP_VAPOR*(t-ZERO);
            for(int i=0;i<w.length;i++)if(w[i]!=0)h+=w[i]*(vapor(i,t)/molarMass[i]-reference[i]);
            return h;
        }
        double airHeatCapacity(double t,double[] w){double c=CP_AIR+inertHumidity*CP_VAPOR;for(int i=0;i<w.length;i++)if(w[i]!=0)c+=w[i]*vapour.heatCapacity(index[i],t)/molarMass[i];return c;}
        double saturatedEnthalpy(double t,double[] cap){return airEnthalpy(t,saturated(t,cap));}
        double waterHumidity(double[] w){for(int i=0;i<index.length;i++)if(index[i]==components-1)return w[i];return inertHumidity;}
        /** The liquid's wet bulb in the ambient air: saturated air at it has the ambient air's enthalpy. */
        double wetBulb(double[] cap) {
            double far=ambientEnthalpy;DoubleUnaryOperator approach=t->saturatedEnthalpy(t,cap)-far;
            double lo=Math.min(220,inlet-1),flo=approach.applyAsDouble(lo),hi=inlet,fhi=approach.applyAsDouble(hi);
            for(int n=0;n<8&&fhi<0;n++){hi+=25;fhi=approach.applyAsDouble(hi);}
            if(flo>=0)return lo;if(fhi<=0)return hi;
            return root(approach,lo,flo,hi,fhi,1e-10);
        }
        /** The caps of {@link #saturated} for {@code water} kg/s of this liquid against {@code g} kg/s of dry air. */
        double[] caps(double water,double g) {
            double[] cap=new double[index.length];
            for(int i=0;i<cap.length;i++)cap[i]=(water*perKg[index[i]]*molarMass[i]/g+ambientHumidity[i])*AIR_MOLAR_MASS/molarMass[i];
            return cap;
        }

        /**
         * The let-down at the nozzle (COOLING_TOWER_PLAN.md 3.2): no flash where the partial pressures sum to no more than
         * the atmosphere at the inlet; otherwise a flash at constant enthalpy on the table, the vapour share {@code psi} and
         * the temperature {@code T'} solving Rachford-Rice and {@code c_p (T_in - T') = psi sum y_i R b_i} per mole. The
         * outer search is on {@code psi} (the heat balance falls with it), the inner one on {@code T'} at that share
         * (Rachford-Rice's sum rises with T): a single compound's flash, at its boiling point for every share, is no step.
         * No engine call.
         */
        Nozzle nozzle(FluidThermodynamics model) {
            double p=CoolingTower.PRESSURE;int k=index.length;
            double[] z=new double[k];for(int i=0;i<k;i++)z[i]=perKg[index[i]]/molesPerKg;
            DoubleUnaryOperator bubble=t->{double sum=0;for(int i=0;i<k;i++)if(z[i]>0)sum+=partial(i,t);return sum/p-1;};
            if(bubble.applyAsDouble(inlet)<=0)return new Nozzle(new double[components],1,inlet,0,this);
            double molar=heatCapacity/molesPerKg;
            java.util.function.DoubleUnaryOperator at=psi->flashTemperature(z,psi);
            DoubleUnaryOperator heat=psi->{double t=at.applyAsDouble(psi),latent=0;
                for(int i=0;i<k;i++)if(z[i]>0){double kk=partial(i,t)/(z[i]*p);latent+=z[i]*kk/(1+psi*(kk-1))*GAS_CONSTANT*b[i];}
                return molar*(inlet-t)-psi*latent;};
            double h0=heat.applyAsDouble(0),h1=heat.applyAsDouble(1);
            double psi=h0<=0?0:h1>=0?1:root(heat,0,h0,1,h1,1e-13),t=at.applyAsDouble(psi);
            double[] vapourPerKg=new double[components],liquidPerKg=new double[components];double vapourMass=0,energy=0;
            for(int i=0;i<k;i++)if(z[i]>0){
                int c=index[i];double kk=partial(i,t)/(z[i]*p),x=z[i]/(1+psi*(kk-1));
                vapourPerKg[c]=psi*kk*x*molesPerKg;liquidPerKg[c]=(1-psi)*x*molesPerKg;
                vapourMass+=vapourPerKg[c]*molarMass[i];energy+=vapourPerKg[c]*vapor(i,t);
            }
            double remaining=1-vapourMass;
            if(!(remaining>1e-12)||psi>=1){
                double all=0;for(int i=0;i<k;i++)if(z[i]>0)all+=perKg[index[i]]*vapor(i,t);
                return new Nozzle(perKg.clone(),0,t,all,null);
            }
            for(int c=0;c<components;c++)liquidPerKg[c]/=remaining;
            return new Nozzle(vapourPerKg,remaining,t,energy,after(model,t,liquidPerKg));
        }
        /** The temperature at which vapour share {@code psi} satisfies Rachford-Rice at the atmosphere; {@link #COLDEST} where even there it is exceeded. */
        private double flashTemperature(double[] z,double psi) {
            double p=CoolingTower.PRESSURE;
            DoubleUnaryOperator f=t->{double sum=0;for(int i=0;i<z.length;i++)if(z[i]>0){double kk=partial(i,t)/(z[i]*p);sum+=z[i]*(kk-1)/(1+psi*(kk-1));}return sum;};
            double lo=COLDEST,flo=f.applyAsDouble(lo);if(flo>=0)return lo;
            double hi=inlet,fhi=f.applyAsDouble(hi);
            for(int n=0;n<16&&fhi<0;n++){hi+=50;fhi=f.applyAsDouble(hi);}
            if(fhi<=0)return hi;
            return root(f,lo,flo,hi,fhi,1e-13);
        }
    }
    /** The nozzle's let-down per kilogram of the stream's liquid: the vapour (mol/kg by component), the liquid left (kg/kg), its temperature, the vapour's enthalpy (J/kg) and that liquid (null when nothing stays liquid). */
    record Nozzle(double[] vapourPerKg,double remaining,double temperature,double energyPerKg,Liquid liquid) {}

    /** The smooth lesser of two positive numbers, {@code x y / (x^4 + y^4)^(1/4)}; the smaller one where they are three decades apart. */
    private static double lesser(double x,double y){
        if(!(x>0)||!(y>0))return 0;
        double r=y/x;if(r>1e3)return x;if(r<1e-3)return y;
        double xx=x*x,yy=y*y;return x*y/Math.sqrt(Math.sqrt(xx*xx+yy*yy));
    }
    /** The ambient air's water partial pressure, Pa, on the package's water record. */
    private static double ambientWaterPressure(FluidThermodynamics model){return CoolingTower.RELATIVE_HUMIDITY*model.saturationPressure(CoolingTower.AIR_TEMPERATURE);}
    /** The ambient air's humidity, kg water per kg dry air. */
    private static double ambientWater(FluidThermodynamics model){double pw=ambientWaterPressure(model);return model.waterMolecularWeight/AIR_MOLAR_MASS*pw/(CoolingTower.PRESSURE-pw);}
    /** The table's second reading, K: always below the inlet and smooth in it (288.3 K for a 308.15 K inlet, 281 K at the
     * ambient wet bulb); a switch here would put a step in the table, and so in the residual. */
    private static double second(double inlet){double d=inlet-AMBIENT_WET_BULB;return inlet-Math.sqrt(d*d+100);}
    /** The components the ambient air brings to a fill: nitrogen, oxygen and water, those the package holds. */
    static int[] airComponents(FluidThermodynamics model) {
        int nitrogen=model.components().indexOf("Nitrogen"),oxygen=model.components().indexOf("Oxygen");
        return java.util.stream.IntStream.of(nitrogen,oxygen,model.wet()?model.componentCount()-1:-1).filter(c->c>=0).toArray();
    }
    /**
     * The liquid of {@code oil} and {@code aqueous} (amounts of the two liquid slots, any scale, conserved basis) at
     * {@code inlet}, at the atmosphere; null where the equation of state has no liquid root there (the whole liquid then
     * leaves with the air, {@link #spray}).
     */
    static Liquid of(FluidThermodynamics model,double[] oil,double[] aqueous,double inlet) {
        int n=model.componentCount();double p=CoolingTower.PRESSURE,t2=second(inlet);
        double[][] slots={oil,aqueous};double[] slotMoles=new double[2];double mass=0;
        for(int s=0;s<2;s++)for(int c=0;c<n;c++){slotMoles[s]+=slots[s][c];mass+=slots[s][c]*model.molecularWeight(c);}
        double total=slotMoles[0]+slotMoles[1];
        if(!(mass>0))return null;
        double[] perKg=new double[n];for(int c=0;c<n;c++)perKg[c]=(oil[c]+aqueous[c])/mass;
        HydrocarbonModel.Phase[][] at=new HydrocarbonModel.Phase[2][2];double h1=0,h2=0;
        try{
            for(int s=0;s<2;s++)if(slotMoles[s]>0){
                at[s][0]=model.hydrocarbon.phase(inlet,p,slots[s],PengRobinsonKernel.Root.LIQUID);
                at[s][1]=model.hydrocarbon.phase(t2,p,slots[s],PengRobinsonKernel.Root.LIQUID);
                h1+=slotMoles[s]*at[s][0].molarEnthalpy();h2+=slotMoles[s]*at[s][1].molarEnthalpy();
            }
        }catch(IllegalArgumentException noLiquid){return null;}
        var air=airComponents(model);int nitrogen=model.components().indexOf("Nitrogen"),oxygen=model.components().indexOf("Oxygen");
        double pw=ambientWaterPressure(model),dry=p-pw,lnP=DeterministicMath.log(p);
        int[] index=new int[n];int k=0;
        for(int c=0;c<n;c++){final int each=c;if(perKg[c]>0||Arrays.stream(air).anyMatch(q->q==each))index[k++]=c;}
        index=Arrays.copyOf(index,k);
        boolean[] isAir=new boolean[k];double[] molarMass=new double[k],fraction=new double[k],pooled=new double[k],a=new double[k],b=new double[k],ambient=new double[k];
        double[][] slotA=new double[k][2],slotB=new double[k][2];double[] share={slotMoles[0]/total,slotMoles[1]/total};
        for(int i=0;i<k;i++){
            int c=index[i];molarMass[i]=model.molecularWeight(c);isAir[i]=c==nitrogen||c==oxygen;
            // The slot it is most concentrated in (at equilibrium both give the same fugacity); the larger liquid for a trace.
            int s=-1;double best=-1;
            for(int q=0;q<2;q++)if(slotMoles[q]>0){double x=slots[q][c]/slotMoles[q];if(x>best||x==best&&slotMoles[q]>slotMoles[s]){best=x;s=q;}}
            fraction[i]=best;pooled[i]=best>0?(perKg[c]*mass/total)/best:share[s];
            for(int q=0;q<2;q++)if(slotMoles[q]>0){
                double l1=at[q][0].logFugacityView()[c]+lnP,l2=at[q][1].logFugacityView()[c]+lnP;
                slotB[i][q]=(l2-l1)/(1/inlet-1/t2);slotA[i][q]=l1+slotB[i][q]/inlet;
            }
            a[i]=slotA[i][s];b[i]=slotB[i][s];
            ambient[i]=c==nitrogen?NITROGEN*dry:c==oxygen?OXYGEN*dry:c==n-1?pw:0;
        }
        var hc=model.hydrocarbon;
        Vapour ideal=new Vapour(){public double enthalpy(int c,double t){return hc.idealGasEnthalpy(c,t);}public double heatCapacity(int c,double t){return hc.idealGasHeatCapacity(c,t);}};
        return new Liquid(model,ideal,inlet,(h1-h2)/(mass*(inlet-t2)),perKg,share,index,isAir,molarMass,fraction,pooled,a,b,slotA,slotB,ambient);
    }
    /** Utility water (the package's water record: Wagner-Pruss, Region 1, its steam) at {@code inlet}: the calibration's liquid. */
    private static Liquid utilityWater(FluidThermodynamics model,double inlet) {
        int n=model.componentCount();double t2=second(inlet),m=model.waterMolecularWeight;
        double l1=DeterministicMath.log(model.saturationPressure(inlet)),l2=DeterministicMath.log(model.saturationPressure(t2));
        double b=(l2-l1)/(1/inlet-1/t2),a=l1+b/inlet,h1=model.waterLiquid(inlet,CoolingTower.PRESSURE).molarEnthalpy(),h2=model.waterLiquid(t2,CoolingTower.PRESSURE).molarEnthalpy();
        double[] perKg=new double[n];perKg[n-1]=1/m;
        Vapour steam=new Vapour(){public double enthalpy(int c,double t){return model.vaporWaterEnthalpy(t);}public double heatCapacity(int c,double t){return (model.vaporWaterEnthalpy(t+.01)-model.vaporWaterEnthalpy(t-.01))/.02;}};
        return new Liquid(model,steam,inlet,(h1-h2)/(m*(inlet-t2)),perKg,new double[]{0,1},new int[]{n-1},new boolean[]{false},new double[]{m},new double[]{1},new double[]{1},
            new double[]{a},new double[]{b},new double[][]{{a,a}},new double[][]{{b,b}},new double[]{ambientWaterPressure(model)});
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
    private static double merkel(Liquid liquid,double[] cap,double inlet,double fall,double gain) {
        double total=0;
        for(double at:CHEBYSHEV) {
            double drive=liquid.saturatedEnthalpy(inlet-fall+at*fall,cap)-liquid.ambientEnthalpy-gain*at*fall;
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
        if(water<=0||air==0)return still(liquid,air);
        int k=liquid.index.length;double g=Math.abs(air),inlet=liquid.inlet,far=liquid.ambientEnthalpy;
        double[] cap=liquid.caps(water,g);double span=inlet-liquid.wetBulb(cap);
        if(span==0)return still(liquid,air);
        double capacity=water*liquid.heatCapacity,gain=capacity/g;
        double units=fit.coefficient()*area*fill*DeterministicMath.pow(g/(2*area),.6)/water;
        // The fall as a share of what the wet bulb allows, and of what the air could take up saturated at the inlet.
        double top=Math.min(1,(liquid.saturatedEnthalpy(inlet,cap)-far)/(gain*span));
        DoubleUnaryOperator shortfall=share->merkel(liquid,cap,inlet,share*span,gain)-units;
        double atTop=shortfall.applyAsDouble(top),share=atTop<=0?top:root(shortfall,0,-units,top,atTop,1e-11);
        double fall=share*span,exchanged=capacity*fall;
        // With a Lewis number of one the air moves straight towards saturation at the liquid's mean temperature, for every
        // compound. A compound's loss is at most what the stream brings, and its gain at most what brings the leaving liquid to
        // equilibrium with the ambient air (the air's water into a liquid that holds little of it).
        double film=inlet-fall/2,leaving=inlet-fall;double[] toward=liquid.saturated(film,cap);
        double drive=liquid.airEnthalpy(film,toward)-far,approach=(exchanged/g)/(drive>=0?Math.max(drive,PINCH):Math.min(drive,-PINCH));
        // Two passes: the caps and the air's gases read the leaving temperature, first the rating's (inlet - fall), then
        // the liquid balance's (the outlet below), which for a mixture differs by a few kelvin (n-hexane: 3 K).
        // The equilibrium caps count the liquid that leaves: on the second pass, the inlet's moles less what the first left.
        double[] out=null,losses=null;double energy=0,airTemperature=film,outlet=leaving,leavingMoles=water*liquid.molesPerKg;
        for(int pass=0;pass<2;pass++) {
            if(pass==1){leaving=outlet;for(int c=0;c<liquid.components;c++)if(losses[c]!=0)leavingMoles-=losses[c]/molarMassOf(liquid,c);}
            out=new double[k];losses=new double[liquid.components];double excess=0;
            for(int i=0;i<k;i++)if(!liquid.air[i]) {
                int c=liquid.index[i];double brought=water*liquid.perKg[c]*liquid.molarMass[i],own=water*liquid.perKg[c];
                double equilibrium=liquid.pooled[i]*liquid.ambient[i]/DeterministicMath.exp(liquid.a[i]-liquid.b[i]/leaving);
                double gainable=equilibrium<1?Math.max(0,equilibrium*(leavingMoles-own)/(1-equilibrium)-own)*liquid.molarMass[i]:Double.POSITIVE_INFINITY;
                out[i]=liquid.ambientHumidity[i]+approach*(toward[i]-liquid.ambientHumidity[i]);
                double loss=g*(out[i]-liquid.ambientHumidity[i]),held=Math.clamp(loss,-gainable,brought);
                if(held!=loss){excess+=(loss-held)*(liquid.vapor(i,film)/liquid.molarMass[i]-liquid.reference[i]);loss=held;out[i]=liquid.ambientHumidity[i]+held/g;}
                losses[c]=loss;
            }
            double airEnthalpy=far+(exchanged-excess)/g;airTemperature=film;
            for(int n=0;n<4;n++)airTemperature-=(liquid.airEnthalpy(airTemperature,out)-airEnthalpy)/liquid.airHeatCapacity(airTemperature,out);
            energy=(airEnthalpy-far)*g;
            for(int i=0;i<k;i++)if(!liquid.air[i])energy+=losses[liquid.index[i]]*liquid.reference[i];
            // The air's nitrogen and oxygen: the liquid leaves saturated at its leaving temperature, slot by slot; a gain
            // arrives at the ambient air's temperature.
            for(int i=0;i<k;i++)if(liquid.air[i]) {
                int c=liquid.index[i];double held=0;
                for(int q=0;q<2;q++)if(liquid.slotShare[q]>0)held+=liquid.slotShare[q]*liquid.ambient[i]/DeterministicMath.exp(liquid.slotA[i][q]-liquid.slotB[i][q]/leaving);
                double loss=liquid.molarMass[i]*(water*liquid.perKg[c]-held*leavingMoles);
                losses[c]=loss;energy+=loss/liquid.molarMass[i]*liquid.vapor(i,loss>0?airTemperature:CoolingTower.AIR_TEMPERATURE);
            }
            // The liquid's balance: each component leaves at its partial molar enthalpy in the liquid, its vapour's less R b
            // (exact under ln(phi P) linear in 1/T), not at the mixture's specific enthalpy (a compound's datum, a formation
            // enthalpy, would then price the whole stream); so the fall is the latent and sensible heat that left, on no datum.
            double partial=0,partialCp=0;
            for(int i=0;i<k;i++){double moles=losses[liquid.index[i]]/liquid.molarMass[i];partial+=moles*(liquid.vapor(i,inlet)-GAS_CONSTANT*liquid.b[i]);partialCp+=moles*liquid.vapour.heatCapacity(liquid.index[i],inlet);}
            outlet=inlet-(energy-partial)/(capacity-partialCp);
        }
        double vapourMass=liquid.inertHumidity,vapourMoles=liquid.inertHumidity/liquid.waterMolarMass;
        for(int i=0;i<k;i++)if(!liquid.air[i]){vapourMass+=out[i];vapourMoles+=out[i]/liquid.molarMass[i];}
        return new Contact(outlet,losses,capacity*(inlet-outlet),energy,air,airTemperature,liquid.waterHumidity(out),vapourMass,vapourMoles);
    }

    /** Component {@code c}'s molar mass from {@code liquid}'s exchanging set (only those carry a loss). */
    private static double molarMassOf(Liquid liquid,int c){for(int i=0;i<liquid.index.length;i++)if(liquid.index[i]==c)return liquid.molarMass[i];throw new IllegalStateException("No exchange of component "+c);}
    /** A stream read once for every fill evaluation of one tower reading: the liquid and its let-down at the nozzle. */
    record Prepared(Stream stream,Liquid liquid,Nozzle nozzle) {}
    /** {@code stream} read off the engine: no liquid where it carries none, or where the equation of state has no liquid root at the atmosphere. */
    static Prepared prepare(FluidThermodynamics model,Stream stream) {
        var liquid=stream.liquidMass()>0?of(model,stream.oil(),stream.aqueous(),stream.temperature()):null;
        return new Prepared(stream,liquid,liquid==null?null:liquid.nozzle(model));
    }
    /**
     * What the tower takes from {@code prepared} at fan speed {@code speed}: the arriving gas, the nozzle's vapour and the
     * fill's exchange on the liquid left (COOLING_TOWER_PLAN.md 3.4). {@code idle} is the leaving temperature reported where
     * no liquid reaches the fill.
     */
    static Result spray(FluidThermodynamics model,CoolingTower tower,Prepared prepared,double idle,double speed) {
        var stream=prepared.stream();int n=model.componentCount();double[] losses=new double[n];
        for(int c=0;c<n;c++)losses[c]=stream.gas()[c]*model.molecularWeight(c);
        double energy=stream.gasEnthalpy();
        var nozzle=prepared.nozzle();
        if(prepared.liquid()==null&&stream.liquidMass()>0){
            // No liquid root at the atmosphere: the whole liquid leaves with the air, as transported.
            for(int c=0;c<n;c++)losses[c]+=(stream.oil()[c]+stream.aqueous()[c])*model.molecularWeight(c);
            energy+=stream.liquidEnthalpy();
        }
        if(nozzle!=null){double mass=stream.liquidMass();for(int c=0;c<n;c++)losses[c]+=mass*nozzle.vapourPerKg()[c]*model.molecularWeight(c);energy+=mass*nozzle.energyPerKg();}
        var post=nozzle==null?null:nozzle.liquid();double water=post==null?0:stream.liquidMass()*nozzle.remaining();
        var r=fill(model,tower,post==null?calibration(model).water():post,water,speed);
        for(int c=0;c<n;c++)losses[c]+=r.loss(c);energy+=r.energyWatt();
        double outlet=post==null?(nozzle==null?idle:nozzle.temperature()):r.outletTemperature();
        double duty=prepared.liquid()==null?0:stream.liquidMass()*prepared.liquid().heatCapacity*(prepared.liquid().inlet-outlet);
        return new Result(outlet,losses,duty,energy,r.dryAirFlow(),r.fanPowerWatt(),speed,r.airTemperature(),r.airHumidity());
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
    /** {@code water} kg/s of pure liquid water at {@code inlet} on the engine, through the nozzle and the fill ({@link #spray}). */
    public static Result fill(FluidThermodynamics model,CoolingTower tower,double water,double inlet,double speed) {
        int n=model.componentCount();
        if(!(water>0))return spray(model,tower,new Prepared(Stream.none(n),null,null),inlet,speed);
        double[] none=new double[n],aqueous=none.clone();aqueous[n-1]=water/model.waterMolecularWeight;
        return spray(model,tower,prepare(model,new Stream(none,none.clone(),aqueous,0,0,water,inlet)),inlet,speed);
    }
    private static double signedRoot(double square){return Math.signum(square)*Math.sqrt(Math.abs(square));}
    /** The fan speed that brings the leaving liquid to the tower's set point; the nearer end stop when no speed does. */
    static double targetSpeed(FluidThermodynamics model,CoolingTower tower,Prepared prepared,double idle) {
        DoubleUnaryOperator error=speed->spray(model,tower,prepared,idle,speed).outletTemperature()-tower.setPoint();
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
