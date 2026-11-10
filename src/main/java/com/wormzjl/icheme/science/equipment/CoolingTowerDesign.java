package com.wormzjl.icheme.science.equipment;
import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.fluid.network.CoolingTower;

import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;
import com.wormzjl.icheme.science.equipment.EquipmentShape.*;
import java.util.List;

/** Parametric repeated fan cells and a hollow hyperbolic chimney. No air is a solid volume. */
final class CoolingTowerDesign extends UnitDesign {
    private final boolean natural;
    CoolingTowerDesign(boolean natural) { this.natural=natural; }
    @Override public double minSize(){return natural?40:8;}
    @Override public double maxSize(){return natural?80:14;}
    @Override public double sizeStep(){return natural?10:2;}
    @Override public int minLength(double d){return natural?CoolingTower.naturalHeight(d):1;}
    @Override public int maxLength(double d){return natural?CoolingTower.naturalHeight(d):3;}
    @Override public int defaultLength(double d){return natural?CoolingTower.naturalHeight(d):2;}
    @Override public int lengthStep(){return natural?10:1;}
    @Override public int minSupport(double d,int l){return 0;}
    @Override public int maxSupport(){return 0;}
    @Override public Orientation orientation(){return Orientation.VERTICAL;}
    @Override public boolean moves(){return !natural;}
    @Override public String sizeRow(){return natural?"tower_diameter":"tower_cell_width";}
    @Override public String lengthRow(){return natural?"tower_height":"tower_fill_depth";}
    @Override public String supportRow(){return null;}
    @Override void check(double d,int l,int s,ProcessUnit.Bank bank){
        if(natural)CoolingTower.natural(d,l);
        else CoolingTower.induced(d,l,1,1);
    }
    @Override public Line held(EquipmentShape s){return new Line("tower_cells",s.coolingTower().cells());}
    @Override public List<Line> datasheet(EquipmentShape s){
        var t=s.coolingTower();
        return List.of(new Line("tower_cells",t.cells()),new Line("tower_fill_area",number(t.area())),
                new Line("tower_basin",number(t.basinVolume())),new Line("tower_reference",number(t.referenceWater())));
    }
    @Override void build(ProcessUnit u){
        var t=u.shape.coolingTower();double d=t.width();
        if(natural)natural(u,d,t.height());else bank(u,t,u.shape.towerBank());
    }
    private static final int STACK_SIDES=16;
    private static void bank(ProcessUnit u,CoolingTower t,TowerBank bank){
        double d=t.width(),w=d*bank.columns(),l=d*bank.rows(),rim=CoolingTower.BASIN_HEIGHT,inlet=t.shellBaseHeight(),deck=t.deckHeight()+Access.DECK;
        // A closed shell of whole panels with their relief in the sprites: a shared metal basin, then the casing on its rim.
        // Nothing is drawn inside, and the walls collide as walls.
        u.solids.add(ProcessUnit.Solid.box(new double[]{0,0,0},new double[]{w,.2,l}));
        double[][][] faces={{{0,0,0},{1,0,0},{0,0,-1}},{{w,0,l},{-1,0,0},{0,0,1}},{{0,0,l},{0,0,-1},{-1,0,0}},{{w,0,0},{0,0,1},{1,0,0}}};
        for(var face:faces){
            double[] start=face[0],along=face[1],out=face[2];double length=along[0]!=0?w:l;
            boolean air=along[0]!=0?bank.columns()>=bank.rows():bank.rows()>=bank.columns();
            double[] from=at(start,along,0,0,out,0),to=at(start,along,length,0,out,-.1);
            u.solids.add(ProcessUnit.Solid.box(min(from,to,rim),max(from,to,deck)));
            double[] lip=at(start,along,-RIM,0,out,RIM),end=at(start,along,length+RIM,0,out,0);
            u.solids.add(ProcessUnit.Solid.box(min(lip,end,0),max(lip,end,rim)));
            panels(u,lip,along,out,length+2*RIM,0,rim,rim,Material.TOWER_PANEL);
            u.mesh.flat(List.of(at(start,along,-RIM,rim,out,RIM),at(start,along,length+RIM,rim,out,RIM),at(start,along,length,rim,out,0),at(start,along,0,rim,out,0)),new double[]{0,1,0},Material.TOWER_PANEL);
            panels(u,start,along,out,length,rim,inlet,2,air?Material.TOWER_LOUVER:Material.TOWER_PANEL);
            panels(u,start,along,out,length,inlet,deck,4,Material.TOWER_PANEL);
        }
        u.fluidPoint=new double[]{.1,.1,.1};
        // The bank's FE port (W5, reading A58): the basin rim's back corner at grade, away from the hot-water inlet on the front,
        // the ladder on the end wall and the cold-water outlet mid-back; one store for every cell's fan (reading A71).
        u.terminal=new double[]{-.1,.25,l+.1};
        double r=t.fanRadius(),flare=CoolingTower.BELL*r,mouth=1.06*r,top=t.height(),bell=deck+.25*(top-deck),floor=t.distributorHeight()+.4,arm=bell-.5;
        for(int x=0;x<bank.columns();x++)for(int z=0;z<bank.rows();z++){
            double a=x*d,b=z*d,cx=a+d/2,cz=b+d/2;
            var frame=Frame.of(new double[]{cx,0,cz},1,1);
            // A square deck round the bell mouth and a flared stack. Through the fan: its drive on a cross beam, and the spray and drift eliminators baked on one floor.
            for(int j=0;j<STACK_SIDES;j++){
                double first=2*Math.PI*j/STACK_SIDES,last=2*Math.PI*(j+1)/STACK_SIDES;
                u.mesh.flat(List.of(frame.at(first,flare,deck),frame.at(first,corner(first,d),deck),frame.at(last,corner(last,d),deck),frame.at(last,flare,deck)),new double[]{0,1,0},Material.TOWER_DECK);
            }
            u.solids.add(ProcessUnit.Solid.ring(cx,cz,deck-Access.DECK,deck,flare,d/2*Math.sqrt(2),0,0)
                    .clip(new double[]{a,deck-Access.DECK,b},new double[]{a+d,deck,b+d}));
            u.mesh.frustum(frame,flare,r,deck,bell,STACK_SIDES,Material.TOWER_STACK);
            u.mesh.frustum(frame,r,mouth,bell,top,STACK_SIDES,Material.TOWER_STACK);
            u.mesh.bore(frame,r,floor,top,STACK_SIDES,Material.TOWER_STACK);
            u.mesh.annulus(frame,r,mouth,top,STACK_SIDES,1,Material.TOWER_STACK);
            u.mesh.annulus(frame,0,r,floor,STACK_SIDES,1,Material.TOWER_DRIFT);
            u.box(new double[]{cx-r,arm-.25,cz-.12},new double[]{cx+r,arm,cz+.12},56,Material.SUPPORT,false);
            u.box(new double[]{cx-.12,arm-.25,cz-r},new double[]{cx+.12,arm,cz+r},11,Material.SUPPORT,false);
            u.mesh.cylinder(frame,.2*r+.1,arm,bell,6,Material.MOTOR);
            u.towerFan(frame,r,bell+.05,ProcessUnit.FAN_SPEED);
            u.solids.add(ProcessUnit.Solid.hollowCone(cx,cz,deck,bell,flare,r+.1,.1));
            u.solids.add(ProcessUnit.Solid.ring(cx,cz,bell,top,r,mouth,0,2*Math.PI));
            // The fan's floor and rotor collide as one disc: nobody walks through the blades.
            u.solids.add(ProcessUnit.Solid.rod(new double[]{cx,0,cz},1,bell-.2,bell+.45,r,0));
            // The exhaust leaves the fan stack's mouth (batch 2026-11-10-custom-shader-system, W4): one mark per cell, row by row.
            u.mouth("fan_exhaust_"+(x*bank.rows()+z),cx,cz,mouth,top);
        }
        nozzle(u,Role.HOT_WATER_INLET,w/2,t.distributorHeight(),0,2,-1,.05);
        basinOutlet(u,w/2,l);
        // One ladder up the end wall to the deck's corner, where the stack leaves room to step off; a rest hangs off the wall.
        double level=t.deckHeight(),station=.85;
        double[] x={1,0,0},z={0,0,1},back={-1,0,0},up={0,0,-1};
        u.climb(new Access.Line(new double[]{w,0,0},z,x,2*station),0,deck,station,station,0);
        u.rails(new Access.Line(new double[]{w,0,0},z,x,l),level,0,false,false,station-Access.WALK_THROUGH,station+Access.WALK_THROUGH);
        u.railing(new Access.Line(new double[]{0,0,0},x,up,w),level,0);
        u.railing(new Access.Line(new double[]{w,0,l},back,z,w),level,0);
        u.railing(new Access.Line(new double[]{0,0,l},up,back,l),level,0);
    }
    /** The basin's rim stands this far outside the casing, m. */
    private static final double RIM=.2;
    private static double[] at(double[] start,double[] along,double s,double y,double[] out,double off){
        return new double[]{start[0]+along[0]*s+out[0]*off,y,start[2]+along[2]*s+out[2]*off};
    }
    private static double[] min(double[] p,double[] q,double y){return new double[]{Math.min(p[0],q[0]),y,Math.min(p[2],q[2])};}
    private static double[] max(double[] p,double[] q,double y){return new double[]{Math.max(p[0],q[0]),y,Math.max(p[2],q[2])};}
    /** Where the square cell's edge lies at frame angle {@code a}. */
    private static double corner(double a,double d){return d/2/Math.max(Math.abs(DeterministicMath.cos(a)),Math.abs(DeterministicMath.sin(a)));}
    /** A wall facing {@code out} from {@code y0} to {@code y1}, cut into panels of about 4 m by {@code high}, each one sprite. */
    private static void panels(ProcessUnit u,double[] start,double[] along,double[] out,double length,double y0,double y1,double high,Material material){
        int across=Math.max(1,(int)Math.rint(length/4)),rows=Math.max(1,(int)Math.rint((y1-y0)/high));
        for(int i=0;i<across;i++)for(int j=0;j<rows;j++){
            double s0=length*i/across,s1=length*(i+1)/across,h0=y0+(y1-y0)*j/rows,h1=y0+(y1-y0)*(j+1)/rows;
            u.mesh.flat(List.of(at(start,along,s0,h0,out,0),at(start,along,s1,h0,out,0),at(start,along,s1,h1,out,0),at(start,along,s0,h1,out,0)),out,material);
        }
    }
    /** The chimney, basin and water triangulation share one polygon and angular reference. */
    static int naturalFacets(double diameter){return Math.max(48,(int)Math.ceil(Math.PI*diameter/4));}
    static Frame naturalFrame(double diameter){double axis=diameter/2+.5;return Frame.of(new double[]{axis,0,axis},1,1);}
    private static void natural(ProcessUnit u,double d,double height){
        // Four-metre chords and eight-metre bands keep the shell smooth without sub-block GPU facets.
        double r=d/2,axis=r+.5,base=u.shape.coolingTower().shellBaseHeight();
        int facets=u.mesh.detail().sides(naturalFacets(d));
        var frame=naturalFrame(d);
        u.mesh.annulus(frame,0,r,.2,facets,1,Material.TOWER_FLOOR);
        u.solids.add(ProcessUnit.Solid.rod(new double[]{axis,0,axis},1,0,.2,r,0));
        u.mesh.ring(frame,r,r+.2,.2,CoolingTower.BASIN_HEIGHT,facets,3,Material.CONCRETE);
        u.solids.add(ProcessUnit.Solid.ring(axis,axis,.2,CoolingTower.BASIN_HEIGHT,r,r+.2,0,2*Math.PI));
        // Resize every chimney radius uniformly above the supports; height, throat elevation and curvature stay unchanged.
        double throat=.55*r,shellScale=.9,zthroat=.75*height-base,b=zthroat/Math.sqrt(r*r/(throat*throat)-1);
        for(double y=base;y<height;y+=8){
            double hi=Math.min(height,y+8),ra=shellRadius(throat,shellScale,zthroat,b,base,y),rb=shellRadius(throat,shellScale,zthroat,b,base,hi);
            u.mesh.frustum(frame,ra,rb,y,hi,facets,Material.TOWER_CONCRETE);
            // Reverse inner face, interpolated with the same radial samples.
            for(int j=0;j<facets;j++){
                double a=2*Math.PI*j/facets,c=2*Math.PI*(j+1)/facets;
                double[] p={axis+(ra-.25)*DeterministicMath.cos(a),y,axis+(ra-.25)*DeterministicMath.sin(a)};
                double[] q={axis+(rb-.25)*DeterministicMath.cos(a),hi,axis+(rb-.25)*DeterministicMath.sin(a)};
                double[] s={axis+(rb-.25)*DeterministicMath.cos(c),hi,axis+(rb-.25)*DeterministicMath.sin(c)};
                double[] v={axis+(ra-.25)*DeterministicMath.cos(c),y,axis+(ra-.25)*DeterministicMath.sin(c)};
                u.mesh.flat(List.of(p,q,s,v),new double[]{-DeterministicMath.cos(a),0,-DeterministicMath.sin(a)},Material.TOWER_CONCRETE);
            }
            u.solids.add(ProcessUnit.Solid.hollowCone(axis,axis,y,hi,ra,rb,.25));
        }
        // The wall is thin, as a real shell's is: a stiffening ring closes its top and stands proud of it on both sides.
        double lip=shellRadius(throat,shellScale,zthroat,b,base,height),edge=d/100;
        u.mesh.ring(frame,lip-.25-edge/2,lip+edge/2,height-2*edge,height,facets,3,Material.CONCRETE);
        u.solids.add(ProcessUnit.Solid.ring(axis,axis,height-2*edge,height,lip-.25-edge/2,lip+edge/2,0,2*Math.PI));
        // The exhaust leaves through the top ring (batch 2026-11-10-custom-shader-system, W4): its mark, the ring's opening.
        u.mouth("throat",axis,axis,lip-.25-edge/2,height);
        // Keep the V-pairs: each basin-floor foot leans inward to two tops on the resized chimney's ring.
        double footRadius=r-.5,strut=d/100,supportRadius=shellScale*r-strut/2;int bays=32;
        for(int i=0;i<bays;i++){
            double a=2*Math.PI*i/bays;
            double[] bottom={axis+footRadius*DeterministicMath.cos(a),0,axis+footRadius*DeterministicMath.sin(a)};
            for(int sign:new int[]{-1,1}){
                double top=a+sign*Math.PI/bays;
                double[] end={axis+supportRadius*DeterministicMath.cos(top),base,axis+supportRadius*DeterministicMath.sin(top)};
                u.bar(bottom,end,strut,new double[]{0,1,0},Mesh.NO_ENDS,Material.CONCRETE,true);
            }
        }
        u.mesh.ring(frame,supportRadius-strut/2,supportRadius+strut/2,base-strut,base,facets,3,Material.CONCRETE);
        u.solids.add(ProcessUnit.Solid.ring(axis,axis,base-strut,base,supportRadius-strut/2,supportRadius+strut/2,0,2*Math.PI));
        // A steel grillage and nine internal posts carry the fill, leaving the air inlet open between them.
        double beam=d/160;
        for(int direction=0;direction<2;direction++)for(int line=-1;line<=1;line++){
            double offset=line*r/2,reach=Math.sqrt(supportRadius*supportRadius-offset*offset),y=base-beam/2;
            double[] a={axis-reach,y,axis+offset},end={axis+reach,y,axis+offset};
            if(direction==1){a=new double[]{axis+offset,y,axis-reach};end=new double[]{axis+offset,y,axis+reach};}
            u.bar(a,end,beam,new double[]{0,1,0},Mesh.NO_ENDS,Material.STEELWORK,true);
        }
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++){
            double px=axis+x*r/2,pz=axis+z*r/2;
            u.bar(new double[]{px,.2,pz},new double[]{px,base-beam,pz},beam,new double[]{0,1,0},Mesh.NO_ENDS,Material.STEELWORK,true);
        }
        // The fill shows only its underside from the air inlet and its top down the throat, each a deck to the shell's inner wall.
        double under=shellRadius(throat,shellScale,zthroat,b,base,base)-.25,over=shellRadius(throat,shellScale,zthroat,b,base,base+4.2)-.25;
        u.mesh.annulus(frame,0,under,base,facets,-1,Material.TOWER_FILL);
        u.solids.add(ProcessUnit.Solid.rod(new double[]{axis,0,axis},1,base,base+.2,under,0));
        u.mesh.annulus(frame,0,over,base+4.2,facets,1,Material.TOWER_FILL);
        u.solids.add(ProcessUnit.Solid.rod(new double[]{axis,0,axis},1,base+4,base+4.2,over,0));
        u.fluidPoint=new double[]{axis,.1,axis};
        // Attach inside the resized shell wall at the distributor, preserving the external nozzle position.
        double lower=shellRadius(throat,shellScale,zthroat,b,base,base),upper=shellRadius(throat,shellScale,zthroat,b,base,base+4);
        double inletY=Math.floor(base+3.5)+.5,inletRadius=lower+(upper-lower)*(inletY-base)/4;
        nozzle(u,Role.HOT_WATER_INLET,axis,base+3.5,axis-r,2,-1,r-inletRadius+.1);
        basinOutlet(u,axis,axis+r);
    }
    private static double shellRadius(double throat,double scale,double zthroat,double b,double base,double y){
        return scale*throat*Math.sqrt(1+DeterministicMath.pow((y-base-zthroat)/b,2));
    }
    /** Both tower kinds draw and register their only outlet in the bottom basin wall. */
    private static void basinOutlet(ProcessUnit u,double x,double z){
        nozzle(u,Role.COLD_WATER_OUTLET,x,.5,z,2,1,-.1);
    }
    /** The shared pipe-sized neck starts inside the wall; depth is measured inward from the external flange reference. */
    private static void nozzle(ProcessUnit u,Role role,double x,double y,double z,int axis,int sign,double depth){
        double[] base={x,y,z};for(int k=0;k<3;k++)if(k!=axis)base[k]=Math.floor(base[k])+.5;
        double face=sign<0?Math.floor(base[axis]-1.8):Math.ceil(base[axis]+1.8);
        base[axis]-=sign*depth;
        u.nozzles.add(new Nozzle(role,base,axis,sign,sign*(face-base[axis]),.4,0));
    }
}
