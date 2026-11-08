from pathlib import Path
s=Path('tmp/pipe-guide-audit/ConduitSectionsRender.java').read_text().replace('public class ConduitSectionsRender','public class LineLayoutRender')
a=s.index('    public static void main(');b=s.index('    static int signed(',a)
s=s[:a]+'''    public static void main(String[] args)throws Exception {
        java.nio.file.Files.createDirectories(Path.of(args[0]));blocks=4;
        for(var medium:com.wormzjl.icheme.science.equipment.CableMedium.values()){
            var quads=new ArrayList<Quad>();texture=medium.texture();markerColour=medium.colour();
            var profile=com.wormzjl.icheme.world.level.block.LineProfile.of(medium);
            var port=new com.wormzjl.icheme.runtime.control.DataPort(new UUID(0,71),medium==com.wormzjl.icheme.science.equipment.CableMedium.DATA?com.wormzjl.icheme.runtime.control.DataPort.Kind.SENSOR:com.wormzjl.icheme.runtime.control.DataPort.Kind.POWER_INPUT,medium==com.wormzjl.icheme.science.equipment.CableMedium.DATA?7:0,"minecraft:overworld",new net.minecraft.core.BlockPos(-1,0,0),new net.minecraft.world.phys.Vec3(-.25,.5,.5),net.minecraft.core.BlockPos.ZERO,net.minecraft.core.Direction.WEST,4,-3);
            var piece=new com.wormzjl.icheme.world.level.block.LinePieces.Piece(port,new com.wormzjl.icheme.runtime.control.DataBus.Site(port.dimension(),port.cell(),0));
            var first=new com.wormzjl.icheme.world.level.block.PipeAnchor(com.wormzjl.icheme.world.level.block.PipeAnchor.Kind.NOZZLE,net.minecraft.core.BlockPos.ZERO,net.minecraft.core.Direction.WEST,port.owner(),0,-1,net.minecraft.core.Direction.EAST,com.wormzjl.icheme.runtime.control.DataPorts.packed(port),com.wormzjl.icheme.world.level.block.PipeAnchor.Fit.ANY,null);
            var last=new com.wormzjl.icheme.world.level.block.PipeAnchor(com.wormzjl.icheme.world.level.block.PipeAnchor.Kind.FREE,new net.minecraft.core.BlockPos(2,0,0),null,null,0,-1,net.minecraft.core.Direction.WEST,-1,com.wormzjl.icheme.world.level.block.PipeAnchor.Fit.ANY,null);
            var route=new com.wormzjl.icheme.science.equipment.PipeRoute.Result(List.of(new com.wormzjl.icheme.science.equipment.PipeRoute.Cell(0,0,0),new com.wormzjl.icheme.science.equipment.PipeRoute.Cell(1,0,0),new com.wormzjl.icheme.science.equipment.PipeRoute.Cell(2,0,0)),List.of(-1,-1,-1),null);
            var layout=com.wormzjl.icheme.world.level.block.LineLayout.build(null,new com.wormzjl.icheme.world.level.block.LineLayout.Anchor(first,null,null,-1,com.wormzjl.icheme.runtime.control.DataPorts.packed(port)),new com.wormzjl.icheme.world.level.block.LineLayout.Anchor(last,null,null,-1,-1),route,new com.wormzjl.icheme.world.level.block.LineProfile.Settings(profile,medium.outside(),medium.outside(),0,0)).layout();
            var n=port.face().getOpposite();var end=port.cablePoint().subtract(port.centre());
            add(quads,com.wormzjl.icheme.science.equipment.ConduitGeometry.lead(new double[]{n.getStepX(),n.getStepY(),n.getStepZ()},new double[]{end.x,end.y,end.z},new double[]{n.getStepX(),n.getStepY(),n.getStepZ()},medium),port.centre().x+1,port.centre().y,port.centre().z);
            for(var cell:layout.cells())add(quads,com.wormzjl.icheme.science.equipment.ConduitGeometry.polygons(new com.wormzjl.icheme.science.equipment.ConduitGeometry.Key(cell.joints(),cell.points(),medium),false),cell.site().cell().getX()+1,0,0);
            render(quads,new double[]{-.25,-.65,1.3},args[0]+"/"+medium.name().toLowerCase()+"-layout.png");
        }
    }
'''+s[b:]
Path('tmp/line-consolidation/LineLayoutRender.java').write_text(s)
