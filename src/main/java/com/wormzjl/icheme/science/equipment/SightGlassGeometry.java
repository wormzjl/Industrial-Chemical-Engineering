package com.wormzjl.icheme.science.equipment;

import java.util.List;
import java.util.Objects;
import com.wormzjl.icheme.science.math.DeterministicMath;
import static com.wormzjl.icheme.science.equipment.Mesh.*;

/** Diameter-driven round-window accessory. The viewing bore is genuinely open through both sides of the housing. */
public final class SightGlassGeometry {
    private static final double[] CENTRE={.5,.5,.5},UP={0,1,0};
    private static final int SIDES=32;
    private SightGlassGeometry(){}

    /** World-coordinate axis of the paired window normals. */
    public enum Axis {
        X,Y,Z;
        private double coordinate(double x,double y,double z){return switch(this){case X->x;case Y->y;case Z->z;};}
        private double[] vector(){return switch(this){case X->new double[]{1,0,0};case Y->new double[]{0,1,0};case Z->new double[]{0,0,1};};}
    }

    /** A visual key, independent of the pipe's hydraulic facing. Window axes always remain perpendicular to the run. */
    public record Key(LineGeometry.Key pipe,Axis window) {
        public Key {
            Objects.requireNonNull(pipe);Objects.requireNonNull(window);
            var run=run(pipe.connections(),pipe.facing());if(window==run)window=defaultWindow(run);
        }
        public Key(LineGeometry.Key pipe){this(pipe,defaultWindow(run(pipe.connections(),pipe.facing())));}
    }

    /** The permitted window axis closest to the installer, using the eye offset from the block centre. */
    public static Axis towards(int connections,int facing,double x,double y,double z) {
        var run=run(connections,facing);var chosen=defaultWindow(run);
        for(var axis:Axis.values())if(axis!=run&&Math.abs(axis.coordinate(x,y,z))>Math.abs(chosen.coordinate(x,y,z)))chosen=axis;
        return chosen;
    }
    private static Axis run(int connections,int facing) {
        int face=connections==0?facing:Integer.numberOfTrailingZeros(connections);
        return switch(LineGeometry.axis(face)){case 0->Axis.X;case 1->Axis.Y;default->Axis.Z;};
    }
    private static Axis defaultWindow(Axis run){return run==Axis.Z?Axis.X:Axis.Z;}

    public record Geometry(List<Polygon> metal,List<Polygon> glass,List<Polygon> liquid) {
        public Geometry{metal=List.copyOf(metal);glass=List.copyOf(glass);liquid=List.copyOf(liquid);}
    }
    /** Shared dynamic-overlay aperture, with its plane between liquid and clear glass. */
    public record Window(double radius,double plane,Axis axis){
        public Window{Objects.requireNonNull(axis);if(!Double.isFinite(radius)||!Double.isFinite(plane)||radius<=0||plane<=0)throw new IllegalArgumentException("Invalid window aperture");}
    }
    private record Size(double radius,double depth,double crossScale,double root){}
    private static Size size(Key look){
        double od=PipeSizes.outsideOf(look.pipe().outside()),radius=.09+.37*od,half=.93*radius,cross=Math.max(half,.5*od+.01);
        // Keep the axial flange clearance, while enclosing the full pipe rim across the run and window normal.
        double root=Math.min(.90*radius,half*(1.66-(.5*od+.005)/cross));
        return new Size(radius,Math.max(.04+.34*od,.5*od+.01),cross/half,root);
    }
    public static Window window(Key look){var size=size(look);return new Window(.60*.995*size.radius(),size.depth()+.085*size.radius(),look.window());}

    /** Unconnected and dead-end pipes draw straight; two contacts must be opposite. Corners and junctions are excluded. */
    public static boolean supports(int connections){return Integer.bitCount(connections)<=1||connections==3||connections==12||connections==48;}

