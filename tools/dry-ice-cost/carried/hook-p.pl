#!/usr/bin/perl
# Scratch: writes the instrumented copies of the solver classes to the overlay folder. Read-only hooks; every substitution
# must match exactly once or it dies. perl hook.pl <worktree> <overlay folder> [patched FluidThermodynamics.java]
use strict; use warnings;
my ($W,$OUT,$FT)=@ARGV;
sub slurp{open my $f,'<:raw',$_[0] or die $_[0];local $/;my $s=<$f>;$s=~s/\r\n/\n/g;$s}
sub spew{my($p,$s)=@_;(my $d=$p)=~s{/[^/]+$}{};system('mkdir','-p',$d);open my $f,'>:raw',$p or die $p;print $f $s;close $f}
sub sub1{my($sr,$from,$to)=@_;my $n=()=$$sr=~/\Q$from\E/g;die "match $n: $from" unless $n==1;$$sr=~s/\Q$from\E/$to/;}
my $base="src/main/java/com/wormzjl/icheme/science/fluid";
my $T="com.wormzjl.icheme.science.fluid.diagnostics.StepTrace";

# ---- the interval controller
my $s=slurp("$W/$base/network/PassiveIntervalSolver.java");
sub1(\$s,'PassiveNetwork accepted=refreshed.graph();',"PassiveNetwork accepted=refreshed.graph();$T.run(duration,startingStep,guard==StageGuard.NONE?\"none\":\"solid\");");
sub1(\$s,'checkpoint.run();double step=Math.min(h,duration-elapsed);',"checkpoint.run();double step=Math.min(h,duration-elapsed);$T.attemptStart(attempt,elapsed,step,h);");
sub1(\$s,'SolverDiagnostics.attempt(attempt,step,false,"be-reopen",candidates.size());',"SolverDiagnostics.attempt(attempt,step,false,\"be-reopen\",candidates.size());$T.attemptEnd(\"reopen\",Double.NaN,String.valueOf(candidates.size()));");
sub1(\$s,'double change=stateChange(accepted,result.states());',"double change=stateChange(accepted,result.states());$T.change(accepted,result.states(),result.massFlows(),step,STATE_MASS_REFERENCE_DENSITY);");
sub1(\$s,'if(!ok){previousStateChange=change;',"$T.attemptEnd(ok?(jump&&step>transitionFloor?\"accepted-jump\":\"accepted\"):\"state\",change,null);if(!ok){previousStateChange=change;");
sub1(\$s,'SolverDiagnostics.attempt(attempt,step,false,"transition",Double.NaN);',"SolverDiagnostics.attempt(attempt,step,false,\"transition\",Double.NaN);$T.attemptEnd(\"transition\",Double.NaN,transition.getMessage());");
sub1(\$s,'if(!(rejected instanceof StateChangeRejection))SolverDiagnostics.attempt(attempt,step,false,diagnosticReason(rejected),Double.NaN);',"if(!(rejected instanceof StateChangeRejection)){SolverDiagnostics.attempt(attempt,step,false,diagnosticReason(rejected),Double.NaN);$T.attemptEnd(diagnosticReason(rejected).replace(' ','_'),Double.NaN,rejected.getMessage());}");
spew("$OUT/com/wormzjl/icheme/science/fluid/network/PassiveIntervalSolver.java",$s);

