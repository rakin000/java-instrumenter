package otrack.agent;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import java.io.File;
import java.util.Arrays;

/** java -cp object-tracker-agent.jar otrack.agent.Attach &lt;pid|main-class-substring&gt; key=value... */
public final class Attach {
    public static void main(String[] a) throws Exception {
        if (a.length < 1) {
            System.err.println("usage: Attach <pid|display-name-substring> [classes=a.B,+c.D] [cmd=stop] [sample=N] ...");
            System.exit(2);
        }
        String pid = a[0];
        if (!pid.matches("\\d+")) {
            String found = null;
            for (VirtualMachineDescriptor d : VirtualMachine.list()) {
                if (d.displayName().contains(pid)) {
                    if (found != null) throw new IllegalStateException("'" + pid + "' matches several JVMs; use a pid");
                    found = d.id();
                }
            }
            if (found == null) throw new IllegalStateException("no JVM matches '" + pid + "'");
            pid = found;
        }
        String args = String.join(";", Arrays.copyOfRange(a, 1, a.length));
        File jar = new File(Attach.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        VirtualMachine vm = VirtualMachine.attach(pid);
        try {
            vm.loadAgent(jar.getAbsolutePath(), args);
        } finally {
            vm.detach();
        }
        System.out.println("agent command sent to pid " + pid + " (see the target's stderr for [otrack] messages)");
    }
}
