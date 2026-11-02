#!/bin/sh
# Compare two sweeps of PhProbe, from the worktree root: cmp.sh <base stem> <form stem>. Reads tmp/ph-false-root/<stem>-trips.tsv and <stem>-gap.tsv.
d=tmp/ph-false-root
a=$d/$1; b=$d/$2
echo "== trips: per feed: PH misses base->form, PH fails, UV misses, UV fails, calls"
paste "$a-trips.tsv" "$b-trips.tsv" | awk -F'\t' '
function off(x,y){d=x-y;if(d<0)d=-d;return d}
NR>1 && $4!="REFUSED" {
  k=$1; if(!(k in seen)){order[++n]=k;seen[k]=1}
  o=19; if($(o+4)=="REFUSED"){refused[k]++; next}
  st[k]++
  if($8=="true"  && off($9,$2)>1e-5) pa[k]++
  if($(o+8)=="true" && off($(o+9),$2)>1e-5) pb[k]++
  if($8!="true") fa[k]++
  if($(o+8)!="true") fb[k]++
  if($14=="true" && (off($15,$2)>1e-5 || off($16/$3,1)>1e-5)) ua[k]++
  if($(o+14)=="true" && (off($(o+15),$2)>1e-5 || off($(o+16)/$3,1)>1e-5)) ub[k]++
  if($14!="true") va[k]++
  if($(o+14)!="true") vb[k]++
  ca[k]+=$13; cb[k]+=$(o+13); if($(o+13)>mb[k])mb[k]=$(o+13)
  da[k]+=$19; db[k]+=$(o+19); if($(o+19)>md[k])md[k]=$(o+19)
  if($8=="true"&&$(o+8)=="true"&&off($9,$(o+9))>1e-6) moved[k]++
  if($14=="true"&&$(o+14)=="true"&&(off($15,$(o+15))>1e-6||off($16/$(o+16),1)>1e-6)) umoved[k]++
}
END{ printf "%-20s %5s | %9s %9s %6s %12s %4s | %9s %9s %6s %12s %5s\n","feed","n","PHmiss","PHfail","moved","calls","max","UVmiss","UVfail","moved","calls","max"
 for(i=1;i<=n;i++){k=order[i]; printf "%-20s %5d | %4d->%-3d %4d->%-3d %6d %5.1f->%-5.1f %4d | %4d->%-3d %4d->%-3d %6d %5.1f->%-6.1f %5d\n",k,st[k],pa[k],pb[k],fa[k],fb[k],moved[k],ca[k]/st[k],cb[k]/st[k],mb[k],ua[k],ub[k],va[k],vb[k],umoved[k],da[k]/st[k],db[k]/st[k],md[k]}}'
echo "== gap: per feed: fails, balance answers, calls"
paste "$a-gap.tsv" "$b-gap.tsv" | awk -F'\t' '
function off(x,y){d=x-y;if(d<0)d=-d;return d}
NR>1 { k=$1; if(!(k in seen)){order[++n]=k;seen[k]=1}
  o=13; st[k]++
  if($6!="true")fa[k]++; if($(o+6)!="true")fb[k]++
  if($6!="true"&&$(o+6)=="true")won[k]++; if($6=="true"&&$(o+6)!="true")lost[k]++
  if($11=="true")ba[k]++; if($(o+11)=="true")bb[k]++
  ca[k]+=$12; cb[k]+=$(o+12); if($(o+12)>m[k])m[k]=$(o+12)
  if($6=="true"&&$(o+6)=="true"){x=off($7,$(o+7)); if(x>1e-6)mv[k]++; if(x>big[k])big[k]=x; y=off($8,$(o+8)); if(y>hc[k])hc[k]=y}
}
END{ printf "%-20s %4s | %9s %4s %4s | %9s | %12s %4s | %5s %10s %9s\n","feed","n","fail","won","lost","balance","calls","max","moved","maxdT","maxdHcV"
 for(i=1;i<=n;i++){k=order[i]; printf "%-20s %4d | %4d->%-3d %4d %4d | %4d->%-3d | %5.1f->%-5.1f %4d | %5d %10.4g %9.4g\n",k,st[k],fa[k],fb[k],won[k],lost[k],ba[k],bb[k],ca[k]/st[k],cb[k]/st[k],m[k],mv[k],big[k],hc[k]}}'
