// Worked numbers for TWO_PHASE_MACHINES_RESEARCH_REVIEW.md (2026-11-04).
const f=(x,d=3)=>x.toFixed(d);
// Q1: Turpin onset GLR_T = 3 P[psia]/2000 = 0.02176 P[bar]; alpha_T = r/(1+r); alpha50 = 1.6 alpha_T; M = 1/(1+(a/a50)^4)
const aT=P=>{const r=0.02176*P;return r/(1+r);};
const M=(a,a50)=>1/(1+Math.pow(a/a50,4));
console.log('Q1 onset and multiplier');
for(const P of [1,2.5,5,10,20]){const t=aT(P),a50=1.6*t;
  console.log(`P=${P} bar aT=${f(100*t,1)}% a50=${f(100*a50,1)}%`,[0.01,0.02,0.04,0.07,0.10,0.20,0.30].map(a=>`${a*100}%:${f(M(a,a50),2)}/${f(M(a,a50)*(1-a),2)}`).join(' '));}
// Q3: NPSH3 from suction specific speed S (metric rpm, m3/s, m), S=200 ~ US 10,300
const npsh3=(n,Q,S=200)=>Math.pow(n*Math.sqrt(Q)/S,4/3);
console.log('Q3 NPSH3_r', [[2950,100/3600],[1475,100/3600],[2950,500/3600],[1475,1000/3600]].map(([n,Q])=>`${n}rpm ${f(Q*3600,0)}m3/h: ${f(npsh3(n,Q),2)} m`).join('; '));
console.log('Q3 f(x)=0.4+0.6x^2', [0,0.5,1,1.2,1.5].map(x=>`${x}:${f(0.4+0.6*x*x,2)}`).join(' '));
// Q4/Q5: intensity I(s) ~ s^3 (si - s)^2.83, s = NPSHA/NPSH3, normalised at its peak
const si=4, sp=3*si/5.83;
const I=s=>s<=0||s>=si?0:Math.pow(s/sp,3)*Math.pow((si-s)/(si-sp),2.83);
console.log(`Q4 peak at s=${f(sp,2)}`, [0.25,0.5,0.75,1,1.5,2,2.5,3,3.5,4].map(s=>`${s}:${f(I(s),2)}`).join(' '));
// gas cushion g(a) = exp(-a/0.02)
console.log('gas cushion', [0,0.005,0.01,0.02,0.05].map(a=>`${a*100}%:${f(Math.exp(-a/0.02),2)}`).join(' '));
// Wear: life at intensity I with tau=2 h online at I=1
for(const s of [1,1.5,2,3,3.5]) console.log(`life s=${s}: ${f(2/Math.max(I(s),1e-9),1)} h online = ${f(2/Math.max(I(s),1e-9)*3,0)} MC days`);
// Q6: wet gas LVF vs LMF, natural gas 50 bar ~ 40 kg/m3, condensate 600 kg/m3; air 1 bar 1.2, water 998
const lmf=(lvf,rg,rl)=>lvf*rl/(lvf*rl+(1-lvf)*rg);
for(const [n,rg,rl] of [['gas 50 bar/condensate',40,600],['air 1 bar/water',1.2,998]]) console.log(n,[0.001,0.01,0.03,0.05].map(v=>`LVF ${v*100}%: LMF ${f(100*lmf(v,rg,rl),1)}%`).join('; '));
// Today's compressor limit 1 % by mass as LVF
const lvf=(m,rg,rl)=>(m/rl)/(m/rl+(1-m)/rg);
console.log('1% LMF as LVF: gas50bar', f(100*lvf(0.01,40,600),3),'% ; air1bar',f(100*lvf(0.01,1.2,998),4),'%');
