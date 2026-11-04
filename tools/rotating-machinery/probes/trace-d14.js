// Temporary trace (never committed): D14 suction floors. Prints (1) each new liquid-pump floor with its supplier and the
// engine's TP phases just above and below it, (2) at a failed Newton the worst row, every edge, every node, and per liquid
// pump its mode, floor, vapourPressure and suction-end pressure, (3) each balance anchoring at the floor.
// Usage: node trace-d14.js <PassiveStepSolver.java>; restore with git checkout afterwards.
const fs=require("fs");const p=process.argv[2];let t=fs.readFileSync(p,"utf8");const crlf=t.includes("\r\n");if(crlf)t=t.replace(/\r\n/g,"\n");
function once(a,b){if(t.split(a).length!==2)throw new Error("anchor: "+a.slice(0,60));t=t.replace(a,()=>b);}
once(`    static final int BALANCE_ROUNDS=2;`,`    static final int BALANCE_ROUNDS=2;
    static final java.util.concurrent.atomic.AtomicInteger D14_FAILS=new java.util.concurrent.atomic.AtomicInteger(),D14_BAL=new java.util.concurrent.atomic.AtomicInteger();
    static final java.util.Set<String> D14_SEEN=java.util.concurrent.ConcurrentHashMap.newKeySet();
    static String d14ph(FluidThermodynamics.State s){if(s==null)return "null";return (FluidThermodynamics.holdsPhase(s,FluidThermodynamics.GAS)?"G":"")+(FluidThermodynamics.holdsPhase(s,FluidThermodynamics.OIL)?"O":"")+(FluidThermodynamics.holdsPhase(s,FluidThermodynamics.WATER)?"W":"");}
    String d14tp(FluidThermodynamics.State s,double pr){try{var at=model.equilibriumTP(s.temperature(),pr,FluidThermodynamics.phaseMoles(s,FluidThermodynamics.OIL),()->{},null);return d14ph(at)+String.format(java.util.Locale.ROOT,"(vf=%.3g)",at.vaporVolume()/at.volume());}catch(IllegalArgumentException x){return x.getClass().getSimpleName()+":"+String.valueOf(x.getMessage()).replaceAll("\\s+"," ").substring(0,Math.min(60,String.valueOf(x.getMessage()).length()));}}
    void d14floors(PassiveNetwork graph,List<FluidThermodynamics.State> states,boolean rate,String where){
        for(int e=0;e<graph.pipes().size();e++){var pipe=graph.pipes().get(e);if(!(pipe.control() instanceof FlowControl.Mover m&&m.liquid()))continue;
            var walk=InletPhase.walk(graph,e);var sup=walk.via()<0?null:states.get(walk.node());double fl=e<suctionFloors.length?suctionFloors[e]:Double.NaN;
            String key=where+" e"+e+" "+pipe.first()+">"+pipe.second()+" walk="+walk.node()+" floor="+fl+(sup==null?"":" supT="+sup.temperature()+" supP="+sup.pressure()+" ph="+d14ph(sup));
            if(D14_SEEN.size()<400&&D14_SEEN.add(key)){String probe=sup!=null&&Double.isFinite(fl)?" TP@0.99floor="+d14tp(sup,fl*.99)+" TP@1.01floor="+d14tp(sup,fl*1.01)+" TP@supP="+d14tp(sup,sup.pressure()):"";
                System.out.printf(java.util.Locale.ROOT,"D14FLOOR %s rate=%b kind1=%s first=%s links=%s%s target=%.4g%n",key,rate,graph.reservoirs().get(walk.node()).kind(),d14ph(states.get(pipe.first())),java.util.Arrays.toString(walk.links()),probe,m.targetVolumeFlow());}}
    }`);
once(`        suctionFloors=suctionFloors(inputGraph,inputStates,checkpoint);
        SolverDiagnostics.count(SolverDiagnostics.implicitSolves);`,`        suctionFloors=suctionFloors(inputGraph,inputStates,checkpoint);
        d14floors(inputGraph,inputStates,rateOnly,"solve");
        SolverDiagnostics.count(SolverDiagnostics.implicitSolves);`);
