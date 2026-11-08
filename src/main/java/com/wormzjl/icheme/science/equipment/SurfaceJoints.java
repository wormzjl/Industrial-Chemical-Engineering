package com.wormzjl.icheme.science.equipment;

import java.util.*;
import static com.wormzjl.icheme.science.equipment.Mesh.*;

/** A small facet graph for native connections that leave a section through an inclined structural member. */
final class SurfaceJoints {
    record Edge(double[] a,double[] b,Material material) {}
    private record Face(double[] a,double[] b,double[] c,Material material,int[] nodes) {}
    private record Visit(int node,double cost) {}
    private final List<double[]> points=new ArrayList<>();private final Map<String,Integer> indices=new HashMap<>();private final List<Map<Integer,Link>> links=new ArrayList<>();private final List<Face> faces=new ArrayList<>();
    private record Link(double length,Material material) {}
    private int node(double[] p){String k=Math.round(p[0]*1e7)+":"+Math.round(p[1]*1e7)+":"+Math.round(p[2]*1e7);var found=indices.get(k);if(found!=null)return found;int id=points.size();points.add(p);indices.put(k,id);links.add(new HashMap<>());return id;}
    private void link(int a,int b,Material material){if(a==b)return;double d=SurfaceSection.distance(points.get(a),points.get(b));links.get(a).put(b,new Link(d,material));links.get(b).put(a,new Link(d,material));}
    SurfaceJoints(List<Polygon> polys){
        for(var poly:polys){var p=poly.positions();for(int t=1;t+1<poly.size();t++){var a=Arrays.copyOfRange(p,0,3);var b=Arrays.copyOfRange(p,3*t,3*t+3);var c=Arrays.copyOfRange(p,3*t+3,3*t+6);if(dot(cross(sub(b,a),sub(c,a)),cross(sub(b,a),sub(c,a)))<1e-15)continue;var mid=scale(plus(plus(a,b),c),1./3);int[] ns={node(a),node(b),node(c),node(mid)};faces.add(new Face(a,b,c,poly.material(),ns));for(int i=0;i<4;i++)for(int j=0;j<i;j++)link(ns[i],ns[j],poly.material());}}
        // Corners meeting the middle of a native face are structural joints too. No uniform surface grid is generated.
        int count=points.size();for(int i=0;i<count;i++){var p=points.get(i);for(var face:faces){if(!nearBox(p,face,.023))continue;var q=nearest(p,face);if(SurfaceSection.distance(p,q)>.022)continue;int at=node(q);link(i,at,face.material);for(int n:face.nodes)link(at,n,face.material);}}
        // Native struts can intersect the middle of a curved facet without sharing a vertex.
        // Split those exact edge/face crossings too, rather than loosening the joint-gap tolerance.
        for(var owner:faces)for(int e=0;e<3;e++){
            int a=owner.nodes[e],b=owner.nodes[(e+1)%3];var start=points.get(a);var finish=points.get(b);var delta=sub(finish,start);
            for(var face:faces){if(face==owner)continue;boolean near=true;for(int k=0;k<3;k++)if(Math.max(start[k],finish[k])<Math.min(face.a[k],Math.min(face.b[k],face.c[k]))-1e-8||Math.min(start[k],finish[k])>Math.max(face.a[k],Math.max(face.b[k],face.c[k]))+1e-8){near=false;break;}if(!near)continue;
                var n=cross(sub(face.b,face.a),sub(face.c,face.a));double den=dot(n,delta);if(Math.abs(den)<1e-12)continue;double amount=dot(n,sub(face.a,start))/den;if(amount<1e-7||amount>1-1e-7)continue;var q=plus(start,scale(delta,amount));if(SurfaceSection.distance(q,nearest(q,face))>1e-7)continue;
                int at=node(q);link(a,at,owner.material);link(at,b,owner.material);for(int target:face.nodes)link(at,target,face.material);
            }
        }

    }
    private static boolean nearBox(double[] p,Face f,double e){for(int a=0;a<3;a++)if(p[a]<Math.min(f.a[a],Math.min(f.b[a],f.c[a]))-e||p[a]>Math.max(f.a[a],Math.max(f.b[a],f.c[a]))+e)return false;return true;}
    private int attach(double[] p){double d=Double.POSITIVE_INFINITY;Face chosen=null;double[] near=null;for(var face:faces){var q=nearest(p,face);double x=SurfaceSection.distance(p,q);if(x<d){d=x;chosen=face;near=q;}}if(d>.065)throw new IllegalArgumentException("No native wiring joint at entry: "+d);int id=node(near);for(int n:chosen.nodes)link(id,n,chosen.material);return id;}
    List<Edge> route(double[] a,double[] b){int start=attach(a),end=attach(b);double[] cost=new double[points.size()];Arrays.fill(cost,Double.POSITIVE_INFINITY);cost[start]=0;int[] parent=new int[cost.length];Arrays.fill(parent,-1);var queue=new PriorityQueue<Visit>(Comparator.comparingDouble(Visit::cost));queue.add(new Visit(start,0));while(!queue.isEmpty()){var p=queue.remove();if(p.cost!=cost[p.node])continue;if(p.node==end)break;for(var e:links.get(p.node).entrySet()){double next=p.cost+e.getValue().length;if(next<cost[e.getKey()]){cost[e.getKey()]=next;parent[e.getKey()]=p.node;queue.add(new Visit(e.getKey(),next));}}}if(!Double.isFinite(cost[end])){var other=new HashSet<Integer>();var todo=new ArrayDeque<Integer>();other.add(end);todo.add(end);while(!todo.isEmpty())for(int n:links.get(todo.remove()).keySet())if(other.add(n))todo.add(n);double gap=Double.POSITIVE_INFINITY;double[] qa=null,qb=null;for(int i=0;i<cost.length;i++)if(Double.isFinite(cost[i]))for(int j:other){double d=SurfaceSection.distance(points.get(i),points.get(j));if(d<gap){gap=d;qa=points.get(i);qb=points.get(j);}}throw new IllegalArgumentException("No joined native surface route gap="+gap+" at "+Arrays.toString(qa)+" / "+Arrays.toString(qb));}var out=new ArrayList<Edge>();for(int p=end;p!=start;){int prior=parent[p];out.add(new Edge(points.get(prior),points.get(p),links.get(prior).get(p).material));p=prior;}Collections.reverse(out);return out;}
    private static double[] nearest(double[] p,Face f){var u=sub(f.b,f.a);var v=sub(f.c,f.a);var n=cross(u,v);var q=sub(p,scale(n,dot(sub(p,f.a),n)/dot(n,n)));var w=sub(q,f.a);double aa=dot(u,u),bb=dot(u,v),cc=dot(v,v),wu=dot(w,u),wv=dot(w,v),det=aa*cc-bb*bb,s=(wu*cc-wv*bb)/det,t=(wv*aa-wu*bb)/det;if(s>=0&&t>=0&&s+t<=1)return q;double best=Double.POSITIVE_INFINITY;for(var z:List.of(new double[][]{f.a,f.b},new double[][]{f.b,f.c},new double[][]{f.c,f.a})){var x=plus(z[0],scale(sub(z[1],z[0]),SurfaceSection.amount(p,z[0],z[1])));double d=SurfaceSection.distance(p,x);if(d<best){best=d;q=x;}}return q;}
}
