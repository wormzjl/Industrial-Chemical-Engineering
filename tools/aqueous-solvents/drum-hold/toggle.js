// node tools/aqueous-solvents/drum-hold/toggle.js <5|6> <off|on> (5: initialModes, a flow valve set to no flow starts closed; 6: stocked): disables or restores one of the two cold-start rules, for the gate's proof.
const fs=require('fs');const f='src/main/java/com/wormzjl/icheme/science/fluid/network/PassiveStepSolver.java';
let s=fs.readFileSync(f,'utf8');const [rule,mode]=process.argv.slice(2);
const pairs={5:['!valve.bare()&&!valve.pressureControl()&&valve.targetMassFlow(1)==0&&!stands','false&&!valve.bare()&&!valve.pressureControl()&&valve.targetMassFlow(1)==0&&!stands'],
             6:['boolean stocked=stocked(j,','boolean stocked=false&&stocked(j,']};
const [on,off]=pairs[rule];
if(mode==='off'){if(!s.includes(on)||s.includes(off))throw new Error('not in the on state');s=s.replace(on,off);}
else{if(!s.includes(off))throw new Error('not in the off state');s=s.replace(off,on);}
fs.writeFileSync(f,s);console.log('rule',rule,mode);
