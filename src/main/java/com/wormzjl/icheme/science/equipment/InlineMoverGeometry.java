package com.wormzjl.icheme.science.equipment;

import java.util.ArrayList;
import java.util.List;
import static com.wormzjl.icheme.science.equipment.Mesh.*;

/** Upright line-mounted pump with a suspended impeller bowl, and geared compressor (Sundyne LMC). */
public final class InlineMoverGeometry {
    private static final double[] CENTRE={.5,.5,.5},UP={0,1,0};
    private InlineMoverGeometry(){}
    /** Straight, end or isolated sections, with a horizontal run axis. */
    public static boolean supports(int mask,int facing){
        if(!SightGlassGeometry.supports(mask))return false;
        int axis=mask==0?facing:Integer.numberOfTrailingZeros(mask);
        return axis>=2&&axis<6;
    }
    /** Above approximately 0.2 m bore, use block-face joints at the model key's drawn-size precision. */
    public static boolean boundaryJoints(int outside){return outside>PipeSizes.code(PipeSizes.outside(.2));}
    public static List<Polygon> polygons(LineGeometry.Key key,boolean compressor,boolean item){
        if(!supports(key.connections(),key.facing()))throw new IllegalArgumentException("Inline movers require horizontal straight pipe");
        double od=PipeSizes.outsideOf(key.outside()),r=Math.max(compressor?.15:.12,(compressor?.78:.72)*od+.04);
        boolean boundary=boundaryJoints(key.outside());
        double thickness=PipeSizes.flangeThickness(od),connection=boundary?.5:Math.min(.16+.40*od,.5-2*thickness-.015);
        double root=connection-thickness-(boundary?.04:0);
        double axial=Math.min(boundary?root-.02:.25,.96*r),low=Math.max(-.39,-Math.max(1.10*r,.65*od)),lid=Math.max(.57*r,.53*od);
        int n=Math.max(20,PipeSizes.sides(2*r)),axis=key.facing()&~1;var along=LineGeometry.step(key.facing());
        var body=new Mesh();var up=new Frame(CENTRE,UP,along);
        // The wet end wraps around the vertical shaft; its horizontal nozzles enter below the top cover.
        volute(body,up,axial,r,low,lid,n);
        var pads=new Mesh();
        for(int sign:new int[]{-1,1})pads.block(plus(up.along(.04*r),scale(up.side(),sign*1.02*r)),scale(along,.38*axial),scale(UP,.16*r),scale(up.side(),.09*r),Material.FLANGE);
        var arrows=PortMarkings.fittingOverhanging(LineGeometry.overhanging(pads),key.facing(),-.6*axial,.6*axial);
        double motorBase;
        if(compressor){
            // LMC: a distinct lower gear barrel and wide horizontal split beneath the ribbed upper casting.
            double gearBottom=lid+.025+.035*od,split=gearBottom+.95*r,gearTop=split+.18+.40*r;
            body.frustum(up,.72*r,.82*r,lid,gearBottom,n,Material.MACHINE);
            body.frustum(up,.82*r,.94*r,gearBottom,gearBottom+.12*r,n,Material.MACHINE);
            body.prism(up,.94*r,gearBottom+.12*r,split-.025,n,Material.MACHINE);
            body.ring(up,.70*r,1.02*r,split-.025,split-.002,n,3,Material.FLANGE);
            body.prism(up,1.015*r,split-.002,split+.002,n,Material.OPENING);
            body.ring(up,.70*r,1.02*r,split+.002,split+.025,n,3,Material.FLANGE);
            body.bolts(up,od,.74*r,1.02*r,split+.025,1,Material.BOLT);
            body.frustum(up,.90*r,.55*r,split+.025,gearTop,n,Material.MACHINE);
            body.annulus(up,0,.55*r,gearTop,n,1,Material.MACHINE);
            for(int i=0;i<8;i++)web(body,up,i*Math.PI/4,.56*r,.99*r,split+.020,gearTop-.022,Math.max(.010,.055*r),Material.MACHINE);
            gearboxDetails(body,up,r,gearBottom,split,od,along);
            motorBase=gearTop+.15+.20*od;stand(body,up,.43*r,gearTop,motorBase,along);

        }else{
            double shoulder=lid+.025,neck=.10+.15*od;motorBase=shoulder+neck;
            body.ring(up,.35*r,.66*r,lid+.008,shoulder,n,3,Material.MACHINE);
            stand(body,up,.48*r,shoulder,motorBase,along);
        }
        mountingFeet(body,up,axial,r);
        var drain=new Frame(up.along(low+.007),neg(UP),along);
        body.cylinder(drain,Math.max(.011,.07*r),0,.025,10,Material.MACHINE);
        body.cylinder(drain,Math.max(.014,.085*r),.023,.041,6,Material.BOLT);
        for(int face:new int[]{axis,axis^1}){
            var nozzle=new Frame(CENTRE,LineGeometry.step(face),UP);var end=LineGeometry.end(key,face,od);
            boolean suction=face==(key.facing()^1);
            flowPassage(body,up,root,axial,r,low,od,suction,n);
            if(boundary){
                end=LineGeometry.fittingEnd(key,face,od);
                LineGeometry.arm(body,face,root,od,end,LineGeometry.ref(face),PipeSizes.sides(od),false);
            }else{
                processJoint(body,nozzle,od,connection,n);
                if(!item)LineGeometry.arm(body,face,connection+thickness-.002,od,end,LineGeometry.ref(face),PipeSizes.sides(od),false);
            }
        }
        double rm=Math.max(compressor?.11:.09,(compressor?.32:.27)*od+.06),height=(compressor?.42:.30)+(compressor?.66:.45)*od;
        motor(body,up,rm,motorBase,height,along,n);
        var result=new ArrayList<>(LineGeometry.overhanging(body));result.addAll(LineGeometry.overhanging(pads));result.addAll(arrows);return List.copyOf(result);
    }
    /** A compact pair of mating flanges belongs to the machine, followed by the host pipe's straight tails. */
    private static void processJoint(Mesh m,Frame f,double od,double at,int sides){
        // The machine joint may overhang: retain the ordinary flange proportions beyond a one-block diameter.
        double outside=(1.25*od+.085)/2,t=PipeSizes.flangeThickness(od),seam=PipeSizes.FLANGE_SEAM/2;
        m.ring(f,.44*od,outside,at-t,at-seam,sides,3,Material.FLANGE);
        m.prism(f,outside,at-seam,at+seam,sides,Material.OPENING);
        m.ring(f,.44*od,outside,at+seam,at+t,sides,3,Material.FLANGE);
        m.bolts(f,od,.50*od,outside,at-t,-1,Material.BOLT);m.bolts(f,od,.50*od,outside,at+t,1,Material.BOLT);
        m.bore(f,.44*od,at-seam,at+seam,sides,Material.OPENING);
        // The opaque casing has no modeled internal cavity; terminate its recessed throat ahead of the casting walls.
        m.annulus(f,0,.44*od,at-t+.001,sides,1,Material.OPENING);
    }
    private static void volute(Mesh m,Frame up,double axial,double r,double low,double lid,int sides){
        // A broad scroll/manifold at pipe height, with the impeller bowl visibly suspended below it.
        castingRing(m,up,.70*r,.35*r,Math.min(1,axial/(1.05*r)),24,12);
        ellipticalBand(m,up,.66*axial,.68*r,low,low+.15*r,.64,1,sides,Material.MACHINE);
        ellipticalBand(m,up,.66*axial,.68*r,low+.15*r,-.10*r,1,1,sides,Material.MACHINE);
        ellipticalCap(m,up,.66*axial*.64,.68*r*.64,0,low,-1,sides,Material.MACHINE);
        ellipticalBand(m,up,axial,.83*r,.05*r,lid-.02,1,.98,sides,Material.MACHINE);
        ellipticalBand(m,up,axial,r,lid-.022,lid-.003,1.04,1.04,sides,Material.FLANGE);
        ellipticalBand(m,up,axial,r,lid-.003,lid+.001,1.025,1.025,sides,Material.OPENING);
        ellipticalBand(m,up,axial,r,lid+.001,lid+.015,1.04,1.04,sides,Material.FLANGE);
        ellipticalCap(m,up,1.04*axial,1.04*r,0,lid+.015,1,sides,Material.FLANGE);
        for(int i=0;i<8;i++){
            var at=ellipsePoint(up,.88*axial,.88*r,(i+.5)*Math.PI/4,lid+.015);
            m.cylinder(new Frame(at,UP,up.ref()),Math.max(.004,.03*r),-.001,.012,6,Material.BOLT);
        }
    }
    private static void castingRing(Mesh m,Frame f,double major,double tube,double axialScale,int rings,int sides){
        for(int i=0;i<rings;i++)for(int j=0;j<sides;j++){
            double a=i*2*Math.PI/rings,b=(i+1)*2*Math.PI/rings,c=j*2*Math.PI/sides,d=(j+1)*2*Math.PI/sides;
            var p=ringPoint(f,major,tube,axialScale,a,c);var q=ringPoint(f,major,tube,axialScale,a,d);
            var r=ringPoint(f,major,tube,axialScale,b,d);var s=ringPoint(f,major,tube,axialScale,b,c);
            var centre=ellipsePoint(f,major*axialScale,major,(a+b)/2,0);
            var around=new Uv(p,unit(sub(s,p)),major*a,Material.MACHINE.period());
            var round=new Uv(p,unit(sub(q,p)),tube*c,Material.MACHINE.period());
            castingFace(m,List.of(p,q,r),sub(plus(plus(p,q),r),scale(centre,3)),around,round);
            castingFace(m,List.of(p,r,s),sub(plus(plus(p,r),s),scale(centre,3)),around,round);
        }
    }
    private static double[] ringPoint(Frame f,double major,double tube,double axialScale,double angle,double round){
        double radius=major+tube*com.wormzjl.icheme.science.math.DeterministicMath.cos(round);
        return ellipsePoint(f,radius*axialScale,radius,angle,tube*com.wormzjl.icheme.science.math.DeterministicMath.sin(round));
    }
    private static double[] castingPoint(Frame f,double along,double y,double side){return plus(f.along(y),plus(scale(f.ref(),along),scale(f.side(),side)));}
    /** Curved cast passages blend the same-level flanges into the lower inlet bowl and upper scroll. */
    private static void flowPassage(Mesh m,Frame f,double root,double axial,double r,double low,double od,boolean suction,int sides){
        double sign=suction?-1:1;double[][] c={castingPoint(f,sign*root,0,0),castingPoint(f,sign*.62*root,0,0),
                suction?castingPoint(f,-.50*axial,.47*low,0):castingPoint(f,.48*axial,.10*r,-.63*r),
                suction?castingPoint(f,-.08*axial,.59*low,0):castingPoint(f,.12*axial,.05*r,-.65*r)};
        int segments=10,around=Math.min(20,sides);double[][] previous=null;double[] oldCentre=null;double travelled=0;
        for(int i=0;i<=segments;i++){
            double t=i/(double)segments,u=1-t;var centre=new double[3];var tangent=new double[3];
            for(int k=0;k<3;k++){centre[k]=u*u*u*c[0][k]+3*u*u*t*c[1][k]+3*u*t*t*c[2][k]+t*t*t*c[3][k];tangent[k]=3*u*u*(c[1][k]-c[0][k])+6*u*t*(c[2][k]-c[1][k])+3*t*t*(c[3][k]-c[2][k]);}
            tangent=unit(tangent);var vertical=unit(sub(UP,scale(tangent,dot(UP,tangent))));var sideways=cross(tangent,vertical);
            double radius=(1-t)*.50*od+t*Math.max(.47*r,.51*od)+com.wormzjl.icheme.science.math.DeterministicMath.sin(Math.PI*t)*.16*r;
            // Large bores widen the casting laterally; the swept wall must remain behind its mating flange.
            double va=dot(vertical,f.ref()),sa=dot(sideways,f.ref()),extent=radius*Math.sqrt(va*va+sa*sa);
            double room=Math.max(0,root-Math.abs(dot(sub(centre,f.origin()),f.ref()))),squeeze=extent==0?1:Math.min(1,room/extent);
            // The large-bore wet end flattens toward its bowl, leaving the supporting block unobstructed.
            double yExtent=radius*Math.sqrt(vertical[1]*vertical[1]+sideways[1]*sideways[1]);
            double yRoom=centre[1]-f.origin()[1]-((1-t)*(-.5*od)+t*low),ySqueeze=yExtent==0?1:Math.min(1,Math.max(0,yRoom)/yExtent);
            var ring=new double[around][];for(int j=0;j<around;j++){
                double a=2*Math.PI*j/around;var offset=plus(scale(vertical,radius*com.wormzjl.icheme.science.math.DeterministicMath.cos(a)),scale(sideways,radius*com.wormzjl.icheme.science.math.DeterministicMath.sin(a)));
                offset[1]*=ySqueeze;
                ring[j]=plus(centre,sub(offset,scale(f.ref(),dot(offset,f.ref())*(1-squeeze))));
            }
            if(previous!=null){
                var advance=sub(centre,oldCentre);double distance=Math.sqrt(dot(advance,advance));
                for(int j=0;j<around;j++){
                    int k=(j+1)%around;var normal=sub(plus(plus(previous[j],previous[k]),plus(ring[j],ring[k])),scale(plus(oldCentre,centre),2));
                    var circum=new Uv(previous[j],unit(sub(previous[k],previous[j])),j*Math.PI*od/around,Material.MACHINE.period());
                    var length=new Uv(oldCentre,unit(advance),travelled,Material.MACHINE.period());
                    castingFace(m,List.of(previous[j],previous[k],ring[k]),normal,circum,length);
                    castingFace(m,List.of(previous[j],ring[k],ring[j]),normal,circum,length);
                }
                travelled+=distance;
            }
            previous=ring;oldCentre=centre;
        }
    }
    /** Wrap the casting texture across each patch instead of changing box projection between adjacent triangles. */
    private static void castingFace(Mesh m,List<double[]> points,double[] outward,Uv u,Uv v){
        var centre=new double[3];for(var point:points)for(int i=0;i<3;i++)centre[i]+=point[i]/points.size();
        m.add(points,sub(centre,outward),FLAT,Material.MACHINE,u,v);
    }
    private static double[] ellipsePoint(Frame f,double axial,double side,double angle,double height){
        return plus(f.along(height),plus(scale(f.ref(),axial*com.wormzjl.icheme.science.math.DeterministicMath.cos(angle)),scale(f.side(),side*com.wormzjl.icheme.science.math.DeterministicMath.sin(angle))));
    }
    private static void ellipticalBand(Mesh m,Frame f,double axial,double side,double low,double high,double from,double to,int sides,Material material){
        for(int i=0;i<sides;i++){
            double a=2*Math.PI*i/sides,b=2*Math.PI*(i+1)/sides;
            var p=ellipsePoint(f,axial*from,side*from,a,low);var q=ellipsePoint(f,axial*from,side*from,b,low);
            var r=ellipsePoint(f,axial*to,side*to,b,high);var s=ellipsePoint(f,axial*to,side*to,a,high);
            var normal=sub(ellipsePoint(f,1,1,(a+b)/2,0),f.origin());
            var around=new Uv(p,unit(sub(q,p)),a*Math.sqrt((axial*axial+side*side)/2),material.period());
            var height=new Uv(f.origin(),f.axis(),0,material.period());
            var centre=scale(plus(plus(p,q),plus(r,s)),.25);m.add(List.of(p,q,r,s),sub(centre,normal),FLAT,material,around,height);
        }
    }
    private static void ellipticalCap(Mesh m,Frame f,double axial,double side,double inner,double at,int sign,int sides,Material material){
        for(int i=0;i<sides;i++){
            double a=2*Math.PI*i/sides,b=2*Math.PI*(i+1)/sides;
            var p=ellipsePoint(f,axial,side,a,at);var q=ellipsePoint(f,axial,side,b,at);
            if(inner==0)m.flat(List.of(f.along(at),p,q),scale(f.axis(),sign),material);
            else m.flat(List.of(ellipsePoint(f,axial*inner,side*inner,a,at),p,q,ellipsePoint(f,axial*inner,side*inner,b,at)),scale(f.axis(),sign),material);
        }
    }
    private static void mountingFeet(Mesh m,Frame up,double axial,double r){
        double thick=Math.max(1/32d,.045*r),floor=-.5+thick/2;
        for(int a:new int[]{-1,1})for(int b:new int[]{-1,1}){
            var lateral=plus(scale(up.ref(),a*.72*axial),scale(up.side(),b*.75*r));
            m.bar(plus(up.along(.06*r),lateral),plus(up.along(floor+thick/2-.003),lateral),Math.max(1/32d,.10*r),up.ref(),Material.MACHINE);
        }
        m.block(up.along(floor),scale(up.ref(),1.02*axial),scale(UP,thick/2),scale(up.side(),.96*r),Material.MACHINE);
        for(int a:new int[]{-1,1})for(int b:new int[]{-1,1}){
            var at=plus(plus(up.along(floor+thick/2),scale(up.ref(),a*.86*axial)),scale(up.side(),b*.82*r));
            m.cylinder(new Frame(at,UP,up.ref()),Math.max(.004,.025*r),-.001,.011,6,Material.BOLT);
        }
    }
    private static void fasteners(Mesh m,Frame f,double pitch,double at,double radius,int count){
        for(int i=0;i<count;i++){
            var bolt=new Frame(f.at((i+.5)*2*Math.PI/count,pitch,at),f.axis(),f.ref());
            m.cylinder(bolt,1.3*radius,-.001,.003,12,Material.FLANGE);
            m.cylinder(bolt,radius,.002,.012+radius,6,Material.BOLT);
        }
    }
    private static void gearboxDetails(Mesh m,Frame up,double r,double gearBottom,double split,double od,double[] along){
        // Inspection cover on the opposite side from the oil filter, above the flow-arrow panels.
        double level=(gearBottom+split)/2,surface=.86*r;
        var cap=new Frame(up.at(-Math.PI/2,surface-.003,level),neg(up.side()),UP);double cr=Math.max(.022,.19*r);
        m.cylinder(cap,cr,0,.022,12,Material.FLANGE);fasteners(m,cap,.70*cr,.022,Math.max(.003,.12*cr),4);
        m.cylinder(cap,.45*cr,.023,.027,12,Material.OPENING);
        var can=new Frame(up.at(Math.PI/2,1.12*r,split-.035),UP,along);double radius=Math.max(.025,.12*r),bottom=-.12-.08*od;
        m.cylinder(can,radius,bottom,.035,16,Material.MACHINE);
        m.ring(can,.90*radius,1.15*radius,.025,.045,16,3,Material.FLANGE);
        m.prism(can,1.01*radius,.019,.024,16,Material.OPENING);
        m.cylinder(can,.50*radius,bottom-.012,bottom+.003,6,Material.BOLT);
        var tap=up.at(Math.PI/2,.72*r,split+.015);var port=can.along(.055);double tube=Math.max(.007,.025*r);
        m.bar(tap,port,tube,UP,Material.PIPE);
        m.cylinder(new Frame(port,UP,along),tube*.95,-.01,.025,6,Material.FLANGE);
        // A second, short return line bends above the canister, clear of the arrows below it.
        var returnTap=up.at(Math.PI/2,.62*r,split+.055);var elbow=plus(can.along(.09),scale(along,.08*r));
        m.bar(returnTap,elbow,tube,UP,Material.PIPE);m.bar(elbow,can.along(.055),tube,along,Material.PIPE);
    }
    /** Closed triangular casting web in a radial/axial plane, extruded tangentially. */
    private static void web(Mesh m,Frame f,double angle,double inner,double outer,double low,double high,double width,Material material){
        var radial=sub(f.at(angle,1,0),f.origin());var tangent=cross(f.axis(),radial);
        double[][] section={f.at(angle,inner,low),f.at(angle,outer,low),f.at(angle,inner,high)};
        var left=new ArrayList<double[]>();var right=new ArrayList<double[]>();
        for(var point:section){left.add(plus(point,scale(tangent,-width/2)));right.add(plus(point,scale(tangent,width/2)));}
        m.flat(left,neg(tangent),material);m.flat(right,tangent,material);
        double[] rr={inner,outer,inner},zz={low,low,high};
        for(int i=0;i<3;i++){int j=(i+1)%3;var normal=plus(scale(radial,zz[j]-zz[i]),scale(f.axis(),rr[i]-rr[j]));m.flat(List.of(left.get(i),left.get(j),right.get(j),right.get(i)),normal,material);}
    }
    private static void stand(Mesh m,Frame up,double r,double low,double high,double[] along){
        m.ring(up,0,1.22*r,low-.012,low+.012,16,3,Material.FLANGE);
        m.cylinder(up,.24*r,low,high,10,Material.BOLT);
        for(int i=0;i<4;i++)m.bar(up.at(i*Math.PI/2,r,low),up.at(i*Math.PI/2,r,high),Math.max(.018,.18*r),along,Material.MACHINE);
        m.ring(up,0,1.22*r,high-.012,high+.012,16,3,Material.FLANGE);
    }
    private static void motor(Mesh m,Frame up,double radius,double low,double height,double[] along,int sides){
        double top=low+height,cowl=Math.max(.045,.18*radius);
        m.cylinder(up,radius,low,top,sides,Material.MOTOR);
        for(int i=0;i<16;i++)m.bar(up.at(i*Math.PI/8,radius+.002,low+.025),up.at(i*Math.PI/8,radius+.002,top-.015),Math.max(.009,.07*radius),along,Material.MOTOR);
        m.cylinder(up,1.045*radius,top-.02,top+cowl,sides,Material.MOTOR);
        m.cylinder(up,.92*radius,top+cowl,top+cowl+.018,sides,Material.MOTOR);
        var box=up.at(Math.PI/2,radius+.045,low+.50*height);double w=Math.max(.045,.33*radius);
        m.box(new double[]{box[0]-w,box[1]-w,box[2]-w},new double[]{box[0]+w,box[1]+w,box[2]+w},ALL_FACES,Material.MOTOR);
    }
}
