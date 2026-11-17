package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.math.DeterministicMath;

/**
 * How well a vessel keeps its phases apart at its size and flows (batch 2026-11-17-three-phase-separator, plan sections 3
 * and 4; owner: "the drum should only outputs well separated water and oil when properly sized, otherwise you will find
 * some oil in water and some water in oil"). A liquid enters as drops in the other liquid. A drop settles at Stokes'
 * speed with Schiller and Naumann's drag correction, and a layer is an ideal settler: every drop leaves it where
 * {@code N = v_s A / Q} is 1 or more ({@code A} the layer's plan area, {@code Q} its own outflow), and the share
 * {@code 1 − N} stays under 1. The gas leaves clean while its speed through the vapour space stays under Souders and
 * Brown's. Pure arithmetic: what a vessel carries is a slice input, never an unknown of a step.
 */
public final class Separation {
    /**
     * The drops a vessel's liquids settle as, m.
     *
     * @param water a water drop in the oil
     * @param oil   an oil drop in the water
     */
    public record Drops(double water,double oil) {
        public Drops {
            if(!(water>0)||!(oil>0)||!Double.isFinite(water+oil))throw new IllegalArgumentException("Invalid drop sizes");
        }
    }
    /**
     * The drops a vessel without a coalescer leaves to settle (owner, 2026-11-17, option B): water in oil 0.5 mm, the
     * oil pad's usual design drop where no laboratory data exists; oil in water 0.2 mm, the upper end of design
     * practice's 0.15 to 0.2 mm. A desalter's field makes its own water drop larger.
     */
    public static final Drops DROPS=new Drops(5e-4,2e-4);
    /** Souders and Brown's K of a standing vessel without a demister, m/s (GPSA's 0.107 m/s halved). */
    public static final double GAS_K=.107/2;
    /** A lying drum's K over a standing one's: its drops fall across the gas, not against it. */
    public static final double LYING=1.25;
    /**
     * The gas space a standing vessel needs above its liquid for its full {@link #GAS_K}, m (batch
     * 2026-11-17-three-phase-separator, owner: "what happens if rules are not followed"). Drops need height to fall back:
     * API 12J's K table, read from secondary copies, gives 0.35 ft/s for a standing vessel 10 ft high and 0.24 ft/s for one
     * 5 ft high. Assumed: the table is on the vessel's height and a design vessel runs half full, so the full K needs half
     * of 10 ft of gas space, 1.524 m.
     */
    public static final double GAS_SPACE=1.524;
    /**
     * The exponent of a standing vessel's gas space factor {@code (gas space / GAS_SPACE)^0.56}: API 12J's own for a lying
     * vessel's length, {@code (L / 10 ft)^0.56}. On the standing row it gives 0.678 for 5 ft against the table's 0.686
     * (0.24 / 0.35). Assumed: under 5 ft of vessel the factor is extrapolated; the table stops there.
     */
    public static final double GAS_SPACE_EXPONENT=.56;
    private static final int PASSES=5;

    private Separation(){}

    /**
     * What a vessel's outlets carry beside their own phase.
     *
     * @param waterInOil  water drawn with the oil, m³ per m³ of oil
     * @param oilInWater  oil drawn with the water, m³ per m³ of water
     * @param liquidInGas the share of the gas outlet's volume drawn as the vessel's bulk, 0 to 1
     */
    public record Carried(double waterInOil,double oilInWater,double liquidInGas) {
        /** A vessel that keeps its phases apart: today's draws. */
        public static final Carried NONE=new Carried(0,0,0);
        public Carried {
            if(!(waterInOil>=0&&waterInOil<=1)||!(oilInWater>=0&&oilInWater<=1)||!(liquidInGas>=0&&liquidInGas<=1))
                throw new IllegalArgumentException("Invalid carry-over");
        }
        public boolean none(){return waterInOil==0&&oilInWater==0&&liquidInGas==0;}
    }

    /**
     * Which of the laws act on a vessel: the liquids' (an outlet for each liquid) and the gas's (a gas outlet).
     */
    public record Acts(boolean liquids,boolean gas) {
        private static final Acts NEITHER=new Acts(false,false);
        public boolean any(){return liquids||gas;}
    }

