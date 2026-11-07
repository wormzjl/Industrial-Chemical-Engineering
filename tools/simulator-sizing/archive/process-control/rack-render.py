from pathlib import Path
p=Path('tmp/process-control/ControlAssetRender.java');s=p.read_text(encoding='utf-8');a=s.index('        for(var entry:models.entrySet())')
addition='''        var rackView=new ArrayList<Quad>();int arms=1<<2|1<<3;var rack=new com.wormzjl.icheme.science.equipment.RackGeometry.Rack(2,0,com.wormzjl.icheme.science.equipment.RackGeometry.BENT_BACK|com.wormzjl.icheme.science.equipment.RackGeometry.BENT_FRONT|com.wormzjl.icheme.science.equipment.RackGeometry.END_BACK|com.wormzjl.icheme.science.equipment.RackGeometry.END_FRONT|com.wormzjl.icheme.science.equipment.RackGeometry.GROUNDED,com.wormzjl.icheme.science.equipment.RackGeometry.BEAM_TOP);
        var pipe=new com.wormzjl.icheme.science.equipment.RackGeometry.Lane(com.wormzjl.icheme.science.equipment.RackGeometry.option(0,com.wormzjl.icheme.science.equipment.RackGeometry.TIER),2,arms,arms,com.wormzjl.icheme.science.equipment.PipeSizes.code(.25));
        var cableA=new com.wormzjl.icheme.science.equipment.RackGeometry.Lane(com.wormzjl.icheme.science.equipment.RackGeometry.option(2,com.wormzjl.icheme.science.equipment.RackGeometry.TIER),1,arms,arms,com.wormzjl.icheme.science.equipment.PipeSizes.code(.0625));
        var cableB=new com.wormzjl.icheme.science.equipment.RackGeometry.Lane(com.wormzjl.icheme.science.equipment.RackGeometry.option(3,com.wormzjl.icheme.science.equipment.RackGeometry.TIER),1,arms,arms,com.wormzjl.icheme.science.equipment.PipeSizes.code(.0625));
        for(var q:com.wormzjl.icheme.science.equipment.RackGeometry.polygons(rack,List.of(pipe,cableA,cableB)))mesh(rackView,q);
        for(var q:com.wormzjl.icheme.science.equipment.RackGeometry.lane(rack,pipe))mesh(rackView,q);
        for(var lane:List.of(cableA,cableB))for(var q:com.wormzjl.icheme.science.equipment.DataCableGeometry.path(com.wormzjl.icheme.science.equipment.RackGeometry.path(rack,lane)))mesh(rackView,q);models.put("mixed-pipe-cable-rack",rackView);
'''
s=s[:a]+addition+s[a:];p.write_text(s,encoding='utf-8')
