// Polluted soil texture options (batch 2026-11-18-ground-pollution, G4b). node make.js <out folder> [texture path]: writes options A, B, C and their sheet to the folder, and the chosen option (C, owner 2026-11-18) to the texture path.
const fs=require('fs'),zlib=require('zlib'),path=require('path');
const out=process.argv[2],placeholder=process.argv[3];
function crc32(b){let c,t=[];for(let n=0;n<256;n++){c=n;for(let k=0;k<8;k++)c=c&1?0xedb88320^(c>>>1):c>>>1;t[n]=c>>>0;}let r=0xffffffff;for(const x of b)r=t[(r^x)&255]^(r>>>8);return (r^0xffffffff)>>>0;}
function png(w,h,px){const raw=Buffer.alloc((w*4+1)*h);for(let y=0;y<h;y++){raw[y*(w*4+1)]=0;for(let x=0;x<w;x++){const i=(y*w+x)*4,o=y*(w*4+1)+1+x*4;raw[o]=px[i];raw[o+1]=px[i+1];raw[o+2]=px[i+2];raw[o+3]=px[i+3];}}
 const ch=(t,d)=>{const l=Buffer.alloc(4);l.writeUInt32BE(d.length);const td=Buffer.concat([Buffer.from(t),d]);const c=Buffer.alloc(4);c.writeUInt32BE(crc32(td));return Buffer.concat([l,td,c]);};
 const ih=Buffer.alloc(13);ih.writeUInt32BE(w,0);ih.writeUInt32BE(h,4);ih[8]=8;ih[9]=6;return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),ch('IHDR',ih),ch('IDAT',zlib.deflateSync(raw)),ch('IEND',Buffer.alloc(0))]);}
let seed=12345;const rnd=()=>{seed=(seed*1103515245+12345)&0x7fffffff;return seed/0x7fffffff;};
function tex(base,specks,stain,stainShare){seed=777;const px=new Uint8Array(16*16*4);
 for(let i=0;i<256;i++){const v=(rnd()-.5)*22;let c=base.map(x=>x+v);const r=rnd();
  if(r<stainShare)c=stain.map(x=>x+(rnd()-.5)*18);else if(r<stainShare+.12)c=specks.map(x=>x+(rnd()-.5)*12);
  px.set([...c.map(x=>Math.max(0,Math.min(255,Math.round(x)))),255],i*4);}
 return px;}
const opts={
 A_ash:tex([92,86,80],[58,54,52],[128,124,112],.10),          // grey ash dirt, dark specks, pale ash flecks
 B_crust:tex([110,84,60],[70,52,38],[160,158,72],.14),        // brown dirt, sickly yellow-green crust spots
 C_oily:tex([62,54,50],[36,32,34],[92,70,104],.12),           // dark soil, oily violet sheen
};
const dirt=tex([134,96,67],[96,68,47],[121,85,58],.0);
fs.mkdirSync(out,{recursive:true});
for(const [k,v] of Object.entries(opts))fs.writeFileSync(path.join(out,'polluted_soil_'+k+'.png'),png(16,16,v));
// sheet: dirt reference, then A, B, C; each 3x3 tiles at x8 (384 px), 16 px gaps
const tiles=[dirt,opts.A_ash,opts.B_crust,opts.C_oily],S=8,T=16*3*S,G=16,W=tiles.length*T+(tiles.length+1)*G,H=T+2*G;
const sheet=new Uint8Array(W*H*4).fill(255);
tiles.forEach((t,n)=>{const ox=G+n*(T+G);for(let y=0;y<T;y++)for(let x=0;x<T;x++){const sx=Math.floor(x/S)%16,sy=Math.floor(y/S)%16,i=(sy*16+sx)*4,o=((G+y)*W+ox+x)*4;sheet.set(t.slice(i,i+4),o);}});
fs.writeFileSync(path.join(out,'polluted_soil_options.png'),png(W,H,sheet));
if(placeholder)fs.writeFileSync(placeholder,png(16,16,opts.C_oily));
console.log('done');
