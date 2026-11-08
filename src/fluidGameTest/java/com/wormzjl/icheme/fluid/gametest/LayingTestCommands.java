package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.runtime.control.DataPort;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.world.level.block.*;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import java.util.*;

/** Test command syntax only. Resolution, preview, validation and mutation are the production LineLaying owners. */
final class LayingTestCommands {
    private LayingTestCommands() {}
    static BlockPos block(PipeRoute.Cell c){return new BlockPos(c.x(),c.y(),c.z());}
    static LineProfile.Settings settings(CableMedium medium){return new LineProfile.Settings(LineProfile.of(medium),medium.outside(),medium.outside(),0,0);}
    static LineLayout.Anchor resolve(Level level,LineTarget end,Map<UUID,DataPort> ports,CableMedium medium){return LineAnchors.resolve(level,end,settings(medium)).anchor();}
    static List<LinePlacement.Lead> leads(LineLayout.Anchor a,LineLayout.Anchor b){return LineLaying.leads(a,b);}
    static LineLaying.Design attachment(Level level,LineTarget end,Map<UUID,DataPort> ports,CableMedium medium){var design=LineLaying.attachment(level,end,settings(medium));return design.refusal()==null?design:null;}
    static Component run(ServerPlayer player,InteractionHand hand,LineProfile profile,LineLaying.Action action,LineTarget a,LineTarget b,List<BlockPos> path,List<Integer> options){var result=LineLaying.execute(player,new LineLaying.Request(UUID.randomUUID(),hand==InteractionHand.OFF_HAND,profile,action,a,b,path,options));return result.success()?null:result.message();}
    static Component lay(ServerPlayer p,InteractionHand hand,LineTarget a,LineTarget b,List<BlockPos> path,List<Integer> options,CableMedium medium){return run(p,hand,LineProfile.of(medium),path.isEmpty()?LineLaying.Action.ATTACH:LineLaying.Action.LAY,a,b,path,options);}
    static Component attach(ServerPlayer p,InteractionHand hand,LineTarget a,CableMedium medium){return run(p,hand,LineProfile.of(medium),LineLaying.Action.ATTACH,a,a,List.of(),List.of());}
    static Component lay(ServerPlayer p,InteractionHand hand,PipeAnchor.Click a,PipeAnchor.Click b,List<BlockPos> path,List<Integer> options){return run(p,hand,LineProfile.FLUID,LineLaying.Action.LAY,LineTarget.of(a),LineTarget.of(b),path,options);}
    static Component dismantle(ServerPlayer p,InteractionHand hand,PipeAnchor.Click a){return run(p,hand,LineProfile.FLUID,LineLaying.Action.DISMANTLE,LineTarget.of(a),LineTarget.of(a),List.of(),List.of());}
}
