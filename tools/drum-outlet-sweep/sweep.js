// Replica of EquipmentShape.bootFor's outlet placement for lying boot drums; compares the current rule with
// option A (cell centre, derated largest pipe) and option B (off-centre point in 1/32 m steps, full largest pipe).
const SW=.375, SP=.125, MAXO=.8;
const flange=o=>Math.min(1,1.25*o+.085);
const axis=D=>{const c=Math.ceil(D);return (c%2==0?c+1:c)/2;};
const headH=D=>Math.ceil(axis(D)-D/2+.3-.5)+.5;
const rows=[];
for(let D=.5;D<=10;D+=.5)for(let L=1;L<=64;L++){
  const size=Math.min(D/2,Math.max(D<.9?.3:D<1.4?.4:.5,D/3));
  const od=Math.min(MAXO,D/2), fl=flange(od)/2, bT=D/4, tT=bT+L, saddle=SW/2+.15, clear=SW/2+.05+size/2, half=SW/2;
  const near=bT+SP*L, far=tT-SP*L;
  const station=Math.max(Math.ceil(near+clear-.5)+.5,Math.floor(near+half-1e-9)+1.5);
  if(station>Math.min(far-clear+1e-9,Math.floor(far-half)-.5))continue; // no boot
  const cellA=Math.floor(near-half)-.5; // last cell centre whose column clears the saddle's blocks
  const outlet=Math.min(Math.floor(near-saddle-fl-.5)+.5,cellA);
  const under=bT-outlet<=.6*bT+1e-9&&outlet>od/2;
  const head=!under? (axis(D)-headH(D)):null;
  // option A: largest flange radius at cellA clearing the saddle edge by .15
  const room=(near-half-.15)-cellA, odA=Math.min(od,(2*room-.085)/1.25);
  const okA=bT-cellA<=.6*bT+1e-9;
  // option B: off-centre point, 1/32 steps, in cellA's column
  const yB=Math.floor((near-saddle-fl)*32)/32, offB=yB-cellA;
  const okB=bT-yB<=.6*bT+1e-9 && Math.abs(offB)<=.5-od/2+1e-9;
  rows.push({D,L,under,head,cellA,odA:+odA.toFixed(3),okA,yB,offB,okB});
}
const fb=rows.filter(r=>!r.under);
console.log('boot drums',rows.length,'outlet under now',rows.length-fb.length,'fall back to head',fb.length);
const byD={};for(const r of fb){(byD[r.D]??=[]).push(r);}
for(const D in byD){const a=byD[D];console.log(`D ${D}: L ${a[0].L}-${a.at(-1).L} (${a.length}) head row ${a[0].head} m under axis | A cell ${a.map(r=>r.cellA).filter((v,i,s)=>s.indexOf(v)==i)} od<=${[...new Set(a.map(r=>r.odA))]} ok ${a.every(r=>r.okA)} | B off ${[...new Set(a.map(r=>r.offB))]} ok ${a.filter(r=>r.okB).length}/${a.length}`);}
const r=rows.find(r=>r.D==2&&r.L==6);console.log('D2 L6',JSON.stringify(r));
// Option A as built (bootFor 2026-10-28): which fallbacks move under the drum, and their largest bore.
const moved=fb.filter(r=>{const bT=r.D/4,near=bT+SP*r.L,room=near-(SW/2+.15)-r.cellA,bore=Math.floor((2*room-.085)/1.25/1.1/.05+1e-9)*.05;r.bore=+bore.toFixed(2);return bT-r.cellA<=.6*bT+1e-9&&bore>=.05&&r.cellA>1.1*bore/2;});
console.log('A moves',moved.length,moved.map(r=>`${r.D}x${r.L} bore ${r.bore}`).join(', '));
