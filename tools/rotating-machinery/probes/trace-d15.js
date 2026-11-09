// Temporary trace (never committed): D15 active-set cycle. Buffers each solve of an island with a liquid pump drawing
// through a suction walk; prints the buffer of a solve that throws (and the last good one before it). Per pass: the
// cycle-key parts, each liquid pump's mode, floor, suctionHead (boiling branch or floor), supplier, and which rule moved
// a mode (balance / carry / cavitates / nextMode) or which other branch re-solved the pass.
// Usage: node trace-d15.js <PassiveStepSolver.java>; restore with git checkout afterwards.
const fs=require("fs");const p=process.argv[2];let t=fs.readFileSync(p,"utf8");const crlf=t.includes("\r\n");if(crlf)t=t.replace(/\r\n/g,"\n");
function once(a,b){if(t.split(a).length!==2)throw new Error("anchor: "+a.slice(0,70));t=t.replace(a,()=>b);}
// 1. wrapper and helpers
once(`    private Result solve(PassiveNetwork inputGraph,double dt,Runnable checkpoint,boolean rateOnly,List<FlowControl.Mode> acceptedEndpointModes) {`,
`    static final ThreadLocal<StringBuilder> D15B=new ThreadLocal<>();static final ThreadLocal<String> D15_LAST=new ThreadLocal<>();
    static final java.util.concurrent.atomic.AtomicInteger D15_PRINTED=new java.util.concurrent.atomic.AtomicInteger();
    static void d15(String f,Object... a){var b=D15B.get();if(b!=null&&b.length()<600000)b.append(String.format(java.util.Locale.ROOT,f,a)).append('\\n');}
    static String d15ph(FluidThermodynamics.State s){if(s==null)return "null";return (FluidThermodynamics.holdsPhase(s,FluidThermodynamics.GAS)?"G":"")+(FluidThermodynamics.holdsPhase(s,FluidThermodynamics.OIL)?"O":"")+(FluidThermodynamics.holdsPhase(s,FluidThermodynamics.WATER)?"W":"");}
    static boolean d15watch(PassiveNetwork g){for(int e=0;e<g.pipes().size();e++)if(g.pipes().get(e).control() instanceof FlowControl.Mover m&&m.liquid()&&InletPhase.walk(g,e).via()>=0)return true;return false;}
    static String d15modes(PassiveNetwork g,List<FlowControl.Mode> modes){StringBuilder s=new StringBuilder();for(int e=0;e<g.pipes().size();e++)if(g.pipes().get(e).control() instanceof FlowControl.Mover||g.pipes().get(e).control() instanceof FlowControl.Valve)s.append(" e").append(e).append(':').append(modes.get(e));return s.toString();}
    String d15pump(Equations eq,int e,List<FluidThermodynamics.State> st){
        try{var g=eq.graph;var pipe=g.pipes().get(e);int a=pipe.first();var walk=InletPhase.walk(g,e);
            double vap=vapourPressure(eq,e,st.get(a)),margin=cavitationMargin(eq,e,st),sh=suctionHead(eq,e,st);
            String sup="none";if(walk.via()>=0){var s=st.get(walk.node());boolean boil=FluidThermodynamics.holdsPhase(s,FluidThermodynamics.GAS)&&FluidThermodynamics.holdsPhase(s,FluidThermodynamics.OIL);
                sup=String.format(java.util.Locale.ROOT,"n%d %s P=%.10g end=%.10g T=%.6f ph=%s branch=%s vf=%.3g",walk.node(),g.reservoirs().get(walk.node()).kind(),s.pressure(),endPressure(g.reservoirs().get(walk.node()),g.pipes().get(walk.via()).portAt(walk.node()),s),s.temperature(),d15ph(s),boil?"boiling":"floor",s.vaporVolume()/s.volume());}
            return String.format(java.util.Locale.ROOT,"e%d[n%d>n%d] a: P=%.10g T=%.6f ph=%s seed=%s | floor0=%.10g vapP=%.10g margin=%.6g suctionHead=%.6g band=%.3g | sup %s",
                e,a,pipe.second(),st.get(a).pressure(),st.get(a).temperature(),d15ph(st.get(a)),d15ph(eq.seeds.get(a)),e<suctionFloors.length?suctionFloors[e]:Double.NaN,vap,margin,sh,shutoffBand(eq.pressureScales[e],NEWTON_TOLERANCE),sup);}
        catch(RuntimeException x){return "e"+e+" trace failed "+x;}}
    String d15pumps(Equations eq,List<FluidThermodynamics.State> st){StringBuilder s=new StringBuilder();for(int e=0;e<eq.graph.pipes().size();e++)if(eq.graph.pipes().get(e).control() instanceof FlowControl.Mover m&&m.liquid())s.append("\\n    ").append(d15pump(eq,e,st));return s.toString();}
    private Result solve(PassiveNetwork inputGraph,double dt,Runnable checkpoint,boolean rateOnly,List<FlowControl.Mode> acceptedEndpointModes) {
        if(!d15watch(inputGraph))return solveD15(inputGraph,dt,checkpoint,rateOnly,acceptedEndpointModes);
        var outer=D15B.get();var b=new StringBuilder();D15B.set(b);
        d15("D15SOLVE dt=%.6g rate=%b nodes=%d pipes=%d accEndModes=%s",dt,rateOnly,inputGraph.reservoirs().size(),inputGraph.pipes().size(),acceptedEndpointModes==null?"null":d15modes(inputGraph,acceptedEndpointModes));
        try{var r=solveD15(inputGraph,dt,checkpoint,rateOnly,acceptedEndpointModes);d15("D15OK modes=%s",d15modes(inputGraph,r.modes()));D15_LAST.set(b.toString());return r;}
        catch(RuntimeException x){if(D15_PRINTED.getAndIncrement()<10){String last=D15_LAST.get();synchronized(System.out){if(last!=null){System.out.print("D15PREV-OK-BEGIN\\n"+last+"D15PREV-OK-END\\n");D15_LAST.set(null);}System.out.print(b);System.out.println("D15THROW "+x.getMessage());}}throw x;}
        finally{D15B.set(outer);}
    }
    private Result solveD15(PassiveNetwork inputGraph,double dt,Runnable checkpoint,boolean rateOnly,List<FlowControl.Mode> acceptedEndpointModes) {`);
