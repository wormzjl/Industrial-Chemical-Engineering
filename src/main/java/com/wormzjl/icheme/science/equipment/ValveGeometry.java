package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.ArrayList;
import java.util.List;
import static com.wormzjl.icheme.science.equipment.Mesh.*;

/**
 * The pipe valves' castings, in the block's unit cell: the check valve (compact flanged casting with one word-free axial
 * band of flow arrows), the shutoff valve (full-port ball-valve silhouette: cast body, split joint, bonnet and quarter-turn
 * safety-yellow lever) and the pressure-control valve (flanged globe casting, bolted bonnet, exposed stem and yoke and a
 * full-height diaphragm actuator). An accessory casting sits on its host pipe with an open bore; a placed valve block
 * draws its own flanged arms instead.
 */
public final class ValveGeometry {
    private static final double[] CENTRE={.5,.5,.5};
    private ValveGeometry(){}

    /** The check valve; its arrows paint the casting's axial facets. */
    public static List<Polygon> check(LineGeometry.Key key,boolean accessory) { return check(key,accessory,RenderDetail.NORMAL); }
    public static List<Polygon> check(LineGeometry.Key key,boolean accessory,RenderDetail detail) {
        var mesh=new Mesh(detail);double od=PipeSizes.outsideOf(key.outside());
        if(accessory)od=Math.max(.40,od);
        int axis=key.facing()&~1,sides=Math.max(16,PipeSizes.sides(od));
        double radius=Math.min(.485,Math.max(.065,.64*od)),half=Math.min(.27,Math.max(.085,.48*od));
        double[] run=LineGeometry.step(axis|1),up=axis==0?new double[]{0,0,-1}:new double[]{0,1,0};
        var body=new Frame(CENTRE,run,up);
        mesh.prism(body,radius,-.72*half,.72*half,sides,Material.FLANGE);
        mesh.frustum(body,.82*radius,radius,-half,-.72*half,sides,Material.FLANGE);
        mesh.frustum(body,radius,.82*radius,.72*half,half,sides,Material.FLANGE);
        double aperture=accessory?Math.min(.43*od,.75*radius):0;
        for(int sign:new int[]{-1,1})mesh.annulus(body,aperture,.82*radius,sign*half,sides,sign,Material.FLANGE);
        if(aperture>0)mesh.bore(body,aperture,-half,half,sides,Material.PIPE);
        mesh.hoop(body,radius,radius+.001,-.62*half,-.58*half,sides,Material.OPENING);
        double bolt=Math.min(.025,Math.max(.006,.05*od));
        for(int sign:new int[]{-1,1})for(int i=0;i<4;i++)
            mesh.nut(new Frame(body.at(Math.PI/4+i*Math.PI/2,.90*radius,sign*half),new double[]{sign*run[0],sign*run[1],sign*run[2]},up),bolt,0,bolt,6,Material.BOLT);
        if(!accessory)arms(mesh,key,half,od,sides);
        var out=new ArrayList<>(mesh.cell(0,0,0));
        boolean positive=(key.facing()&1)!=0;
        out.addAll(PortMarkings.fitting(out,key.facing(),(positive?-.45:-.62)*half,(positive?.62:.45)*half));
        return List.copyOf(out);
    }

