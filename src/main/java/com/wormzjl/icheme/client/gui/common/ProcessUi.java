package com.wormzjl.icheme.client.gui.common;
import com.wormzjl.icheme.science.display.DisplayUnit.PressureUnit;
import com.wormzjl.icheme.science.display.DisplayUnit;

import com.wormzjl.icheme.science.material.MaterialName;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.EditBox;
import org.lwjgl.glfw.GLFW;
import java.util.Locale;

/**
 * Shared instrument palette, table cells, labels and buttons, numeric and material-name formatting, screen cursor
 * recovery, and the small editor types both process screens use (temperature unit, numeric draft, row scrollbar).
 */
public final class ProcessUi {
    public static final int BG=com.wormzjl.icheme.client.gui.common.kit.Palette.BG,PANEL=com.wormzjl.icheme.client.gui.common.kit.Palette.PANEL,
        LINE=com.wormzjl.icheme.client.gui.common.kit.Palette.LINE,TEXT=com.wormzjl.icheme.client.gui.common.kit.Palette.TEXT,
        MUTED=com.wormzjl.icheme.client.gui.common.kit.Palette.MUTED,ACCENT=com.wormzjl.icheme.client.gui.common.kit.Palette.ACCENT,
        WARN=com.wormzjl.icheme.client.gui.common.kit.Palette.WARN;
    private ProcessUi(){}
    public static String number(double value){
        return com.wormzjl.icheme.client.gui.common.kit.Format.number(value);
    }
    /** The gauge's resolution, Pa (owner decision D42, {@code NetworkReduction.REST_RESOLUTION}). */
    public static final double PRESSURE_RESOLUTION=0.1;
    /**
     * A pressure (Pa) as the gauge shows it: rounded to {@link #PRESSURE_RESOLUTION}, written in kPa with four decimals
     * ({@code 101.3250 kPa}). Only the text is rounded; the value the server sent is untouched. A pressure difference below
     * half the resolution reads as zero.
     */
    public static String pressure(double pascal){
        return PressureUnit.KILOPASCAL.format(pascal);
    }
    /** Resource-pack translations over server-owned bounded scientific naming descriptors. */
    public static String materialName(MaterialName n) {
        String base=Component.translatableWithFallback(n.translationKey(),n.fallback()).getString();
        if(!n.kind().equals("petroleum_fraction"))return base;
        String range;
        if(n.lowerKelvin()==null)range=Component.translatableWithFallback(n.rangeKey(),"%s, NBP below %s°C",base,n.upperCelsius()).getString();
        else if(n.upperKelvin()==null)range=Component.translatableWithFallback(n.rangeKey(),"%s, NBP above %s°C",base,n.lowerCelsius()).getString();
        else range=Component.translatableWithFallback(n.rangeKey(),"%s, NBP %s–%s°C",base,n.lowerCelsius(),n.upperCelsius()).getString();
        return range+(n.estimated()?Component.translatableWithFallback("material.icheme.estimated"," (estimated)").getString():"");
    }
    /** A screen label, drawn muted and clipped to its width. */
    public record Label(String text,int x,int y,int width){}
    /** A literal-text button whose tooltip repeats its text; the caller positions it and adds it to the screen. */
    public static Button button(String text,int x,int y,int width,Runnable action){
        Button button=Button.builder(Component.literal(text),b->action.run()).bounds(x,y,width,18).build();
        button.setTooltip(Tooltip.create(Component.literal(text)));return button;
    }
    /** A focus/control hand-off can hide/grab the cursor without reopening the existing screen. */
    public static void restoreCursor(Minecraft client,Screen screen){
        if(client==null||client.screen!=screen||!client.isWindowActive())return;
        if(client.mouseHandler.isMouseGrabbed())client.mouseHandler.releaseMouse();
        long window=client.getWindow().getWindow();
        if(GLFW.glfwGetInputMode(window,GLFW.GLFW_CURSOR)!=GLFW.GLFW_CURSOR_NORMAL)
            GLFW.glfwSetInputMode(window,GLFW.GLFW_CURSOR,GLFW.GLFW_CURSOR_NORMAL);
    }
    /** Focused text editors consume gameplay bindings before container-screen shortcuts. */
    public static boolean textKeyPressed(Screen screen,int key,int scan,int modifiers){
        if(!(screen.getFocused() instanceof EditBox edit)||!edit.isFocused()||!edit.active||!edit.visible)return false;
        if(edit.keyPressed(key,scan,modifiers))return true;
        // Escape closes the screen, and Tab retains ordinary focus traversal.
        return key!=GLFW.GLFW_KEY_ESCAPE&&key!=GLFW.GLFW_KEY_TAB;
    }
    public static void tableRow(GuiGraphics g,Font font,String[] cells,int x,int y,int width,boolean header){
        g.fill(x,y-2,x+width,y+12,header?LINE:PANEL);int at=x,first=width/2;
        for(int i=0;i<cells.length;i++){
            int cellWidth=i==0?first:(width-first)/Math.max(1,cells.length-1);
            g.drawString(font,font.plainSubstrByWidth(cells[i],Math.max(0,cellWidth-6)),at+3,y,header?TEXT:MUTED,false);at+=cellWidth;
        }
    }