once(`        suctionFloors=suctionFloors(graph,graph.reservoirs().stream().map(PassiveNetwork.Reservoir::state).toList(),()->{});`,
`        suctionFloors=suctionFloors(graph,graph.reservoirs().stream().map(PassiveNetwork.Reservoir::state).toList(),()->{});
        d14floors(graph,graph.reservoirs().stream().map(PassiveNetwork.Reservoir::state).toList(),true,"balanceStart");`);
once(`                        modes.set(e,FlowControl.Mode.PUMP_SUCTION_LIMIT);anchor[a]=floor;p[a]=floor;mover[e]=false;suctionLimited[e]=true;prescribed[e]=Math.max(0,inflowExcept(a,e,p));changed=true;`,
`                        modes.set(e,FlowControl.Mode.PUMP_SUCTION_LIMIT);anchor[a]=floor;p[a]=floor;mover[e]=false;suctionLimited[e]=true;prescribed[e]=Math.max(0,inflowExcept(a,e,p));changed=true;
                        if(D14_BAL.getAndIncrement()<80)System.out.printf(java.util.Locale.ROOT,"D14BAL e%d node%d pa=%.6g floor=%.6g prescribed=%.5g round=%d%n",e,a,pa,floor,prescribed[e],round);`);
once(`            catch(SparseNewton.Nonconvergence failure) {
                if(failure.lastVariables()==null)throw failure;`,`            catch(SparseNewton.Nonconvergence failure) {
                if(failure.lastVariables()!=null&&D14_FAILS.getAndIncrement()<150)try{double[] fr=equations.residual(failure.lastVariables());int worst=0;for(int k=1;k<fr.length;k++)if(Math.abs(fr[k])>Math.abs(fr[worst]))worst=k;
                    String kind=worst>=equations.edgeOffset&&worst<equations.edgeOffset+graph.pipes().size()?"edge "+(worst-equations.edgeOffset):"node/other";for(int e=0;e<graph.pipes().size();e++)if(equations.controlOffsets[e]==worst)kind="control "+e;
                    StringBuilder sb=new StringBuilder();for(int e=0;e<graph.pipes().size();e++){var pp=graph.pipes().get(e);double xq=failure.lastVariables()[equations.edgeOffset+e];int c=equations.controlOffsets[e];sb.append(String.format(java.util.Locale.ROOT," e%d[%d>%d %s %s q=%.4g h=%.4g%s]",e,pp.first(),pp.second(),pp.control().getClass().getSimpleName(),modes.get(e),xq,c<0?0:failure.lastVariables()[c]*1e5,boundaryClosed[e]?" bc":""));}
                    var st=equations.states(failure.lastVariables());StringBuilder ns=new StringBuilder();for(int nd=0;nd<st.size();nd++){var s=st.get(nd);if(s!=null)ns.append(String.format(java.util.Locale.ROOT," n%d[%s T=%.1f P=%.0f %s seed=%s vapV=%.3g/%.3g]",nd,graph.reservoirs().get(nd).kind(),s.temperature(),s.pressure(),d14ph(s),d14ph(equations.seeds.get(nd)),s.vaporVolume(),s.volume()));}
                    StringBuilder ps=new StringBuilder();for(int e=0;e<graph.pipes().size();e++){var pp=graph.pipes().get(e);if(!(pp.control() instanceof FlowControl.Mover m&&m.liquid()))continue;int a0=pp.first();
                        ps.append(String.format(java.util.Locale.ROOT," pump e%d mode=%s floor=%.6g vapP=%.6g endP=%.6g node%d ph=%s;",e,modes.get(e),e<suctionFloors.length?suctionFloors[e]:Double.NaN,vapourPressure(equations,e,st.get(a0)),endPressure(graph.reservoirs().get(a0),pp.firstPort(),st.get(a0)),a0,d14ph(st.get(a0))));}
                    System.out.printf(java.util.Locale.ROOT,"D14FAIL dt=%.4g rate=%b pass=%d %s worst=%d (%s) f=%.4g%n  pumps:%s%n  edges:%s%n  nodes:%s%n",dt,rateOnly,pass,failure.getMessage(),worst,kind,fr[worst],ps,sb,ns);}catch(RuntimeException traceFailed){System.out.println("D14FAIL trace failed "+traceFailed);}
                if(failure.lastVariables()==null)throw failure;`);
if(crlf)t=t.replace(/\n/g,"\r\n");fs.writeFileSync(p,t);console.log("ok");