# ---- the step solver: stages and every exit of the pass loop
$s=slurp("$W/$base/network/PassiveStepSolver.java");
sub1(\$s,"this.rateOnly=rateOnly;\n        inputStates=","this.rateOnly=rateOnly;$T.solveStart(rateOnly,dt);\n        inputStates=");
sub1(\$s,'else{start=balanceStart(graph,modes,boundaryClosed,keep,checkpoint);balanceFor=inputGraph;',"else{$T.mark(\"preBalance\");start=balanceStart(graph,modes,boundaryClosed,keep,checkpoint);$T.mark(\"balance\");balanceFor=inputGraph;");
sub1(\$s,'var seen=new HashSet<PassKey>();',"$T.mark(\"seeds\");var seen=new HashSet<PassKey>();");
sub1(\$s,'try{numerical=SparseNewton.solve(equations,equations.initial(),new SparseNewton.Settings(SparseNewton.ITERATIONS,tolerance,1e-6,24),checkpoint,workspace);}',"long traced=$T.iterations();$T.mark(\"preNewton\");try{numerical=SparseNewton.solve(equations,equations.initial(),new SparseNewton.Settings(SparseNewton.ITERATIONS,tolerance,1e-6,24),checkpoint,workspace);$T.mark(\"newton\");$T.newton(true,traced,null);}");
sub1(\$s,"catch(SparseNewton.Nonconvergence failure) {\n                if(failure.lastVariables()==null)throw failure;","catch(SparseNewton.Nonconvergence failure) {$T.mark(\"newtonFailed\");$T.newton(false,traced,failure.getMessage());\n                if(failure.lastVariables()==null)throw failure;");
sub1(\$s,'if(cavitates(equations,failure.lastVariables(),modes,boundaryClosed,tolerance))continue;',"if(cavitates(equations,failure.lastVariables(),modes,boundaryClosed,tolerance)){$T.pass(pass,\"cavitates\");continue;}");
sub1(\$s,'if(drained!=null){emptying=drained;continue;}',"if(drained!=null){$T.pass(pass,\"runsDry\");emptying=drained;continue;}");
sub1(\$s,'if(refuted!=0){foldedPhases[proposal.node()]|=refuted;',"if(refuted!=0){$T.pass(pass,\"refuted node=\"+proposal.node()+\" bits=\"+refuted);foldedPhases[proposal.node()]|=refuted;");
sub1(\$s,'if(changedSeeds==null)throw new SparseNewton.Nonconvergence(failure.getMessage()+"; active-set pass="+pass,failure.lastVariables(),failure.phaseHold());',"if(changedSeeds==null){$T.pass(pass,\"fail\");throw new SparseNewton.Nonconvergence(failure.getMessage()+\"; active-set pass=\"+pass,failure.lastVariables(),failure.phaseHold());}$T.pass(pass,\"foldedOrDrained\");");
sub1(\$s,'if(reactivate(equations,states,promoted)>0){seeds=states;continue;}',"if(reactivate(equations,states,promoted)>0){$T.pass(pass,\"reactivate\");seeds=states;continue;}");
sub1(\$s,'if(emptying!=null){var kept=keptEmptying(states,emptying,emptied);if(kept!=null){emptying=kept;continue;}}',"if(emptying!=null){var kept=keptEmptying(states,emptying,emptied);if(kept!=null){$T.pass(pass,\"keptEmptying\");emptying=kept;continue;}}");
sub1(\$s,'if(!changed&&illegalDirection>=0){closeBoundary(graph,illegalDirection,boundaryClosed,modes);changed=true;}',"if(!changed&&illegalDirection>=0){$T.pass(pass,\"illegal edge=\"+illegalDirection);closeBoundary(graph,illegalDirection,boundaryClosed,modes);changed=true;}if(deviceChange>=0)$T.pass(pass,\"device edge=\"+deviceChange+\" \"+deviceNext);");
sub1(\$s,'if(equations.segmentsMoved(flows)){seeds=states;',"if(equations.segmentsMoved(flows)){$T.pass(pass,\"segments\");seeds=states;");
sub1(\$s,'if(filled(equations,projection)){seeds=projection.states();continue;}',"$T.mark(\"reconstruct\");if(filled(equations,projection)){$T.pass(pass,\"filled\");seeds=projection.states();continue;}");
sub1(\$s,'if(phaseAppeared(graph,states,projection.states())){seeds=projection.states();continue;}',"if(phaseAppeared(graph,states,projection.states())){$T.pass(pass,\"appeared\");seeds=projection.states();continue;}");
sub1(\$s,'if(recordFolds(graph,states,projection.states(),foldedPhases)){SolverDiagnostics.count(SolverDiagnostics.traceFoldPasses);',"if(recordFolds(graph,states,projection.states(),foldedPhases)){$T.pass(pass,\"fold\");SolverDiagnostics.count(SolverDiagnostics.traceFoldPasses);");
sub1(\$s,"var changedSeeds=phaseCorrection(graph,projection.states(),checkpoint,equations.cachedPrepared,foldedPhases);\n            if(changedSeeds!=null){changedSeeds=flashSeeds","var changedSeeds=phaseCorrection(graph,projection.states(),checkpoint,equations.cachedPrepared,foldedPhases);$T.mark(\"phaseCheck\");\n            if(changedSeeds!=null){$T.pass(pass,\"proposal\");changedSeeds=flashSeeds");
sub1(\$s,'if(maximumResidual>gate){SolverDiagnostics.count(SolverDiagnostics.equationGateRejections);',"if(maximumResidual>gate){$T.pass(pass,\"gate\");SolverDiagnostics.count(SolverDiagnostics.equationGateRejections);");
sub1(\$s,"checkConservation(original,projection);\n            previousPipes=pipeIdentities;","checkConservation(original,projection);$T.pass(pass,\"accept\");\n            previousPipes=pipeIdentities;");
spew("$OUT/com/wormzjl/icheme/science/fluid/network/PassiveStepSolver.java",$s);

