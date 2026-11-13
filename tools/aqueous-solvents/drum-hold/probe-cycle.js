// The active-set cycle probe, on from the start of the run: probe-on2 (the failure probe, always on, its ring dumped at a
// hold) with the whole cycle key in every PASS note, a BRANCH note at each place the pass loop solves again and a CYCLE
// note at the throw; at a hold it also prints PROBE_GRAPH for the held interval and for the interval solved before it
// (base64 of the codec's topology and state, probe-codec.js), for an offline replay of the pair on one solver.
// Written for cold seed 431 (FINAL_LANE_2_REVIEW.md) on 9c251f4d7. An armed probe (probe-on5) does not see this hold:
// the held interval solves when it is asked again in place. Run from the worktree root.
// Undo: git checkout -- PassiveStepSolver.java PassiveIntervalSolver.java FluidCheckpointCodec.java (their full paths).
const fs=require('fs'),cp=require('child_process');
cp.execFileSync('node',['tools/aqueous-solvents/drum-hold/probe-on2.js'],{stdio:'inherit'});
cp.execFileSync('node',['tools/aqueous-solvents/drum-hold/probe-codec.js'],{stdio:'inherit'});
let p='src/main/java/com/wormzjl/icheme/science/fluid/network/PassiveStepSolver.java';let s=fs.readFileSync(p,'utf8');
const rep=(a,b)=>{const n=s.split(a).length-1;if(n!==1)throw new Error('site x'+n+': '+a.slice(0,80));s=s.replace(a,b);};
const note=(label,extra)=>'if(PROBE)probeNote("BRANCH '+label+' pass="+pass'+(extra?'+'+extra:'')+');';
// The whole cycle key in the PASS note.
rep('+" proposal="+(proposal==null?"-":proposal.node()+":"+proposal.phases()));',
    '+" proposal="+(proposal==null?"-":proposal.node()+":"+proposal.phases())+" folded="+Arrays.toString(foldedPhases)+" closed="+Arrays.toString(boundaryClosed)+" restarts="+restarts+" donors="+Arrays.toString(equations.junctionDonorFirst)+" segments="+Arrays.toString(equations.segmentCodes())+" columns="+Arrays.toString(equations.columnCodes())+" supports="+Arrays.toString(supportCodes(supports))+" balance="+(balanceLawFlows!=null));');
rep('restarts)))throw new SparseNewton.Nonconvergence("Phase/device active-set cycle");',
    'restarts))){if(PROBE)probeNote("CYCLE pass="+pass);throw new SparseNewton.Nonconvergence("Phase/device active-set cycle");}');
// The phase codes of a list of states.
rep('    private byte[] supportCodes(byte[][] supports) {',
    '    private String probeCodes(List<FluidThermodynamics.State> states){var b=new StringBuilder("[");for(var x:states)b.append(phaseCode(x)).append(" ");return b.append("]").toString();}\n    private byte[] supportCodes(byte[][] supports) {');