// 2. carry
once(`            if(carried==FlowControl.Mode.PUMP_SUCTION_LIMIT&&(suctionFloors[i]<Double.POSITIVE_INFINITY||FluidThermodynamics.holdsPhase(inputStates.get(pipe.first()),FluidThermodynamics.WATER)))
                modes.set(i,startHead(graph,i)>0?carried:FlowControl.Mode.CLOSED);
            else if(carried==FlowControl.Mode.CLOSED&&!(shutoffMargin(graph,i)>band&&startHead(graph,i)>band))
                modes.set(i,carried);`,
`            var d15before=modes.get(i);
            if(carried==FlowControl.Mode.PUMP_SUCTION_LIMIT&&(suctionFloors[i]<Double.POSITIVE_INFINITY||FluidThermodynamics.holdsPhase(inputStates.get(pipe.first()),FluidThermodynamics.WATER)))
                modes.set(i,startHead(graph,i)>0?carried:FlowControl.Mode.CLOSED);
            else if(carried==FlowControl.Mode.CLOSED&&!(shutoffMargin(graph,i)>band&&startHead(graph,i)>band))
                modes.set(i,carried);
            d15("D15CARRY e%d carried=%s before=%s after=%s floor=%.10g startHead=%.6g shutoffMargin=%.6g band=%.3g",i,carried,d15before,modes.get(i),suctionFloors[i],startHead(graph,i),shutoffMargin(graph,i),band);`);
// 3. balance start
once(`            graph=start.graph();balanceLawFlows=start.lawFlows();`,`            graph=start.graph();balanceLawFlows=start.lawFlows();d15("D15BALSTART cached=%b converged=%b modes=%s",start==balanceCache&&balanceFor==inputGraph,start.converged(),d15modes(graph,start.modes()));`);
once(`                    boolean below=mode==FlowControl.Mode.MOVER_LAW&&graph.reservoirs().get(a).junction()&&floor<Double.POSITIVE_INFINITY&&pa<floor*(1-1e-9);`,
`                    boolean below=mode==FlowControl.Mode.MOVER_LAW&&graph.reservoirs().get(a).junction()&&floor<Double.POSITIVE_INFINITY&&pa<floor*(1-1e-9);
                    if(floor<Double.POSITIVE_INFINITY)d15("D15BAL e%d mode=%s pa=%.10g floor=%.10g below=%b limited=%b supplierHead=%.6g",e,mode,pa,floor,below,suctionLimited[e],supplierHead(e,floor,p));`);
// 4. pass key
once(`            if(!seen.add(new PassKey(structure,equations.segmentCodes(),equations.columnCodes(),foldedPhases.clone(),proposal==null?-1:proposal.node(),proposal==null?0:proposal.phases())))throw new SparseNewton.Nonconvergence("Phase/device active-set cycle");`,
`            boolean d15new=seen.add(new PassKey(structure,equations.segmentCodes(),equations.columnCodes(),foldedPhases.clone(),proposal==null?-1:proposal.node(),proposal==null?0:proposal.phases()));
            {StringBuilder bc=new StringBuilder();for(int e=0;e<boundaryClosed.length;e++)if(boundaryClosed[e])bc.append(e).append(',');StringBuilder fo=new StringBuilder();for(int n=0;n<foldedPhases.length;n++)if(foldedPhases[n]!=0)fo.append(n).append('=').append(foldedPhases[n]).append(',');
             d15("D15PASS pass=%d dt=%.6g rate=%b new=%b modes=%s phases=%s folded=%s prop=%d/%d seg=%08x col=%08x sup=%08x donors=%08x bc=%s emptying=%s seeds:%s",pass,dt,rateOnly,d15new,d15modes(graph,modes),Arrays.toString(phases),fo,proposal==null?-1:proposal.node(),proposal==null?0:proposal.phases(),
               Arrays.hashCode(equations.segmentCodes()),Arrays.hashCode(equations.columnCodes()),Arrays.deepHashCode(new Object[]{structure.supports}),Arrays.hashCode(equations.junctionDonorFirst),bc,emptying==null?"-":Arrays.toString(emptying),d15pumps(equations,seeds));}
            if(!d15new)throw new SparseNewton.Nonconvergence("Phase/device active-set cycle");`);