    /** The shutoff valve. Pure geometry; the open flag changes only the lever, never pipe diameter or collision. */
    public static List<Polygon> shutoff(LineGeometry.Key key,boolean open,boolean accessory) { return shutoff(key,open,accessory,RenderDetail.NORMAL); }
    public static List<Polygon> shutoff(LineGeometry.Key key,boolean open,boolean accessory,RenderDetail detail) {
        var mesh=new Mesh(detail);double od=PipeSizes.outsideOf(key.outside());
        int axis=key.facing()&~1,sides=Math.max(16,PipeSizes.sides(od));
        double radius=Math.min(.495,Math.max(.065,.64*od)),half=Math.min(.31,Math.max(.085,.55*od));
        double[] run=LineGeometry.step(axis|1),stem=axis==0?new double[]{0,0,-1}:new double[]{0,1,0};
        var body=new Frame(CENTRE,run,stem);
        mesh.prism(body,radius,-.78*half,.78*half,sides,Material.FLANGE);
        mesh.frustum(body,.88*radius,radius,-half,-.78*half,sides,Material.FLANGE);
        mesh.frustum(body,radius,.88*radius,.78*half,half,sides,Material.FLANGE);
        double aperture=accessory?Math.min(.46*od,.82*radius):0;
        mesh.annulus(body,aperture,.88*radius,-half,sides,-1,Material.FLANGE);
        mesh.annulus(body,aperture,.88*radius,half,sides,1,Material.FLANGE);
        if(aperture>0)mesh.bore(body,aperture,-half,half,sides,Material.PIPE);
        mesh.hoop(body,radius,radius+.002,-.015*half,.015*half,sides,Material.OPENING);
        double bolt=Math.min(.03,Math.max(.008,.065*od));
        for(int sign:new int[]{-1,1})for(int i=0;i<4;i++) {
            var cap=new Frame(body.at(Math.PI/4+i*Math.PI/2,.90*radius,sign*half),new double[]{sign*run[0],sign*run[1],sign*run[2]},stem);
            mesh.nut(cap,bolt,0,1.2*bolt,6,Material.BOLT);
        }
        var bonnet=new Frame(CENTRE,stem,run);double neck=Math.max(.016,.12*od),rise=Math.max(.055,.17*od);
        mesh.cylinder(bonnet,1.65*neck,.80*radius,radius+.30*rise,12,Material.FLANGE);
        mesh.cylinder(bonnet,neck,radius,radius+rise,12,Material.BOLT);
        mesh.cylinder(bonnet,1.35*neck,radius+.50*rise,radius+.66*rise,6,Material.BOLT);
        double length=Math.min(.95,Math.max(.36,1.10*od)),width=Math.max(.065,Math.min(.19,.24*od)),thickness=Math.max(.035,.085*od);
        var pivot=bonnet.along(radius+rise);double[] lever=open?run:cross(stem,run);
        bar(mesh,pivot,lever,stem,1.35*width,1.15*thickness,-.28*length,.12*length,BOTH_ENDS,Material.FLANGE);
        // Buried shaft/grip ends have no face: only the outer cap closes the tip, avoiding coplanar paint/metal.
        bar(mesh,pivot,lever,stem,width,thickness,0,.54*length,START,Material.FLANGE);
        var rib=pivot.clone();for(int i=0;i<3;i++)rib[i]-=.65*thickness*stem[i];
        bar(mesh,rib,lever,stem,.48*width,.65*thickness,-.10*length,.65*length,BOTH_ENDS,Material.STEELWORK);
        bar(mesh,pivot,lever,stem,1.28*width,1.50*thickness,.48*length,.96*length,START,Material.GUARD);
        bar(mesh,pivot,lever,stem,1.30*width,1.55*thickness,.94*length,length,BOTH_ENDS,Material.BOLT);
        mesh.cylinder(new Frame(pivot,stem,run),Math.max(1.35*neck,.55*width),-thickness,1.05*thickness,6,Material.BOLT);
        if(!accessory)arms(mesh,key,half,od,sides);
        return LineGeometry.overhanging(mesh);
    }

