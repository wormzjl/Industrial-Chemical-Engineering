import com.wormzjl.icheme.science.column.v3.*;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnUnit;
import com.wormzjl.icheme.science.plant.column.ColumnSolve;
import java.lang.management.ManagementFactory;
import java.security.MessageDigest;
import java.util.HexFormat;

public class ColumnRepeat {
    static void append(MessageDigest digest, double[] values) {
        for (double value : values) {
            long bits = Double.doubleToRawLongBits(value);
            for(int i=0;i<8;i++) digest.update((byte)(bits>>>(8*i)));
        }
    }
    static String digest(V3NeuralSeed seed)throws Exception{
        var d=MessageDigest.getInstance("SHA-256");
        for(var row:seed.liquid())append(d,row);
        for(var row:seed.vapor())append(d,row);
        append(d,seed.temperatures());append(d,seed.freeWater());
        for(boolean wet:seed.wetTrays())d.update((byte)(wet?1:0));
        return HexFormat.of().formatHex(d.digest());
    }
    public static void main(String[] args)throws Exception{
        var c=ColumnBench.of("literature");
        var input=c.input();
        var base=(V3ColumnOutcome.Success)ColumnSolve.calculate(input,null,c.catalog(),ColumnUnit.SCREEN,ColumnSolve.ColdStart.LEARNED,()->{});
        var seed=base.result().warmSeed().orElseThrow();
        // Hold all flows, pressure and setpoints fixed while raising inlet temperature by 2 K.
        var target=new V3ColumnInput(input.schemaVersion(),input.packageId(),input.assayId(),input.componentBasis(),
            input.feedComponentMolarFlowsMolPerSecond(),input.feedTemperatureKelvin()+2,input.stageCount(),
            input.feedStageNumber(),input.topPressurePascal(),input.stagePressureDropPascal(),input.specifications(),
            input.sideDraws(),input.steamFeeds(),input.pumparounds(),input.columnDiameterMetres());
        var bean=ManagementFactory.getPlatformMXBean(com.sun.management.ThreadMXBean.class);
        for(int i=-2;i<4;i++){
            long cpu=bean.getCurrentThreadCpuTime(),mem=bean.getCurrentThreadAllocatedBytes();
            var outcome=ColumnSolve.calculate(target,seed,c.catalog(),ColumnUnit.SCREEN,ColumnSolve.ColdStart.CLASSICAL,()->{});
            long usedCpu=bean.getCurrentThreadCpuTime()-cpu, bytes=bean.getCurrentThreadAllocatedBytes()-mem;
            if(!(outcome instanceof V3ColumnOutcome.Success ok))throw new IllegalStateException(ColumnBench.describe(outcome));
            if(i>=0) System.out.println("COLUMN,"+i+","+usedCpu+","+bytes+","+digest(ok.result().warmSeed().orElseThrow()));
        }
    }
}
