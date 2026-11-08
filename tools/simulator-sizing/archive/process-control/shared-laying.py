from pathlib import Path
p=Path('src/main/java/com/wormzjl/icheme/client/PipeLayingClient.java');s=p.read_text(encoding='utf-8');a=s.index('    private static @Nullable PipeAnchor.Click aimed(Minecraft mc)');b=s.index('    /** Whether '+chr(123)+'@code click'+chr(125)+' lands on a lane',a)
method=s[a:b].replace('private static @Nullable PipeAnchor.Click aimed(Minecraft mc)','public static @Nullable PipeAnchor.Click aimed(Minecraft mc,java.util.function.BiPredicate<PipeRackBlockEntity,PipeAnchor.Click> onLane)').replace('onLane(rack, vanilla)','vanilla!=null&&onLane.test(rack,vanilla)')
header='''package com.wormzjl.icheme.client;

import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.world.level.block.PipeAnchor;
import com.wormzjl.icheme.world.level.block.entity.PipeRackBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.*;
import org.jetbrains.annotations.Nullable;
import java.util.*;

/** Shared pipe/data tool selection, grid-ray rack targeting, slot footprints and placement previews. */
public final class LayingToolAim {
    private LayingToolAim(){}
    public static boolean holding(LocalPlayer player){return player.getMainHandItem().is(ModItems.PIPE_LAYING_TOOL.get())||player.getOffhandItem().is(ModItems.PIPE_LAYING_TOOL.get());}
    public static ItemStack held(LocalPlayer player){return player.getMainHandItem().is(ModItems.PIPE_LAYING_TOOL.get())?player.getMainHandItem():player.getOffhandItem();}
    public static List<AABB> freeSlots(PipeRackBlockEntity rack){
        var frame=rack.rack();int taken=RackGeometry.taken(frame,rack.layingLanes());var boxes=new ArrayList<AABB>();
        for(int position=0;position<RackGeometry.POSITIONS;position++){if((taken>>position&1)!=0)continue;var c=frame.world(RackGeometry.centre(position,1),(RackGeometry.BEAM_TOP+2)*LineGeometry.OFFSET,.5);double h=.04;boxes.add(new AABB(c[0]-h,c[1]-h,c[2]-h,c[0]+h,c[1]+h,c[2]+h).move(rack.getBlockPos()));}return List.copyOf(boxes);
    }
    public record Slot(PipeRackBlockEntity rack,RackGeometry.Lane lane,boolean fits){}
    public static Slot slot(net.minecraft.world.level.Level level,PipeAnchor.Click click,double outside){
        if(click==null||!(level.getBlockEntity(click.clicked()) instanceof PipeRackBlockEntity rack))return null;
        var aim=PipeAnchor.aimed(rack,click.local(),outside);if(aim==null)return null;int code=PipeSizes.code(outside),width=RackGeometry.width(PipeSizes.outsideOf(code));
        return new Slot(rack,new RackGeometry.Lane(aim.option(),width,1<<aim.through()|1<<aim.open(),1<<aim.through(),code),aim.fits());
    }
'''
Path('src/main/java/com/wormzjl/icheme/client/LayingToolAim.java').write_text(header+method+'}\n',encoding='utf-8')
s=s[:a]+'''    private static @Nullable PipeAnchor.Click aimed(Minecraft mc){return LayingToolAim.aimed(mc,PipeLayingClient::onLane);}

'''+s[b:]
s=s.replace('return tool(player.getMainHandItem()) || tool(player.getOffhandItem());','return LayingToolAim.holding(player)&&!PipeLayingToolItem.data(LayingToolAim.held(player));')
s=s.replace('return tool(player.getMainHandItem()) ? player.getMainHandItem() : player.getOffhandItem();','return LayingToolAim.held(player);')
a=s.index('        if (!(level.getBlockEntity(click.clicked()) instanceof PipeRackBlockEntity rack) || onLane(rack, click)) return null;',s.index('private static @Nullable Slot slot'))
b=s.index('\n    }',a)
s=s[:a]+'''        if(!(level.getBlockEntity(click.clicked()) instanceof PipeRackBlockEntity rack)||onLane(rack,click))return null;
        var aimed=LayingToolAim.slot(level,click,outside);if(aimed==null)return null;
        int through=Integer.numberOfTrailingZeros(aimed.lane().joints());
        return new Slot(click.clicked(),rack.frame(),aimed.lane(),through,refused||!aimed.fits());'''+s[b:]
a=s.index('        var frame = rack.rack();',s.index('    private static void freeSlots('));b=s.index('\n    }',a)
s=s[:a]+'''        int position=0;for(var box:LayingToolAim.freeSlots(rack)){var key="icheme:free_slot:"+position++;shown.add(key);Outlines.box(key,box,FREE_COLOUR,1/32F);}'''+s[b:]
p.write_text(s,encoding='utf-8')
p=Path('src/main/java/com/wormzjl/icheme/world/level/block/entity/PipeRackBlockEntity.java');s=p.read_text(encoding='utf-8');mark='    /** The route search\'s view of this cell:';a=s.index(mark);s=s[:a]+'''    /** All laid lines take the same rack slots, regardless of the medium carried. */
    public List<RackGeometry.Lane> layingLanes(){var out=new ArrayList<>(drawnLanes());for(var cable:cables)out.add(cableLane(cable));return List.copyOf(out);}

'''+s[a:];p.write_text(s,encoding='utf-8')
p=Path('src/main/java/com/wormzjl/icheme/world/level/block/PipeAnchor.java');s=p.read_text(encoding='utf-8').replace('RackGeometry.aim(rack.rack(), rack.drawnLanes(), outside,','RackGeometry.aim(rack.rack(), rack.layingLanes(), outside,');p.write_text(s,encoding='utf-8')
p=Path('src/main/java/com/wormzjl/icheme/world/level/block/DataCableLaying.java');s=p.read_text(encoding='utf-8').replace('RackGeometry.aim(rack.rack(),rack.room().lanes(),DataCableGeometry.OUTSIDE,new double[]{click.local().x,click.local().y,click.local().z})','PipeAnchor.aimed(rack,click.local(),DataCableGeometry.OUTSIDE)');p.write_text(s,encoding='utf-8')
