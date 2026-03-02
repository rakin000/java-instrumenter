package com.instrumenter.transformers;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import com.instrumenter.core.AbstractInstrumentationVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MethodTracer instruments methods to log entry and exit points.
 * Useful for method call tracing and performance analysis.
 */
public class MethodTracerVisitor extends AbstractInstrumentationVisitor {
    
    private static final Logger logger = LoggerFactory.getLogger(MethodTracerVisitor.class);
    
    public MethodTracerVisitor(ClassVisitor classVisitor) {
        super(classVisitor);
    }
    
    @Override
    protected MethodVisitor createMethodVisitor(MethodVisitor methodVisitor, 
                                               int access, String name, String descriptor) {
        // Skip synthetic methods and the static initializer
        if (isSyntheticOrSpecial(access, name)) {
            return methodVisitor;
        }
        
        logger.debug("Instrumenting method: {}.{}{}", className, name, descriptor);
        return new MethodTracingVisitor(methodVisitor, className, name, descriptor, access);
    }
    
    private boolean isSyntheticOrSpecial(int access, String name) {
        return (access & Opcodes.ACC_SYNTHETIC) != 0 || 
               "<clinit>".equals(name) ||
               "<init>".equals(name);
    }
    
    /**
     * Inner class that instruments individual methods.
     */
    private static class MethodTracingVisitor extends MethodVisitor {
        
        private final String className;
        private final String methodName;
        private final String descriptor;
        private final int access;
        
        public MethodTracingVisitor(MethodVisitor methodVisitor, String className, 
                                   String methodName, String descriptor, int access) {
            super(Opcodes.ASM9, methodVisitor);
            this.className = className;
            this.methodName = methodName;
            this.descriptor = descriptor;
            this.access = access;
        }
        
        @Override
        public void visitCode() {
            // Add entry logging
            visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            visitLdcInsn("[ENTRY] " + className + "." + methodName + descriptor);
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", 
                          "(Ljava/lang/String;)V", false);
            
            super.visitCode();
        }
        
        @Override
        public void visitInsn(int opcode) {
            // Add exit logging for return instructions
            if (isReturnOpcode(opcode)) {
                visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                visitLdcInsn("[EXIT] " + className + "." + methodName + descriptor);
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", 
                              "(Ljava/lang/String;)V", false);
            }
            
            super.visitInsn(opcode);
        }
        
        private boolean isReturnOpcode(int opcode) {
            return opcode == Opcodes.RETURN ||
                   opcode == Opcodes.IRETURN ||
                   opcode == Opcodes.LRETURN ||
                   opcode == Opcodes.FRETURN ||
                   opcode == Opcodes.DRETURN ||
                   opcode == Opcodes.ARETURN;
        }
    }
}
