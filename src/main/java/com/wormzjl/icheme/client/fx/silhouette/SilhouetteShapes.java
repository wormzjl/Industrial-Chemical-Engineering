package com.wormzjl.icheme.client.fx.silhouette;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Mesh;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A structure's far silhouette as boxes (batch 2026-11-10-custom-shader-system, W10, plan section 4.7). Plain Java on the
 * shape alone, run on the silhouette worker, deterministic for a shape and a paint.
 * <ul>
 * <li>The cells are those of {@link ShapeRasterizer#layout} (shell and interior), the same cells Distant Horizons draws as
 * grey voxels. A shell cell takes the {@link Finish} of the parts with the most surface in it; an interior cell, the body
 * paint.</li>
 * <li>{@link Built#near}: cells of one finish merge greedily into boxes (along x, then z, then up), each grown by
 * {@link #GROW} on every side so that it hides DH's voxel of the same cells. More than {@link #STRUCTURE_BOXES}: none, and the
 * structure shows its hull only.</li>
 * <li>{@link Built#far}: at most {@link #HULL_BOXES} boxes, one per band of layers with the same footprint rectangle and
 * finish, the bands with the least added volume merged first, grown by {@link #FAR_GROW}: the hull DH's coarse voxels
 * (2 to 8 blocks a voxel past four vanilla render distances) need.</li>
 * </ul>
 */
public final class SilhouetteShapes {
    /** How far a near box reaches past its cells on every side, m. */
    public static final double GROW=.02;
    /** How far a hull box reaches past its cells on every side, m: half DH's smallest far voxel (2 blocks). */
    public static final double FAR_GROW=1;
    /** The most hull boxes per structure. */
    public static final int HULL_BOXES=16;
    /** The most near boxes per structure; past it the structure shows its hull only. */
    public static final int STRUCTURE_BOXES=8192;

    /** What a cell looks like from far away, and how DH shades it. */
    public enum Finish {
        BODY(false),STRUCTURE(false),STEEL(false),COPPER(false),CONCRETE(true),BRICK(true);
        static final Finish[] ALL=values();
        public final boolean stone;
        Finish(boolean stone){this.stone=stone;}
        /** The colour under {@code paint}, 0xRRGGBB. */
        public int rgb(Paint paint) {
            return switch(this){
                case BODY->paint.body();
                case STRUCTURE->paint.structure();
                case STEEL->0x8A8D8F;
                case COPPER->0xB0703C;
                case CONCRETE->0xA6A29A;
                case BRICK->0x8E4B32;
            };
        }
        /** The finish of a part of {@code material}; null for one that shows nothing far away (glass). */
        static Finish of(Mesh.Material material) {
            if(material.channel()==Paint.BODY)return BODY;
            if(material.channel()==Paint.STRUCTURE)return STRUCTURE;
            return switch(material){
                case GLASS->null;
                case CONCRETE,TOWER_CONCRETE,TOWER_FLOOR->CONCRETE;
                case BRICK->BRICK;
                case COPPER,COPPER_LINE->COPPER;
                default->STEEL;
            };
        }
    }

    /** A structure's boxes: the near set (empty past {@link #STRUCTURE_BOXES}) and the hull. */
    public record Built(List<SilhouetteBox> near,List<SilhouetteBox> far) {
        public Built{near=List.copyOf(near);far=List.copyOf(far);}
        public int boxes(){return near.size()+far.size();}
    }

    /** The cells of a structure by finish, in a grid over their bounds; -1 for an empty cell. */
    record Cells(int x0,int y0,int z0,int nx,int ny,int nz,byte[] finish) {
        int index(int x,int y,int z){return (y*nz+z)*nx+x;}
        int at(int x,int y,int z){return finish[index(x,y,z)];}
    }

    private SilhouetteShapes(){}

    public static Built build(EquipmentShape shape,Paint paint){return build(cells(shape),paint);}

    static Built build(Cells cells,Paint paint) {
        if(cells==null)return new Built(List.of(),List.of());
        var near=near(cells,paint);
        return new Built(near.size()>STRUCTURE_BOXES?List.of():near,hull(cells,paint));
    }

    /** Every layout cell with its finish; null for a shape with none. */
    static Cells cells(EquipmentShape shape) {
        var layout=ShapeRasterizer.layout(shape);
        if(layout.shell().isEmpty()&&layout.interior().isEmpty())return null;
        int x0=Integer.MAX_VALUE,y0=x0,z0=x0,x1=Integer.MIN_VALUE,y1=x1,z1=x1;
        for(var list:List.of(layout.shell(),layout.interior()))for(var c:list){
            x0=Math.min(x0,c.x());y0=Math.min(y0,c.y());z0=Math.min(z0,c.z());x1=Math.max(x1,c.x());y1=Math.max(y1,c.y());z1=Math.max(z1,c.z());
        }
        var cells=new Cells(x0,y0,z0,x1-x0+1,y1-y0+1,z1-z0+1,new byte[(x1-x0+1)*(y1-y0+1)*(z1-z0+1)]);
        Arrays.fill(cells.finish,(byte)-1);
        for(var c:layout.interior())cells.finish[cells.index(c.x()-x0,c.y()-y0,c.z()-z0)]=(byte)Finish.BODY.ordinal();
        for(var c:layout.shell())cells.finish[cells.index(c.x()-x0,c.y()-y0,c.z()-z0)]=(byte)finish(ShapeRasterizer.surface(ShapeRasterizer.key(shape,c.x(),c.y(),c.z()))).ordinal();
        return cells;
    }

    /** The finish with the most surface among {@code polygons}; the body paint for a cell that shows none. */
    static Finish finish(List<Mesh.Polygon> polygons) {
        var area=new double[Finish.ALL.length];
        for(var p:polygons){var f=Finish.of(p.material());if(f!=null)area[f.ordinal()]+=area(p.positions());}
        int best=Finish.BODY.ordinal();
        for(int k=0;k<area.length;k++)if(area[k]>area[best])best=k;
        return Finish.ALL[best];
    }

    /** A planar polygon's area from its flat (x, y, z) positions. */
    static double area(double[] p) {
        double ax=0,ay=0,az=0;int n=p.length/3;
        for(int k=1;k+1<n;k++){
            double ux=p[3*k]-p[0],uy=p[3*k+1]-p[1],uz=p[3*k+2]-p[2],vx=p[3*k+3]-p[0],vy=p[3*k+4]-p[1],vz=p[3*k+5]-p[2];
            ax+=uy*vz-uz*vy;ay+=uz*vx-ux*vz;az+=ux*vy-uy*vx;
        }
        return .5*Math.sqrt(ax*ax+ay*ay+az*az);
    }

    /** Greedy boxes over cells of one finish: along x, then rows along z, then whole rectangles up; each grown by {@link #GROW}. */
    static List<SilhouetteBox> near(Cells c,Paint paint) {
        var out=new ArrayList<SilhouetteBox>();var done=new boolean[c.finish.length];
        for(int y=0;y<c.ny;y++)for(int z=0;z<c.nz;z++)for(int x=0;x<c.nx;x++){
            int f=c.at(x,y,z);if(f<0||done[c.index(x,y,z)])continue;
            int x1=x,z1=z,y1=y;
            while(x1+1<c.nx&&free(c,done,f,x1+1,x1+1,y,z,z))x1++;
            while(z1+1<c.nz&&free(c,done,f,x,x1,y,z1+1,z1+1))z1++;
            while(y1+1<c.ny&&free(c,done,f,x,x1,y1+1,z,z1))y1++;
            for(int yy=y;yy<=y1;yy++)for(int zz=z;zz<=z1;zz++)for(int xx=x;xx<=x1;xx++)done[c.index(xx,yy,zz)]=true;
            var finish=Finish.ALL[f];
            out.add(new SilhouetteBox(c.x0+x-GROW,c.y0+y-GROW,c.z0+z-GROW,c.x0+x1+1+GROW,c.y0+y1+1+GROW,c.z0+z1+1+GROW,finish.rgb(paint),finish.stone));
        }
        return out;
    }
    /** Whether every cell of the row or rectangle [x0, x1] × y × [z0, z1] has finish {@code f} and no box yet. */
    private static boolean free(Cells c,boolean[] done,int f,int x0,int x1,int y,int z0,int z1) {
        for(int z=z0;z<=z1;z++)for(int x=x0;x<=x1;x++){int i=c.index(x,y,z);if(c.finish[i]!=f||done[i])return false;}
        return true;
    }

    /** A band of layers: its footprint rectangle (cells, upper exclusive) and its cell count by finish. */
    private record Band(int y0,int y1,int x0,int z0,int x1,int z1,int[] counts) {
        double volume(){return (double)(y1-y0)*(x1-x0)*(z1-z0);}
        Band join(Band o) {
            var counts=this.counts.clone();for(int k=0;k<counts.length;k++)counts[k]+=o.counts[k];
            return new Band(Math.min(y0,o.y0),Math.max(y1,o.y1),Math.min(x0,o.x0),Math.min(z0,o.z0),Math.max(x1,o.x1),Math.max(z1,o.z1),counts);
        }
        int finish(){int best=0;for(int k=1;k<counts.length;k++)if(counts[k]>counts[best])best=k;return best;}
        boolean sameAs(Band o){return x0==o.x0&&z0==o.z0&&x1==o.x1&&z1==o.z1&&finish()==o.finish();}
    }

    /** At most {@link #HULL_BOXES} boxes over every cell, grown by {@link #FAR_GROW}. */
    static List<SilhouetteBox> hull(Cells c,Paint paint) {
        var bands=new ArrayList<Band>();
        for(int y=0;y<c.ny;y++){
            int x0=Integer.MAX_VALUE,z0=x0,x1=-1,z1=-1;var counts=new int[Finish.ALL.length];
            for(int z=0;z<c.nz;z++)for(int x=0;x<c.nx;x++){int f=c.at(x,y,z);if(f<0)continue;counts[f]++;x0=Math.min(x0,x);z0=Math.min(z0,z);x1=Math.max(x1,x+1);z1=Math.max(z1,z+1);}
            if(x1<0)continue;
            var band=new Band(y,y+1,x0,z0,x1,z1,counts);
            if(!bands.isEmpty()&&bands.getLast().y1==y&&bands.getLast().sameAs(band))bands.set(bands.size()-1,bands.getLast().join(band));
            else bands.add(band);
        }
        while(bands.size()>HULL_BOXES){
            int best=0;double least=Double.MAX_VALUE;
            for(int k=0;k+1<bands.size();k++){
                var a=bands.get(k);var b=bands.get(k+1);double added=a.join(b).volume()-a.volume()-b.volume();
                if(added<least){least=added;best=k;}
            }
            bands.set(best,bands.get(best).join(bands.remove(best+1)));
        }
        var out=new ArrayList<SilhouetteBox>(bands.size());
        for(var b:bands){
            var finish=Finish.ALL[b.finish()];
            out.add(new SilhouetteBox(c.x0+b.x0-FAR_GROW,c.y0+b.y0-FAR_GROW,c.z0+b.z0-FAR_GROW,c.x0+b.x1+FAR_GROW,c.y0+b.y1+FAR_GROW,c.z0+b.z1+FAR_GROW,finish.rgb(paint),finish.stone));
        }
        return out;
    }
}