    /** Which laws act on node {@code node} of {@code graph}: the rule {@link #plan} applies. */
    public static Acts acts(PassiveNetwork graph,int node) {
        boolean oil=false,water=false,gas=false;
        for(var pipe:graph.pipes())for(int end=0;end<2;end++) {
            if((end==0?pipe.first():pipe.second())!=node)continue;
            var port=end==0?pipe.firstPort():pipe.secondPort();
            if(port==PassiveNetwork.PhasePort.BOOT_HC)oil=true;
            else if(port==PassiveNetwork.PhasePort.BOOT_WATER)water=true;
            else if(port==PassiveNetwork.PhasePort.VAPOR)gas=true;
        }
        return acts(graph.reservoirs().get(node),oil,water,gas);
    }
    /** A closed vessel that is no kettle, pot or tower basin takes the law on the outlets it has. */
    private static Acts acts(PassiveNetwork.Reservoir reservoir,boolean oilPort,boolean waterPort,boolean gasPort) {
        var inventory=reservoir.inventory();
        if(reservoir.kind()!=PassiveNetwork.NodeKind.RESERVOIR||inventory.open()||inventory.pool()!=null||inventory.tower()!=null)return Acts.NEITHER;
        return new Acts(oilPort&&waterPort,gasPort);
    }

    /**
     * A drop's settling speed, m/s: {@code g d² Δρ / (18 μ (1 + 0.15 Re^0.687))}, {@code Re = ρ v d / μ}, by five
     * fixed-point passes from Stokes' speed.
     *
     * @param drop       its diameter, m
     * @param difference the two liquids' density difference, kg/m³ (its sign does not matter: a drop falls or rises)
     * @param viscosity  of the liquid it crosses, Pa s
     * @param density    of the liquid it crosses, kg/m³
     */
    public static double settling(double drop,double difference,double viscosity,double density) {
        if(!(drop>0)||!(viscosity>0)||!(density>0)||!Double.isFinite(difference)||!Double.isFinite(drop+viscosity+density))
            throw new IllegalArgumentException("Invalid settling state");
        double stokes=PassiveStepSolver.GRAVITY*drop*drop*Math.abs(difference)/(18*viscosity),speed=stokes;
        if(stokes==0)return 0;
        for(int pass=0;pass<PASSES;pass++)speed=stokes/(1+.15*DeterministicMath.pow(density*speed*drop/viscosity,.687));
        return speed;
    }

    /** A layer's settling number {@code N = v_s A / Q}: positive infinity where nothing leaves it. */
    public static double number(double speed,double area,double outflow) {
        if(!(speed>=0)||!(area>=0)||!(outflow>=0))throw new IllegalArgumentException("Invalid settling layer");
        return outflow>0?speed*area/outflow:Double.POSITIVE_INFINITY;
    }

    /**
     * What a liquid outlet carries of the other liquid, m³ per m³ of its own: the share {@code 1 − N} of the drops that
     * did not settle, at most volume for volume, of the other liquid that entered per volume of its own ({@code other}
     * and {@code own}, m³ over the last slice). Nothing where nothing of the other liquid entered: a still vessel settles.
     */
    public static double carried(double number,double other,double own) {
        if(Double.isNaN(number)||!(other>=0)||!(own>=0))throw new IllegalArgumentException("Invalid carry-over state");
        if(number>=1||!(other>0))return 0;
        return (1-Math.max(0,number))*(own>0?Math.min(1,other/own):1);
    }

    /** The gas speed a vessel's vapour space takes, m/s: {@code K sqrt((ρ_l − ρ_g) / ρ_g)}, {@link #LYING} times that in a
     * lying drum; positive infinity where the gas is no lighter than the liquid (nothing to carry). A standing vessel's
     * value with a gas space of at least {@link #GAS_SPACE}. */
    public static double gasLimit(boolean lying,double liquidDensity,double gasDensity) {
        return limit(lying,1,liquidDensity,gasDensity);
    }
    /**
     * The gas limit of a vessel of {@code geometry} with its liquid surface at {@code level}, m/s: {@link #gasLimit(boolean,
     * double, double)} times, in a standing vessel, {@link #gasSpaceFactor} of the gas space from the surface to the top.
     * A lying drum's drops fall across the gas: its limit does not read the level. 0 for a standing vessel full of liquid.
     */
    public static double gasLimit(VesselGeometry geometry,double level,double liquidDensity,double gasDensity) {
        if(Double.isNaN(level))throw new IllegalArgumentException("Invalid level");
        return limit(geometry.lying(),geometry.lying()?1:gasSpaceFactor(Math.max(0,geometry.height()-level)),liquidDensity,gasDensity);
    }
    /** The one formula of the gas limit: {@code factor} times the full-height limit. */
    private static double limit(boolean lying,double factor,double liquidDensity,double gasDensity) {
        if(!(liquidDensity>0)||!(gasDensity>0))throw new IllegalArgumentException("Invalid densities");
        if(liquidDensity<=gasDensity)return Double.POSITIVE_INFINITY;
        double full=(lying?LYING:1)*GAS_K*Math.sqrt((liquidDensity-gasDensity)/gasDensity);
        return factor==1?full:full*factor;
    }
    /**
     * A standing vessel's gas limit over its full value at a gas space of {@code gasSpace} m above the liquid:
     * {@code (gasSpace / GAS_SPACE)^GAS_SPACE_EXPONENT}, 1 from {@link #GAS_SPACE} up, 0 with no gas space.
     */
    public static double gasSpaceFactor(double gasSpace) {
        if(Double.isNaN(gasSpace))throw new IllegalArgumentException("Invalid gas space");
        if(gasSpace<=0)return 0;
        if(gasSpace>=GAS_SPACE)return 1;
        return DeterministicMath.pow(gasSpace/GAS_SPACE,GAS_SPACE_EXPONENT);
    }

