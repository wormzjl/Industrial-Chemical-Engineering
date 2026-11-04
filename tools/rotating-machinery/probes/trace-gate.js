// Temporary trace (never committed): at an equation-gate refusal, prints the four worst rows at the reconstructed point (kind,
// value there and at the Newton point), the modes, flows and heads, and every node's Newton and reconstructed P, T and mass.
// Usage: node trace-gate.js <PassiveStepSolver.java>; restore with git checkout afterwards.
const fs=require("fs");const p=process.argv[2];let t=fs.readFileSync(p,"utf8");const crlf=t.includes("\r\n");if(crlf)t=t.replace(/\r\n/g,"\n");
function once(a,b){if(t.split(a).length!==2)throw new Error("anchor: "+a.slice(0,60));t=t.replace(a,()=>b);}
once(`    static final int BALANCE_ROUNDS=2;`,`    static final int BALANCE_ROUNDS=2;
    static final java.util.concurrent.atomic.AtomicInteger GATE_TRACES=new java.util.concurrent.atomic.AtomicInteger();`);
const a=`            if(maximumResidual>gate){SolverDiagnostics.count(SolverDiagnostics.equationGateRejections);throw new SparseNewton.Nonconvergence("Conservative reconstruction fails equation gate: "+maximumResidual);}`;
once(a,`            if(maximumResidual>gate&&GATE_TRACES.getAndIncrement()<6){
                double[] fr=equations.residual(reconstructed),fx=equations.residual(x);
                Integer[] order=new Integer[fr.length];for(int k=0;k<fr.length;k++)order[k]=k;
                java.util.Arrays.sort(order,(r1,r2)->Double.compare(Math.abs(fr[r2]),Math.abs(fr[r1])));
                StringBuilder rows=new StringBuilder();
                for(int r=0;r<Math.min(4,order.length);r++){int k=order[r];String kind="other";
                    for(int nd=0;nd<equations.layout.length;nd++)if(equations.layout[nd]!=null){int len=equations.layout[nd].encode(states.get(nd)).length;if(k>=equations.offsets[nd]&&k<equations.offsets[nd]+len)kind="node "+nd+" row "+(k-equations.offsets[nd]);}
                    for(int e=0;e<graph.pipes().size();e++){if(k==equations.edgeOffset+e)kind="hydraulic "+e;if(equations.controlOffsets[e]==k)kind="control "+e;}
                    rows.append(String.format(java.util.Locale.ROOT," [%s rec=%.4g newton=%.4g]",kind,fr[k],fx[k]));}
                StringBuilder ns=new StringBuilder();
                for(int nd=0;nd<states.size();nd++){var s=states.get(nd);var q=projection.states().get(nd);if(s!=null&&q!=null)ns.append(String.format(java.util.Locale.ROOT," n%d[P %.10g/%.10g T %.10g/%.10g m %.13g/%.13g]",nd,s.pressure(),q.pressure(),s.temperature(),q.temperature(),s.mass(),q.mass()));}
                System.out.printf(java.util.Locale.ROOT,"GATETRACE dt=%.4g max=%.4g rate=%b modes=%s%n  flows=%s heads=%s scales=%s%n  rows:%s%n  nodes:%s%n",dt,maximumResidual,rateOnly,modes,java.util.Arrays.toString(flows),java.util.Arrays.toString(heads),java.util.Arrays.toString(equations.pressureScales),rows,ns);
            }
`+a);
if(crlf)t=t.replace(/\n/g,"\r\n");fs.writeFileSync(p,t);console.log("ok");
