// Temporary probe (never committed): counterfactual B for D14 holds. The balance closes a pump whose suction line delivers
// nothing at the floor (no NPSH available) instead of anchoring its suction there. Apply after trace-d14.js.
const fs=require("fs");const p=process.argv[2];let t=fs.readFileSync(p,"utf8");const crlf=t.includes("\r\n");if(crlf)t=t.replace(/\r\n/g,"\n");
function once(a,b){if(t.split(a).length!==2)throw new Error("anchor: "+a.slice(0,60));t=t.replace(a,()=>b);}
once(`                    if(mode==FlowControl.Mode.MOVER_LAW&&graph.reservoirs().get(a).junction()&&floor<Double.POSITIVE_INFINITY&&pa<floor*(1-1e-9)) {`,
`                    if(mode==FlowControl.Mode.MOVER_LAW&&graph.reservoirs().get(a).junction()&&floor<Double.POSITIVE_INFINITY&&pa<floor*(1-1e-9)
                            &&!(probeAt(p,a,floor)<=0)) {`);
once(`        private double inflowExcept(int node,int except,double[] p) {`,`        private double probeQ(int a,int e,double[] p,double floor){double keep=p[a];p[a]=floor;double q=inflowExcept(a,e,p);p[a]=keep;return q;}
        private double inflowExcept(int node,int except,double[] p) {`);
// the closure branch: a pump on its law whose line gives nothing at the floor closes
once(`                    } else if(suctionLimited[e]){double q=Math.max(0,inflowExcept(a,e,p));`,`                    } else if(mode==FlowControl.Mode.MOVER_LAW&&graph.reservoirs().get(a).junction()&&floor<Double.POSITIVE_INFINITY&&pa<floor*(1-1e-9)){
                        System.out.printf(java.util.Locale.ROOT,"D14PROBE closed e%d pa=%.6g floor=%.6g%n",e,pa,floor);modes.set(e,FlowControl.Mode.CLOSED);closed[e]=true;mover[e]=false;changed=true;
                    } else if(suctionLimited[e]){double q=Math.max(0,inflowExcept(a,e,p));`);
t=t.replace("!(probeAt(p,a,floor)<=0)","!(probeQ(a,e,p,floor)<=0)");
if(crlf)t=t.replace(/\n/g,"\r\n");fs.writeFileSync(p,t);console.log("ok");
