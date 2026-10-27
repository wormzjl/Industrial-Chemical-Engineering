package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.math.DeterministicMath;

/** Homogeneous Darcy-Weisbach loss with a C1 laminar/turbulent blend over Reynolds 2000..4000. */
public final class PipeResistance {
    /** Fixed steel-wall roughness for player-built piping. */
    public static final double DEFAULT_ROUGHNESS_METRES=0.000045;
    /** ln 10, the same double the loss used to recompute on every call. */
    private static final double LN_10=DeterministicMath.log(10);
    private PipeResistance() {}
    /**
     * A run of {@code channels} equal channels in parallel, each of this length, bore, roughness and minor loss: a pipe is
     * one channel, an exchanger side's passage its tubes (batch 2026-10-27-heat-exchanger-design). The flow divides
     * evenly, so the run loses what one channel loses at the flow over the count; its area and volume are all channels'.
     */
    public record Geometry(double length,double diameter,double roughness,double minorLoss,int channels) {
        public Geometry(double length,double diameter,double roughness,double minorLoss){this(length,diameter,roughness,minorLoss,1);}
        public Geometry {
            if(!Double.isFinite(length)||length<=0||!Double.isFinite(diameter)||diameter<=0
                    ||!Double.isFinite(roughness)||roughness<0||roughness>=diameter
                    ||!Double.isFinite(minorLoss)||minorLoss<0||channels<1)throw new IllegalArgumentException("Invalid SI pipe geometry");
        }
        /** The flow area of every channel. */
        public double area(){return channels*Math.PI*diameter*diameter/4;}
        /** This run with {@code value} channels. */
        public Geometry withChannels(int value){return new Geometry(length,diameter,roughness,minorLoss,value);}
    }
    public record Loss(double pressureDrop,double massFlowDerivative,double reynolds) {}
    public static Loss evaluate(Geometry pipe,double massFlow,double density,double viscosity) {
        double[] rest=new double[2];
        return new Loss(pressureDrop(pipe,massFlow,density,viscosity,rest),rest[0],rest[1]);
    }
    /** The pressure drop alone, which is all the residual reads, without a record per section per
     * edge per evaluation. Same arithmetic and same checks as {@link #evaluate}. */
    public static double pressureDrop(Geometry pipe,double massFlow,double density,double viscosity) {
        return pressureDrop(pipe,massFlow,density,viscosity,null);
    }
    /** {@code rest}, when present, receives the mass-flow derivative and the Reynolds number. */
    private static double pressureDrop(Geometry pipe,double massFlow,double density,double viscosity,double[] rest) {
        if(!Double.isFinite(massFlow)||!Double.isFinite(density)||density<=0||!Double.isFinite(viscosity)||viscosity<=0) {
            throw new IllegalArgumentException("Invalid pipe transport properties");
        }
        // One channel's flow: the loss is one channel's, its derivative per kg/s of the whole run's flow.
        double q=Math.abs(massFlow)/pipe.channels,reSlope=4/(Math.PI*viscosity*pipe.diameter),re=q*reSlope;
        double area=Math.PI*pipe.diameter*pipe.diameter/4,coefficient=1/(2*density*area*area);
        // Integer powers as products, and re^1.9 as re^0.9 re (batch 2026-09-27-jdk-determinism, pow cost).
        double diameterSquared=pipe.diameter*pipe.diameter;
        double laminarSlope=128*viscosity*pipe.length/(Math.PI*density*(diameterSquared*diameterSquared));
        double loss=laminarSlope*q,derivative=laminarSlope;
        if(re>2000) {
            double re09=DeterministicMath.pow(re,.9),argument=pipe.roughness/(3.7*pipe.diameter)+5.74/re09;
            double logarithm=DeterministicMath.log10(argument),friction=.25/(logarithm*logarithm);
            double df=-.5/(logarithm*logarithm*logarithm)*(-.9*5.74/(re09*re))/(argument*LN_10);
            double turbulent=friction*pipe.length/pipe.diameter*coefficient*q*q;
            double turbulentSlope=pipe.length/pipe.diameter*coefficient*(2*q*friction+q*q*df*reSlope);
            double s=Math.min(1,(re-2000)/2000),weight=s*s*(3-2*s),weightSlope=6*s*(1-s)*reSlope/2000;
            derivative=derivative*(1-weight)+turbulentSlope*weight+(turbulent-loss)*weightSlope;
            loss=loss*(1-weight)+turbulent*weight;
        }
        loss+=pipe.minorLoss*coefficient*q*q;derivative+=2*pipe.minorLoss*coefficient*q;derivative/=pipe.channels;
        if(!Double.isFinite(loss)||!Double.isFinite(derivative)||derivative<=0)throw new IllegalArgumentException("Nonfinite pipe pressure loss");
        if(rest!=null){rest[0]=derivative;rest[1]=re;}
        return Math.copySign(loss,massFlow);
    }
}
