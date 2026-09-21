package otrack.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.jar.JarFile;

/**
 * Thin entry point loaded by the system class loader. All real code sits in object-tracker-boot.jar, which is
 * appended to the bootstrap search path (with ASM relocated) so it cannot clash with Elasticsearch's libs
 * and can be called from instrumented classes of any loader.
 */
public final class Agent {
    private static boolean bootAppended;

    private Agent() {}

    public static void premain(String args, Instrumentation inst) throws Exception {
        agentmain(args, inst);
    }

    public static synchronized void agentmain(String args, Instrumentation inst) throws Exception {
        if (!bootAppended) {
            File self = new File(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File boot = new File(self.getParentFile(), "object-tracker-boot.jar");
            inst.appendToBootstrapClassLoaderSearch(new JarFile(boot));
            bootAppended = true;
        }
        Class<?> c = Class.forName("otrack.Controller", true, null); // null = bootstrap loader
        Method m = c.getMethod("command", String.class, Instrumentation.class);
        m.invoke(null, args, inst);
    }
}
