from pathlib import Path
r=Path('src/main/java/com/wormzjl/icheme')
p=r/'world/level/block/LinePlacement.java';s=p.read_text().replace('        for(var lead:leads)for(var box:bounds(lead))', '''        for(int i=0;i<leads.size();i++)for(int j=0;j<i;j++)if(!leads.get(i).equals(leads.get(j)))for(var a:bounds(leads.get(i)))for(var b:bounds(leads.get(j)))if(a.deflate(1e-6).intersects(b.deflate(1e-6)))return PipeLaying.message("occupied",BlockPos.containing(a.getCenter()).toShortString());
        for(var lead:leads)for(var box:bounds(lead))''',1);p.write_text(s)
p=r/'client/LineToolSession.java';s=p.read_text().replace('        context(context);var aim=adapter.aimed();','        context(context);if(dimension==null){dimension=context.dimension();mode=context.mode();}var aim=adapter.aimed();');p.write_text(s)
p=r/'world/level/block/ConduitLineBackend.java';s=p.read_text().replace('||!port.owner().equals(target.clicked()))','||!port.owner().equals(target.clicked())||!port.dimension().equals(level.dimension().location().toString()))');p.write_text(s)
p=r/'world/level/block/LineLaying.java';s=p.read_text().replace('        if(host.lane()==0)return player.gameMode.destroyBlock(host.cell())?null:PipeLaying.message("not_allowed");','''        for(var piece:LinePieces.at(level,host.cell()))if(piece.parent().cell().equals(host.cell())&&piece.parent().lane()==host.lane())for(var cell:piece.cells().keySet())if(!LinePlacement.editable(player,cell))return PipeLaying.message("not_allowed");
        if(host.lane()==0){boolean done=player.gameMode.destroyBlock(host.cell());LinePieces.flush(player.server);return done?null:PipeLaying.message("not_allowed");}''');s=s.replace('backend.forgetLane(level,world,host);backend.refresh(level,List.of(host.cell()));','backend.forgetLane(level,world,host);backend.refresh(level,List.of(host.cell()));LinePieces.flush(player.server);');p.write_text(s)
p=r/'client/CableLayingClient.java';s=p.read_text();needle='            for(int i=0;i<boxes.size();i++){';i=s.index(needle);end=s.index('\n        }else if(',i);s=s[:end]+'''
            int pieceIndex=0;for(var piece:LinePieces.at(level,hovered.clicked()))if(piece.parent().cell().equals(hovered.clicked())&&piece.parent().lane()==hovered.lane())for(var part:piece.cells().entrySet())for(var box:part.getValue().toAabbs())markers.box("icheme:cable_remove_lead:"+pieceIndex++,box.move(part.getKey()),LineToolMarkers.REFUSED);'''+s[end:];p.write_text(s)
p=Path('src/test/java/com/wormzjl/icheme/world/level/block/LineContractTest.java');s=p.read_text();i=s.index('    @Test void leadSelectionAndCollision');s=s[:i]+'''    @Test void everyMediumReservesLeadCellsDuringBothSearchAndAuthoritativeReconstruction(){
        var lead=new LinePlacement.Lead(BlockPos.ZERO,new BlockPos(2,0,0),new Vec3(.8,.5,.5),.125,List.of(new Vec3(.8,.5,.5),new Vec3(2,.5,.5)));
        for(var profile:LineProfile.values()){
            var world=PipeRoute.Space.of(cell->PipeRoute.Room.FREE,List.of());var space=LinePlacement.reserve(world,List.of(lead));
            assertEquals(PipeRoute.Admit.BLOCKED,space.room(new PipeRoute.Cell(1,0,0)).admit(),profile.name());
            assertEquals(PipeRoute.Admit.FREE,space.room(new PipeRoute.Cell(2,0,0)).admit(),profile.name());
            var start=new PipeRoute.End(new PipeRoute.Cell(0,1,0),-1),end=new PipeRoute.End(new PipeRoute.Cell(2,1,0),-1);
            var route=PipeRoute.search(start,5,end,.14,space,PipeRoute.Limits.DEFAULT);assertTrue(route.found());
            assertEquals(-1,PipeRoute.blocked(route.cells(),route.options(),start,5,end,.14,space));
        }
    }
'''+s[i:];# no compound var declaration Java
s=s.replace('var start=new PipeRoute.End(new PipeRoute.Cell(0,1,0),-1),end=new PipeRoute.End(new PipeRoute.Cell(2,1,0),-1);','var start=new PipeRoute.End(new PipeRoute.Cell(0,1,0),-1);var end=new PipeRoute.End(new PipeRoute.Cell(2,1,0),-1);');p.write_text(s)
