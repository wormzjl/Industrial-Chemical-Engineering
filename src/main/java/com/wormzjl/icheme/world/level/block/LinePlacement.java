package com.wormzjl.icheme.world.level.block;

import com.wormzjl.icheme.science.equipment.PipeRoute;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import java.util.List;

/** Shared pipe/data/power placement checks and survival inventory transaction. Registration remains medium-specific. */
public final class LinePlacement {
    private LinePlacement(){}
    public static Component check(Level level,PipeAnchor start,PipeAnchor end,List<BlockPos> path,List<Integer> options,double outside){
        var fit=PipeAnchor.refuses(start,end,outside);if(fit!=null)return PipeLaying.refusal(fit);
        var cells=path.stream().map(PipeAnchor::cell).toList();
        var refusal=PipeRoute.check(cells,options,start.end(),end.end(),PipeRoute.Limits.DEFAULT.cells());if(refusal!=null)return PipeLaying.refusal(refusal);
        int blocked=PipeRoute.blocked(cells,options,start.end(),start.leaving(),end.end(),outside,PipeAnchor.space(level));
        return blocked<0?null:PipeLaying.message("occupied",path.get(blocked).toShortString());
    }
    public static boolean editable(ServerPlayer player,BlockPos pos){
        var level=player.serverLevel();return level.hasChunkAt(pos)&&level.getWorldBorder().isWithinBounds(pos)&&level.mayInteract(player,pos)
                &&!player.blockActionRestricted(level,pos,player.gameMode.getGameModeForPlayer());
    }
    public static boolean take(ServerPlayer player,Item material,int count){
        var inventory=player.getInventory();int held=0;
        for(int i=0;i<inventory.getContainerSize();i++)if(inventory.getItem(i).is(material))held+=inventory.getItem(i).getCount();
        if(held<count)return false;
        for(int i=0;i<inventory.getContainerSize()&&count>0;i++){
            var stack=inventory.getItem(i);if(!stack.is(material))continue;int taken=Math.min(count,stack.getCount());stack.shrink(taken);count-=taken;
        }inventory.setChanged();return true;
    }
}
