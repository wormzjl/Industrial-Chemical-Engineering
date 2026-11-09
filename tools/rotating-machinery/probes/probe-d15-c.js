// Temporary counterfactual (never committed), D14 review option C: a one-liquid hydrocarbon supplier's bubble pressure is
// bisected at the liquid's own enthalpy (PH equilibrium of its totals), not at its temperature (TP), so a throttled liquid
// warmed by its Joule-Thomson effect reaches the floor still liquid. Apply after trace-d15.js. Restore with git checkout.
const fs=require("fs");const p=process.argv[2];let t=fs.readFileSync(p,"utf8");const crlf=t.includes("\r\n");if(crlf)t=t.replace(/\r\n/g,"\n");
function once(a,b){if(t.split(a).length!==2)throw new Error("anchor: "+a.slice(0,70));t=t.replace(a,()=>b);}
once(`                else if(FluidThermodynamics.holdsPhase(top,FluidThermodynamics.OIL)&&!oneLiquid(t,lo,x,checkpoint)) {
                    for(int k=0;k<200&&hi>lo*(1+BUBBLE_WIDTH);k++){double mid=Math.sqrt(lo*hi);if(oneLiquid(t,mid,x,checkpoint))hi=mid;else lo=mid;}
                    bubble=hi;
                }`,`                else if(FluidThermodynamics.holdsPhase(top,FluidThermodynamics.OIL)&&!FluidThermodynamics.holdsPhase(s,FluidThermodynamics.WATER)&&!oneLiquidPH(s,lo,checkpoint)) {
                    for(int k=0;k<200&&hi>lo*(1+BUBBLE_WIDTH);k++){double mid=Math.sqrt(lo*hi);if(oneLiquidPH(s,mid,checkpoint))hi=mid;else lo=mid;}
                    bubble=hi;
                    double tpBubble=Double.NaN;try{double l2=NumericalBox.MINIMUM_PRESSURE,h2=s.pressure();for(int k=0;k<200&&h2>l2*(1+BUBBLE_WIDTH);k++){double mid=Math.sqrt(l2*h2);if(oneLiquid(t,mid,x,checkpoint))h2=mid;else l2=mid;}tpBubble=h2;}catch(IllegalArgumentException ignored){}
                    System.out.printf(java.util.Locale.ROOT,"D15C bubblePH=%.10g bubbleTP=%.10g supP=%.10g T=%.6f%n",bubble,tpBubble,s.pressure(),t);
                }
                else if(FluidThermodynamics.holdsPhase(top,FluidThermodynamics.OIL)&&!oneLiquid(t,lo,x,checkpoint)) {
                    for(int k=0;k<200&&hi>lo*(1+BUBBLE_WIDTH);k++){double mid=Math.sqrt(lo*hi);if(oneLiquid(t,mid,x,checkpoint))hi=mid;else lo=mid;}
                    bubble=hi;
                }`);
once(`    /** Whether the TP equilibrium of {@code x} at {@code (t, p)} is one hydrocarbon liquid ({@link #bubblePressure}). */`,
`    private boolean oneLiquidPH(FluidThermodynamics.State s,double p,Runnable checkpoint) {
        try{var at=model.equilibriumPH(s.temperature(),p,s.enthalpy(),PhaseLayout.totalAmounts(s),checkpoint);
            return at!=null&&FluidThermodynamics.holdsPhase(at,FluidThermodynamics.OIL)&&!FluidThermodynamics.holdsPhase(at,FluidThermodynamics.GAS);}
        catch(FluidThermodynamics.UnsupportedPhases split){return false;}
    }
    /** Whether the TP equilibrium of {@code x} at {@code (t, p)} is one hydrocarbon liquid ({@link #bubblePressure}). */`);
if(crlf)t=t.replace(/\n/g,"\r\n");fs.writeFileSync(p,t);console.log("ok");
