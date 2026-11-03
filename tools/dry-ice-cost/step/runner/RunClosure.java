import java.lang.reflect.*;
/** Scratch: runs the test methods of one test class without Gradle. java RunClosure <class> */
public class RunClosure {
    public static void main(String[] a) throws Exception {
        Class<?> c=Class.forName(a[0]);
        for(Method m:c.getDeclaredMethods()){
            if(m.getParameterCount()!=0||m.getAnnotation(org.junit.jupiter.api.Test.class)==null)continue;
            var k=c.getDeclaredConstructor();k.setAccessible(true);m.setAccessible(true);
            try{m.invoke(k.newInstance());System.out.println("PASS "+m.getName());}
            catch(InvocationTargetException e){var t=e.getCause();var s=String.valueOf(t.getMessage());System.out.println("FAIL "+m.getName()+": "+s.substring(0,Math.min(300,s.length())));}
        }
    }
}
