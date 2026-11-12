// probe-on2 with the probe off until an interval holds; the held interval is then solved again in place with the probe on.
// Undo: git checkout -- PassiveStepSolver.java PassiveIntervalSolver.java.
const fs=require('fs'),cp=require('child_process');
cp.execFileSync('node',['tools/aqueous-solvents/drum-hold/probe-on2.js'],{stdio:'inherit'});
let p='src/main/java/com/wormzjl/icheme/science/fluid/network/PassiveStepSolver.java';let s=fs.readFileSync(p,'utf8');
let rep=(a,b)=>{if(s.split(a).length!==2)throw new Error('site '+a.slice(0,70)+' '+(s.split(a).length-1));s=s.replace(a,b);};
rep('static final boolean PROBE=true;','static volatile boolean PROBE=false;');
fs.writeFileSync(p,s);
p='src/main/java/com/wormzjl/icheme/science/fluid/network/PassiveIntervalSolver.java';s=fs.readFileSync(p,'utf8');const nl=s.includes('\r\n')?'\r\n':'\n';
const head='    public Result solve(PassiveNetwork initial,double duration,Settings settings,Runnable checkpoint,double startingStep,Map<Long,ExchangerRelay.Read> reads,Map<Long,PortPlan> ports) {'+nl;
rep(head,head+
'        try{return solve0(initial,duration,settings,checkpoint,startingStep,reads,ports);}'+nl+
'        catch(SparseNewton.Nonconvergence held) {'+nl+
'            if(!PassiveStepSolver.PROBE&&held.getMessage()!=null&&held.getMessage().startsWith("Substep refinement exhausted")) {'+nl+
'                PassiveStepSolver.PROBE=true;System.out.println("PROBE_RERUN first: "+held.getMessage());'+nl+
'                try{solve0(initial,duration,settings,checkpoint,startingStep,reads,ports);System.out.println("PROBE_RERUN passed");}catch(RuntimeException again){System.out.println("PROBE_RERUN again: "+again.getMessage());}finally{PassiveStepSolver.PROBE=false;}'+nl+
'            }'+nl+
'            throw held;'+nl+
'        }'+nl+
'    }'+nl+
'    private Result solve0(PassiveNetwork initial,double duration,Settings settings,Runnable checkpoint,double startingStep,Map<Long,ExchangerRelay.Read> reads,Map<Long,PortPlan> ports) {'+nl);
fs.writeFileSync(p,s);
