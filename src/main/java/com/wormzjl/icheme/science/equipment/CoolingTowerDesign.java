package com.wormzjl.icheme.science.equipment;
import com.wormzjl.icheme.science.math.DeterministicMath;

import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;
import com.wormzjl.icheme.science.equipment.EquipmentShape.*;
import java.util.List;

/** Parametric repeated fan cells and a hollow hyperbolic chimney. No chimney air is a solid volume. */
final class CoolingTowerDesign extends UnitDesign {
    private final boolean natural;
    CoolingTowerDesign(boolean natural) { this.natural=natural; }
    @Override public double minSize(){return natural?40:4;}
    @Override public double maxSize(){return natural?80:8;}
    @Override public double sizeStep(){return natural?10:2;}
    @Override public int minLength(double d){return natural?(int)(2*d):2;}
    @Override public int maxLength(double d){return natural?(int)(2*d):6;}
    @Override public int defaultLength(double d){return natural?(int)(2*d):2;}
    @Override public int lengthStep(){return natural?10:1;}
    @Override public int minSupport(double d,int l){return 0;}
    @Override public int maxSupport(){return 0;}
    @Override public Orientation orientation(){return Orientation.VERTICAL;}
    @Override public boolean moves(){return !natural;}
    @Override public String sizeRow(){return natural?"tower_diameter":"tower_cell_width";}
    @Override public String lengthRow(){return natural?"tower_height":"tower_fill_depth";}
    @Override public String supportRow(){return null;}
    @Override void check(double d,int l,int s,ProcessUnit.Bank bank){
        if(natural)com.wormzjl.icheme.science.fluid.network.CoolingTower.natural(d,l);
        else com.wormzjl.icheme.science.fluid.network.CoolingTower.induced(d,l,1,1);
    }
    @Override public Line held(EquipmentShape s){return new Line("tower_cells",s.coolingTower().cells());}
    @Override public List<Line> datasheet(EquipmentShape s){
        var t=s.coolingTower();
        return List.of(new Line("tower_cells",t.cells()),new Line("tower_fill_area",number(t.area())),
                new Line("tower_basin",number(t.basinVolume())),new Line("tower_reference",number(2*t.area())));
    }
    @Override void build(ProcessUnit u){
        var t=u.shape.coolingTower();double d=t.width();
        if(natural)natural(u,d,t.height());else bank(u,d,t.fillDepth(),u.shape.towerBank());
    }
    private static void bank(ProcessUnit u,double d,double fill,TowerBank bank){
        double w=d*bank.columns(),l=d*bank.rows();
        // A shared metal basin at grade, carried by the steel frame; no concrete plinth.
        u.box(new double[]{0,0,0},new double[]{w,.2,l},Material.SUPPORT,true);
        u.box(new double[]{-.2,.2,-.2},new double[]{0,3,l+.2},Material.SUPPORT,true);
        u.box(new double[]{w,.2,-.2},new double[]{w+.2,3,l+.2},Material.SUPPORT,true);
        u.box(new double[]{0,.2,-.2},new double[]{w,3,0},Material.SUPPORT,true);
        u.box(new double[]{0,.2,l},new double[]{w,3,l+.2},Material.SUPPORT,true);
        u.fluidPoint=new double[]{.1,.1,.1};
        for(int x=0;x<bank.columns();x++)for(int z=0;z<bank.rows();z++){
            double a=x*d,b=z*d,top=4+fill;
            for(double xx:new double[]{a+.2,a+d-.2})for(double zz:new double[]{b+.2,b+d-.2})
                u.box(new double[]{xx-.1,0,zz-.1},new double[]{xx+.1,top+3,zz+.1},Material.STEELWORK,true);
            u.box(new double[]{a+.15,4,b+.15},new double[]{a+d-.15,top,b+d-.15},Material.AC_FINS,true);
            // Distributor and eliminator are repeated once per cell, never stretched across a bank.
            u.box(new double[]{a+.1,top+.3,b+.1},new double[]{a+d-.1,top+.45,b+d-.1},Material.PIPE,true);
            u.box(new double[]{a+.1,top+1,b+.1},new double[]{a+d-.1,top+1.2,b+d-.1},Material.AC_FINS,true);
            u.box(new double[]{a,top+.45,b},new double[]{a+.1,top+3,b+d},Material.AC_PLENUM,true);
            u.box(new double[]{a+d-.1,top+.45,b},new double[]{a+d,top+3,b+d},Material.AC_PLENUM,true);
            u.box(new double[]{a+.1,top+.45,b},new double[]{a+d-.1,top+3,b+.1},Material.AC_PLENUM,true);
            u.box(new double[]{a+.1,top+.45,b+d-.1},new double[]{a+d-.1,top+3,b+d},Material.AC_PLENUM,true);
            // A square fan deck with a circular opening seals the plenum against air bypass.
            for(int j=0;j<32;j++){
                double first=2*Math.PI*j/32,last=2*Math.PI*(j+1)/32;
                double r0=d/2/Math.max(Math.abs(DeterministicMath.cos(first)),Math.abs(DeterministicMath.sin(first)));
                double r1=d/2/Math.max(Math.abs(DeterministicMath.cos(last)),Math.abs(DeterministicMath.sin(last)));
                double[] inner0={a+d/2+.35*d*DeterministicMath.cos(first),top+3,b+d/2+.35*d*DeterministicMath.sin(first)};
                double[] outer0={a+d/2+r0*DeterministicMath.cos(first),top+3,b+d/2+r0*DeterministicMath.sin(first)};
                double[] outer1={a+d/2+r1*DeterministicMath.cos(last),top+3,b+d/2+r1*DeterministicMath.sin(last)};
                double[] inner1={a+d/2+.35*d*DeterministicMath.cos(last),top+3,b+d/2+.35*d*DeterministicMath.sin(last)};
                u.mesh.flat(List.of(inner1,outer1,outer0,inner0),new double[]{0,1,0},Material.AC_PLENUM);
            }
            // Match the square deck, not its circular bound: the corners collide without projecting beyond the cell.
            u.solids.add(ProcessUnit.Solid.ring(a+d/2,b+d/2,top+3,top+3+Access.DECK,.35*d,d/2*Math.sqrt(2),0,0)
                    .clip(new double[]{a,top+3,b},new double[]{a+d,top+3+Access.DECK,b+d}));
            var frame=Frame.of(new double[]{a+d/2,0,b+d/2},1,1);
            u.axialFan(frame,.35*d,top+4,1,32,ProcessUnit.FAN_SPEED);
            // The guarded rotor fills the cowl for collision; neither its blades nor its grille can be walked through.
            u.solids.add(ProcessUnit.Solid.rod(new double[]{a+d/2,0,b+d/2},1,top+3,top+4,.35*d,0));
            u.solids.add(ProcessUnit.Solid.ring(a+d/2,b+d/2,top+3,top+4,.35*d,.35*d+.08,0,2*Math.PI));
        }
        nozzle(u,Role.HOT_WATER_INLET,w/2,5+fill,0,2,-1,.05);
        basinOutlet(u,w/2,l);
        var access=new Access.Line(new double[]{w,0,0},new double[]{0,0,1},new double[]{1,0,0},l);
        double landing=fill+7+Access.DECK,upper=landing>10?2.85:.85;
        u.platform(access,fill+7,Access.MIN_DECK,Access.RAILED|Access.WALL_EDGE,true,true,upper-Access.WALK_THROUGH,upper+Access.WALK_THROUGH);
        var climb=new Access.Line(new double[]{w+Access.MIN_DECK,0,0},access.direction(),access.out(),l);
        u.climb(climb,0,landing,.85,upper,.15);
    }
    private static void natural(ProcessUnit u,double d,double height){
        // About 2 m chords keep radial error below 2 cm while avoiding sub-block shell facets.
        double r=d/2,axis=r+.5;int facets=Math.max(48,(int)Math.ceil(Math.PI*d/2));
        var frame=Frame.of(new double[]{axis,0,axis},1,1);
        u.mesh.cylinder(frame,r,0,.2,facets,Material.CONCRETE);
        u.solids.add(ProcessUnit.Solid.rod(new double[]{axis,0,axis},1,0,.2,r,0));
        u.mesh.ring(frame,r,r+.2,.2,3,facets,3,Material.CONCRETE);
        u.solids.add(ProcessUnit.Solid.ring(axis,axis,.2,3,r,r+.2,0,2*Math.PI));
        // Ring segments approximate one hyperbola; thin collision shells leave the chimney hollow.
        double throat=.55*r,zthroat=.75*(height-10),b=zthroat/Math.sqrt(r*r/(throat*throat)-1);
        for(double y=10;y<height;y+=4){
            double hi=Math.min(height,y+4),ra=shellRadius(throat,zthroat,b,y),rb=shellRadius(throat,zthroat,b,hi);
            u.mesh.frustum(frame,ra,rb,y,hi,facets,Material.CONCRETE);
            // Reverse inner face, interpolated with the same radial samples.
            for(int j=0;j<facets;j++){
                double a=2*Math.PI*j/facets,c=2*Math.PI*(j+1)/facets;
                double[] p={axis+(ra-.25)*DeterministicMath.cos(a),y,axis+(ra-.25)*DeterministicMath.sin(a)};
                double[] q={axis+(rb-.25)*DeterministicMath.cos(a),hi,axis+(rb-.25)*DeterministicMath.sin(a)};
                double[] s={axis+(rb-.25)*DeterministicMath.cos(c),hi,axis+(rb-.25)*DeterministicMath.sin(c)};
                double[] v={axis+(ra-.25)*DeterministicMath.cos(c),y,axis+(ra-.25)*DeterministicMath.sin(c)};
                u.mesh.flat(List.of(p,q,s,v),new double[]{-DeterministicMath.cos(a),0,-DeterministicMath.sin(a)},Material.CONCRETE);
            }
            u.solids.add(ProcessUnit.Solid.hollowCone(axis,axis,y,hi,ra,rb,.25));
        }
        for(int i=0;i<32;i++){
            double a=2*Math.PI*i/32,x=axis+(r-.5)*DeterministicMath.cos(a),z=axis+(r-.5)*DeterministicMath.sin(a);
            u.box(new double[]{x-.2,3,z-.2},new double[]{x+.2,10,z+.2},Material.CONCRETE,true);
        }
        u.mesh.cylinder(frame,r-.5,10,12,facets,Material.AC_FINS);
        u.solids.add(ProcessUnit.Solid.rod(new double[]{axis,0,axis},1,10,12,r-.5,0));
        u.mesh.cylinder(frame,r-.5,13,13.15,facets,Material.PIPE);
        u.mesh.cylinder(frame,r-.5,14,14.2,facets,Material.AC_FINS);
        u.fluidPoint=new double[]{axis,.1,axis};
        // Attach inside the shell wall at the distributor, using the same straight band as the mesh and collision.
        double lower=shellRadius(throat,zthroat,b,10),upper=shellRadius(throat,zthroat,b,14);
        double inletRadius=lower+(upper-lower)*3.5/4;
        nozzle(u,Role.HOT_WATER_INLET,axis,13.5,axis-r,2,-1,r-inletRadius+.1);
        basinOutlet(u,axis,axis+r);
    }
    private static double shellRadius(double throat,double zthroat,double b,double y){
        return throat*Math.sqrt(1+DeterministicMath.pow((y-10-zthroat)/b,2));
    }
    /** Both tower kinds draw and register their only outlet in the bottom basin wall. */
    private static void basinOutlet(ProcessUnit u,double x,double z){
        nozzle(u,Role.COLD_WATER_OUTLET,x,.5,z,2,1,-.1);
    }
    /** The weld ends within the wall; depth is measured inward from the reference used for the external flange. */
    private static void nozzle(ProcessUnit u,Role role,double x,double y,double z,int axis,int sign,double depth){
        double[] base={x,y,z};for(int k=0;k<3;k++)if(k!=axis)base[k]=Math.floor(base[k])+.5;
        var root=base.clone();var stub=Frame.of(root,axis,sign);
        u.mesh.cylinder(stub,.2,-depth,.55,12,Material.PIPE);
        double low=Math.min(root[axis]-sign*depth,root[axis]+sign*.55),high=Math.max(root[axis]-sign*depth,root[axis]+sign*.55);
        u.solids.add(ProcessUnit.Solid.rod(root,axis,low,high,.2,0));
        double face=sign<0?Math.floor(base[axis]-1.8):Math.ceil(base[axis]+1.8);
        // Root outside the hollow basin wall; the pipe-sized stub welds it back to the distributor or pan.
        base[axis]+=sign*.55;
        u.nozzles.add(new Nozzle(role,base,axis,sign,sign*(face-base[axis]),.4,0));
    }
}
