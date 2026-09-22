package otrack;

import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Appends a call to {@link Tracker#onNew} before every RETURN of every constructor that calls
 * super(...) (constructors delegating with this(...) are skipped so each object is seen once).
 * The transformer is stateless w.r.t. bytes: retransforming always starts from the original class,
 * so returning null (no longer a target) restores the class.
 *
 * With field tracking on, it also reports the values of the class's own instance fields: at the tail of
 * the constructor (one merged "new" event) and before every PUTFIELD of such a field made by the class's
 * own code. Fields of non-instrumented superclasses, and writes made by other classes, are not seen.
 */
final class Transformer implements ClassFileTransformer {
    private final Set<String> exact = ConcurrentHashMap.newKeySet();    // internal names
    private final Set<String> subtypes = ConcurrentHashMap.newKeySet(); // internal names, incl. their subtypes
    private final Set<String> simple = ConcurrentHashMap.newKeySet();         // package-less names, match in any package
    private final Set<String> simpleSubtypes = ConcurrentHashMap.newKeySet(); // same, incl. subtypes
    private final Map<String, Boolean> headerCache = new ConcurrentHashMap<>();
    final Set<String> instrumented = ConcurrentHashMap.newKeySet();
    private volatile boolean active = true;
    private final boolean fieldsOn;
    private final Set<String> fieldNames; // null = every instance field

    Transformer(boolean fieldsOn, Set<String> fieldNames) {
        this.fieldsOn = fieldsOn;
        this.fieldNames = fieldNames;
    }

    void addTarget(String dotted, boolean withSubtypes) {
        String n = dotted.replace('.', '/');
        exact.add(n);
        if (withSubtypes) subtypes.add(n);
        if (n.indexOf('/') < 0) { // no package given: also resolve against any package at load time
            simple.add(n);
            if (withSubtypes) simpleSubtypes.add(n);
        }
        headerCache.clear();
    }

    /**
     * True if the class part of {@code internal} equals an entry of {@code names}, either whole
     * ({@code Outer$Inner}) or as the innermost nested name ({@code Inner}). JDK types never match.
     */
    private static boolean simpleHit(Set<String> names, String internal) {
        if (names.isEmpty()) return false;
        if (internal.startsWith("java/") || internal.startsWith("javax/") || internal.startsWith("jdk/")
                || internal.startsWith("sun/") || internal.startsWith("com/sun/")) return false;
        String s = internal.substring(internal.lastIndexOf('/') + 1);
        if (names.contains(s)) return true;
        int d = s.lastIndexOf('$');
        return d >= 0 && d + 1 < s.length() && names.contains(s.substring(d + 1));
    }

    void deactivate() {
        active = false;
        exact.clear();
        subtypes.clear();
        simple.clear();
        simpleSubtypes.clear();
        headerCache.clear();
    }

    /** Match for an already loaded class, using reflection (no bytes needed). */
    boolean matches(Class<?> c) {
        String n = c.getName().replace('.', '/');
        if (exact.contains(n) || simpleHit(simple, n)) return true;
        return (!subtypes.isEmpty() || !simpleSubtypes.isEmpty()) && inHierarchy(c, 0);
    }

    private boolean inHierarchy(Class<?> c, int depth) {
        if (c == null || depth > 64) return false;
        String n = c.getName().replace('.', '/');
        if (subtypes.contains(n) || simpleHit(simpleSubtypes, n)) return true;
        if (inHierarchy(c.getSuperclass(), depth + 1)) return true;
        for (Class<?> i : c.getInterfaces()) if (inHierarchy(i, depth + 1)) return true;
        return false;
    }

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain pd, byte[] bytes) {
        if (!active || name == null || loader == null || name.startsWith("otrack/")) return null;
        try {
            boolean match = redefined != null ? matches(redefined)
                    : exact.contains(name) || simpleHit(simple, name)
                            || ((!subtypes.isEmpty() || !simpleSubtypes.isEmpty()) && headerMatches(loader, new ClassReader(bytes)));
            if (!match) return null;
            byte[] out = instrument(name, bytes);
            if (out != null && instrumented.add(name) && !exact.contains(name) && simpleHit(simple, name))
                Controller.log("package-less name resolved to " + name.replace('/', '.'));
            return out;
        } catch (Throwable t) {
            Controller.log("cannot instrument " + name + ": " + t);
            return null; // never break class loading
        }
    }

    private boolean headerMatches(ClassLoader loader, ClassReader cr) {
        if (superMatches(loader, cr.getSuperName(), 0)) return true;
        for (String i : cr.getInterfaces()) if (superMatches(loader, i, 0)) return true;
        return false;
    }

    /** Walks supertypes by reading .class resources, never loading classes. JDK types are not traversed. */
    private boolean superMatches(ClassLoader loader, String n, int depth) {
        if (n == null || depth > 64) return false;
        if (subtypes.contains(n) || simpleHit(simpleSubtypes, n)) return true;
        if (n.startsWith("java/") || n.startsWith("javax/") || n.startsWith("jdk/") || n.startsWith("sun/")) return false;
        Boolean cached = headerCache.get(n);
        if (cached != null) return cached;
        boolean r = false;
        try (InputStream in = loader.getResourceAsStream(n + ".class")) {
            if (in != null) r = headerMatches(loader, new ClassReader(in));
        } catch (Throwable ignored) {
        }
        headerCache.put(n, r);
        return r;
    }

    private byte[] instrument(String name, byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        if ((cr.getAccess() & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0) return null;
        if (cr.readShort(6) < 49) return null; // ldc <class> needs class file version 49+
        int id = Tracker.register(name.replace('/', '.'));
        if (id < 0) {
            Controller.log("too many tracked classes, skipping " + name);
            return null;
        }
        Map<String, String> fields = new LinkedHashMap<>(); // tracked instance fields: name -> descriptor
        Map<String, Integer> maxLocals = new HashMap<>();   // name+desc -> max_locals of the original code
        if (fieldsOn) {
            cr.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public FieldVisitor visitField(int acc, String fname, String desc, String sig, Object value) {
                    if ((acc & (Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC)) == 0
                            && (fieldNames == null || fieldNames.contains(fname))) fields.put(fname, desc);
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(int acc, String mname, String desc, String sig, String[] exc) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMaxs(int maxStack, int locals) {
                            maxLocals.put(mname + desc, locals);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS); // no branches added -> frames stay valid
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int acc, String mname, String desc, String sig, String[] exc) {
                MethodVisitor mv = super.visitMethod(acc, mname, desc, sig, exc);
                if (mv == null) return null;
                // Temp locals for PUTFIELD hooks start at the original max_locals, so no frame mentions them.
                int scratch = fieldsOn ? maxLocals.getOrDefault(mname + desc, -1) : -1;
                if (mname.equals("<init>")) return new CtorVisitor(mv, name, id, fields, fieldsOn, scratch);
                return scratch < 0 || fields.isEmpty() ? mv : new PutFieldVisitor(mv, name, fields, scratch, true);
            }
        }, 0);
        return cw.toByteArray();
    }

    /** Type-specific overload suffix understood by Tracker.onInit/onSet. */
    private static String hookType(Type t) {
        return switch (t.getSort()) {
            case Type.BOOLEAN -> "Z";
            case Type.CHAR -> "C";
            case Type.BYTE, Type.SHORT, Type.INT -> "I";
            case Type.LONG -> "J";
            case Type.FLOAT -> "F";
            case Type.DOUBLE -> "D";
            default -> "Ljava/lang/Object;";
        };
    }

    /** Reports the new value of tracked fields before each PUTFIELD: {@code Tracker.onSet(obj, value, name)}. */
    private static class PutFieldVisitor extends MethodVisitor {
        private final String owner;
        private final Map<String, String> fields;
        private final int scratch;
        boolean armed; // false while `this` is still uninitialized in a constructor

        PutFieldVisitor(MethodVisitor mv, String owner, Map<String, String> fields, int scratch, boolean armed) {
            super(Opcodes.ASM9, mv);
            this.owner = owner;
            this.fields = fields;
            this.scratch = scratch;
            this.armed = armed;
        }

        @Override
        public void visitFieldInsn(int op, String o, String n, String d) {
            if (op == Opcodes.PUTFIELD && armed && scratch >= 0 && o.equals(owner) && d.equals(fields.get(n))) {
                Type t = Type.getType(d);
                super.visitVarInsn(t.getOpcode(Opcodes.ISTORE), scratch); // objref
                super.visitInsn(Opcodes.DUP);                             // objref objref
                super.visitVarInsn(t.getOpcode(Opcodes.ILOAD), scratch);  // objref objref value
                super.visitLdcInsn(n);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, "otrack/Tracker", "onSet",
                        "(Ljava/lang/Object;" + hookType(t) + "Ljava/lang/String;)V", false); // objref
                super.visitVarInsn(t.getOpcode(Opcodes.ILOAD), scratch);  // objref value
            }
            super.visitFieldInsn(op, o, n, d);
        }
    }

    private static final class CtorVisitor extends PutFieldVisitor {
        private final String owner;
        private final int id;
        private final Map<String, String> fields;
        private final boolean fieldsOn;
        private int pendingNew;
        private boolean delegating;

        CtorVisitor(MethodVisitor mv, String owner, int id, Map<String, String> fields, boolean fieldsOn, int scratch) {
            super(mv, owner, fields, scratch, false);
            this.owner = owner;
            this.id = id;
            this.fields = fields;
            this.fieldsOn = fieldsOn;
        }

        @Override
        public void visitTypeInsn(int op, String type) {
            if (op == Opcodes.NEW) pendingNew++;
            super.visitTypeInsn(op, type);
        }

        @Override
        public void visitMethodInsn(int op, String o, String n, String d, boolean itf) {
            if (op == Opcodes.INVOKESPECIAL && n.equals("<init>")) {
                if (pendingNew > 0) pendingNew--;          // constructing some other object
                else {
                    if (o.equals(owner)) delegating = true; // this(...)
                    armed = true;                           // `this` is initialized from here on
                }
            }
            super.visitMethodInsn(op, o, n, d, itf);
        }

        @Override
        public void visitInsn(int op) {
            if (op == Opcodes.RETURN && !delegating) {
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitLdcInsn(Type.getObjectType(owner));
                super.visitLdcInsn(id);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, "otrack/Tracker", "onNew",
                        "(Ljava/lang/Object;Ljava/lang/Class;I)V", false);
                if (fieldsOn) {
                    for (Map.Entry<String, String> f : fields.entrySet()) {
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitFieldInsn(Opcodes.GETFIELD, owner, f.getKey(), f.getValue());
                        super.visitLdcInsn(f.getKey());
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "otrack/Tracker", "onInit",
                                "(Ljava/lang/Object;" + hookType(Type.getType(f.getValue())) + "Ljava/lang/String;)V", false);
                    }
                    super.visitVarInsn(Opcodes.ALOAD, 0);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "otrack/Tracker", "onInitDone",
                            "(Ljava/lang/Object;)V", false);
                }
            }
            super.visitInsn(op);
        }
    }
}