    /** The pressure-control valve; its arrows paint the casting's diagonal facets, clear of the bonnet. */
    public static List<Polygon> pressure(LineGeometry.Key key,boolean accessory) { return pressure(key,accessory,RenderDetail.NORMAL); }
    public static List<Polygon> pressure(LineGeometry.Key key,boolean accessory,RenderDetail detail) {
        var mesh=new Mesh(detail);pressureBody(mesh,key,accessory);
        double half=pressureHalf(PipeSizes.outsideOf(key.outside()));
        var arrows=PortMarkings.fitting(mesh.cell(0,0,0),key.facing(),-.42*half,.42*half,Math.PI/4);
        pressureTop(mesh,key,accessory);var out=new ArrayList<>(LineGeometry.overhanging(mesh));out.addAll(arrows);
        return List.copyOf(out);
    }
    /** A placed pressure-control valve block's casting, into the line's mesh. */
    static void pressure(Mesh mesh,LineGeometry.Key key){pressureBody(mesh,key,false);pressureTop(mesh,key,false);}
    private static double pressureRadius(double od){return Math.min(.495,Math.max(.105,.62*od+.06));}
    private static double pressureHalf(double od){return Math.min(.31,Math.max(.105,.20+.12*od));}
    private static double[] stem(int facing){return (facing&~1)==0?new double[]{0,0,-1}:new double[]{0,1,0};}
    private static double positionerSeat(LineGeometry.Key key){double od=PipeSizes.outsideOf(key.outside());return pressureRadius(od)+Math.max(.075,.19*od);}
    /** Cable entry on the positioner's outward side, perpendicular to its process pipe. */
    public static SocketGeometry.Socket socket(LineGeometry.Key key){var up=stem(key.facing());var run=LineGeometry.step(key.facing()|1);var n=cross(run,up);var q=plus(CENTRE,plus(scale(up,positionerSeat(key)+.18),scale(n,.245)));return new SocketGeometry.Socket(q[0],q[1],q[2],EquipmentServices.face(n),false);}

    private static void pressureBody(Mesh mesh,LineGeometry.Key key,boolean accessory) {
        double od=PipeSizes.outsideOf(key.outside()),r=pressureRadius(od),h=pressureHalf(od);int sides=Math.max(16,PipeSizes.sides(2*r));
        var frame=new Frame(CENTRE,LineGeometry.step(key.facing()|1),stem(key.facing()));
        mesh.frustum(frame,.67*r,r,-h,-.50*h,sides,Material.FLANGE);
        mesh.prism(frame,r,-.50*h,.50*h,sides,Material.FLANGE);
        mesh.frustum(frame,r,.67*r,.50*h,h,sides,Material.FLANGE);
        double hole=accessory?Math.min(.44*od,.60*r):0;
        for(int sign:new int[]{-1,1})mesh.annulus(frame,hole,.67*r,sign*h,sides,sign,Material.FLANGE);
        if(hole>0)mesh.bore(frame,hole,-h,h,sides,Material.OPENING);
    }

