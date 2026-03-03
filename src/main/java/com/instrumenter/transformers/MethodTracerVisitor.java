package com.instrumenter.transformers;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.instrumenter.core.AbstractInstrumentationVisitor;
import com.instrumenter.core.InstrumentationFilter;

/**
 * MethodTracer instruments methods to log entry and exit points.
 * Useful for method call tracing and performance analysis.
 */
public class MethodTracerVisitor extends AbstractInstrumentationVisitor {
    
    private static final Logger logger = LoggerFactory.getLogger(MethodTracerVisitor.class);
    
    public MethodTracerVisitor(ClassVisitor classVisitor) {
        super(classVisitor);
    }
    
    public MethodTracerVisitor(ClassVisitor classVisitor, InstrumentationFilter filter) {
        super(classVisitor, filter);
    }
    
    @Override
    protected MethodVisitor createMethodVisitor(MethodVisitor methodVisitor, 
                                               int access, String name, String descriptor) {
        // Skip synthetic methods and the static initializer
        if (isSyntheticOrSpecial(access, name)) {
            return methodVisitor;
        }
        
        // Check filter
        if (!filter.shouldInstrumentMethod(className, name, descriptor)) {
            logger.debug("Skipping method (filtered): {}.{}{}", className, name, descriptor);
            return methodVisitor;
        }
        
        logger.debug("Instrumenting method: {}.{}{}", className, name, descriptor);
        return new MethodTracingVisitor(methodVisitor, className, name, descriptor, access, filter);
    }
    
    private boolean isSyntheticOrSpecial(int access, String name) {
        return (access & Opcodes.ACC_SYNTHETIC) != 0 || 
               "<clinit>".equals(name) ||
               "<init>".equals(name);
    }
    
    /**
     * Inner class that instruments individual methods.
     */
    public static class MethodTracingVisitor extends MethodVisitor {
        
        private final String className;
        private final String methodName;
        private final String descriptor;
        private final int access;
        private final InstrumentationFilter filter; 

        public MethodTracingVisitor(MethodVisitor methodVisitor, String className, 
                                   String methodName, String descriptor, int access) {
            super(Opcodes.ASM9, methodVisitor);
            this.className = className;
            this.methodName = methodName;
            this.descriptor = descriptor;
            this.access = access;
            this.filter = null;
        }

        public MethodTracingVisitor(MethodVisitor methodVisitor, String className, 
                                   String methodName, String descriptor, int access, InstrumentationFilter filter) {
            super(Opcodes.ASM9, methodVisitor);
            this.className = className;
            this.methodName = methodName;
            this.descriptor = descriptor;
            this.access = access;
            this.filter = filter;
        }
        

        @Override
        public void visitCode() {
            // Add entry logging 
            if (filter != null && !filter.shouldInstrumentMethod(className, methodName, descriptor)) {
                super.visitCode();
                return;
            }
            visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            visitLdcInsn("[ENTRY] " + className + "." + methodName + descriptor);
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", 
                          "(Ljava/lang/String;)V", false);
            
            super.visitCode();
        }
        
        @Override
        public void visitInsn(int opcode) {
            // Add exit logging for return instructions
            if (filter != null && !filter.shouldInstrumentMethod(className, methodName, descriptor)) {
                super.visitInsn(opcode);
                return;
            }
            if (isReturnOpcode(opcode)) {
                visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                visitLdcInsn("[EXIT] " + className + "." + methodName + descriptor);
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", 
                              "(Ljava/lang/String;)V", false);
            }
            
            super.visitInsn(opcode);
        }
        
        @Override 
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            
            if( filter != null && !filter.shouldInstrumentField(owner, name, descriptor)) {
                super.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }

            if (opcode == Opcodes.GETFIELD ) {
                visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                visitLdcInsn("[FIELD READ] " + className + "." + methodName + descriptor + " - Accessing field: " + owner + "." + name);
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", 
                              "(Ljava/lang/String;)V", false);
            } else if (opcode == Opcodes.PUTFIELD) {
                visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                visitLdcInsn("[FIELD WRITE] " + className + "." + methodName + descriptor + " - Modifying field: " + owner + "." + name);
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", 
                              "(Ljava/lang/String;)V", false);
            }
            //  else if (opcode == Opcodes.GETSTATIC) {
            //     visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            //     visitLdcInsn("[STATIC FIELD READ] " + className + "." + methodName + descriptor + " - Accessing static field: " + owner + "." + name);
            //     visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", 
            //                   "(Ljava/lang/String;)V", false);
            // } else if (opcode == Opcodes.PUTSTATIC) {
            //     visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            //     visitLdcInsn("[STATIC FIELD WRITE] " + className + "." + methodName + descriptor + " - Modifying static field: " + owner + "." + name);
            //     visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", 
            //                   "(Ljava/lang/String;)V", false);
            // } 

            super.visitFieldInsn(opcode, owner, name, descriptor);
        }


        // public void visitVarInsn(int opcode, int var) {
        //     // Optionally, you could add logging for variable loads/stores here
        //     if (isLoad(opcode)) {
        //         visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        //         visitLdcInsn("[VAR LOAD] " + className + "." + methodName + descriptor + " - Loading variable index: " + var);
        //         visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", 
        //                       "(Ljava/lang/String;)V", false);
        //     } else if (isStore(opcode)) {
        //         visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        //         visitLdcInsn("[VAR STORE] " + className + "." + methodName + descriptor + " - Storing variable index: " + var);
        //         visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", 
        //                       "(Ljava/lang/String;)V", false);
        //     } 

        //     super.visitVarInsn(opcode, var);
        // }

        private boolean isReturnOpcode(int opcode) {
            return opcode == Opcodes.RETURN ||
                   opcode == Opcodes.IRETURN ||
                   opcode == Opcodes.LRETURN ||
                   opcode == Opcodes.FRETURN ||
                   opcode == Opcodes.DRETURN ||
                   opcode == Opcodes.ARETURN;
        }

        private boolean isStore(int opcode) {
            return opcode == Opcodes.ISTORE ||
                opcode == Opcodes.LSTORE ||
                opcode == Opcodes.FSTORE ||
                opcode == Opcodes.DSTORE ||
                opcode == Opcodes.ASTORE;
        }

        private boolean isLoad(int opcode) {
            return opcode == Opcodes.ILOAD ||
                opcode == Opcodes.LLOAD ||
                opcode == Opcodes.FLOAD ||
                opcode == Opcodes.DLOAD ||
                opcode == Opcodes.ALOAD;
        }
    }
}
