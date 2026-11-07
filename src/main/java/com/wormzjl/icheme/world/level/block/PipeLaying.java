package com.wormzjl.icheme.world.level.block;
import com.wormzjl.icheme.science.equipment.PipeRoute;
import net.minecraft.network.chat.Component;
import java.util.Locale;
/** Fluid vocabulary shared by every line mode. Commands are owned by LineLaying. */
public final class PipeLaying {
    public static final double START_RANGE=LineLaying.START_RANGE;
    private PipeLaying(){}
    private static String name(Enum<?> value){return value.name().toLowerCase(Locale.ROOT);}
    public static Component refusal(PipeAnchor.Refusal reason){return message("anchor."+name(reason));}
    public static Component refusal(PipeRoute.Refusal reason){return message("route."+name(reason));}
    public static Component describe(PipeAnchor anchor){return message("end."+name(anchor.kind()));}
    public static Component message(String key,Object...args){return Component.translatable("message.icheme.pipe_laying."+key,args);}
}
