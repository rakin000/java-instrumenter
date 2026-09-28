package otrack;

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
    private final TargetMatcher matcher = new TargetMatcher();
    private final Set<String> instrumented = ConcurrentHashMap.newKeySet(); // internal names
    private volatile boolean active = true;
    private final boolean fieldsOn;
    private final Set<String> fieldNames; // null = every instance field

    Transformer(boolean fieldsOn, Set<String> fieldNames) {
        this.fieldsOn = fieldsOn;
        this.fieldNames = fieldNames;
    }

    void addTarget(Target t) {
        matcher.add(t);
    }

    /** From now on transform() returns null, so retransforming a class restores its original bytes. */
    void deactivate() {
        active = false;
        matcher.clear();
    }

    boolean matches(Class<?> c) {
        return matcher.matches(c);
    }

    boolean isInstrumented(Class<?> c) {
        return instrumented.contains(c.getName().replace('.', '/'));
    }

    /** Internal names of every class instrumented so far. */
    Set<String> instrumentedNames() {
        return Set.copyOf(instrumented);
    }

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain pd, byte[] bytes) {
        if (!active || name == null || loader == null || name.startsWith("otrack/")) return null;
        try {
            boolean match = redefined != null ? matcher.matches(redefined) : matcher.matches(loader, name, bytes);
            if (!match) return null;
            byte[] out = instrument(name, bytes);
            if (out != null && instrumented.add(name) && matcher.matchedByPackageLessName(name))
                Log.log("package-less name resolved to " + name.replace('/', '.'));
            return out;
        } catch (Throwable t) {
            Log.log("cannot instrument " + name + ": " + t);
            return null; // never break class loading
        }
    }

    private byte[] instrument(String name, byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        if ((cr.getAccess() & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0) return null;
        if (cr.readShort(6) < 49) return null; // ldc <class> needs class file version 49+
        int id = Tracker.register(name.replace('/', '.'));
        if (id < 0) {
            Log.log("too many tracked classes, skipping " + name);
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
