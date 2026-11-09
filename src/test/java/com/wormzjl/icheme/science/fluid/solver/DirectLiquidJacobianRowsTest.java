package com.wormzjl.icheme.science.fluid.solver;

import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.thermo.HydrocarbonValues;
import com.wormzjl.icheme.science.fluid.thermo.WaterRegion1;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.PhaseSupport;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The network's node Jacobian is a finite-difference sweep over the same residual rows (P3 plan section 2.3), so the
 * direct liquid path needs no hand-chained derivative: this verifies that the rows the sweep differences take the new
 * values. The pressure column of the volume closure, the energy balance and the equilibrium rows, differenced on the
 * layout's own residual, equals the equation of state's analytic derivative at the state pressure (the liquid's own
 * {@code dv/dP}, {@code dh/dP}, {@code vbar_i/RT}, and Region 1's for water), not the retired global compressibility's;
 * and the velocity limit's acoustic bound reads each phase's own compressibility.
 */
class DirectLiquidJacobianRowsTest {
    private static final String NETWORK="icheme:tjl20_methane_nitrogen";
    private final MaterialCatalog catalog=MaterialCatalog.bundled();
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(catalog,NETWORK);

    private double[] assay(boolean cutsOnly) {
        var crude=com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.thermo("icheme:tjl20_methane");
        var feed=crude.crudeFeed(com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.ASSAY_ID).moleFractions();
        double[] n=new double[model.componentCount()];
        for(int c=0;c<feed.length;c++){String id=crude.componentBasis().componentId(c);if(!cutsOnly||id.startsWith("crude_"))n[model.components().indexOf(id)]=feed[c];}
        return n;
    }
    private static double[] normalised(double[] amounts){double[] x=amounts.clone();double total=0;for(double v:x)total+=v;for(int i=0;i<x.length;i++)x[i]/=total;return x;}
    private static double column(SparseNewtonColumn f,double[] x,int column,int row) {
        double h=1e-6;double[] plus=x.clone(),minus=x.clone();plus[column]+=h;minus[column]-=h;
        return (f.residual(plus)[row]-f.residual(minus)[row])/(2*h);
    }
    private interface SparseNewtonColumn{double[] residual(double[] x);}

    @Test void aLiquidFullNodesVolumeAndEnergyRowsDifferenceToTheEquationOfStateAtTheStatePressure() {
        double[] n=assay(true);n[n.length-1]=.05;
        double t=350,p=1e6;
        var state=model.flashTP(t,p,n,()->{});
        assertTrue(state.liquidVolume()>0&&state.waterVolume()>0&&state.vaporVolume()==0,"liquid-full with free water");
        var layout=new PhaseLayout(model,state);var x=layout.encode(state);double[] totals=PhaseLayout.totalAmounts(state);
        var equations=layout.wallClosure(totals,state.internalEnergy(),state.volume(),Double.NaN);
        int energyRow=layout.componentBalanceCount(),volumeRow=energyRow+1,pressure=layout.size()-1;
        assertEquals(Math.log(p/1e5),x[pressure],1e-15,"the last unknown of a dry liquid-full node is ln P");
        double[] liquid=normalised(state.liquid());double nl=0;for(double v:state.liquid())nl+=v;
        var d=HydrocarbonValues.of(model.hydrocarbon,t,p,liquid,Root.LIQUID);
        var region=WaterRegion1.evaluate(catalog.requirePackage(NETWORK).water(),t,p);double mw=model.waterMolecularWeight;
        double dvdp=nl*d.volumePressureDerivative()+state.waterLiquid()*region.volumePressureDerivative()*mw;
        double dhdp=nl*d.enthalpyPressureDerivative()+state.waterLiquid()*region.enthalpyPressureDerivative()*mw;
        double expectedVolume=p*dvdp/state.volume();
        double expectedEnergy=p*(dhdp-state.volume()-p*dvdp)/layout.energyScale();
        double volume=column(equations::residual,x,pressure,volumeRow),energy=column(equations::residual,x,pressure,energyRow);
        double retired=-p*1e-9*(state.liquidVolume()+state.waterVolume())/state.volume();
        System.out.printf(Locale.ROOT,"liquid-full node d(volume row)/d ln P: swept %.6e, EOS %.6e, retired global k %.6e; d(energy row)/d ln P: swept %.6e, EOS %.6e%n",
                volume,expectedVolume,retired,energy,expectedEnergy);
        assertEquals(expectedVolume,volume,1e-5*Math.abs(expectedVolume));
        assertEquals(expectedEnergy,energy,1e-5*Math.abs(expectedEnergy));
        assertTrue(Math.abs(volume/retired-1)>.05,"the column is the EOS's compressibility, not 1e-9 1/Pa: "+volume/retired);
        // The acoustic bound of the velocity clamp: each condensed phase's own compressibility.
        double compliance=state.liquidVolume()*state.liquidProperties().isothermalCompressibility()+state.waterVolume()*(-region.volumePressureDerivative()/region.specificVolume());
        assertEquals(state.volume()/Math.sqrt(state.mass()*compliance),model.isothermalAcousticBound(state),1e-12*model.isothermalAcousticBound(state));
        assertEquals(-region.volumePressureDerivative()/region.specificVolume(),state.waterCompressibility(),1e-15*state.waterCompressibility());
    }

    @Test void aTwoPhaseNodesEquilibriumRowsDifferenceToThePartialMolarVolumesAtTheStatePressure() {
        double[] n=assay(false);n[model.components().indexOf("Nitrogen")]=.1;
        double t=350,p=150000;
        var state=model.flashTP(t,p,n,()->{});
        assertTrue(state.liquidVolume()>0&&state.vaporVolume()>0,"two-phase");
        var layout=new PhaseLayout(model,state);var x=layout.encode(state);
        var equations=layout.wallClosure(PhaseLayout.totalAmounts(state),state.internalEnergy(),state.volume(),Double.NaN);
        int pressure=layout.size()-1;assertEquals(Math.log(p/1e5),x[pressure],1e-15);
        var liquid=HydrocarbonValues.of(model.hydrocarbon,t,p,normalised(state.liquid()),Root.LIQUID);
        var vapour=HydrocarbonValues.of(model.hydrocarbon,t,p,normalised(state.vapor()),Root.VAPOR);
        int row=layout.componentBalanceCount()+2,checked=0;double worst=0;
        for(int i=0;i<model.hydrocarbon.componentCount();i++) {
            if(layout.support(i)!=PhaseSupport.BOTH)continue;
            double expected=p*(liquid.partialMolarVolumesView()[i]-vapour.partialMolarVolumesView()[i])/(FluidThermodynamics.R*t);
            double swept=column(equations::residual,x,pressure,row++);
            worst=Math.max(worst,Math.abs(swept-expected)/Math.max(Math.abs(expected),1e-3));
            assertEquals(expected,swept,1e-5*Math.max(Math.abs(expected),1e-3),"equilibrium row of component "+i);
            checked++;
        }
        System.out.printf(Locale.ROOT,"two-phase node: %d equilibrium rows' ln P columns against p (vbar_L - vbar_V)/RT, worst %.2e%n",checked,worst);
        assertTrue(checked>5);
    }
}
