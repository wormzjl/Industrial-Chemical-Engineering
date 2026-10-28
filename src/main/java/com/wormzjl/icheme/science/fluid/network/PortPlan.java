package com.wormzjl.icheme.science.fluid.network;

import java.util.Objects;

/**
 * A running column's port for one slice (batch 2026-10-28-column-operation, plan 4.2 and 4.3): a slice input read on the
 * server at dispatch, as an exchanger side's read is. {@code node} is the port's fixed node, {@code valve} the boundary
 * valve's node, regulated to {@code massTarget} kg/s for the slice (the master works the valve while it runs). An outlet's
 * port is a generator whose state for the slice is its account's {@code moles} at {@code temperature} and the port's own
 * pressure; an inlet's is a void, and {@code moles} is null.
 *
 * <p>The valve's target bounds what the generator gives: the server plans it from what the account holds, so a slice never
 * takes more than that (plan 4.4). A port without a plan is never solved: the server plans every port it holds.</p>
 */
public record PortPlan(long node,long valve,double massTarget,double temperature,double[] moles) {
    /** The sensor a valve the column works reads: the column's own measurement, so the valve regulates without a flowmeter. */
    public static final java.util.UUID COLUMN_SENSOR=new java.util.UUID(0x636f6c756d6e0000L,1);
    public PortPlan {
        if(!(massTarget>=0)||!Double.isFinite(massTarget))throw new IllegalArgumentException("Invalid port target");
        if(moles!=null) {
            moles=moles.clone();double total=0;
            for(double n:moles){if(!(n>=0)||!Double.isFinite(n))throw new IllegalArgumentException("Invalid port composition");total+=n;}
            if(!(total>0)||!(temperature>0)||!Double.isFinite(temperature))throw new IllegalArgumentException("An outlet port needs a composition and a temperature");
        }
    }
    @Override public double[] moles(){return moles==null?null:moles.clone();}
    @Override public boolean equals(Object o){return o instanceof PortPlan p&&node==p.node&&valve==p.valve&&massTarget==p.massTarget&&temperature==p.temperature&&java.util.Arrays.equals(moles,p.moles);}
    @Override public int hashCode(){return Objects.hash(node,valve,massTarget,temperature)*31+java.util.Arrays.hashCode(moles);}
}
