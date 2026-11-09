package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.column.operation.ColumnCycle.Account;
import com.wormzjl.icheme.science.fluid.network.BatchStill;
import java.util.Objects;

/**
 * A placed batch still's facts (batch 2026-11-09-batch-distiller, W3 and W4; BATCH_STILL_ENGINE.md): what the player built
 * and put in. Its identity is its pot's device. Saved in the fluid facts from schema 15, after the pumps' health.
 *
 * <ul>
 * <li>The devices the still registered: its pot (a fired reservoir), its condenser's water side, its distillate and vent
 * outlets (generators carrying the still's outlet valve) and its liquid burners (a void at FUEL); 0 for one it has not.</li>
 * <li>Its design as built: the pot's volume, heater, built-in condenser and column trays (0 for none).</li>
 * <li>The head's two accounts: what reached the head and has not yet left by DISTILLATE or VENT (moles per world component,
 * energy J, at the vapour's enthalpy as it left the pot).</li>
 * <li>The fire: the heat left in the burning item (J; a solid heater), the firing (0 to 1 of the rating: the damper, the
 * burners' setting or the power setting) and the reflux valve (the reflux ratio, {@code +Infinity} closed).</li>
 * <li>Its totals: the heat into the pot (J) and the heat the condenser gave its water or the air (J).</li>
 * </ul>
 */
public record StillUnit(long id,long water,long distillate,long vent,long burners,
                        double volume,BatchStill.Heater heater,boolean condenser,int stages,
                        Account head,Account venting,double burning,double firing,double reflux,double heated,double condensed) {
    /** The reflux ratio a still is built with. */
    public static final double DEFAULT_REFLUX=2;
    /** The firing a still is built with: its fire or power off until the player sets it. */
    public static final double DEFAULT_FIRING=0;
    public StillUnit {
        Objects.requireNonNull(heater);Objects.requireNonNull(head);Objects.requireNonNull(venting);
        new BatchStill.Design(volume,heater,condenser,stages);
        if(id<=0||water<0||distillate<0||vent<0||burners<0)throw new IllegalArgumentException("Invalid still devices");
        if(condenser!=(distillate>0)||condenser!=(vent>0))throw new IllegalArgumentException("A still with a built-in condenser has its distillate and vent outlets, and only it");
        if(water>0&&!condenser)throw new IllegalArgumentException("Only a still's built-in condenser has a water side");
        if(burners>0&&heater!=BatchStill.Heater.LIQUID)throw new IllegalArgumentException("Only a liquid-fired still has burners");
        if(head.moles().length!=venting.moles().length)throw new IllegalArgumentException("A still's accounts share a basis");
        if(!(burning>=0)||!Double.isFinite(burning)||!(firing>=0&&firing<=1)||!(reflux>=0)||Double.isNaN(reflux)
                ||!Double.isFinite(heated)||!(heated>=0)||!Double.isFinite(condensed))throw new IllegalArgumentException("Invalid still settings");
    }
    /** A still as built: empty accounts, its fire out, the valve at {@link #DEFAULT_REFLUX}. */
    public static StillUnit built(long id,long water,long distillate,long vent,long burners,BatchStill.Design design,int components) {
        return new StillUnit(id,water,distillate,vent,burners,design.volume(),design.heater(),design.condenser(),design.stages(),
                Account.empty(components),Account.empty(components),0,DEFAULT_FIRING,DEFAULT_REFLUX,0,0);
    }
    public BatchStill.Design design(){return new BatchStill.Design(volume,heater,condenser,stages);}
    public boolean closedValve(){return reflux==Double.POSITIVE_INFINITY;}
    public StillUnit withAccounts(Account head,Account venting){return new StillUnit(id,water,distillate,vent,burners,volume,heater,condenser,stages,head,venting,burning,firing,reflux,heated,condensed);}
    public StillUnit withBurning(double value){return new StillUnit(id,water,distillate,vent,burners,volume,heater,condenser,stages,head,venting,value,firing,reflux,heated,condensed);}
    public StillUnit withFiring(double value){return new StillUnit(id,water,distillate,vent,burners,volume,heater,condenser,stages,head,venting,burning,value,reflux,heated,condensed);}
    public StillUnit withReflux(double value){return new StillUnit(id,water,distillate,vent,burners,volume,heater,condenser,stages,head,venting,burning,firing,value,heated,condensed);}
    public StillUnit withTotals(double heated,double condensed){return new StillUnit(id,water,distillate,vent,burners,volume,heater,condenser,stages,head,venting,burning,firing,reflux,heated,condensed);}
}
