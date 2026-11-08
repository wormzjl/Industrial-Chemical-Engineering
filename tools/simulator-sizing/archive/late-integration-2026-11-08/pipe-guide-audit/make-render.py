from pathlib import Path
s=Path('tmp/cable-style/PowerLookRender.java').read_text(encoding='utf-8').replace('PowerLookRender','ConduitSectionsRender')
s=s.replace('double[] normal,BufferedImage texture','double[] normal,double[] shades,BufferedImage texture')
a=s.index('    public static void main(');b=s.index('    static String texture=',a)
s=s[:a]+'''    public static void main(String[] args)throws Exception {
        java.nio.file.Files.createDirectories(Path.of(args[0]));blocks=5;
        for(var medium:com.wormzjl.icheme.science.equipment.CableMedium.values()){
            var quads=new ArrayList<Quad>();texture=medium.texture();markerColour=medium.colour();
            var rack=new com.wormzjl.icheme.science.equipment.RackGeometry.Rack(5,0);
            var lane=new com.wormzjl.icheme.science.equipment.RackGeometry.Lane(0,1,48,48,8);
            long points=com.wormzjl.icheme.science.equipment.RackGeometry.points(rack,lane);
            var lead=com.wormzjl.icheme.science.equipment.ConduitGeometry.lead(new double[]{1,0,0},new double[]{1,.25,0},new double[]{1,0,0},medium);
            add(quads,lead,0,.5,.5);
            add(quads,com.wormzjl.icheme.science.equipment.ConduitGeometry.polygons(new com.wormzjl.icheme.science.equipment.ConduitGeometry.Key(0,0,medium).connect(4,8,0).connect(5,0,0),false),1,0,0);
            int left=(int)(points>>40)&1023,right=(int)(points>>50)&1023;
            add(quads,com.wormzjl.icheme.science.equipment.ConduitGeometry.polygons(new com.wormzjl.icheme.science.equipment.ConduitGeometry.Key(0,0,medium).connect(4,0,0).connect(5,signed(left&31),signed(left>>5&31)),false),2,0,0);
            add(quads,com.wormzjl.icheme.science.equipment.ConduitGeometry.lane(rack,lane,new com.wormzjl.icheme.science.equipment.ConduitGeometry.Key(48,points,medium),true),3,0,0);
            add(quads,com.wormzjl.icheme.science.equipment.ConduitGeometry.polygons(new com.wormzjl.icheme.science.equipment.ConduitGeometry.Key(0,0,medium).connect(4,signed(right&31),signed(right>>5&31)).connect(5,0,0),false),4,0,0);
            render(quads,new double[]{-.25,-.65,1.3},args[0]+"/"+medium.name().toLowerCase()+"-sections.png");
        }
    }
    static int signed(int n){return n>=16?n-32:n;}
    static void add(List<Quad> quads,List<com.wormzjl.icheme.science.equipment.Mesh.Polygon> polygons,double x,double y,double z)throws Exception{
        for(var q:polygons){var v=q.positions().clone();for(int i=0;i<v.length;i+=3){v[i]+=x;v[i+1]+=y;v[i+2]+=z;}mesh(quads,new com.wormzjl.icheme.science.equipment.Mesh.Polygon(v,q.uvs(),q.normals(),q.material()),0x376674);}
    }
'''+s[b:]
s=s.replace('quads.add(new Quad(p,uv,new double[]{ns[0],ns[1],ns[2]},t));', 'var shades=new double[4];for(int i=0;i<4;i++)shades[i]=Math.round(255*net.neoforged.neoforge.client.model.lighting.QuadLighter.calculateShade((float)ns[corners[i]*3],(float)ns[corners[i]*3+1],(float)ns[corners[i]*3+2],false))/255.0;quads.add(new Quad(p,uv,new double[]{ns[0],ns[1],ns[2]},shades,t));')
s=s.replace('int r=(int)((rgb>>16&255)*light)', 'light=aa*q.shades()[tri[0]]+bb*q.shades()[tri[1]]+cc*q.shades()[tri[2]];int r=(int)((rgb>>16&255)*light)')
Path('tmp/pipe-guide-audit/ConduitSectionsRender.java').write_text(s,encoding='utf-8')
