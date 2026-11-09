// Temporary trace (never committed): prints a failed Newton's worst residual row, the edges and the node states.
// Usage: node trace-fail.js <PassiveStepSolver.java>; restore with git checkout afterwards.
const fs=require("fs");const p=process.argv[2];let t=fs.readFileSync(p,"utf8");const crlf=t.includes("\r\n");if(crlf)t=t.replace(/\r\n/g,"\n");
const a=`            catch(SparseNewton.Nonconvergence failure) {
                if(failure.lastVariables()==null)throw failure;`;
if(t.split(a).length!==2)throw new Error("anchor");
t=t.replace(a,()=>`            catch(SparseNewton.Nonconvergence failure) {
                if(failure.lastVariables()!=null){double[] fr=equations.residual(failure.lastVariables());int worst=0;for(int k=1;k<fr.length;k++)if(Math.abs(fr[k])>Math.abs(fr[worst]))worst=k;
                    String kind=worst>=equations.edgeOffset&&worst<equations.edgeOffset+graph.pipes().size()?"edge "+(worst-equations.edgeOffset):"node/other";for(int e=0;e<graph.pipes().size();e++)if(equations.controlOffsets[e]==worst)kind="control "+e;
                    StringBuilder sb=new StringBuilder();for(int e=0;e<graph.pipes().size();e++){var pp=graph.pipes().get(e);double x=failure.lastVariables()[equations.edgeOffset+e];int c=equations.controlOffsets[e];sb.append(String.format(java.util.Locale.ROOT," e%d[%d>%d %s %s q=%.4g h=%.4g]",e,pp.first(),pp.second(),pp.control().getClass().getSimpleName(),modes.get(e),x,c<0?0:failure.lastVariables()[c]*1e5));}
                    var st=equations.states(failure.lastVariables());StringBuilder ns=new StringBuilder();for(int nd=0;nd<st.size();nd++){var s=st.get(nd);if(s!=null)ns.append(String.format(java.util.Locale.ROOT," n%d[%s T=%.1f P=%.0f vapV=%.3g/%.3g]",nd,graph.reservoirs().get(nd).kind(),s.temperature(),s.pressure(),s.vaporVolume(),s.volume()));}
                    System.out.printf(java.util.Locale.ROOT,"A2TRACE fail dt=%.4g rate=%b %s worst=%d (%s) f=%.4g%n  edges:%s%n  nodes:%s%n",dt,rateOnly,failure.getMessage(),worst,kind,fr[worst],sb,ns);}
                if(failure.lastVariables()==null)throw failure;`);
if(crlf)t=t.replace(/\n/g,"\r\n");fs.writeFileSync(p,t);console.log("ok");
