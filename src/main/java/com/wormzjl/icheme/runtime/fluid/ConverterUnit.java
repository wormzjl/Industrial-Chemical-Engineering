package com.wormzjl.icheme.runtime.fluid;

import java.util.Arrays;
import java.util.Objects;

/**
 * A placed fluid converter's facts (batch 2026-11-12-fluid-conversion, plan 3.2 and 3.5): what the player built and put in.
 * Its identity is its IChemE vessel's device. Saved in the fluid facts from schema 24, after the batch stills.
 *
 * <ul>
 * <li>The devices it registered: its vessel, its drain port (a void, reverse: IChemE to Forge) with the boundary valve in
 * front of it, and its fill port (a generator, forward) with its valve. A port node has one kind, so each direction has its
 * own port (one void, one generator) behind its own valve. A placed converter's ports (W3) carry their own valves
 * ({@code FlowControl.ValveSource.CONVERTER}) in their vessel's port lane: there {@code drainValve == drain} and
 * {@code fillValve == fill}.</li>
 * <li>Whether it is the closed, blanketed variant (gas rules run only there, owner D4).</li>
 * <li>Its settings: the mode, the most it converts (L per process second), the balance deadband (a fill fraction) and four
 * level thresholds (fill fractions; NaN for none): IChemE high and low, Forge high and low.</li>
 * <li>Its Forge tank: capacity and contents in whole mB (1 mB = 1 L), its fluid's id kept as its string ({@code ""} for none),
 * even where that fluid no longer resolves.</li>
 * <li>The remainder: litres booked out of the network not yet whole mB in the Forge tank (a fraction, or what a full tank
 * could not take yet), never lost.</li>
 * <li>The account: litres taken out of the Forge tank for the island's port and not yet converted, with their fluid's id.</li>
 * </ul>
 */
public record ConverterUnit(long id,long drain,long drainValve,long fill,long fillValve,boolean closed,
                            Mode mode,double maxRate,double deadband,double[] thresholds,
                            long capacity,String fluid,long stored,double remainder,String accountFluid,double account) {
    /** The converter's direction setting, saved by name. */
    public enum Mode {OFF,TO_ICHEME,TO_FORGE,BALANCE}
    /** The deadband a converter is built with: 5 % of fill. */
    public static final double DEFAULT_DEADBAND=.05;
    /** The thresholds' order. */
    public static final int ICHEME_HIGH=0,ICHEME_LOW=1,FORGE_HIGH=2,FORGE_LOW=3,THRESHOLDS=4;
    public ConverterUnit {
        Objects.requireNonNull(mode);Objects.requireNonNull(fluid);Objects.requireNonNull(accountFluid);
        thresholds=thresholds.clone();
        if(id<=0||drain<0||drainValve<0||fill<0||fillValve<0||(drain==0)!=(drainValve==0)||(fill==0)!=(fillValve==0))throw new IllegalArgumentException("Invalid converter devices");
        if(!(maxRate>=0)||!Double.isFinite(maxRate)||!(deadband>=0&&deadband<=1))throw new IllegalArgumentException("Invalid converter settings");
        if(thresholds.length!=THRESHOLDS)throw new IllegalArgumentException("A converter has four level thresholds");
        for(double t:thresholds)if(!Double.isNaN(t)&&!(t>=0&&t<=1))throw new IllegalArgumentException("A level threshold is a fill fraction");
        if(capacity<0||stored<0||stored>capacity)throw new IllegalArgumentException("Invalid Forge tank contents");
        if(stored>0&&fluid.isEmpty())throw new IllegalArgumentException("A Forge tank's contents name their fluid");
        if(!(remainder>=0)||!Double.isFinite(remainder)||!(account>=0)||!Double.isFinite(account))throw new IllegalArgumentException("Invalid converter remainder or account");
        if(account>0&&accountFluid.isEmpty())throw new IllegalArgumentException("A converter's account names its fluid");
    }
    /** A converter as built: off, no rate, the default deadband, no thresholds, an empty Forge tank of {@code capacity} mB. */
    public static ConverterUnit built(long id,long drain,long drainValve,long fill,long fillValve,boolean closed,long capacity) {
        var none=new double[THRESHOLDS];Arrays.fill(none,Double.NaN);
        return new ConverterUnit(id,drain,drainValve,fill,fillValve,closed,Mode.OFF,0,DEFAULT_DEADBAND,none,capacity,"",0,0,"",0);
    }
    @Override public double[] thresholds(){return thresholds.clone();}
    /** The Forge tank's fill fraction. */
    public double forgeFill(){return capacity>0?(double)stored/capacity:0;}
    public ConverterUnit withSettings(Mode mode,double maxRate,double deadband){return new ConverterUnit(id,drain,drainValve,fill,fillValve,closed,mode,maxRate,deadband,thresholds,capacity,fluid,stored,remainder,accountFluid,account);}
    public ConverterUnit withThresholds(double[] values){return new ConverterUnit(id,drain,drainValve,fill,fillValve,closed,mode,maxRate,deadband,values,capacity,fluid,stored,remainder,accountFluid,account);}
    /** The Forge tank holding {@code stored} mB of {@code fluid} (its fluid forgotten once it holds nothing and no remainder waits). */
    public ConverterUnit withTank(String fluid,long stored,double remainder) {
        var kept=stored==0&&remainder==0?"":fluid;
        return new ConverterUnit(id,drain,drainValve,fill,fillValve,closed,mode,maxRate,deadband,thresholds,capacity,kept,stored,remainder,accountFluid,account);
    }
    public ConverterUnit withAccount(String fluid,double litres){return new ConverterUnit(id,drain,drainValve,fill,fillValve,closed,mode,maxRate,deadband,thresholds,capacity,this.fluid,stored,remainder,litres>0?fluid:"",litres);}
    @Override public boolean equals(Object o) {
        return o instanceof ConverterUnit u&&id==u.id&&drain==u.drain&&drainValve==u.drainValve&&fill==u.fill&&fillValve==u.fillValve&&closed==u.closed&&mode==u.mode
                &&Double.compare(maxRate,u.maxRate)==0&&Double.compare(deadband,u.deadband)==0&&Arrays.equals(thresholds,u.thresholds)&&capacity==u.capacity&&fluid.equals(u.fluid)
                &&stored==u.stored&&Double.compare(remainder,u.remainder)==0&&accountFluid.equals(u.accountFluid)&&Double.compare(account,u.account)==0;
    }
    @Override public int hashCode(){return Objects.hash(id,drain,fill,mode,maxRate,capacity,fluid,stored,remainder,account)*31+Arrays.hashCode(thresholds);}
}
