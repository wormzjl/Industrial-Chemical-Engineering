package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.column.operation.*;
import com.wormzjl.icheme.science.column.v3.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ColumnProbeTest {
    @Test void internalMassReadingsUseTheCyclesTotalFeedBasisIncludingWater(){
        var world=new EventPathWorld();var axis=List.of("hc","Water");
        var ops=new ColumnOperation(world.ledger,world.registry,world.coordinator,axis,new double[]{.1,.018});
        var input=new V3ColumnInput(1,"test:probe","test:probe",new V3ComponentBasis(List.of("hc")),new double[]{90},450,0,1,300000,0,
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(330),new V3ColumnSpecification.OrganicRefluxRatio(1),new V3ColumnSpecification.ReboilerDuty(1000)),List.of());
        var condenser=new V3ColumnInspection.Node(330,300000,10,0,0,0,List.of(1.),List.of(0.));
        var sump=new V3ColumnInspection.Node(450,300000,50,20,3,0,List.of(1.),List.of(1.));
        var accepted=new V3ColumnInspection(input,List.of(condenser,sump),new V3AcceptanceAudit(List.of(V3AcceptanceAudit.Check.pass("fixture",0,0,"bounded accepted reading fixture"))));
        var settings=new Setpoints(200,100,330,1,10,new double[0],new double[0],new double[0]);
        var ref=new SplitTable.Reference(new double[]{.9,.1},0,30,new double[0],.5,settings,450,new double[0]);
        var table=new SplitTable(axis,List.of(new SplitTable.Product(Port.BOTTOMS,0,new double[]{1,1},450,300000)),10,new double[0],-10,ref,new boolean[]{true,true});
        var readings=ops.probes(accepted,table);
        assertNotNull(readings);assertEquals(1./100,readings.refluxPerFeed(),1e-12);
        assertEquals((2+3*.018)/100,readings.vapourPerFeed(),1e-12,"water vapor contributes mass; feed water remains in the cycle denominator");
        assertEquals(330,readings.refluxTemperature());assertEquals(450,readings.bottomTemperature());
    }
}