    /** The share of a gas outlet's volume it draws as the vessel's bulk: {@code 1 − limit / speed} above the limit. */
    public static double entrained(double speed,double limit) {
        if(!(speed>=0)||!(limit>0))throw new IllegalArgumentException("Invalid gas speed");
        return speed>limit?1-limit/speed:0;
    }

    /**
     * A vessel's liquid layer for one slice.
     *
     * @param volume    what the vessel holds of it, m³
     * @param density   kg/m³
     * @param viscosity Pa s
     * @param outflow   what left through its own outlet, m³/s
     * @param inflow    what entered the vessel of it, m³/s
     */
    public record Layer(double volume,double density,double viscosity,double outflow,double inflow) {
        public Layer {
            if(!(volume>=0)||!(density>=0)||!(viscosity>=0)||!(outflow>=0)||!(inflow>=0)||!Double.isFinite(volume+density+viscosity+outflow+inflow))
                throw new IllegalArgumentException("Invalid layer");
        }
        boolean held(){return volume>0&&density>0&&viscosity>0;}
    }

    /**
     * The settling numbers of a vessel's two liquid layers: the oil layer's (water drops leaving the oil) and the water
     * layer's (oil drops leaving the water), each on its plan area at its mid-height, the upper one's inside the body,
     * above a boot, each for its own drop of {@code drops}. Positive infinity for both where the vessel does not hold
     * both liquids, and for a layer nothing leaves. The sizer reads the same numbers at design flow ({@code DrumSizing}).
     */
    public static double[] numbers(VesselGeometry geometry,Drops drops,Layer oil,Layer water) {
        if(!oil.held()||!water.held())return new double[]{Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY};
        boolean heavyWater=water.density()>=oil.density();
        Layer lower=heavyWater?water:oil,upper=heavyWater?oil:water;
        double split=geometry.heightAt(lower.volume()),top=geometry.heightAt(lower.volume()+upper.volume());
        double lowerArea=geometry.planArea((-geometry.bootDepth()+split)/2),upperArea=geometry.planArea((Math.max(split,0)+Math.max(top,0))/2);
        double difference=water.density()-oil.density();
        return new double[]{number(settling(drops.water(),difference,oil.viscosity(),oil.density()),heavyWater?upperArea:lowerArea,oil.outflow()),
                number(settling(drops.oil(),difference,water.viscosity(),water.density()),heavyWater?lowerArea:upperArea,water.outflow())};
    }

    /**
     * What a vessel of {@code geometry} carries for a slice.
     *
     * @param drops      the drops its liquids settle as ({@link #DROPS}; a desalter's own)
     * @param oil        its hydrocarbon layer
     * @param water      its aqueous layer
     * @param twoOutlets whether each liquid has its own outlet (a boot drum, a desalter): with one liquid outlet there is
     *                   nothing to keep apart
     * @param gasOutflow what left through its gas outlet, m³/s
     * @param gasDensity kg/m³; 0 with no gas
     */
    public static Carried of(VesselGeometry geometry,Drops drops,Layer oil,Layer water,boolean twoOutlets,double gasOutflow,double gasDensity) {
        boolean heavyWater=water.density()>=oil.density();
        Layer lower=heavyWater?water:oil,upper=heavyWater?oil:water;
        double split=geometry.heightAt(lower.volume()),top=geometry.heightAt(lower.volume()+upper.volume());
        double waterInOil=0,oilInWater=0;
        if(twoOutlets&&oil.held()&&water.held()) {
            double[] numbers=numbers(geometry,drops,oil,water);
            waterInOil=carried(numbers[0],water.inflow(),oil.inflow());
            oilInWater=carried(numbers[1],oil.inflow(),water.inflow());
        }
        double liquidInGas=0;
        if(gasOutflow>0&&gasDensity>0&&(oil.held()||water.held())) {
            // The liquid the gas would lift: the lighter one the vessel holds, which lies on top.
            // No gas space (a lying drum's section, a standing vessel's height): the gas outlet draws the bulk.
            double liquid=upper.held()?upper.density():lower.density(),section=geometry.vapourSection(top);
            boolean space=section>0&&(geometry.lying()||geometry.height()-top>0);
            liquidInGas=space?entrained(gasOutflow/section,gasLimit(geometry,top,liquid,gasDensity)):1;
        }
        return new Carried(waterInOil,oilInWater,liquidInGas);
    }

