package com.wormzjl.icheme.science.column.v3;
import com.google.gson.GsonBuilder;
import com.wormzjl.icheme.science.material.*;
import com.wormzjl.icheme.science.column.v3.thermo.*;
import java.util.*;
import java.nio.file.*;

/** Offline fixture qualification; only called against compiled test/catalog classes. */
public class BinaryPhaseProbe {
 public static void main(String[] args) throws Exception {
  MaterialRuntime.publish(Cdu17TestCatalog.withProduction());
  List<Object> output = new ArrayList<>();
  var thermo=V3PengRobinsonThermo.fromRegisteredPackage(Cdu17TestCatalog.PACKAGE_ID);
  if (args[0].equals("learned")) {
   thermo=V3PengRobinsonThermo.fromRegisteredPackage("icheme:tjl19_dwsim");
   double[] feed=new double[thermo.componentBasis().componentCount()];feed[7]=50;feed[15]=50;
   for(int stages:new int[]{2,8})for(double q:new double[]{500000,2000000}) {
    var input=new V3ColumnInput(1,thermo.packageId(),"test:recovery",thermo.componentBasis(),feed,650,stages,stages/2,250000,750,
      List.of(new V3ColumnSpecification.CondenserOutletTemperature(300),new V3ColumnSpecification.OrganicRefluxRatio(2),new V3ColumnSpecification.ReboilerDuty(q)),
      List.of(new V3SideDrawSpec(stages==2?1:6,2)),List.of(),List.of(),V3ColumnInput.PRESCRIBED_DROP_DIAMETER);
    var outcome=V3TestInputs.calculate(input);output.add(Map.of("stages",stages,"Q",q,"outcome",outcome));
   }
  } else if (args[0].equals("pot")) {
   double[] feed=new double[thermo.componentBasis().componentCount()];feed[6]=50;feed[13]=50;
   for(int interior:new int[]{0,1})for(boolean wet:new boolean[]{false,true})for(double q:new double[]{0,500000}) {
    var input=new V3ColumnInput(1,thermo.packageId(),"test:pot",thermo.componentBasis(),feed,550,interior,interior+1,250000,750,
      List.of(new V3ColumnSpecification.CondenserOutletTemperature(300),new V3ColumnSpecification.OrganicRefluxRatio(2),new V3ColumnSpecification.ReboilerDuty(q)),
      List.of(),wet?List.of(new V3SteamFeedSpec(interior+1,10,600)):List.of(),List.of(),8);
    var outcome=V3TestInputs.calculate(input);output.add(Map.of("interior",interior,"wet",wet,"Q",q,"outcome",outcome));
    System.out.println(interior+" "+wet+" "+q+" "+outcome);
   }
  } else if (args[0].equals("baseline")) {
   var type=Class.forName("com.wormzjl.icheme.science.column.v3.V3ExactWarmStartSweepTest");
   var inputMethod=type.getDeclaredMethod("binaryInput",double.class,double.class,double.class,double.class,double.class,double.class,double.class);
   var attemptMethod=type.getDeclaredMethod("coldAttempt",V3ColumnInput.class,V3CondenserPhaseBranch.class);
   inputMethod.setAccessible(true);attemptMethod.setAccessible(true);
   for(double[] p:new double[][]{{550,750,50000},{551,750,50000},{550,0,50000},{550,750,20000},{550,750,100000}}) {
    var input=(V3ColumnInput)inputMethod.invoke(null,100.,p[0],250000.,p[1],300.,2.,p[2]);
    for(var branch:List.of(V3CondenserPhaseBranch.TWO_PHASE,V3CondenserPhaseBranch.LIQUID_ONLY)) {
     Object cold=attemptMethod.invoke(null,input,branch);
     var accessor=cold.getClass().getDeclaredMethod("attempt");accessor.setAccessible(true);
     var attempt=(V3SimultaneousColumnSolver.Attempt)accessor.invoke(cold);
     output.add(Map.of("input",input,"branch",branch,"state",attempt.state(),"evidence",attempt.evidence().convergenceEvidence()));
    }
   }
  } else {
   double[] feed=new double[thermo.componentBasis().componentCount()];feed[6]=50;feed[13]=50;
   for(double t:new double[]{600,650})for(double q:new double[]{500000,2000000}) {
    var input=V3TestInputs.dry(1,thermo.packageId(),"test:binary-probe",thermo.componentBasis(),feed,t,2,1,250000,750,List.of(new V3ColumnSpecification.CondenserOutletTemperature(300),new V3ColumnSpecification.OrganicRefluxRatio(2),new V3ColumnSpecification.ReboilerDuty(q)));
    var outcome=V3TestInputs.calculate(input);
    output.add(Map.of("T",t,"Q",q,"outcome",outcome));System.out.println(t+" "+q+" "+outcome);
   }
  }
  Files.writeString(Path.of(args[1]),new GsonBuilder().setPrettyPrinting().create().toJson(output));
 }
}
