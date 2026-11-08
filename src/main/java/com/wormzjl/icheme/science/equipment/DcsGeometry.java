package com.wormzjl.icheme.science.equipment;

import java.util.List;

/** One standing operator console: model, selection and collision share these bounds in sixteenths of a block. */
public final class DcsGeometry {
    public enum Finish { CASE, METAL, SIDE, REAR, DOOR, FRONT, BEZEL, KEYBOARD, LABEL, ALARMS }
    public record Part(String name,double x,double y,double z,double X,double Y,double Z,Finish body,Finish front,Finish top){
        public Part moveDown(double amount){return new Part(name,x,y-amount,z,X,Y-amount,Z,body,front,top);}
    }
    private static Part box(String name,double x,double y,double z,double X,double Y,double Z,Finish body){
        return new Part(name,x,y,z,X,Y,Z,body,body,body);
    }
    private static Part front(Part part,Finish face){return new Part(part.name,part.x,part.y,part.z,part.X,part.Y,part.Z,part.body,face,part.top);}
    private static Part top(Part part,Finish face){return new Part(part.name,part.x,part.y,part.z,part.X,part.Y,part.Z,part.body,part.front,face);}
    private static final List<Part> PARTS=List.of(
            box("steel plinth",1,0,2,15,2,14,Finish.METAL),
            front(box("lower service cabinet",2,2,7,14,14,14,Finish.CASE),Finish.DOOR),
            box("worktop edge",.5,14,1,15.5,15,15,Finish.METAL),
            box("operator worktop",.5,15,1,15.5,16,15,Finish.CASE),
            top(box("operator keyboard",4.25,16,2,14,16.7,6.75,Finish.BEZEL),Finish.KEYBOARD),
            box("trackball base",1.5,16,3.5,3.75,16.4,6,Finish.BEZEL),
            box("trackball",2.25,16.4,4.25,3,16.75,5,Finish.CASE),
            box("display pedestal",6,16,11,10,20.5,13.5,Finish.METAL),
            box("monitor enclosure",.75,20,9,15.25,29.25,12.5,Finish.CASE),
            box("monitor bezel",1,20.5,8.65,15,28.75,9.05,Finish.BEZEL),
            front(box("process overview display",1.25,21.5,8.6,14.75,28.25,8.7,Finish.BEZEL),Finish.FRONT),
            front(box("operator identification strip",1.25,20.65,8.59,14.75,21.3,8.7,Finish.BEZEL),Finish.LABEL),
            box("alarm fascia",1,29.25,10,15,31.5,12.5,Finish.BEZEL),
            front(box("alarm annunciators",1.5,29.7,9.95,14.5,31.05,10.05,Finish.BEZEL),Finish.ALARMS),
            box("top cover",.75,31.5,9.75,15.25,32,12.75,Finish.CASE));
    private static final List<Part> LOWER=PARTS.stream().filter(p->p.Y<=16).toList();
    private static final List<Part> UPPER=PARTS.stream().filter(p->p.y>=16).map(p->p.moveDown(16)).toList();
    private static final List<Part> LOCAL=List.of(
            box("steel mounting foot",3,0,5,13,2,12,Finish.METAL),
            front(box("local loop controller",2,2,3,14,15,13,Finish.CASE),Finish.FRONT));
    private DcsGeometry(){}
    public static List<Part> parts(){return PARTS;}
    public static List<Part> localParts(){return LOCAL;}
    public static List<Part> parts(boolean upper){return upper?UPPER:LOWER;}
    /** Bounds after clockwise horizontal quarter turns, still local to this half. */
    public static double[] bounds(Part part,int turns){
        double x=part.x,z=part.z,X=part.X,Z=part.Z;
        for(int i=0;i<Math.floorMod(turns,4);i++){double oldX=x,oldMaxX=X;x=16-Z;X=16-z;z=oldX;Z=oldMaxX;}
        return new double[]{x,part.y,z,X,part.Y,Z};
    }
}