// 5. failed newton
once(`                if(cavitates(equations,failure.lastVariables(),modes,boundaryClosed,tolerance))continue;`,
`                {String d15it;try{d15it=d15pumps(equations,equations.states(failure.lastVariables()));}catch(RuntimeException x){d15it=" iterate states refused "+x.getMessage();}d15("D15FAIL pass=%d %s iterate:%s",pass,failure.getMessage(),d15it);}
                var d15m=List.copyOf(modes);
                if(cavitates(equations,failure.lastVariables(),modes,boundaryClosed,tolerance)){d15("D15RULE cavitates %s ->%s",d15modes(graph,d15m),d15modes(graph,modes));continue;}`);
once(`                if(drained!=null){emptying=drained;continue;}`,`                if(drained!=null){d15("D15BRANCH runsDry %s",Arrays.toString(drained));emptying=drained;continue;}`);
once(`                    if(refuted!=0){foldedPhases[proposal.node()]|=refuted;`,`                    if(refuted!=0){d15("D15BRANCH proposalRefuted node=%d phases=%d",proposal.node(),refuted);foldedPhases[proposal.node()]|=refuted;`);
once(`                seeds=changedSeeds;continue;
            }
            // A pass that converges ends the proposal`,`                d15("D15BRANCH foldedIterate/drainedSeeds");seeds=changedSeeds;continue;
            }
            d15("D15CONVERGED pass=%d iterations=%s",pass,numerical.iterations());
            // A pass that converges ends the proposal`);
// 6. converged branches
once(`            if(reactivate(equations,states,promoted)>0){seeds=states;continue;}`,`            if(reactivate(equations,states,promoted)>0){d15("D15BRANCH reactivate");seeds=states;continue;}`);
once(`if(kept!=null){emptying=kept;continue;}`,`if(kept!=null){d15("D15BRANCH keptEmptying");emptying=kept;continue;}`);
once(`                next=nextMode(equations,i,mode,flows[i],heads[i],states,up,suction,tolerance);`,
`                next=nextMode(equations,i,mode,flows[i],heads[i],states,up,suction,tolerance);
                if(pipe.control() instanceof FlowControl.Mover d15m&&d15m.liquid()){double rho=suction.density;
                    d15("  D15DEV e%d mode=%s next=%s flow=%.6g head=%.6g rho=%.5g rise-demand=%.6g lawRes=%.6g %s",i,mode,next,flows[i],heads[i],rho,riseLimit(graph,pipe,riseDensity(i,rho),states.get(pipe.first()).pressure())-demand(graph,pipe,states,rho),
                        lawResidual(graph,pipe,flows[i]/rho,heads[i],riseDensity(i,rho),states.get(pipe.first()).pressure()),d15pump(equations,i,states));}`);
once(`            if(deviceChange>=0) {
                modes.set(deviceChange,deviceNext);changed=true;`,`            if(illegalDirection>=0)d15("D15ILLEGAL e%d flow=%.6g",illegalDirection,flows[illegalDirection]);
            if(deviceChange>=0) {
                d15("D15RULE nextMode e%d %s ->%s",deviceChange,modes.get(deviceChange),deviceNext);
                modes.set(deviceChange,deviceNext);changed=true;`);
once(`            if(!changed&&illegalDirection>=0){closeBoundary(`,`            if(!changed&&illegalDirection>=0){d15("D15BRANCH closeBoundary e%d",illegalDirection);closeBoundary(`);
once(`            if(equations.segmentsMoved(flows)){seeds=states;`,`            if(equations.segmentsMoved(flows)){d15("D15BRANCH segmentsMoved");seeds=states;`);
once(`                if(arrived!=point){seeds=arrived;`,`                if(arrived!=point){d15("D15BRANCH arrivingPhases");seeds=arrived;`);
once(`            if(filled(equations,projection)){seeds=projection.states();continue;}`,`            if(filled(equations,projection)){d15("D15BRANCH filled");seeds=projection.states();continue;}`);
once(`            if(phaseAppeared(graph,states,projection.states())){seeds=projection.states();continue;}`,`            if(phaseAppeared(graph,states,projection.states())){d15("D15BRANCH phaseAppeared");seeds=projection.states();continue;}`);
once(`            if(recordFolds(graph,states,projection.states(),foldedPhases)){`,`            if(recordFolds(graph,states,projection.states(),foldedPhases)){d15("D15BRANCH recordFolds");`);
once(`            if(changedSeeds!=null){changedSeeds=flashSeeds(`,`            if(changedSeeds!=null){d15("D15BRANCH phaseCorrection");changedSeeds=flashSeeds(`);
if(crlf)t=t.replace(/\n/g,"\r\n");fs.writeFileSync(p,t);console.log("ok");
