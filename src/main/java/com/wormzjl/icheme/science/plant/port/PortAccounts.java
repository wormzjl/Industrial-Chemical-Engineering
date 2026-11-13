package com.wormzjl.icheme.science.plant.port;

import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.PortPlan;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.plant.column.ColumnCycle.Account;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * The port arithmetic a running column ({@code runtime/fluid/ColumnOperation}) and a batch still ({@code runtime/fluid/Stills})
 * share (codebase review 2026-11-13, PLANT_COUPLING_REVIEW finding C6; wave-c/C4C_REPORT.md): the engine's cycle and the
 * outlet fence, an account's moles between the world list and an island's own list, an outlet's account as of the fence,
 * the plan of an outlet that gives nothing, the debit of what an outlet gave, and the dated logs' window. Minecraft-free and
 * pure: the units keep their own plans, logs and utility debts, which differ (the report's table), and call these.
 */
public final class PortAccounts {
    /** The engine's cycle, ticks (5 s of play at 20 TPS): a column's cycle, the still's windows. */
    public static final int CYCLE_TICKS=100;
    /** The outlet fence, ticks: an outlet's slice plans from what its account held this many ticks before the slice's start. */
    public static final long FENCE_TICKS=440;

    private PortAccounts(){}

    /**
     * {@code moles} on the world list {@code world} on an island's own list {@code own}: the same array where the lists are
     * equal, else a new one; null where a compound with moles is not on {@code own} (a compound with none is skipped).
     */
    public static double[] onIsland(List<String> own,List<String> world,double[] moles) {
        if(own.equals(world))return moles;
        var result=new double[own.size()];
        for(int i=0;i<moles.length;i++){if(moles[i]==0)continue;int j=own.indexOf(world.get(i));if(j<0)return null;result[j]=moles[i];}
        return result;
    }

    /**
     * {@code moles} on island {@code island}'s own list {@code own} on the world list {@code world}: the same array where the
     * lists are equal, else a new one. A compound with moles that the world list does not carry is an error.
     */
    public static double[] onWorld(long island,List<String> own,List<String> world,double[] moles) {
        if(own.equals(world))return moles;
        var result=new double[world.size()];
        for(int i=0;i<moles.length;i++){if(moles[i]==0)continue;int j=world.indexOf(own.get(i));if(j<0)throw new IllegalStateException("Island "+island+" carries "+own.get(i)+", which the world list does not");result[j]=moles[i];}
        return result;
    }

    /** The mass of {@code moles} at molar masses {@code weights}, kg (kg/mol times mol, summed in the list's order). */
    public static double mass(double[] weights,double[] moles){double m=0;for(int i=0;i<moles.length;i++)m+=moles[i]*weights[i];return m;}

    /**
     * {@code held} less {@code later}, in place, each component never below 0: an outlet's account as of the fence is what it
     * holds now less each entry booked into it after the fence, one entry at a time.
     */
    public static void less(double[] held,double[] later){for(int i=0;i<held.length;i++)held[i]=Math.max(0,held[i]-later[i]);}

    /** The plan of an outlet that gives nothing this slice: target 0 on valve {@code valve}, the port node's own state. */
    public static PortPlan idle(PassiveNetwork.Reservoir node,long valve) {
        return new PortPlan(node.id(),valve,0,node.state().temperature(),PhaseLayout.totalAmounts(node.state()));
    }

    /** Told of one component an outlet gave beyond what its account held (the unit logs it as OVERDRAWN). */
    @FunctionalInterface
    public interface Overdrawn {void component(int component,double held,double given);}

    /** An outlet's account after a debit, the moles it gave and the energy debited from it, J. */
    public record Debit(Account account,double moles,double energy) {}

    /**
     * Debits {@code account} by {@code given} (mol by component on the account's list; a negative entry gives nothing):
     * exactly what the island booked, component by component, never below 0, and the account's energy by the same share of
     * its moles, at most all. A component given beyond {@code held·(1+1e-9)+1e-12} is reported to {@code overdrawn}.
     */
    public static Debit debit(Account account,double[] given,Overdrawn overdrawn) {
        var held=account.moles();double before=account.total(),moles=0;
        for(int i=0;i<held.length;i++){double g=Math.max(0,given[i]);if(g>held[i]*(1+1e-9)+1e-12)overdrawn.component(i,held[i],g);held[i]=Math.max(0,held[i]-g);moles+=g;}
        double share=before>0?Math.min(1,moles/before):0,debited=share*account.energy();
        return new Debit(new Account(held,account.energy()-debited),moles,debited);
    }

    /** Drops the oldest entries of a dated {@code log} whose tick is before {@code from}, always keeping the newest one. */
    public static <T> void keep(ArrayDeque<T> log,ToLongFunction<T> tick,long from) {
        while(log.size()>1&&tick.applyAsLong(log.peekFirst())<from)log.removeFirst();
    }
}
