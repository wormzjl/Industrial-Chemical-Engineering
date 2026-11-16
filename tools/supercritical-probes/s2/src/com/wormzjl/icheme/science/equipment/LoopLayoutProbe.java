package com.wormzjl.icheme.science.equipment;

import java.util.*;

/**
 * S2c GameTest 1 (the closed CO2 loop): the layout replayed off-line with the rules of AssemblyGameTests.Plant (route:
 * breadth-first over air cells inside the box that touch no device or nozzle cell except the allowed ones; pipe; the
 * in-line pump on the first straight horizontal pipe with air above), and the exchanger's tube and shell volumes. Each
 * route and the charge cells are checked as the test lays them. Coordinates are relative to the plant's base.
 */
public final class LoopLayoutProbe {
    record P(int x,int y,int z){P rel(int dx,int dy,int dz){return new P(x+dx,y+dy,z+dz);}
        static final int[][] D={{0,-1,0},{0,1,0},{0,0,-1},{0,0,1},{-1,0,0},{1,0,0}};} // Direction.values(): DOWN UP NORTH SOUTH WEST EAST
    static final Set<P> solid=new HashSet<>(),reserved=new HashSet<>(),devices=new LinkedHashSet<>();
    static P low,high;
    static boolean inside(P a){return a.x>=low.x&&a.x<=high.x&&a.y>=low.y&&a.y<=high.y&&a.z>=low.z&&a.z<=high.z;}
    static boolean air(P a){return !solid.contains(a)&&!devices.contains(a);}
    static boolean free(P at,Set<P> allowed) {
        if(!inside(at)||!air(at)||reserved.contains(at)&&!allowed.contains(at)||devices.contains(at))return false;
        for(var d:P.D){var n=at.rel(d[0],d[1],d[2]);if(!allowed.contains(n)&&(devices.contains(n)||reserved.contains(n)))return false;}
        return true;
    }
    static List<P> route(P from,P to,Set<P> joins) {
        var allowed=new HashSet<>(joins);allowed.add(from);allowed.add(to);
        var prev=new HashMap<P,P>();var q=new ArrayDeque<P>();prev.put(from,from);q.add(from);
        while(!q.isEmpty()&&!prev.containsKey(to)){var at=q.poll();for(var d:P.D){var n=at.rel(d[0],d[1],d[2]);if(prev.containsKey(n)||!free(n,allowed))continue;prev.put(n,at);q.add(n);}}
        if(!prev.containsKey(to))throw new IllegalStateException("No route from "+from+" to "+to);
        var path=new ArrayList<P>();for(var at=to;;at=prev.get(at)){path.add(at);if(at.equals(from))break;}
        Collections.reverse(path);devices.addAll(path);return path;
    }
    record Port(P front,int[] out){}
    static Map<EquipmentShape.Role,Port> place(EquipmentShape shape,int dx) {
        var origin=new P(dx,0,0);
        var layout=ShapeRasterizer.layout(shape);
        for(var c:layout.shell())solid.add(origin.rel(c.x(),c.y(),c.z()));
        for(var c:layout.interior())solid.add(origin.rel(c.x(),c.y(),c.z()));
        var ports=new EnumMap<EquipmentShape.Role,Port>(EquipmentShape.Role.class);
        for(var p:ShapeRasterizer.ports(shape)) {
            var front=origin.rel(p.cell().x()+p.dx(),p.cell().y()+p.dy(),p.cell().z()+p.dz());reserved.add(front);
            ports.put(shape.nozzles().get(p.nozzle()).role(),new Port(front,new int[]{p.dx(),p.dy(),p.dz()}));
        }
        return ports;
    }
    public static void main(String[] args) {
        var d=UnitDesign.of(EquipmentShape.Kind.EXCHANGER);double size=d.minSize();int length=d.defaultLength(size);
        var shape=EquipmentShape.designed(EquipmentShape.Kind.EXCHANGER,size,length,EquipmentShape.Heading.NORTH,d.defaultSupport(size,length),ProcessUnit.Bank.ONE);
        for(var def:HeatSides.of(shape)){var g=def.passage();System.out.printf(Locale.ROOT,"side %s %s->%s area %.2f m2 passage L %.2f m d %.4f m channels %d volume %.4f m3%n",def.side(),def.inlet(),def.outlet(),def.area(),g.length(),g.diameter(),g.channels(),g.channels()*Math.PI*g.diameter()*g.diameter()/4*g.length());}
        // span as the test computes it
        int[] s={Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};
        for(var c:ShapeRasterizer.layout(shape).shell()){s[0]=Math.min(s[0],c.x());s[1]=Math.max(s[1],c.x());s[2]=Math.min(s[2],c.z());s[3]=Math.max(s[3],c.z());s[4]=Math.max(s[4],c.y());}
        for(var p:ShapeRasterizer.ports(shape)){int x=p.cell().x()+p.dx(),z=p.cell().z()+p.dz();s[0]=Math.min(s[0],x-1);s[1]=Math.max(s[1],x+1);s[2]=Math.min(s[2],z-1);s[3]=Math.max(s[3],z+1);}
        int pitch=s[1]-s[0]+7,far=s[3]+6;
        System.out.println("span "+Arrays.toString(s)+" pitch "+pitch+" far "+far);
        low=new P(s[0]-8,-3,s[2]-8);high=new P(pitch+s[1]+8,s[4]+8,far+8);
        var e1=place(shape,0);var e2=place(shape,pitch);
        for(var e:List.of(e1,e2))for(var r:List.of(EquipmentShape.Role.TUBE_INLET,EquipmentShape.Role.TUBE_OUTLET,EquipmentShape.Role.SHELL_INLET,EquipmentShape.Role.SHELL_OUTLET))System.out.println("  "+r+" front "+e.get(r).front()+" out "+Arrays.toString(e.get(r).out()));
        // utilities
        for(var e:List.of(e1,e2)) {
            var in=e.get(EquipmentShape.Role.SHELL_INLET);var out=e.get(EquipmentShape.Role.SHELL_OUTLET);
            devices.add(in.front());devices.add(out.front());
            devices.add(in.front().rel(in.out()[0],in.out()[1],in.out()[2]));devices.add(out.front().rel(out.out()[0],out.out()[1],out.out()[2]));
        }
        var tank=new P(pitch/2,0,far);devices.add(tank);
        String mode=args.length>0?args[0]:"new";
        try {
            List<P> ret,suction,mid;
            if(mode.equals("old")) {
                suction=route(tank.rel(-1,0,0),e1.get(EquipmentShape.Role.TUBE_INLET).front(),Set.of());
                mid=route(e1.get(EquipmentShape.Role.TUBE_OUTLET).front(),e2.get(EquipmentShape.Role.TUBE_INLET).front(),Set.of());
                ret=route(e2.get(EquipmentShape.Role.TUBE_OUTLET).front(),tank.rel(0,0,-1),Set.of());
            } else {
                // new: the suction leaves the tank's west face, the return enters its east face (each route's tank end allowed beside the tank)
                suction=route(tank.rel(-1,0,0),e1.get(EquipmentShape.Role.TUBE_INLET).front(),Set.of());
                mid=route(e1.get(EquipmentShape.Role.TUBE_OUTLET).front(),e2.get(EquipmentShape.Role.TUBE_INLET).front(),Set.of());
                ret=route(e2.get(EquipmentShape.Role.TUBE_OUTLET).front(),tank.rel(1,0,0),Set.of(tank));
            }
            System.out.println("suction "+suction.size()+" "+suction);
            System.out.println("middle "+mid.size()+" "+mid);
            System.out.println("return "+ret.size()+" "+ret);
            // pump: first straight horizontal pipe with air above
            P pump=null;
            for(int i=1;i<suction.size()-1;i++){var a=suction.get(i-1);var at=suction.get(i);var b=suction.get(i+1);
                if(a.y!=at.y||b.y!=at.y||b.x-at.x!=at.x-a.x||b.z-at.z!=at.z-a.z)continue;
                var above=at.rel(0,1,0);if(!air(above)||reserved.contains(above))continue;pump=at;break;}
            System.out.println("pump "+pump);
            // no loop pipe but each route's tank end touches the tank; no pipe of one route touches another route's
            for(var r:List.of(suction,mid,ret))for(var c:r){if(c.equals(tank.rel(-1,0,0))||c.equals(tank.rel(1,0,0)))continue;for(var dd:P.D)if(c.rel(dd[0],dd[1],dd[2]).equals(tank))System.out.println("TOUCHES THE TANK: "+c);}
            var routes=List.of(suction,mid,ret);
            for(int i=0;i<3;i++)for(int j=0;j<3;j++)if(i!=j)for(var c:routes.get(i))for(var dd:P.D){var n=c.rel(dd[0],dd[1],dd[2]);if(routes.get(j).contains(n))System.out.println("ROUTES "+i+" AND "+j+" TOUCH at "+c+" / "+n);}
            // the charge on the tank's south face: its pipe and generator cells must be air and touch nothing but the tank and each other
            var cp=tank.rel(0,0,1);var gen=tank.rel(0,0,2);
            for(var c:List.of(cp,gen)){boolean ok=air(c)&&inside(c)&&!reserved.contains(c);
                for(var dd:P.D){var n=c.rel(dd[0],dd[1],dd[2]);if(n.equals(tank)||n.equals(cp)||n.equals(gen))continue;if(devices.contains(n)||reserved.contains(n))ok=false;}
                System.out.println("charge cell "+c+" clear "+ok);}
            // the pump's power block above it touches which devices
            if(pump!=null){var pw=pump.rel(0,1,0);var touch=new ArrayList<P>();for(var dd:P.D){var n=pw.rel(dd[0],dd[1],dd[2]);if(devices.contains(n)&&!n.equals(pump))touch.add(n);}System.out.println("power block "+pw+" touches "+touch);}
            double pipes=suction.size()+mid.size()+ret.size();
            System.out.println("pipe blocks in the loop "+pipes);
        } catch(IllegalStateException e){System.out.println(mode+": "+e.getMessage());}
    }
}