    /** Housing and straight arms; static windows and transient liquid share the same saved visual orientation. */
    public static Geometry build(Key look,boolean accessory) { return build(look,accessory,RenderDetail.NORMAL); }
    public static Geometry build(Key look,boolean accessory,RenderDetail detail) {
        var key=look.pipe();
        if(!accessory&&!supports(key.connections()))return new Geometry(LineGeometry.polygons(key,detail),List.of(),List.of());
        double od=PipeSizes.outsideOf(key.outside());
        // The body and viewing aperture scale with diameter. Large cross-run shoulders may overhang the host cell.
        var size=size(look);double radius=size.radius(),window=.60*radius,depth=size.depth();
        int mask=key.connections();
        if(Integer.bitCount(mask)<=1){int face=mask==0?key.facing():Integer.numberOfTrailingZeros(mask);mask=(1<<face)|(1<<(face^1));}
        double[] view=look.window().vector(),reference=look.window()==Axis.Y?new double[]{0,0,1}:UP;
        var frame=new Frame(CENTRE,view,reference);var metal=new Mesh(detail);var glass=new Mesh(detail);var liquid=new Mesh(detail);
        int crossAxis=3-run(key.connections(),key.facing()).ordinal()-look.window().ordinal();
        windowPlate(metal,frame,window,.93*radius,.93*radius,-depth,depth,3,Material.PIPE,crossAxis,size.crossScale());
        for(int sign:new int[]{-1,1}) {
            var side=new Frame(CENTRE,new double[]{sign*view[0],sign*view[1],sign*view[2]},reference);
            // A cast chamber, dark cover joint and bevelled four-bolt cover distinguish the fitting from a bare washer.
            windowPlate(metal,side,window,.96*radius,.96*radius,depth,depth+.025*radius,3,Material.OPENING);
            windowPlate(metal,side,.66*radius,radius,radius,depth+.025*radius,depth+.10*radius,1,Material.FLANGE);
            windowPlate(metal,side,.66*radius,radius,.96*radius,depth+.10*radius,depth+.14*radius,2,Material.FLANGE);
            metal.ring(side,window,.72*radius,depth+.10*radius,depth+.14*radius,SIDES,1,Material.FLANGE);
            metal.frustum(side,.72*radius,.66*radius,depth+.14*radius,depth+.20*radius,SIDES,Material.FLANGE);
            metal.bore(side,window,depth+.14*radius,depth+.20*radius,SIDES,Material.FLANGE);
            metal.annulus(side,window,.66*radius,depth+.20*radius,SIDES,1,Material.FLANGE);
            // Recessed gasket and glass remain behind the circular retaining lip, leaving a clear opening through the body.
            metal.ring(side,window*.98,.64*radius,depth+.07*radius,depth+.095*radius,SIDES,3,Material.OPENING);
            for(int bolt=0;bolt<4;bolt++) {
                var fastener=new Frame(side.at(Math.PI/4+Math.PI*bolt/2,Math.sqrt(2)*.72*radius,depth+.14*radius),side.axis(),reference);
                metal.cylinder(fastener,.13*radius,0,.012*radius,12,Material.FLANGE);
                metal.nut(fastener,.18*radius/Math.sqrt(3),.012*radius,.077*radius,6,Material.BOLT);
                if(detail==RenderDetail.NORMAL)metal.cylinder(fastener,.045*radius,.077*radius,.092*radius,8,Material.BOLT);
            }
            glass.annulus(side,0,window,depth+.09*radius,SIDES,1,Material.GLASS);
            liquid.annulus(side,0,window*.995,depth+.08*radius,SIDES,1,Material.GLASS);
        }
        if(!accessory)for(int face=0;face<6;face++)if((mask>>face&1)!=0) {
            var end=LineGeometry.fittingEnd(key,face,od);
            LineGeometry.arm(metal,face,size.root(),od,end,LineGeometry.ref(face),Math.max(16,PipeSizes.sides(od)),false);
        }
        return new Geometry(LineGeometry.overhanging(metal),LineGeometry.overhanging(glass),LineGeometry.overhanging(liquid));
    }

    /** A chamfered square casting/cover with a circular bore. Different end widths make the cover's edge bevel. */
    private static void windowPlate(Mesh mesh,Frame frame,double bore,double half0,double half1,double z0,double z1,int caps,Material material) {
        windowPlate(mesh,frame,bore,half0,half1,z0,z1,caps,material,0,1);
    }
    private static void windowPlate(Mesh mesh,Frame frame,double bore,double half0,double half1,double z0,double z1,int caps,Material material,int crossAxis,double stretch) {
        mesh.bore(frame,bore,z0,z1,SIDES,material);
        int sides=mesh.detail().sides(SIDES);
        for(int i=0;i<sides;i++) {
            double a=2*Math.PI*i/sides,b=2*Math.PI*(i+1)/sides;
            var p0=outer(frame,a,half0,z0,crossAxis,stretch);var p1=outer(frame,b,half0,z0,crossAxis,stretch);
            var p2=outer(frame,b,half1,z1,crossAxis,stretch);var p3=outer(frame,a,half1,z1,crossAxis,stretch);
            mesh.add(List.of(p0,p1,p2,p3),frame.along((z0+z1)/2),FLAT,material,null,null);
            if((caps&1)!=0)mesh.flat(List.of(frame.at(a,bore,z0),frame.at(b,bore,z0),p1,p0),neg(frame.axis()),material);
            if((caps&2)!=0)mesh.flat(List.of(frame.at(a,bore,z1),frame.at(b,bore,z1),p2,p3),frame.axis(),material);
        }
    }
    private static double[] outer(Frame frame,double angle,double half,double z,int axis,double stretch){var p=frame.at(angle,outline(angle,half),z);p[axis]=frame.origin()[axis]+(p[axis]-frame.origin()[axis])*stretch;return p;}
    private static double outline(double angle,double half) {
        double c=Math.abs(DeterministicMath.cos(angle)),s=Math.abs(DeterministicMath.sin(angle));
        return half*Math.min(1/Math.max(c,s),1.68/(c+s));
    }