    /** Temperatures cross the wire in kelvin; display defaults to Celsius. */
    public enum TemperatureUnit implements DisplayUnit {
        CELSIUS("C",273.15), KELVIN("K",0);
        private final String symbol;
        private final double offset;
        TemperatureUnit(String symbol,double offset){this.symbol=symbol;this.offset=offset;}
        public String symbol(){return symbol;}
        public double display(double kelvin){return kelvin-offset;}
        public double kelvin(double displayed){return displayed+offset;}
        @Override public double si(double displayed){return kelvin(displayed);}
        public TemperatureUnit next(){return this==CELSIUS?KELVIN:CELSIUS;}
    }

    /** Keeps untouched full-precision input values behind concise text. */
    public static final class NumericDraft {
        private final double exact;
        private final double exactSi;
        private final DisplayUnit unit;
        private final String initial;
        private String text;
        public NumericDraft(double value){this(value,null);}
        public NumericDraft(double si,DisplayUnit unit){
            this.unit=unit;exactSi=si;exact=unit==null?si:unit.display(si);
            initial=exact!=0&&Math.abs(exact)<1?String.format(java.util.Locale.ROOT,"%.4g",exact):number(exact);text=initial;
        }
        public String text(){return text;}
        public void text(String value){text=value;}
        public double value(){
            double value=text.equals(initial)?exact:Double.parseDouble(text);
            if(!Double.isFinite(value))throw new IllegalArgumentException("Enter a finite number");
            return value;
        }
        /** Untouched drafts retain the original SI bits across repeated display-unit changes. */
        public double si(){
            double value=text.equals(initial)?exactSi:unit==null?value():unit.si(value());
            if(!Double.isFinite(value))throw new IllegalArgumentException("Enter a finite number");
            return value;
        }
        public NumericDraft in(DisplayUnit next){return new NumericDraft(si(),java.util.Objects.requireNonNull(next));}
    }

    /** Draggable row scrollbar, independent of a screen's data source. */
    public static final class ProcessScroll {
        private int value,total,visible,x,y,height;
        private boolean dragging;
        public int value(){return value;}
        public void reset(){value=0;}
        public void reveal(int index){if(index<value)value=index;else if(index>=value+visible)value=index-visible+1;value=Math.clamp(value,0,max());}
        public void configure(int x,int y,int height,int visible,int total){
            this.x=x;this.y=y;this.height=Math.max(20,height);this.visible=Math.max(1,visible);this.total=total;
            value=Math.clamp(value,0,max());
        }
        private int max(){return Math.max(0,total-visible);}
        private int thumb(){return Math.min(height,Math.max(18,total==0?height:height*visible/total));}
        public void draw(GuiGraphics g){
            if(max()==0)return;
            int at=(height-thumb())*value/max();
            g.fill(x,y,x+8,y+height,PANEL);g.fill(x+1,y+at,x+7,y+at+thumb(),dragging?ACCENT:LINE);
        }
        private void seek(double mouseY){value=Math.clamp((int)Math.round((mouseY-y-thumb()/2.0)*max()/Math.max(1,height-thumb())),0,max());}
        public boolean click(double mx,double my,int button){
            if(button!=0||max()==0||mx<x||mx>=x+8||my<y||my>=y+height)return false;
            dragging=true;seek(my);return true;
        }
        public boolean drag(double my){if(!dragging)return false;seek(my);return true;}
        public void release(){dragging=false;}
        public boolean wheel(double delta){if(max()==0)return false;value=Math.clamp(value-(int)Math.signum(delta)*3,0,max());return true;}
    }
}
