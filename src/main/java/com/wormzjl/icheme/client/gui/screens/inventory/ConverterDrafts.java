package com.wormzjl.icheme.client.gui.screens.inventory;

import com.wormzjl.icheme.client.gui.common.ConverterDoor;
import com.wormzjl.icheme.client.gui.common.ProcessUi.NumericDraft;
import com.wormzjl.icheme.runtime.fluid.FluidView;
import com.wormzjl.icheme.science.display.DisplayUnit.FlowRateUnit;
import java.util.Map;

/**
 * A fluid converter's drafts on its device page (batch 2026-11-12-fluid-conversion, W5; moved out of
 * {@code FluidDeviceScreen}, finding C1): the Mode selector as drafted, with the rate, the deadband and the alarm levels in
 * the screen's fields ({@code converterRate}, {@code converterDeadband}, {@link ConverterDoor#THRESHOLDS}). They follow the
 * converter's settings when those change and stay drafts until Apply, which sends them by the converter's own edit
 * ({@link #apply}).
 */
final class ConverterDrafts {
    /** The Mode selector as drafted. */
    ConverterDoor.Mode mode=ConverterDoor.Mode.OFF;
    /** What the converter's drafts last followed: its settings; the screen forgets it at a new input revision. */
    String shown;
    /** What the door last followed: its rule and its rows. */
    private String door;

    /** The converter's own edit, as Apply sends it: the mode, the rate in L/s, the deadband and the thresholds as fractions (-1 for none). */
    interface Edit {
        void send(com.wormzjl.icheme.runtime.fluid.ConverterUnit.Mode mode,double rate,double deadband,double[] thresholds);
    }

    /** The drafts follow the converter's settings when they change; a new rule or a new count of its rows rebuilds the door. */
    void refresh(FluidView.Converter c,Map<String,NumericDraft> fields,FlowRateUnit flowRateUnit,Runnable rebuild){
        String door=c==null?"":c.rule()+"/"+c.ranges().size()+"/"+(c.fluid().isEmpty());
        if(!door.equals(this.door)){this.door=door;rebuild.run();}
        String shown=c==null?"":c.mode()+"/"+c.rate()+"/"+c.deadband()+"/"+c.thresholds();
        if(shown.equals(this.shown))return;
        this.shown=shown;rebuild.run();if(c==null)return;
        mode=ConverterDoor.Mode.valueOf(c.mode());
        fields.put("converterRate",new NumericDraft(c.rate(),flowRateUnit));
        fields.put("converterDeadband",new NumericDraft(100*c.deadband()));
        for(int i=0;i<ConverterDoor.THRESHOLDS.size();i++){var v=c.thresholds().get(i);var draft=new NumericDraft(v==null?0:100*v);if(v==null)draft.text("");fields.put(ConverterDoor.THRESHOLDS.get(i),draft);}
    }
    /** The converter's settings as drafted, sent by {@code edit}: the rate in L/s, the deadband and the thresholds as fractions, a blank threshold none. An invalid draft throws before anything is sent. */
    void apply(Map<String,NumericDraft> fields,Edit edit){
        double rate=fields.get("converterRate").si(),band=fields.get("converterDeadband").value()/100;
        var t=new double[ConverterDoor.THRESHOLDS.size()];
        for(int i=0;i<t.length;i++){var d=fields.get(ConverterDoor.THRESHOLDS.get(i));t[i]=d.text().isBlank()?-1:d.value()/100;}
        edit.send(com.wormzjl.icheme.runtime.fluid.ConverterUnit.Mode.valueOf(mode.name()),rate,band,t);
    }
}
