import com.wormzjl.icheme.science.equipment.VisualRotation;
public class VisualSpeeds {
    public static void main(String[] args) {
        for (double rpm : new double[]{0,100,300,750,1500,3000,6000,12000,-3000})
            System.out.printf(java.util.Locale.ROOT,"%7.0f rpm -> %.4f visual turns/s%n",rpm,VisualRotation.turnsPerSecond(rpm));
    }
}