// A failed pass.
rep('if(cavitates(equations,failure.lastVariables(),modes,boundaryClosed,tolerance))continue;','if(cavitates(equations,failure.lastVariables(),modes,boundaryClosed,tolerance)){'+note('cavitates')+'continue;}');
rep('if(flashed!=null){seeds=flashed;continue;}','if(flashed!=null){'+note('flashes','" to="+probeCodes(flashed)')+'seeds=flashed;continue;}');
rep('if(openStarved(original,modes,emptying))continue;','if(openStarved(original,modes,emptying)){'+note('openStarved')+'continue;}');
rep('if(refuted!=0){','if(refuted!=0){'+note('refuted','" node="+proposal.node()+" phases="+refuted'));
rep('var changedSeeds=foldedIterate(graph,iterate);','var changedSeeds=foldedIterate(graph,iterate);'+note('failed','" foldedIterate="+(changedSeeds==null?"null":probeCodes(changedSeeds))+" "+failure.getMessage()'));
// A converged pass.
rep('if(reactivate(equations,states,promoted)>0){seeds=states;continue;}','if(reactivate(equations,states,promoted)>0){'+note('reactivate')+'seeds=states;continue;}');
rep('if(corrected!=null){seeds=corrected;continue;}','if(corrected!=null){'+note('correctedAtIllegalDirection','" to="+probeCodes(corrected)')+'seeds=corrected;continue;}');
rep('if(restart!=null){restarted[illegalDirection]=true;','if(restart!=null){'+note('restart','" pipe="+illegalDirection')+'restarted[illegalDirection]=true;');
rep('if(changed){seeds=states;previousPipes=pipeIdentities;','if(changed){'+note('changed','" device="+deviceChange+" modes="+modes+" closed="+Arrays.toString(boundaryClosed)')+'seeds=states;previousPipes=pipeIdentities;');
rep('if(equations.segmentsMoved(flows)){seeds=states;','if(equations.segmentsMoved(flows)){'+note('segmentsMoved')+'seeds=states;');
rep('if(arrived!=point){seeds=arrived;','if(arrived!=point){'+note('arrived')+'seeds=arrived;');
rep('if(filled(equations,projection)){seeds=projection.states();continue;}','if(filled(equations,projection)){'+note('filled')+'seeds=projection.states();continue;}');
rep('if(phaseAppeared(graph,states,projection.states())){seeds=projection.states();continue;}','if(phaseAppeared(graph,states,projection.states())){'+note('phaseAppeared','" from="+probeCodes(states)+" to="+probeCodes(projection.states())')+'seeds=projection.states();continue;}');
rep('if(recordFolds(graph,states,projection.states(),foldedPhases,equations.dry)){','if(recordFolds(graph,states,projection.states(),foldedPhases,equations.dry)){'+note('recordFolds','" from="+probeCodes(states)+" to="+probeCodes(projection.states())+" folded="+Arrays.toString(foldedPhases)'));
rep('proposal=Proposal.of(projection.states(),changedSeeds);seeds=changedSeeds;','proposal=Proposal.of(projection.states(),changedSeeds);'+note('phaseCorrection','" from="+probeCodes(projection.states())+" to="+probeCodes(changedSeeds)+" proposal="+(proposal==null?"-":proposal.node()+":"+proposal.phases())')+'seeds=changedSeeds;');
fs.writeFileSync(p,s);
// The held interval's input graph and the one before it.
p='src/main/java/com/wormzjl/icheme/science/fluid/network/PassiveIntervalSolver.java';s=fs.readFileSync(p,'utf8');const nl=s.includes('\r\n')?'\r\n':'\n';
const head='    public Result solve(PassiveNetwork initial,double duration,Settings settings,Runnable checkpoint,double startingStep,Map<Long,ExchangerRelay.Read> reads,Map<Long,PortPlan> ports) {'+nl;
const data='java.util.Base64.getEncoder().encodeToString(com.wormzjl.icheme.runtime.fluid.FluidCheckpointCodec.probeGraph(';
rep(head,
'    private Object[] probePrevious;'+nl+head+
'        try{var solved=solve0(initial,duration,settings,checkpoint,startingStep,reads,ports);probePrevious=new Object[]{initial,duration,startingStep,settings};return solved;}'+nl+
'        catch(SparseNewton.Nonconvergence held) {'+nl+
'            if(held.getMessage()!=null&&held.getMessage().startsWith("Substep refinement exhausted")) {'+nl+
'                try{System.out.println("PROBE_GRAPH held duration="+duration+" startingStep="+startingStep+" settings="+settings+" compounds="+model.compounds()+" wet="+model.wet()+" reads="+reads.size()+" ports="+ports.size()+" data="+'+data+'initial)));'+nl+
'                    if(probePrevious!=null)System.out.println("PROBE_GRAPH previous duration="+probePrevious[1]+" startingStep="+probePrevious[2]+" settings="+probePrevious[3]+" data="+'+data+'(PassiveNetwork)probePrevious[0])));'+nl+
'                }catch(RuntimeException dump){System.out.println("PROBE_GRAPH failed: "+dump);}'+nl+
'            }'+nl+
'            throw held;'+nl+
'        }'+nl+
'    }'+nl+
'    private Result solve0(PassiveNetwork initial,double duration,Settings settings,Runnable checkpoint,double startingStep,Map<Long,ExchangerRelay.Read> reads,Map<Long,PortPlan> ports) {'+nl);
fs.writeFileSync(p,s);console.log('cycle probe applied');