    /**
     * {@code graph} with what each of its vessels carries over the slice it is dispatched for (plan section 4): read off
     * the vessel's committed state and what its connections moved over the last slice ({@code last}, its
     * {@link PipeTransfer}s by phase; null or empty: nothing moved, so every vessel is clean). A read, no solve.
     *
     * <p>A vessel takes the law where it is a closed vessel that is no kettle, pot or tower basin. The liquids' laws need
     * an outlet for each liquid ({@code BOOT_HC} and {@code BOOT_WATER}); the gas's a {@code VAPOR} port. Each phase's
     * outflow is all of it that left the vessel, and its inflow all of it that reached the vessel through a connection.</p>
     */
    public static PassiveNetwork plan(FluidThermodynamics model,PassiveNetwork graph,@org.jetbrains.annotations.Nullable PassiveIntervalSolver.Result last) {
        int nodes=graph.reservoirs().size();
        double seconds=last==null?0:last.advancedSeconds();
        double[][] out=null,in=null;boolean[] oilPort=new boolean[nodes],waterPort=new boolean[nodes],gasPort=new boolean[nodes];boolean any=false;
        for(var pipe:graph.pipes())for(int end=0;end<2;end++) {
            var port=end==0?pipe.firstPort():pipe.secondPort();int node=end==0?pipe.first():pipe.second();
            if(port==PassiveNetwork.PhasePort.BOOT_HC)oilPort[node]=true;
            else if(port==PassiveNetwork.PhasePort.BOOT_WATER)waterPort[node]=true;
            else if(port==PassiveNetwork.PhasePort.VAPOR)gasPort[node]=true;
            else continue;
            any=true;
        }
        if(any&&seconds>0) {
            var moved=new java.util.HashMap<Long,PipeTransfer>();for(var transfer:last.pipeTransfers())moved.put(transfer.pipeId(),transfer);
            out=new double[nodes][3];in=new double[nodes][3];
            for(var pipe:graph.pipes()) {
                var transfer=moved.get(pipe.id());if(transfer==null)continue;
                double[] forward=transfer.forward().phaseVolumes(),reverse=transfer.reverse().phaseVolumes();
                for(int row=0;row<3;row++) {
                    out[pipe.first()][row]+=forward[row]/seconds;in[pipe.second()][row]+=forward[row]/seconds;
                    out[pipe.second()][row]+=reverse[row]/seconds;in[pipe.first()][row]+=reverse[row]/seconds;
                }
            }
        }
        java.util.List<PassiveNetwork.Reservoir> planned=null;com.wormzjl.icheme.science.fluid.transport.MixtureViscosity.Workspace scratch=null;
        for(int node=0;node<nodes;node++) {
            var reservoir=graph.reservoirs().get(node);var inventory=reservoir.inventory();var carried=Carried.NONE;
            var acts=acts(reservoir,oilPort[node],waterPort[node],gasPort[node]);
            if(out!=null&&acts.any()) {
                var state=reservoir.state();
                if(scratch==null)scratch=new com.wormzjl.icheme.science.fluid.transport.MixtureViscosity.Workspace();
                Layer oil=layer(model,state,FluidThermodynamics.OIL,out[node][0],in[node][0],scratch),water=layer(model,state,FluidThermodynamics.AQUEOUS,out[node][1],in[node][1],scratch);
                double gas=FluidThermodynamics.holdsPhase(state,FluidThermodynamics.GAS)?PortStreams.phaseDensity(model,state,FluidThermodynamics.GAS):0;
                carried=of(inventory.geometry(),DROPS,oil,water,acts.liquids(),acts.gas()?out[node][2]:0,gas);
            }
            if(carried.equals(inventory.carried()))continue;
            if(planned==null)planned=new java.util.ArrayList<>(graph.reservoirs());
            planned.set(node,new PassiveNetwork.Reservoir(reservoir.id(),reservoir.elevation(),reservoir.state(),reservoir.kind(),inventory.withCarried(carried)));
        }
        return planned==null?graph:new PassiveNetwork(planned,graph.pipes(),graph.scheduledTransfers());
    }
    private static Layer layer(FluidThermodynamics model,FluidThermodynamics.State state,int phase,double outflow,double inflow,com.wormzjl.icheme.science.fluid.transport.MixtureViscosity.Workspace scratch) {
        if(!FluidThermodynamics.holdsPhase(state,phase))return new Layer(0,0,0,outflow,inflow);
        return new Layer(FluidThermodynamics.phaseVolume(state,phase),PortStreams.phaseDensity(model,state,phase),model.phaseCarrierViscosity(state,phase,scratch),outflow,inflow);
    }

