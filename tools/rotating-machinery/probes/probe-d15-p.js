// Temporary counterfactual (never committed), probe P: D15's vapour-lock test (suctionHead) is read at no flow, on the
// step's start states (startHead), wherever it decides close-or-limit: the balance (supplierHead), cavitates (the failed
// iterate) and nextMode's MOVER_LAW cavitating branch. The suction-limit -> CLOSED and the CLOSED -> MOVER_LAW tests keep
// the pass's converged point. Apply after trace-d15.js. Restore with git checkout.
const fs=require("fs");const p=process.argv[2];let t=fs.readFileSync(p,"utf8");const crlf=t.includes("\r\n");if(crlf)t=t.replace(/\r\n/g,"\n");
function once(a,b){if(t.split(a).length!==2)throw new Error("anchor: "+a.slice(0,70));t=t.replace(a,()=>b);}
once(`                next=suctionHead(equations,i,states)>0?FlowControl.Mode.PUMP_SUCTION_LIMIT:FlowControl.Mode.CLOSED;`,
     `                next=startHead(graph,i)>0?FlowControl.Mode.PUMP_SUCTION_LIMIT:FlowControl.Mode.CLOSED;`);
once(`                {modes.set(i,suctionHead(equations,i,states)>0?FlowControl.Mode.PUMP_SUCTION_LIMIT:FlowControl.Mode.CLOSED);return true;}`,
     `                {modes.set(i,startHead(equations.graph,i)>0?FlowControl.Mode.PUMP_SUCTION_LIMIT:FlowControl.Mode.CLOSED);return true;}`);
once(`            int s=walk.node();var state=graph.reservoirs().get(s).state();double held=unknown[s]>=0?p[s]:anchor[s];`,
     `            int s=walk.node();var state=graph.reservoirs().get(s).state();double held=Double.NaN;`);
if(crlf)t=t.replace(/\n/g,"\r\n");fs.writeFileSync(p,t);console.log("ok");
