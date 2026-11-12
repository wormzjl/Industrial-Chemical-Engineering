// The failure probe on top of the working tree (stockSeeds, 4f2c922ec, included): patch, on by default, three-slot names, pass notes, a 3000-note ring.
// Undo: git checkout -- PassiveStepSolver.java PassiveIntervalSolver.java (the patch touches both; stockSeeds is committed, 4f2c922ec).
const fs=require('fs'),cp=require('child_process');
cp.execFileSync('git',['apply','tools/minecraft-time-delta/patches/probe-feed-step-178d7e2f4.patch']);
const p='src/main/java/com/wormzjl/icheme/science/fluid/network/PassiveStepSolver.java';let s=fs.readFileSync(p,'utf8');
const rep=(a,b)=>{if(!s.includes(a))throw new Error('missing '+a.slice(0,70));s=s.split(a).join(b);};
rep('Boolean.getBoolean("icheme.probe.failures")','true');
rep('.append(" liq=").append(s.liquidVolume()).append(" water=").append(s.waterVolume())','.append(" cond=").append(s.condensedVolume())');
rep('.append(" liq=").append(String.format("%.4g",st.liquidVolume()))','.append(" cond=").append(String.format("%.4g",st.condensedVolume()))');
rep('+" liq="+s.liquidVolume()+" vap="','+" cond="+s.condensedVolume()+" vap="');
rep('for(int p=0;p<graph.pipes().size();p++)b.append("  pipe ")','for(int n=0;n<graph.reservoirs().size();n++){if(e.layout[n]==null)continue;b.append("  unknowns ").append(n).append(" dry=").append(e.dry[n]).append(" x0=");for(int l=0;l<e.layout[n].size();l++)b.append(String.format("%.6g ",e.initial()[e.offsets[n]+l]));b.append(" x=");for(int l=0;l<e.layout[n].size();l++)b.append(String.format("%.6g ",x[e.offsets[n]+l]));b.append(" scale=").append(e.layout[n].amountScale()).append((char)10);}'+"\n"+'            for(int p=0;p<graph.pipes().size();p++)b.append("  pipe ")');
rep('while(r.size()>80)r.removeFirst();','while(r.size()>3000)r.removeFirst();');
rep('var equations=new Equations(graph,dt,modes,boundaryClosed,seeds,reachable,supports,pipeIdentities);allowedRestart=null;','var equations=new Equations(graph,dt,modes,boundaryClosed,seeds,reachable,supports,pipeIdentities);allowedRestart=null;if(PROBE)probeNote("PASS "+pass+" dt="+dt+" phases="+Arrays.toString(phases)+" modes="+modes+" emptying="+Arrays.toString(emptying)+" proposal="+(proposal==null?"-":proposal.node()+":"+proposal.phases()));');
rep('if(emptying!=null){var kept=keptEmptying(original,states,emptying,emptied);if(kept!=null){emptying=kept;continue;}}','if(emptying!=null){var kept=keptEmptying(original,states,emptying,emptied);if(PROBE){var pb=new StringBuilder("CONVERGED pass="+pass+" emptying="+Arrays.toString(emptying)+" kept="+(kept==null?"null":Arrays.toString(kept)));for(int i=0;i<emptying.length;i++)if(emptying[i])pb.append(" P").append(i).append("=").append(states.get(i).pressure()).append(" wall=").append(original.reservoirs().get(i).inventory().wallWorkPressure());probeNote(pb.toString());}if(kept!=null){emptying=kept;continue;}}');
rep('if(drained!=null){seeds=stockSeeds(','if(drained!=null){if(PROBE)probeNote("RUNS_DRY pass="+pass+" "+Arrays.toString(drained));seeds=stockSeeds(');
fs.writeFileSync(p,s);