    // ---- The band law (batch 2026-11-17-dynamic-settling, plan sections 3 and 4, laws L2, L4, L5). No vessel reads it yet.

    /** The equal parts a slice is advanced in (plan L2: one part is 47 % high above one turnover a slice). */
    public static final int PARTS=16;

    /**
     * A liquid layer's turbid band (plan L1): the part of the layer that still holds drops of the other liquid. In a
     * uniform dispersion every drop moves at one speed, so the band keeps its concentration and loses height behind a
     * sharp front (Kynch, Trans. Faraday Soc. 48 (1952) 166, doi:10.1039/TF9524800166).
     *
     * @param turbid the layer's own liquid that still holds drops, m³
     * @param held   the other liquid in those drops, m³
     */
    public record Band(double turbid,double held) {
        /** A settled layer. */
        public static final Band NONE=new Band(0,0);
        public Band {
            if(!(turbid>=0)||!(held>=0)||!Double.isFinite(turbid+held))throw new IllegalArgumentException("Invalid band");
        }
        public boolean none(){return turbid==0&&held==0;}
        /**
         * The band within what the vessel holds (plan L4): {@code turbid} at most {@code layer}, the layer's own liquid,
         * and {@code held} at most {@code other}, the vessel's volume of the other liquid. Where {@code turbid} is clamped
         * down, {@code held} scales with it. A layer of 0 loses its band.
         */
        public Band within(double layer,double other) {
            if(!(layer>=0)||!(other>=0)||!Double.isFinite(layer+other))throw new IllegalArgumentException("Invalid layer");
            if(layer==0)return NONE;
            double t=turbid,h=held;
            if(t>layer){h*=layer/t;t=layer;}
            h=Math.min(h,other);
            return t==turbid&&h==held?this:new Band(t,h);
        }
    }

    /**
     * One slice of a band.
     *
     * @param next    the band at the slice's end
     * @param carried the dispersed volume the layer's outlet carried per own volume drawn; 0 where nothing was drawn
     */
    public record Slice(Band next,double carried) {}

