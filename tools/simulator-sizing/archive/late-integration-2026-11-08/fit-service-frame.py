from pathlib import Path
p=Path('src/main/java/com/wormzjl/icheme/science/equipment/EquipmentServices.java');s=p.read_text(encoding='utf-8');old='if(low==null){var b=mount(surface,anchor,front,Math.clamp(size*.5,.28,.5),Math.clamp(size*.5,.28,.4),Math.clamp(size*.4,.2,.3));low=b[0];high=b[1];}'
new='''if(low==null){double width=Math.clamp(size*.5,.28,.5),h=Math.clamp(size*.5,.28,.4),depth=Math.clamp(size*.4,.2,.3);var b=mount(surface,anchor,front,width,h,depth);
            if(!fits(north,b)&&kind!=EquipmentShape.Kind.TANK){var fitted=fitPad(surface,north,anchor,front,width,h,depth,zones);anchor=fitted[0];b=new double[][]{fitted[1],fitted[2]};}
            low=b[0];high=b[1];}'''
s=s.replace(old,new)
at=s.index('    private static void brackets(')
s=s[:at]+'''    private static boolean fits(EquipmentShape shape,double[][] box){int[] size={shape.sizeX(),shape.sizeY(),shape.sizeZ()};for(int a=0;a<3;a++)if(box[0][a]<-.00001||box[1][a]>size[a]+.00001)return false;return true;}
    /** Keep an enclosure in the existing frame by moving along a real carrier, shrinking only when space requires it. */
    private static double[][] fitPad(ServiceSurface surface,EquipmentShape shape,double[] anchor,int face,double width,double height,double depth,List<double[]> zones){
        int axis=face==5?0:2,other=axis==0?2:0,sign=face==5?1:-1;
        for(var size:List.of(new double[]{width,height,depth},new double[]{Math.min(width,.3),Math.min(height,.3),Math.min(depth,.18)},new double[]{.22,.28,.12}))for(int step=0;step<=80;step++)for(int side:new int[]{1,-1}){
            var wanted=anchor.clone();wanted[other]+=side*step*.025;double[] point;try{point=surface.ray(axis,wanted,sign,MOUNT);}catch(IllegalArgumentException missing){continue;}var box=mount(surface,point,face,size[0],size[1],size[2]);if(fits(shape,box)&&ServiceSurface.clear(point,zones))return new double[][]{point,box[0],box[1]};
        }
        throw new IllegalArgumentException("No native service pad inside the equipment placement frame: "+shape.kind());
    }
''' +s[at:];p.write_text(s,encoding='utf-8')