# ---- the phase check, its result fields; the file may carry a prototype's patch
$s=slurp($FT // "$W/$base/thermo/FluidThermodynamics.java");
sub1(\$s,'public State phaseCheck(State state,boolean junction,State from,int[] record,int at,Prepared prepared,UnaryOperator<State> vesselSplit,Runnable checkpoint) {',"public State phaseCheck(State state,boolean junction,State from,int[] record,int at,Prepared prepared,UnaryOperator<State> vesselSplit,Runnable checkpoint) {long traced=$T.now();int tTp=-1,tProp=0,tPh=-1,tOwn=-1,tAdd=0;");
sub1(\$s,'int proposed=lacking(kept,equilibrium)&~excluded;',"int proposed=lacking(kept,equilibrium)&~excluded;tTp=phaseBits(equilibrium);tProp=proposed;");
sub1(\$s,'start=engine!=null?engine.withSolidState(kept.solids(),kept.solidMoments()):tpSeed(kept,equilibrium,prepared);',"start=engine!=null?engine.withSolidState(kept.solids(),kept.solidMoments()):tpSeed(kept,equilibrium,prepared);tPh=engine==null?-2:phaseBits(engine);");
sub1(\$s,"if(own!=null) {\n                    SolverDiagnostics.count(SolverDiagnostics.phaseSeeds);","if(own!=null) {tOwn=phaseBits(own);\n                    SolverDiagnostics.count(SolverDiagnostics.phaseSeeds);");
sub1(\$s,'int added=lacking(kept,seed)&~excluded;',"int added=lacking(kept,seed)&~excluded;tAdd=added;");
sub1(\$s,"if(record!=null)record[at]|=phaseBits(state)&~phaseBits(checked)|refused;\n        return checked;","if(record!=null)record[at]|=phaseBits(state)&~phaseBits(checked)|refused;$T.check(at,junction,state.temperature(),state.pressure(),phaseBits(state),hydrocarbonComplete&&waterComplete,tTp,excluded,tProp,tPh,tOwn,refused,tAdd,checked!=state,System.nanoTime()-traced);\n        return checked;");
spew("$OUT/com/wormzjl/icheme/science/fluid/thermo/FluidThermodynamics.java",$s);

# ---- the one choke point of the engine
$s=slurp("$W/$base/thermo/NetworkPhaseEngine.java");
sub1(\$s,'return engine.tp(EquilibriumRequest.tp(t,p,species,overall,competition),workspaces.get());',"long t0=$T.now();var r=engine.tp(EquilibriumRequest.tp(t,p,species,overall,competition),workspaces.get());$T.flash(\"TP\",t0,t,p,overall,competition!=PhaseCompetition.FLUID_ONLY,String.valueOf(r.classification()),r.converged(),r.diagnostics().kernelEvaluations());return r;");
sub1(\$s,'return engine.ph(EquilibriumRequest.ph(p,h,species,overall,competition),workspaces.get());',"long t0=$T.now();var r=engine.ph(EquilibriumRequest.ph(p,h,species,overall,competition),workspaces.get());$T.flash(\"PH\",t0,h,p,overall,competition!=PhaseCompetition.FLUID_ONLY,String.valueOf(r.classification()),r.converged(),r.diagnostics().kernelEvaluations());return r;");
sub1(\$s,'return Double.isFinite(hintT)&&Double.isFinite(hintP)?engine.uv(request,workspaces.get(),hintT,hintP):engine.uv(request,workspaces.get());',"long t0=$T.now();var r=Double.isFinite(hintT)&&Double.isFinite(hintP)?engine.uv(request,workspaces.get(),hintT,hintP):engine.uv(request,workspaces.get());$T.flash(\"UV\",t0,u,v,overall,competition!=PhaseCompetition.FLUID_ONLY,String.valueOf(r.classification()),r.converged(),r.diagnostics().kernelEvaluations());return r;");
spew("$OUT/com/wormzjl/icheme/science/fluid/thermo/NetworkPhaseEngine.java",$s);

# ---- the probe: names the job, stops after the last one asked for
$s=slurp("$W/tmp/dry-ice-symptoms/run/src/com/wormzjl/icheme/runtime/fluid/DryIceProbe.java");
sub1(\$s,'var result=(ProcessSolveServices.FluidIslandSolveResult)command.solve(token);',"$T.job((int)job+1,(int)attempt.islandId(),attempt.slice().startTick(),attempt.slice().endTick());var result=(ProcessSolveServices.FluidIslandSolveResult)command.solve(token);$T.jobEnd();");
sub1(\$s,'if(nanos>=describeMs*1_000_000L){',"if(job>=Long.getLong(\"step.stopAfter\",Long.MAX_VALUE)){jobs.flush();System.exit(0);}if(nanos>=describeMs*1_000_000L){");
spew("$OUT/com/wormzjl/icheme/runtime/fluid/DryIceProbe.java",$s);

# ---- second round: which row the gate refuses, what the guard reads
$s=slurp("$OUT/com/wormzjl/icheme/science/fluid/network/PassiveStepSolver.java");
sub1(\$s,'double maximumResidual=0;for(double residual:equations.residual(reconstructed))maximumResidual=Math.max(maximumResidual,Math.abs(residual));',"double maximumResidual=0;{double[] tracedResidual=equations.residual(reconstructed);for(double residual:tracedResidual)maximumResidual=Math.max(maximumResidual,Math.abs(residual));if(maximumResidual>1e-8&&$T.on){int[] sz=new int[equations.offsets.length];boolean[] js=new boolean[sz.length];for(int q=0;q<sz.length;q++){sz[q]=equations.nodeSize(q);js[q]=graph.reservoirs().get(q).junction();}$T.gate(tracedResidual,equations.offsets,sz,js,equations.edgeOffset);}}");
spew("$OUT/com/wormzjl/icheme/science/fluid/network/PassiveStepSolver.java",$s);
$s=slurp("$OUT/com/wormzjl/icheme/science/fluid/network/PassiveIntervalSolver.java");
sub1(\$s,'if(last!=null)guard.checkRate(liveFilters(accepted),last.states(),last.modes(),last.massFlows());',"$T.line(\"T0 \"+(last!=null?\"last\":carriedFlows(accepted)!=null?\"carried\":guard!=StageGuard.NONE&&attempt==0?\"rate\":\"none\"));if(last!=null)guard.checkRate(liveFilters(accepted),last.states(),last.modes(),last.massFlows());");
spew("$OUT/com/wormzjl/icheme/science/fluid/network/PassiveIntervalSolver.java",$s);
$s=slurp("$W/$base/network/SolidEventIntegrator.java");
sub1(\$s,'var check=mobility(graph,states,prepared,i,flow);
            boolean overPopulated',"var check=mobility(graph,states,prepared,i,flow);if($T.on){int up=flow>=0?pipe.first():pipe.second();var d=prepared[up];var st=states.get(up);$T.mob(check.allowed()?\"ok\":\"FAIL\",i,pipe.first(),pipe.second(),flow,check.reason().name(),check.velocity(),check.minimumVelocity(),up,d.active(),d.solidVolume(),d.liquidVolume(),st.volume(),st.solidMoments().mass());}
            boolean overPopulated");
sub1(\$s,'var failure=failed(graph,states,flows,still);if(failure!=null)throw failure.atStart();',"$T.line(\"GUARD t0\");var failure=failed(graph,states,flows,still);if(failure!=null)throw failure.atStart();");
sub1(\$s,'var failure=failed(graph,states,flows,still);
                if(failure!=null)throw failure;',"$T.line(\"GUARD end\");var failure=failed(graph,states,flows,still);
                if(failure!=null)throw failure;");
sub1(\$s,'failure=failed(graph,candidate.states(),candidate.massFlows(),still);',"$T.line(\"GUARD interval attempt=\"+attempt+\" still=\"+java.util.Arrays.toString(still));failure=failed(graph,candidate.states(),candidate.massFlows(),still);");
sub1(\$s,'var carriedFlows=integrator.carriedFlows(graph);still=',"$T.line(\"GUARD interval rate failed\");var carriedFlows=integrator.carriedFlows(graph);still=");
sub1(\$s,'if(failure==null)break;
            blocked[failure.edge]|=BOTH_DIRECTIONS;',"$T.line(\"CLOSE-T0 \"+(failure==null?\"none\":failure.edge+\" \"+failure.reason()+\" v=\"+failure.check.velocity()+\" min=\"+failure.check.minimumVelocity()));if(failure==null)break;
            blocked[failure.edge]|=BOTH_DIRECTIONS;");
sub1(\$s,'var transition=prefix.transition();int stoppedFilter=-1;',"var transition=prefix.transition();int stoppedFilter=-1;$T.line(\"SEGMENT event=\"+event+\" transition=\"+(transition==null?\"none\":transition.edge+\" \"+transition.reason()+\" atStart=\"+transition.atStart)+\" masks=\"+java.util.Arrays.toString(blocked));");
spew("$OUT/com/wormzjl/icheme/science/fluid/network/SolidEventIntegrator.java",$s);

# ---- third round: the solid ledger of a junction at a gate refusal
$s=slurp("$OUT/com/wormzjl/icheme/science/fluid/network/PassiveStepSolver.java");
sub1(\$s,"$T.gate(tracedResidual,equations.offsets,sz,js,equations.edgeOffset);}}","$T.gate(tracedResidual,equations.offsets,sz,js,equations.edgeOffset);for(int q=0;q<sz.length;q++)if(js[q]&&!graph.reservoirs().get(q).fixed())$T.line(String.format(java.util.Locale.ROOT,\"SOLIDJ n=%d dt=%.4g heldKg=%.6g in_s=%.6g inMass=%.6g newtonKg=%.6g bookedKg=%.6g fluidMassN=%.6g fluidMassB=%.6g draws=%s bdg=%b/%b seedPop=%d\",q,dt,graph.reservoirs().get(q).inventory().solids().massKg(),equations.solidIncoming[q][0],equations.incomingMass[q],states.get(q).solidMoments().mass(),projection.states().get(q).solidMoments().mass(),states.get(q).mass(),projection.states().get(q).mass(),java.util.Arrays.deepToString(draws==null?new double[0][]:new double[][]{draws[2]==null?new double[0]:draws[2]}),FluidThermodynamics.bulkDrawsGas(states.get(q)),FluidThermodynamics.bulkDrawsGas(projection.states().get(q)),states.get(q).solids().populations().size()));}}");
spew("$OUT/com/wormzjl/icheme/science/fluid/network/PassiveStepSolver.java",$s);


# ---- fourth round: what the arriving stage decides
$s=slurp("$OUT/com/wormzjl/icheme/science/fluid/network/PassiveStepSolver.java");
sub1(\$s,'if(laidOutFor(phaseCode(model.folded(engine,true)),phaseCode(seed)))continue;',"if(laidOutFor(phaseCode(model.folded(engine,true)),phaseCode(seed))){$T.line(\"ARRIVE j=\"+j+\" laidOut\");continue;}$T.line(\"ARRIVE j=\"+j+\" split\");");
sub1(\$s,'if(laidOutFor(phaseCode(mixture),phaseCode(seed)))continue;',"if(laidOutFor(phaseCode(mixture),phaseCode(seed))){$T.line(\"ARRIVE j=\"+j+\" splitLaidOut\");continue;}$T.line(\"ARRIVE j=\"+j+\" changed\");");
spew("$OUT/com/wormzjl/icheme/science/fluid/network/PassiveStepSolver.java",$s);

# ---- fifth round: where a failed pass stands
$s=slurp("$OUT/com/wormzjl/icheme/science/fluid/network/PassiveStepSolver.java");
sub1(\$s,"$T.newton(false,traced,failure.getMessage());","$T.newton(false,traced,failure.getMessage());if($T.on&&failure.lastVariables()!=null){try{int[] sz=new int[equations.offsets.length];boolean[] js=new boolean[sz.length];for(int q=0;q<sz.length;q++){sz[q]=equations.nodeSize(q);js[q]=graph.reservoirs().get(q).junction();}$T.gate(equations.residual(failure.lastVariables()),equations.offsets,sz,js,equations.edgeOffset);var fs=equations.states(failure.lastVariables());var b=new StringBuilder(\"ITERATE\");for(int q=0;q<fs.size();q++)b.append(String.format(java.util.Locale.ROOT,\" %d:%.0fPa/%.1fK/b%d\",q,fs.get(q).pressure(),fs.get(q).temperature(),FluidThermodynamics.phaseBits(fs.get(q))));$T.line(b.toString());}catch(RuntimeException traceOnly){}}");
spew("$OUT/com/wormzjl/icheme/science/fluid/network/PassiveStepSolver.java",$s);


# ---- the balance's numbers, and a switch that takes the liquid tables away (step.noLiquidTables=true)
$s=slurp("$OUT/com/wormzjl/icheme/science/fluid/network/PassiveStepSolver.java");
sub1(\$s,'p=net.solve(checkpoint);converged=net.converged;',"p=net.solve(checkpoint);converged=net.converged;if($T.on){var o=new StringBuilder(\"BALANCE round=\"+round+\" conv=\"+converged+\" res=\"+net.finalResidual+\" hull=\"+net.low+\"..\"+net.high+\" iters=\"+net.iterations+\" sweeps=\"+net.sweeps);for(int q=0;q<current.reservoirs().size();q++){var r=current.reservoirs().get(q);o.append(\"~~N\"+q+\" id=\"+r.id()+(r.junction()?\" J\":\" R\")+\" z=\"+r.elevation()+\" u=\"+net.unknown[q]+\" P=\"+p[q]+\" stated=\"+r.state().pressure()+\" T=\"+r.state().temperature()+\" gas=\"+FluidThermodynamics.holdsPhase(r.state(),FluidThermodynamics.GAS)+\" flashFrom=\"+net.flashFrom[q]);if(net.tableP[q]!=null)o.append(\" tp=\"+java.util.Arrays.toString(net.tableP[q])+\" rho=\"+java.util.Arrays.toString(net.tableRho[q])+\" rhoV=\"+java.util.Arrays.toString(net.tableRhoV[q]));}for(int e=0;e<edges;e++){var pp=current.pipes().get(e);o.append(\"~~E\"+e+\" \"+pp.first()+\">\"+pp.second()+\" closed=\"+net.closed[e]+\" mode=\"+modes.get(e)+\" A=\"+pp.minimumArea()+\" cap=\"+net.cap[e][0]+\"/\"+net.cap[e][1]+\" rho=\"+net.rho[e][0]+\"/\"+net.rho[e][1]+\" q=\"+(net.closed[e]?0:net.flow(e,net.endAt(e,0,p),net.endAt(e,1,p))));}$T.line(o.toString());}");
sub1(\$s,'double top=liquid?Math.min(high,s.pressure()):high;if(!(top>low))continue;','double top=liquid?Math.min(high,s.pressure()):high;if(!(top>low)||liquid&&Boolean.getBoolean("step.noLiquidTables"))continue;');
sub1(\$s,'if(at==null)at=model.equilibriumPH(s.temperature(),tp[k],h,held.clone(),checkpoint);','if(at==null&&(liquid||!Boolean.getBoolean("step.noCarriedTables")))at=model.equilibriumPH(s.temperature(),tp[k],h,held.clone(),checkpoint);');
sub1(\$s,'if(empty&&!dry(node,seed))continue;','if(com.wormzjl.icheme.science.fluid.diagnostics.StepTrace.on&&holdsNoFluidVessel(node)){var o=new StringBuilder("EMPTY j="+j+" dry="+dry(node,seed)+" dt="+dt+" moles="+java.util.Arrays.stream(node.inventory().moles()).sum());for(int q=0;q<flows.length;q++){var pp=graph.pipes().get(q);if(pp.first()==j||pp.second()==j){int don=pp.first()==j?pp.second():pp.first();var ds=graph.reservoirs().get(don).state();o.append(" e"+q+"="+flows[q]+"/"+boundaryAllowed(graph,pp,flows[q])+" prevLaw="+(previousLawFlows.length>q?previousLawFlows[q]:Double.NaN)+" carried="+carriedPoint(graph,identities(graph))+" bal="+(balanceLawFlows==null?"null":""+balanceLawFlows[q])+" law="+lawFlows(graph)[q]+" donorP="+ds.pressure()+" donorSeedP="+current.get(don).pressure()+" tankP="+node.state().pressure()+" fromBal="+lawFlowsFromBalance);}}com.wormzjl.icheme.science.fluid.diagnostics.StepTrace.line(o.toString());}if(empty&&!dry(node,seed))continue;');
sub1(\$s,"startPoint(graph,modes,boundaryClosed,identities(graph),null,flows,new double[edges],new boolean[edges]);\n        List<FluidThermodynamics.State> result=null;","startPoint(graph,modes,boundaryClosed,identities(graph),null,flows,new double[edges],new boolean[edges]);\n        if(Boolean.getBoolean(\"step.lawIntoDry\")){double[] law=null;for(int e=0;e<edges;e++){if(flows[e]!=0||boundaryClosed[e]||modes.get(e)==FlowControl.Mode.CLOSED)continue;var pp=graph.pipes().get(e);if(!holdsNoFluidVessel(graph.reservoirs().get(pp.first()))&&!holdsNoFluidVessel(graph.reservoirs().get(pp.second())))continue;if(law==null)law=lawFlows(graph);double q=law[e];int into=q>=0?pp.second():pp.first();if(holdsNoFluidVessel(graph.reservoirs().get(into))&&boundaryAllowed(graph,pp,q)&&!outOfDryEnd(graph,pp,q))flows[e]=q;}}\n        List<FluidThermodynamics.State> result=null;");
spew("$OUT/com/wormzjl/icheme/science/fluid/network/PassiveStepSolver.java",$s);
