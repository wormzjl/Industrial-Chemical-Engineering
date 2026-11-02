import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/** Fits the direct tower correlations to table.csv (the marched model) by Nelder-Mead, weighted to normal operation. */
public class Fit {
    record Row(boolean induced,int cells,double area,double fill,double lift,double L,double T,double s,double out,double evap,double air){}
    static final double WB=291.0,G=9.80665,RHO=1.18,FAN=20,CP=4186,LATENT=2.43e6;
    static List<Row> rows=new ArrayList<>();
    // p[0] b, p[1] T_b, p[2] power, p[3] L0
    static double air(Row r,double[] p){
        double fan=r.induced?FAN*r.s*r.s:0,span=r.T-p[1];
        double drive=fan+G*r.lift*p[0]*Math.signum(span)*Math.pow(Math.abs(span),p[2])*r.L/(r.L+p[3]);
        return r.area*Math.signum(drive)*Math.sqrt(2*RHO*Math.abs(drive)/(3+3*r.fill));
    }
    // p[4] a, p[5] fill power, p[6] air power, p[7] load power, p[8] temperature rate
    static double share(Row r,double g,double[] p){
        double G_=Math.abs(g)/r.area;
        double N=p[4]*Math.pow(r.fill/2,p[5])*Math.pow(G_/2,p[6])*Math.pow(r.L/2,-p[7])*Math.exp(-p[8]*(r.T-308.15));
        return 1-Math.exp(-N);
    }
    // p[9] phi, p[10] humidification, p[11] load scale, p[12] phi temperature slope
    static double evap(Row r,double g,double fall,double[] p){
        double heat=r.L*r.area*CP*fall;
        return (p[9]+p[12]*(r.T-308.15))*heat/LATENT+p[10]*Math.abs(g)*r.L/(r.L+p[11]);
    }
    static double weight(Row r){return (r.L>=1&&r.L<=3&&r.T>=303&&r.T<=323)?8:(r.L>=.5&&r.L<=10&&r.T>=295&&r.T<=338)?4:1;}
    static double costAir(double[] p){double s=0,w=0;for(var r:rows){double e=(air(r,p)-r.air)/Math.max(Math.abs(r.air),.3*r.area);double n=weight(r);s+=n*e*e;w+=n;}return s/w;}
    static double costShare(double[] p){double s=0,w=0;for(var r:rows){if(Math.abs(r.T-WB)<3)continue;double e=(r.T-WB)*share(r,air(r,p),p)-(r.T-r.out);double n=weight(r);s+=n*e*e;w+=n;}return s/w;}
    static double costEvap(double[] p){double s=0,w=0;for(var r:rows){double e=(evap(r,r.air,r.T-r.out,p)-r.evap)/Math.max(Math.abs(r.evap),.002*r.L*r.area);double n=weight(r);s+=n*e*e;w+=n;}return s/w;}
    static double[] minimize(ToDoubleFunction<double[]> f,double[] start,int[] free){
        int n=free.length;double[][] x=new double[n+1][];for(int i=0;i<=n;i++){x[i]=start.clone();if(i>0)x[i][free[i-1]]=x[i][free[i-1]]*1.2+.01;}
        double[] v=new double[n+1];for(int i=0;i<=n;i++)v[i]=f.applyAsDouble(x[i]);
        for(int it=0;it<9000;it++){
            Integer[] o=new Integer[n+1];for(int i=0;i<=n;i++)o[i]=i;Arrays.sort(o,Comparator.comparingDouble(i->v[i]));
            int b=o[0],w=o[n],s=o[n-1];double[] c=start.clone();for(int k:free){c[k]=0;for(int i=0;i<=n;i++)if(i!=w)c[k]+=x[i][k]/n;}
            double[] r=c.clone();for(int k:free)r[k]=2*c[k]-x[w][k];double fr=f.applyAsDouble(r);
            if(fr<v[b]){double[] e=c.clone();for(int k:free)e[k]=3*c[k]-2*x[w][k];double fe=f.applyAsDouble(e);if(fe<fr){x[w]=e;v[w]=fe;}else{x[w]=r;v[w]=fr;}}
            else if(fr<v[s]){x[w]=r;v[w]=fr;}
            else{double[] k2=c.clone();for(int k:free)k2[k]=.5*(c[k]+x[w][k]);double fk=f.applyAsDouble(k2);if(fk<v[w]){x[w]=k2;v[w]=fk;}else for(int i=0;i<=n;i++)if(i!=b){for(int k:free)x[i][k]=.5*(x[i][k]+x[b][k]);v[i]=f.applyAsDouble(x[i]);}}
        }
        int best=0;for(int i=1;i<=n;i++)if(v[i]<v[best])best=i;return x[best];
    }
    public static void main(String[] a)throws Exception{
        var lines=Files.readAllLines(Path.of(a[0]));
        for(var l:lines.subList(1,lines.size())){var f=l.split(",");rows.add(new Row(f[0].equals("1"),Integer.parseInt(f[1]),Double.parseDouble(f[2]),Double.parseDouble(f[3]),Double.parseDouble(f[4]),Double.parseDouble(f[5]),Double.parseDouble(f[6]),Double.parseDouble(f[7]),Double.parseDouble(f[8]),Double.parseDouble(f[9]),Double.parseDouble(f[10])));}
        double[] p={.01,297,.7,.1, 1,.7,.5,.7,.01, .8,.004,.1,0};
        p=minimize(Fit::costAir,p,new int[]{0,1,2,3});
        p=minimize(Fit::costShare,p,new int[]{4,5,6,7,8});
        p=minimize(Fit::costEvap,p,new int[]{9,10,11,12});
        for(int i=0;i<p.length;i++)p[i]=new java.math.BigDecimal(p[i]).round(new java.math.MathContext(3)).doubleValue();System.out.println("params "+Arrays.toString(p));
        double[] q=p;
        report("all",r->true,q);
        report("normal L .5-10, T 295-338",r->r.L>=.5&&r.L<=10&&r.T>=295&&r.T<=338,q);
        report("design L 1-3, T 303-323",r->r.L>=1&&r.L<=3&&r.T>=303&&r.T<=323,q);
        report("hot T 338-353, L .5-10",r->r.L>=.5&&r.L<=10&&r.T>=338,q);
        report("dribble L<=.2",r->r.L<=.2&&r.T>295,q);
        report("flood L>=10",r->r.L>=10&&r.T>295,q);
        report("cold T<=288",r->r.T<=288,q);
        report("natural L 1-5",r->!r.induced&&r.L>=1&&r.L<=5&&r.T>=303,q);
        report("fans on L 1-5",r->r.induced&&r.s>=.5&&r.L>=1&&r.L<=5&&r.T>=303,q);
    }
    static void report(String name,Predicate<Row> f,double[] p){
        double wt=0,st=0,wa=0,sa=0,we=0,se=0;int n=0;Row worst=null;
        for(var r:rows)if(f.test(r)){
            double g=air(r,p),fall=(r.T-WB)*share(r,g,p),out=r.T-fall,e=evap(r,g,fall,p);
            double dt=out-r.out,da=(g-r.air)/Math.max(Math.abs(r.air),.1*r.area),de=(e-r.evap)/Math.max(Math.abs(r.evap),1e-3*r.L*r.area);
            if(Math.abs(dt)>wt){wt=Math.abs(dt);worst=r;}wa=Math.max(wa,Math.abs(da));we=Math.max(we,Math.abs(de));st+=dt*dt;sa+=da*da;se+=de*de;n++;
        }
        System.out.printf(Locale.ROOT,"%-28s n=%4d Tout rms %.2f worst %.2f | air rms %.1f%% worst %.0f%% | evap rms %.1f%% worst %.0f%% | worst at L=%.2f T=%.1f s=%.2f fill=%.0f%n",name,n,Math.sqrt(st/n),wt,100*Math.sqrt(sa/n),100*wa,100*Math.sqrt(se/n),100*we,worst.L,worst.T,worst.s,worst.fill);
    }
}
