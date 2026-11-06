package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.*;
import com.wormzjl.icheme.science.machine.Motor;
import java.util.Locale;

/** Presentation of the motor's finite VFD envelope. The operating point is an accepted engine reading. */
public final class MotorCurve {
    private MotorCurve() {}
    public static void draw(Canvas c,Rect box,double rated,double base,double rpm,double power,HeatControlColumn.Texts texts) {
        if(!(rated>0)||!(base>0)||box.w()<130||box.h()<100)return;
        double maximumShare=Motor.maximumShare(base);
        int x=box.x()+25,y=box.y()+31,w=box.w()-37,h=box.h()-66;
        c.text(texts.get("machine.envelope"),box.x()+5,box.y()+5,Palette.TEXT);
        String rating=String.format(Locale.ROOT,"%.1f kW / %.0f rpm",rated/1000,base);
        c.text(rating,box.x()+5,box.y()+17,Palette.MUTED);
        c.batch(()->{
            c.fill(x,y,x+w+1,y+h+1,Palette.PANEL);
            c.outline(x,y,w+1,h+1,Palette.LINE);
            for(int i=1;i<=2;i++)c.hLine(x,x+w,y+h-i*h/3,Palette.LINE);
            int priorPower=y+h,priorTorque=y+h-(int)(h/1.2);
            for(int i=0;i<=w;i++){
                double share=maximumShare*i/w,p=Math.min(1,share),torque=share<=1?1:1/share;
                int py=y+h-(int)(p*h/1.2),ty=y+h-(int)(torque*h/1.2);
                c.vLine(x+i,Math.min(priorPower,py)-1,Math.max(priorPower,py)+1,Palette.ACCENT);
                c.vLine(x+i,Math.min(priorTorque,ty)-1,Math.max(priorTorque,ty)+1,0xffe3bc63);
                priorPower=py;priorTorque=ty;
            }
            int ox=x+(int)(Math.clamp(rpm,0,Motor.MAXIMUM_RPM)/Motor.MAXIMUM_RPM*w),oy=y+h-(int)(Math.clamp(power/rated,0,1.2)*h/1.2);
            c.fill(ox-2,oy-2,ox+3,oy+3,Palette.TEXT);
        });
        c.text("0",x-10,y+h-3,Palette.MUTED);c.text("100%",box.x()+1,y+Math.max(0,h/6-4),Palette.MUTED);
        c.text("0",x,y+h+4,Palette.MUTED);
        String maximum=String.format(Locale.ROOT,"%.0f rpm",Motor.MAXIMUM_RPM);c.text(maximum,x+w-c.measure().width(maximum),y+h+4,Palette.MUTED);
        c.text(texts.get("machine.envelope.power"),box.x()+5,box.bottom()-12,Palette.ACCENT);
        String torque=texts.get("machine.envelope.torque");c.text(torque,box.right()-5-c.measure().width(torque),box.bottom()-12,0xffe3bc63);
    }
}