    /**
     * Advances a layer's band over one slice (plan L2), in {@link #PARTS} equal parts, each in this order: the front
     * clears {@code min(turbid, v A dt)} at the swarm speed {@code v = speed (1 − φ)^exponent}, {@code φ} the band's own
     * dispersed fraction (Richardson and Zaki, Trans. Inst. Chem. Eng. 32 (1954) 35); the feed arrives turbid; the
     * outlet draws the layer's oldest liquid, clear until the band fills the layer. At steady flow it gives Hazen's ideal
     * basin, the carried share {@code (1 − N) r} with {@code N = v_s A / Q} (Hazen, Trans. ASCE 53 (1904) 45; Camp,
     * Trans. ASCE 111 (1946) 895); filled turbid and left alone, the layer clears after {@code h / v}. Pure arithmetic.
     *
     * @param band     the band at the slice's start
     * @param volume   the layer's own liquid at the slice's start, m³
     * @param area     its plan area, m²
     * @param speed    one drop's settling speed, m/s ({@link #settling})
     * @param exponent Richardson and Zaki's {@code n} ({@link #swarmExponent}); 0: no swarm factor
     * @param inflow   own liquid entering, m³/s
     * @param ratio    dispersed volume per own volume entering, 0 to 1 ({@link #dispersed})
     * @param outflow  own liquid drawn through its outlet, m³/s
     * @param seconds  the slice
     */
    public static Slice advance(Band band,double volume,double area,double speed,double exponent,double inflow,double ratio,double outflow,double seconds) {
        if(band==null||!(volume>=0)||!(area>=0)||!(speed>=0)||!(exponent>=0)||!(inflow>=0)||!(ratio>=0&&ratio<=1)||!(outflow>=0)||!(seconds>=0)
                ||!Double.isFinite(volume+area+speed+exponent+inflow+outflow+seconds))
            throw new IllegalArgumentException("Invalid settling slice");
        double turbid=band.turbid(),held=band.held(),dt=seconds/PARTS,carried=0,drawn=0;
        for(int part=0;part<PARTS;part++) {
            // (a) The front.
            if(turbid>0) {
                double phi=held/(turbid+held),v=exponent==0||phi==0?speed:speed*DeterministicMath.pow(1-phi,exponent);
                double swept=Math.min(turbid,v*area*dt);
                held-=held*swept/turbid;turbid-=swept;
                if(turbid<=0){turbid=0;held=0;}
            }
            // (b) The feed.
            turbid+=inflow*dt;held+=inflow*ratio*dt;
            // (c) The outlet: the oldest liquid first.
            double out=outflow*dt,clear=volume+inflow*dt-turbid,taken=Math.min(turbid,Math.max(0,out-clear));
            if(turbid>0&&taken>0) {
                double c=held*taken/turbid;
                carried+=c;held-=c;turbid-=taken;
            }
            drawn+=out;
            volume=Math.max(0,volume+(inflow-outflow)*dt);
            // Rounding never leaves a negative band, nor drops without the liquid that holds them.
            if(turbid<=0){turbid=0;held=0;}
            else if(held<0)held=0;
        }
        return new Slice(turbid==0&&held==0?Band.NONE:new Band(turbid,held),drawn>0?carried/drawn:0);
    }

    /**
     * Richardson and Zaki's exponent {@code n} of the swarm factor {@code (1 − φ)^n}, by their steps on the single
     * drop's Reynolds number {@code Re = ρ v d / μ} (Trans. Inst. Chem. Eng. 32 (1954) 35, reprinted
     * doi:10.1016/S0263-8762(97)80006-8): 4.65 under Re 0.2, {@code 4.35 Re^-0.03} to Re 1, {@code 4.45 Re^-0.1} to
     * Re 500, 2.39 above. The steps are the paper's own ({@code n} jumps by 0.1 at Re 1); 4.65 and 2.39 were confirmed
     * against the paper, 4.45 agrees with them (2.39 at Re 500), and the factor 4.35 is from memory and not confirmed
     * (plan section 10: at most 2 % in speed at a fraction of 0.3).
     *
     * @param speed     the single drop's settling speed, m/s
     * @param drop      its diameter, m
     * @param viscosity the continuous liquid's, Pa s
     * @param density   the continuous liquid's, kg/m³
     */
    public static double swarmExponent(double speed,double drop,double viscosity,double density) {
        if(!(speed>=0)||!(drop>0)||!(viscosity>0)||!(density>0)||!Double.isFinite(speed+drop+viscosity+density))
            throw new IllegalArgumentException("Invalid swarm state");
        double re=density*speed*drop/viscosity;
        if(re<.2)return 4.65;
        if(re<1)return 4.35*DeterministicMath.pow(re,-.03);
        if(re<500)return 4.45*DeterministicMath.pow(re,-.1);
        return 2.39;
    }

    /**
     * The feed ratios of a vessel's two layers, {@code {oil layer's, water layer's}}, dispersed volume per own volume
     * entering (plan L5, owner's decision D1 B): the liquid with the smaller volume inflow enters as drops in the other,
     * which is continuous (phase inversion by volume share: Selker and Sleicher, Can. J. Chem. Eng. 43 (1965) 298); the
     * dispersed liquid's own layer receives nothing from the feed and grows by what settles. Equal inflows: water in oil.
     *
     * @param oilIn   oil entering, m³/s
     * @param waterIn water entering, m³/s
     */
    public static double[] dispersed(double oilIn,double waterIn) {
        if(!(oilIn>=0)||!(waterIn>=0)||!Double.isFinite(oilIn+waterIn))throw new IllegalArgumentException("Invalid feed");
        if(oilIn==0&&waterIn==0)return new double[]{0,0};
        if(waterIn<=oilIn)return new double[]{waterIn/oilIn,0};
        return new double[]{0,oilIn/waterIn};
    }
}