    // ---- bubbles carried by the flow inside a unit aperture: an illustration of the published speed, not a simulation ----
    // (batch 2026-11-18-equipment-reflections, part 3). Coordinates are in window radii; the window is the unit circle.

    /** Bubbles of a few millimetres rise through still liquid at about this speed, m/s. */
    public static final double RISE=.25;
    /** The slowest and fastest crossing of a window, s. */
    public static final double SLOWEST=6,FASTEST=.4;
    /** The stretch along the flow at the fastest crossing (1 at the slowest). */
    public static final double MOST_STRETCH=2.5;
    /** Where a ring may reach, in window radii (the glass's bore). */
    public static final double APERTURE=.97;

    /**
     * A bubble: centre (x, y), radius across the flow, stretch along it ({@code stretch} x radius), and the unit direction
     * of the flow in the window's plane (ax, ay), all in window radii.
     */
    public record Bubble(double x,double y,double radius,double stretch,double ax,double ay){
    }
    /** Unit travel direction in the front window's local plane. The rear window mirrors its horizontal coordinate. */
    public record Motion(int x,int y){
        public Motion{if(Math.abs(x)+Math.abs(y)!=1)throw new IllegalArgumentException("Invalid bubble motion");}
        public static Motion along(Axis window,Axis run,boolean positive){
            java.util.Objects.requireNonNull(window);java.util.Objects.requireNonNull(run);
            if(window==run)throw new IllegalArgumentException("Flow must cross the window plane");
            int sign=positive?1:-1;
            return switch(window){
                case X->run==Axis.Y?new Motion(0,sign):new Motion(-sign,0);
                case Y->run==Axis.X?new Motion(sign,0):new Motion(0,-sign);
                case Z->run==Axis.X?new Motion(sign,0):new Motion(0,sign);
            };
        }
    }

    /**
     * The bubbles' speed along the flow direction, m/s (negative: against it): the published liquid speed {@code speed}
     * plus the rise, {@code rise} +1 on a vertical run flowing up, -1 flowing down, 0 on a horizontal one. Never slower
     * than a crossing in {@link #SLOWEST} s (where flow and rise cancel, in the rise's direction: bubbles in slow downward
     * liquid rise slowly), never faster than one in {@link #FASTEST} s. {@code diameter}: the window's, m.
     */
    public static double velocity(double speed,double diameter,int rise){
        if(!(diameter>0)||!Double.isFinite(speed)||Math.abs(rise)>1)throw new IllegalArgumentException("Invalid bubble speed");
        double u=Math.max(0,speed)+RISE*rise,slow=diameter/SLOWEST,fast=diameter/FASTEST;
        if(Math.abs(u)<slow)u=rise<0&&u<=0?-slow:slow;
        return Math.copySign(Math.min(Math.abs(u),fast),u);
    }

    /** The time a bubble takes to cross a window of {@code diameter} m at {@code velocity} ({@link #velocity}), s. */
    public static double crossing(double velocity,double diameter){return diameter/Math.abs(velocity);}

    /** How fast the flow reads, 0 at the slowest crossing to 1 at the fastest. */
    public static double pace(double velocity,double diameter){
        double t=SLOWEST/crossing(velocity,diameter);
        return Math.max(0,Math.min(1,(t-1)/(SLOWEST/FASTEST-1)));
    }

