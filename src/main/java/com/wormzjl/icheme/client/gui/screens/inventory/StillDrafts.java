package com.wormzjl.icheme.client.gui.screens.inventory;

import com.wormzjl.icheme.client.gui.common.ProcessUi.NumericDraft;
import com.wormzjl.icheme.network.FluidNetwork;
import com.wormzjl.icheme.runtime.fluid.FluidView;
import java.util.Map;

/**
 * A batch still's drafts on its device page (batch 2026-11-09-batch-distiller, W6; moved out of {@code FluidDeviceScreen},
 * finding C1): the Firing switch and the Reflux valve as drafted, with the firing and the ratio in the screen's fields
 * {@code stillFiring} and {@code stillReflux}. They follow the still's settings when those change and stay drafts until
 * Apply, which sends {@link #controls} with the device's controls.
 */
final class StillDrafts {
    /** The Firing switch at On, and the Reflux valve open, as drafted. */
    boolean firing,open=true;
    /** What the still's drafts and widgets last followed: its settings; the screen forgets it at a new input revision. */
    String shown;
    /** What the door last followed: what a controller holds and the slot's item. */
    private String door;

    /** The drafts follow the still's settings when they change (no device revision moves with them); a controller's hold or a new item in the slot rebuilds the door. */
    void refresh(FluidView.Still s,Map<String,NumericDraft> fields,Runnable rebuild){
        String door=s==null?"":s.heldFiring()+"/"+s.heldReflux()+"/"+s.heldClosed()+"/"+s.fuel();
        if(!door.equals(this.door)){this.door=door;rebuild.run();}
        String shown=s==null?"":s.firing()+"/"+s.reflux()+"/"+s.closed();
        if(shown.equals(this.shown))return;
        this.shown=shown;rebuild.run();if(s==null)return;
        firing=s.firing()>0;open=!s.closed();
        // Off keeps the opening On returns to: full, where none was set.
        fields.put("stillFiring",new NumericDraft(s.firing()>0?s.firing()*100:100));
        fields.put("stillReflux",new NumericDraft(s.reflux()));
    }
    /** The still's settings as drafted: Off fires at 0, Closed sends the ratio drafted with the closed valve. */
    FluidNetwork.StillControls controls(Map<String,NumericDraft> fields){
        double firing=this.firing?fields.get("stillFiring").value()/100:0;
        return new FluidNetwork.StillControls(firing,fields.get("stillReflux").value(),!open);
    }
}
