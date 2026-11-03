import java.lang.reflect.Method;

public class Check {
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("com.wormzjl.icheme.science.column.v3.NativeColumnSolver");
        Method available = type.getDeclaredMethod("available");
        Method version = type.getDeclaredMethod("version");
        Method batch = type.getDeclaredMethod("thermoBatchEnabled");
        Method smoke = type.getDeclaredMethod("smokeSolve");
        for (Method m : new Method[] {available, version, batch, smoke}) m.setAccessible(true);
        System.out.println("resource=" + type.getResource("/natives/windows-x86_64/icheme_column_solver.dll"));
        System.out.println("available=" + available.invoke(null) + " version=" + version.invoke(null)
                + " thermoBatch=" + batch.invoke(null) + " smoke=" + smoke.invoke(null));
    }
}