    /**
     * The distance a glass's bubbles have travelled along the flow, m, from game ticks: carried at {@code velocity} since
     * {@code tick}, where they had travelled {@code distance}. A new published speed continues from where they are
     * ({@link #retarget}), so their phase never jumps; the look (stretch, stratification) eases from {@code before} over
     * one second ({@link #eased}). A paused game stops the ticks, and the bubbles.
     */
    public record Travel(double tick,double distance,double velocity,double before){
        /** As if it had always moved at {@code velocity}: what every client that sees the same sample draws. */
        public static Travel start(double ticks,double velocity){return new Travel(ticks,velocity*ticks/20,velocity,velocity);}
        public double at(double ticks){return distance+velocity*(ticks-tick)/20;}
        public Travel retarget(double ticks,double next){return next==velocity?this:new Travel(ticks,at(ticks),next,eased(ticks));}
        /** The velocity the look follows: from {@code before} to {@code velocity} over 20 ticks. */
        public double eased(double ticks){double s=Math.max(0,Math.min(1,(ticks-tick)/20));return before+(velocity-before)*s*s*(3-2*s);}
    }

    /** Pale translucent rim; the liquid behind its empty centre retains the published colour. */
    public static int bubbleColour(int base,int strength){
        if(strength<0||strength>255)throw new IllegalArgumentException("Invalid bubble strength");
        if(strength==0||base>>>24==0)return 0;
        return (150+80*strength/255)<<24|brighten(base>>16&255)<<16|brighten(base>>8&255)<<8|brighten(base&255);
    }

    /**
     * The bubbles of a glass whose bubbles have travelled {@code distance} m along {@code motion}, at most 8 by the gas share
     * {@code strength}. Each keeps its size; it enters at one edge and leaves at the other, then waits a gap of its own
     * before it comes again. {@code look}: the eased velocity (stretch; on a horizontal run the bias to the top).
     * {@code stratified}: a horizontal run seen from the side, where gas rides the upper part of the window, more at low
     * speed; else bubbles spread evenly across. Positions run past the rim: the shader clips them to the glass, the
     * fallback shrinks them at the edge ({@link #fallbackScale}).
     */
    public static List<Bubble> bubbles(double distance,long seed,int strength,Motion motion,double diameter,double look,boolean stratified){
        if(!Double.isFinite(distance)||strength<0||strength>255||!(diameter>0)||!Double.isFinite(look))throw new IllegalArgumentException("Invalid bubble display");
        java.util.Objects.requireNonNull(motion);
        if(strength==0)return List.of();
        double t=look==0?0:pace(look,diameter),stretch=1+(MOST_STRETCH-1)*t;
        double ax=motion.x(),ay=motion.y(),cx,cy;
        if(stratified){cx=0;cy=1;}else{cx=-ay;cy=ax;}
        var result=new java.util.ArrayList<Bubble>();int count=Math.max(1,(8*strength+127)/255);
        for(int i=0;i<count;i++){
            double first=fraction(seed+0x9E3779B97F4A7C15L*i),second=fraction(seed+0x632BE59BD9B4E019L*(i+1)),third=fraction(seed^0x5851F42D4C957F2DL*(i+3));
            double radius=.08+.06*second,gap=1.6*third;
            // One cycle's path in window radii, independent of the speed (the longest stretch), so a new speed keeps the phase.
            double reach=1+radius*MOST_STRETCH,path=2*reach+gap,phase=distance/(diameter/2*path)+first;phase-=Math.floor(phase);
            double along=-reach+path*phase,cross,spread=fraction(seed+31L*i+7);
            if(stratified){double lo=.45-.85*t,hi=APERTURE-radius-.05;cross=lo+(hi-lo)*spread;}
            else cross=(2*spread-1)*(APERTURE-radius-.05);
            cross+=.03*DeterministicMath.sin(2*Math.PI*2*phase+first*6);
            result.add(new Bubble(ax*along+cx*cross,ay*along+cy*cross,radius,stretch,ax,ay));
        }
        return List.copyOf(result);
    }

    /**
     * The ring's scale on the fallback path, which cannot clip to the round glass: full size across the middle, shrunk only
     * within its own extent of the rim so the whole ring stays inside {@link #APERTURE}, 0 outside.
     */
    public static double fallbackScale(Bubble bubble){
        double extent=bubble.radius()*Math.max(1,bubble.stretch());
        double room=APERTURE-DeterministicMath.hypot(bubble.x(),bubble.y());
        return room>=extent?1:Math.max(0,room/extent);
    }

    private static double fraction(long value){value=(value^(value>>>30))*0xBF58476D1CE4E5B9L;value=(value^(value>>>27))*0x94D049BB133111EBL;return ((value^(value>>>31))>>>11)*0x1.0p-53;}
    private static int brighten(int channel){return (int)Math.round(channel+.65*(255-channel));}
}
