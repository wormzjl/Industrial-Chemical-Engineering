package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.math.DeterministicMath;
import java.util.List;

/**
 * What a client draws of an open (floating-roof) tank (batch 2026-10-21-floating-roof-tanks, plan section 3.4, owner
 * decisions FR4 and FR7): the liquid's height over the bottom and the free water's under it, m, the fill height the shell
 * is drawn against, and the two liquids' colours as packed AARRGGBB tints. Read off a vessel's published view on the
 * engine's schedule; nothing here solves.
 *
 * <p>Colours: each liquid mixes its components' {@link MaterialCatalog.FluidAppearance} subtractively by mass fraction
 * (Beer-Lambert: per colour channel the transmittance {@code T = prod T_i^{w_i}}, {@code T_i} the channel over 255), and
 * its transparency the same way. Free water takes water's appearance.</p>
 */
public record TankDisplay(float level,float waterLevel,float height,int oilArgb,int waterArgb,float fanSpeed) {
    public TankDisplay(float level,float waterLevel,float height,int oilArgb,int waterArgb){this(level,waterLevel,height,oilArgb,waterArgb,0);}
    public TankDisplay {
        if(!Float.isFinite(level)||!Float.isFinite(waterLevel)||!Float.isFinite(height)||!Float.isFinite(fanSpeed)||fanSpeed<0||fanSpeed>1||level<0||waterLevel<0||waterLevel>level||!(height>0))
            throw new IllegalArgumentException("Invalid tank display");
    }
    /** An empty tank of fill height {@code height}. */
    public static TankDisplay empty(float height){return new TankDisplay(0,0,height,0,0);}
    /** This display with its liquid at {@code level} m and its free water no higher; this display itself for a level that is NaN, infinite or negative. */
    private TankDisplay standingAt(double level){return !Double.isFinite(level)||level<0?this:new TankDisplay((float)level,Math.min(waterLevel,(float)level),height,oilArgb,waterArgb,fanSpeed);}

    /**
     * A tank's display gliding to its latest one (owner requests 2026-10-21: the roof moves, and a player on it rides it): a
     * display arrives once a presentation bucket, and the tank shows its levels moving linearly from where they stood
     * ({@code from}) to the new ones ({@code to}) over the {@link FluidPresentation#BUCKET_TICKS} game ticks from {@code
     * tick}. The server keeps one per loaded open tank and sends it whole, so the client's roof and the server's
     * collision stand at the same height at the same game tick.
     */
    public record Glide(TankDisplay from,TankDisplay to,long tick) {
        public Glide{java.util.Objects.requireNonNull(from);java.util.Objects.requireNonNull(to);}
        /** The display at game time {@code ticks} (fractional on the client's frames). */
        public TankDisplay at(double ticks) {
            double t=Math.max(0,Math.min(1,(ticks-tick)/FluidPresentation.BUCKET_TICKS));
            if(t>=1)return to;
            return new TankDisplay((float)(from.level+(to.level-from.level)*t),(float)(from.waterLevel+(to.waterLevel-from.waterLevel)*t),to.height,to.oilArgb,to.waterArgb,(float)(from.fanSpeed+(to.fanSpeed-from.fanSpeed)*t));
        }
        /** The liquid's level at game time {@code ticks}, m. */
        public double level(double ticks){return at(ticks).level;}
        /**
         * The glide to {@code display} arriving at game tick {@code tick}, from where the roof stands then: where {@code old}
         * has it, or with no glide yet ({@code old} null: the tank has just loaded) at {@code standing} m, the level its
         * node cell saved (NaN: at {@code display}). The island runs on while the tank's chunk is unloaded, so a reloaded
         * roof glides from where it stood to the level reached, and what stands on it rides (review
         * 2026-10-26-chunk-loading-review, D2).
         */
        public static Glide next(Glide old,double standing,TankDisplay display,long tick){return new Glide(old!=null?old.at(tick):display.standingAt(standing),display,tick);}
        /** A tank standing still at {@code display}. */
        public static Glide still(TankDisplay display){return new Glide(display,display,0);}
    }

    /**
     * The display of a vessel's view, or null for a view that is not a vessel's. {@code components} is the view's
     * component axis (water last), {@code molecularWeights} its molar masses (kg/mol), {@code packageId} the property
     * package whose appearances name the colours.
     */
    public static TankDisplay of(FluidView view,List<String> components,double[] molecularWeights,MaterialCatalog catalog,String packageId) {
        var tank=view.tank();if(tank==null)return null;
        float height=(float)tank.levelHeight();
        var tower=view.tower();
        float fan=tower==null?0:(float)(tower.rates()!=null?tower.rates().fill().speed():tower.specification().mode()==com.wormzjl.icheme.science.fluid.network.CoolingTower.Mode.TARGET_TEMPERATURE?0:tower.specification().speed());
        var state=view.state();if(state==null)return new TankDisplay(0,0,height,0,0,fan);
        var volumes=state.phaseVolumes();double footprint=state.capacity()/tank.levelHeight();
        float level=(float)(tank.level(state));float water=(float)Math.min(level,tank.geometry().heightAt(volumes[1]));
        var moles=state.phaseMoles();
        int oil=volumes[0]>0?mix(moles[0],components,molecularWeights,catalog,packageId):0;
        int free=volumes[1]>0?catalog.componentAppearance(packageId,"Water").argb():0;
        return new TankDisplay(level,water,height,oil,free,fan);
    }
    /** The subtractive mix (Beer-Lambert) of the components of {@code moles} by mass fraction, as AARRGGBB. */
    static int mix(double[] moles,List<String> components,double[] molecularWeights,MaterialCatalog catalog,String packageId) {
        double total=0;for(int c=0;c<moles.length;c++)total+=moles[c]*molecularWeights[c];
        if(!(total>0))return 0;
        double r=0,g=0,b=0,clear=0;
        for(int c=0;c<moles.length;c++) {
            double w=moles[c]*molecularWeights[c]/total;if(!(w>0))continue;
            var look=catalog.componentAppearance(packageId,components.get(c));int rgb=look.rgb();
            r+=w*log((rgb>>16&255)/255.0);g+=w*log((rgb>>8&255)/255.0);b+=w*log((rgb&255)/255.0);clear+=w*log(look.transparency());
        }
        int alpha=(int)Math.round((1-DeterministicMath.exp(clear))*255);
        return alpha<<24|channel(r)<<16|channel(g)<<8|channel(b);
    }
    /** {@code ln x}, floored at the 8-bit channel's darkest step so a black or opaque component does not take the log to minus infinity. */
    private static double log(double x){return DeterministicMath.log(Math.max(x,.5/255));}
    private static int channel(double logTransmittance){return (int)Math.round(Math.min(1,DeterministicMath.exp(logTransmittance))*255);}
}