    private static void pressureTop(Mesh mesh,LineGeometry.Key key,boolean accessory) {
        double od=PipeSizes.outsideOf(key.outside()),r=pressureRadius(od),h=pressureHalf(od);
        int axis=key.facing()&~1;var run=LineGeometry.step(axis|1);var stem=stem(key.facing());var f=new Frame(CENTRE,stem,run);
        double bonnet=Math.max(.075,.19*od),yoke=Math.max(.22,.42*od)+.14,casing=Math.min(.96,Math.max(.34,.30+.75*od));
        double shoulder=.82*r,seat=r+bonnet,top=seat+yoke,rc=casing/2;
        int n=Math.max(16,PipeSizes.sides(casing));
        mesh.frustum(f,.48*r,.30*r,shoulder,seat,12,Material.MACHINE);
        mesh.annulus(f,0,.30*r,seat,12,1,Material.MACHINE);
        mesh.ring(f,.28*r,.54*r,seat-.035,seat+.015,12,3,Material.FLANGE);
        mesh.bolts(f,od,.29*r,.54*r,seat+.015,1,Material.BOLT);
        double leg=Math.max(.016,.045*casing),spread=Math.max(.045,.28*r);var across=f.side();
        for(int sign:new int[]{-1,1}) {
            var a=f.along(seat+.015);var b=f.along(top);
            for(int i=0;i<3;i++){a[i]+=sign*spread*across[i];b[i]+=sign*.72*spread*across[i];}
            mesh.bar(a,b,leg,run,Material.STEELWORK);
        }
        mesh.cylinder(f,Math.max(.007,.020*od),seat,top+.03,10,Material.BOLT);
        mesh.cylinder(f,Math.max(.013,.035*od),seat+.06*yoke,seat+.20*yoke,6,Material.GUARD);
        mesh.ring(f,0,.30*rc,top-.025,top+.025,12,3,Material.FLANGE);
        // Full-sized diaphragm and bolted split rim; no scaling down to the host block's top face.
        double rim=top+.15*casing,edge=rim+.035,cap=edge+.23*casing;
        mesh.frustum(f,.30*rc,rc,top,rim,n,Material.ACTUATOR);
        mesh.annulus(f,0,.30*rc,top,n,-1,Material.ACTUATOR);
        mesh.ring(f,rc,.96*rc+.025,rim,edge,n,3,Material.FLANGE);
        mesh.prism(f,rc,rim,edge,n,Material.ACTUATOR);
        mesh.frustum(f,rc,.34*rc,edge,cap,n,Material.ACTUATOR);
        mesh.annulus(f,0,.34*rc,cap,n,1,Material.ACTUATOR);
        mesh.cylinder(f,.13*rc,cap,cap+.025,8,Material.BOLT);
        for(int i=0;i<8;i++)mesh.nut(new Frame(f.at(i*Math.PI/4,.92*rc,edge),stem,run),Math.max(.007,.018*casing),0,.022,6,Material.BOLT);
        var normal=cross(run,stem);var positioner=new Frame(plus(CENTRE,scale(stem,seat+.18)),normal,run);
        positioner(mesh,positioner,.12,.13,.03,.23,.035,Material.SERVICE_CASE);
        positioner(mesh,positioner,.109,.119,.228,.239,.028,Material.SERVICE_COVER);
        mesh.frustum(positioner,.105,.095,.125,.205,16,Material.SERVICE_CASE);
        if(!accessory)arms(mesh,key,h,od,PipeSizes.sides(od));
    }
    private static void positioner(Mesh mesh,Frame frame,double width,double height,double back,double front,double radius,Material material){var a=new ArrayList<double[]>();var b=new ArrayList<double[]>();var vertical=cross(frame.axis(),frame.ref());for(double[] corner:new double[][]{{width-radius,height-radius,0},{-width+radius,height-radius,Math.PI/2},{-width+radius,-height+radius,Math.PI},{width-radius,-height+radius,3*Math.PI/2}})for(int j=0;j<4;j++){double angle=corner[2]+j*Math.PI/6;var offset=plus(scale(frame.ref(),corner[0]+radius*DeterministicMath.cos(angle)),scale(vertical,corner[1]+radius*DeterministicMath.sin(angle)));a.add(plus(frame.along(back),offset));b.add(plus(frame.along(front),offset));}mesh.flat(a,neg(frame.axis()),material);mesh.flat(b,frame.axis(),material);for(int i=0;i<a.size();i++)mesh.add(List.of(a.get(i),a.get((i+1)%a.size()),b.get((i+1)%a.size()),b.get(i)),frame.along((back+front)/2),FLAT,material,null,null);}

    /** A placed valve block's own flanged arms to both ends of its run, from the casting's end faces at {@code half}. */
    private static void arms(Mesh mesh,LineGeometry.Key key,double half,double od,int sides) {
        int axis=key.facing()&~1;
        for(int face:new int[]{axis,axis^1})LineGeometry.arm(mesh,face,half,od,LineGeometry.fittingEnd(key,face,od),LineGeometry.ref(face),sides,true);
    }

    private static void bar(Mesh mesh,double[] origin,double[] direction,double[] up,double width,double height,double from,double to,int ends,Material material) {
        double[] side=cross(up,direction);var previous=mesh.transform();
        mesh.transform(new Transform(new int[]{(int)side[0],(int)up[0],(int)direction[0],(int)side[1],(int)up[1],(int)direction[1],(int)side[2],(int)up[2],(int)direction[2]},origin));
        int faces=(ALL_FACES&~48)|((ends&START)!=0?16:0)|((ends&END)!=0?32:0);
        mesh.box(new double[]{-width/2,-height/2,from},new double[]{width/2,height/2,to},faces,material);
        mesh.transform(previous);
    }
}
