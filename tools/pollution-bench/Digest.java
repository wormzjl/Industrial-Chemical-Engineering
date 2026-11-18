import com.wormzjl.icheme.science.pollution.*;
import com.wormzjl.icheme.science.pollution.Environment.Cell;
import java.lang.reflect.Constructor;
import java.util.*;

/** Prints the field digests of the fixed scenarios, with whatever PollutionSettings the class path holds. */
public class Digest {
    static PollutionSettings settings(int maximumCubes, double rise) throws Exception {
        PollutionSettings d = PollutionSettings.DEFAULT;
        for (Constructor<?> c : PollutionSettings.class.getConstructors()) {
            if (c.getParameterCount() == 9) {
                if (rise != 0.0) return null;
                return (PollutionSettings) c.newInstance(d.faceShare(), d.nominalSeconds(), d.halfLifeOpenSeconds(), d.halfLifeEnclosedSeconds(), d.washout(), d.settlingShare(), d.cutoff(), maximumCubes, d.closedAirShare());
            }
            if (c.getParameterCount() == 10) {
                return (PollutionSettings) c.newInstance(d.faceShare(), d.nominalSeconds(), d.halfLifeOpenSeconds(), d.halfLifeEnclosedSeconds(), d.washout(), d.settlingShare(), d.cutoff(), maximumCubes, d.closedAirShare(), rise);
            }
        }
        throw new IllegalStateException();
    }

    public static void main(String[] args) throws Exception {
        for (double rise : new double[] {0.0, 0.12}) {
            PollutionSettings s = settings(600, rise);
            if (s == null) continue;
            System.out.println("rise " + rise + " digest " + Long.toHexString(digest(s)));
        }
    }

    static long digest(PollutionSettings s) {
        Random random = new Random(7);
        Map<Long, Cell> cells = new HashMap<>();
        Cell thin = new Cell(0.3, false, true);
        Cell enclosed = new Cell(1, false, true);
        Cell closed = new Cell(0, false, true);
        for (int x = -6; x < 6; x++) for (int y = -4; y <= 3; y++) for (int z = -6; z < 6; z++) {
            double u = random.nextDouble();
            Cell c = u < 0.7 ? new Cell(1, true, true) : u < 0.85 ? enclosed : u < 0.9 ? thin : u < 0.95 ? closed : Cell.UNLOADED;
            cells.put(CubeKey.of(x, y, z), c);
        }
        Environment environment = new MapEnvironment(cells, new Cell(1, true, true), true, -4, 6);
        List<Release> releases = List.of(new Release(CubeKey.of(0, 0, 0), Channel.TOXIC, 3e5),
                new Release(CubeKey.of(0, 0, 0), Channel.PARTICULATE, 8e4), new Release(CubeKey.of(3, -2, -4), Channel.CORROSIVE, 2e5),
                new Release(CubeKey.of(-5, 2, 1), Channel.COMBUSTIBLE, 1.5e5), new Release(CubeKey.of(2, 5, 2), Channel.TOXIC, 5e4));
        PollutionField field = PollutionField.EMPTY;
        PollutionLedger ledger = PollutionLedger.EMPTY;
        long h = 0xcbf29ce484222325L;
        for (int p = 0; p < 120; p++) {
            PollutionField.Result r = PollutionField.step(field, ledger, p < 80 ? releases : List.of(), environment, 20.0, s);
            field = r.field();
            ledger = r.ledger();
            if (p % 20 == 19) {
                for (long k : field.keys()) h = mix(h, k);
                for (double a : field.amounts()) h = mix(h, Double.doubleToRawLongBits(a));
                for (double v : ledger.values()) h = mix(h, Double.doubleToRawLongBits(v));
            }
        }
        return h;
    }

    static long mix(long h, long v) {
        for (int b = 0; b < 8; b++) {
            h ^= (v >>> (8 * b)) & 0xff;
            h *= 0x100000001b3L;
        }
        return h;
    }
}
