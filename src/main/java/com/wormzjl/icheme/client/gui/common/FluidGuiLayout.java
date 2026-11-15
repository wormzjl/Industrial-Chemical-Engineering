package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Rect;
import java.util.ArrayList;
import java.util.List;

/** Shared fluid-screen control positions, including the minimum supported 300px GUI width. Moved out of FluidDeviceScreen, where it was nested (finding C9). */
public final class FluidGuiLayout {
    private FluidGuiLayout(){}
    /** The screen's image at the player's scale: 8 px round it, 300 to 790 px wide; short doors scroll. */
    public static int imageWidth(int width){return Math.max(300,Math.min(790,width-16));}
    public static int imageHeight(int height){return Math.max(180,height-16);}
    /** The title strip's keys (TitleStrip.keys): the theme key, and left of it the help key where the page has a tour; the theme key stands where it always did. */
    public static List<Rect> titleKeys(int width,boolean help){return TitleStrip.keys(width,help?2:1);}
    /** The door columns' view on every page: from y {@code DOOR_TOP} to {@code DOOR_FOOT} px above the image's foot. */
    public static final int DOOR_TOP=74,DOOR_FOOT=52;
    public static final int HEIGHT=480;
    /** The door view in a window {@code height} GUI px high. */
    public static int doorView(int height){return imageHeight(height)-DOOR_FOOT-DOOR_TOP;}
    public static boolean fits(int width,int height,java.util.function.IntUnaryOperator tallest){
        return tallest.applyAsInt(imageWidth(width))<=doorView(height);
    }
    public record Slot(int x,int width){}
    /** A display key's width: the short labels (Mole, °C, kPa, /min) with room to spare (owner: the wider keys were "too big"). */
    static final int KEY=40;
    /**
     * Close and Apply at the left, then the display keys at the right, {@link #KEY} px each and 6 px apart: the
     * composition and flow basis, the temperature, pressure and flow-time unit. Close and Apply take what is left
     * up to 50 and 60 px, so Apply ends 4 px before the keys' strip ({@link #softKeys}).
     */
    public static List<Slot> footer(int width) {
        if(width<300)throw new IllegalArgumentException("Fluid GUI width below minimum");
        int[] natural={50,60},minimum={38,40};
        int keys=width-10-(4*KEY+3*6),available=keys-8-6-10,total=natural[0]+natural[1],floor=minimum[0]+minimum[1];
        int[] sizes=natural.clone();
        if(available<total)for(int i=0;i<2;i++)sizes[i]=minimum[i]+(natural[i]-minimum[i])*(available-floor)/(total-floor);
        var slots=new ArrayList<Slot>();slots.add(new Slot(10,sizes[0]));slots.add(new Slot(16+sizes[0],sizes[1]));
        for(int i=0;i<4;i++)slots.add(new Slot(keys+i*(KEY+6),KEY));
        return List.copyOf(slots);
    }
    /** The strip the display keys (the footer's basis and units, slots 2 to 5, at {@code height} - 27) sit on: 4 px round them. */
    public static Rect softKeys(int width,int height) {
        var footer=footer(width);var first=footer.get(2);var last=footer.get(5);
        return new Rect(first.x()-4,height-31,last.x()+last.width()+4-(first.x()-4),26);
    }
    /**
     * Every page but the Control tab: the door column's right edge, the column from x 10 (GUI_TOOLKIT_PLAN.md section
     * 14); its bar from here + 3, the divider at + 13, the tables' HMI screen from + 15.
     */
    public static int side(int width) {
        if(width<300)throw new IllegalArgumentException("Fluid GUI width below minimum");
        return Math.clamp(width*3/8,120,190);
    }
    public static List<Slot> tabs(int width,int count) {
        if(width<300||count<1||count>4)throw new IllegalArgumentException("Invalid fluid tabs");
        int size=Math.min(130,(width-20-6*(count-1))/count);var slots=new ArrayList<Slot>();
        for(int i=0;i<count;i++)slots.add(new Slot(10+i*(size+6),size));
        return List.copyOf(slots);
    }
    /**
     * The Control tab's columns (HEAT_GUI_PLAN.md section 2; layout C, GUI_TOOLKIT_PLAN.md section 10): the drawing from
     * x 10 to {@code drawingRight}; the side and the modes from {@code leftX}, {@code leftWidth} wide; the settings, what
     * acts and the notes from {@code rightX}, {@code rightWidth} wide; one 8 px scrollbar at {@code scrollX}. Where the
     * right column would be narrower than {@link #SPLIT_MIN}, one column as before: {@code leftWidth} 0, the rows from
     * {@code rightX}.
     */
    public record ControlSlots(int drawingRight,int leftX,int leftWidth,int rightX,int rightWidth,int scrollX){
        public boolean split(){return leftWidth>0;}
    }
    public static final int SPLIT_MIN=120;
    public static ControlSlots control(int width) {
        if(width<300)throw new IllegalArgumentException("Fluid GUI width below minimum");
        int drawingRight=width*38/100,x=drawingRight+10,area=width-20-x,left=Math.clamp((area-12)*2/5,120,170);
        if(area-left-12>=SPLIT_MIN)return new ControlSlots(drawingRight,x,left,x+left+12,area-left-12,width-16);
        int panel=Math.clamp(width*9/25,150,240),px=width-panel-20;
        return new ControlSlots(px-10,px,0,px,panel,px+panel+4);
    }
}
