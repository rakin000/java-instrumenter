import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;

/** Build-time only: Shade OUT_DIR IN... (dirs or jars); rewrites org.objectweb.asm -> otrack.shaded.asm. */
public class Shade {
    static final Remapper R = new Remapper() {
        @Override public String map(String n) {
            return n.startsWith("org/objectweb/asm/") ? "otrack/shaded/asm/" + n.substring(18) : n;
        }
    };

    public static void main(String[] a) throws Exception {
        Path out = Path.of(a[0]);
        for (int i = 1; i < a.length; i++) {
            Path in = Path.of(a[i]);
            if (Files.isDirectory(in)) {
                try (var s = Files.walk(in)) {
                    for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator)
                        emit(out, in.relativize(p).toString(), Files.readAllBytes(p));
                }
            } else {
                try (JarFile jf = new JarFile(in.toFile())) {
                    for (JarEntry e : Collections.list(jf.entries()))
                        if (!e.isDirectory()) emit(out, e.getName(), jf.getInputStream(e).readAllBytes());
                }
            }
        }
    }

    static void emit(Path out, String name, byte[] data) throws IOException {
        if (!name.endsWith(".class") || name.equals("module-info.class") || name.startsWith("META-INF/")) return;
        ClassReader cr = new ClassReader(data);
        ClassWriter cw = new ClassWriter(0);
        cr.accept(new ClassRemapper(cw, R), 0);
        Path dst = out.resolve(R.map(cr.getClassName()) + ".class");
        Files.createDirectories(dst.getParent());
        Files.write(dst, cw.toByteArray());
    }
}
