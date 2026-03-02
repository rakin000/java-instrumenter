package com.instrumenter.transformers;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.instrumenter.core.AbstractInstrumentationVisitor;

public class MethodTracerVisitorWithFilter extends AbstractInstrumentationVisitor {
    
    private static final Logger logger = LoggerFactory.getLogger(MethodTracerVisitorWithFilter.class);
    
    public MethodTracerVisitorWithFilter(ClassVisitor classVisitor) {
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
        return new MethodTracingVisitorWithFilter(methodVisitor, className, name, descriptor, access);
    }
    
    private boolean isSyntheticOrSpecial(int access, String name) {
        return (access & Opcodes.ACC_SYNTHETIC) != 0 || 
               "<clinit>".equals(name) ||
               "<init>".equals(name);
    }
    
    private static class MethodTracingVisitorWithFilter extends MethodVisitor {
        
        private final String className;
        private final String methodName;
        private final String descriptor;
        private final int access;
        
        public MethodTracingVisitorWithFilter(MethodVisitor methodVisitor, String className, 
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
            if (OpcodeUtils.isReturnOpcode(opcode)) {
                visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                visitLdcInsn("[EXIT] " + className + "." + methodName + descriptor);
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", 
                              "(Ljava/lang/String;)V", false);
            }
            
            super.visitInsn(opcode);
        }
        
        @Override 
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            // Optionally, you could add logging for field accesses here
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
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }


        public void visitVarInsn(int opcode, int var) {
            if (OpcodeUtils.isLoad(opcode)) {
                visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                visitLdcInsn("[VAR LOAD] " + className + "." + methodName + descriptor + " - Loading variable index: " + var);
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", 
                              "(Ljava/lang/String;)V", false);
            } else if (OpcodeUtils.isStore(opcode)) {
                visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                visitLdcInsn("[VAR STORE] " + className + "." + methodName + descriptor + " - Storing variable index: " + var);
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", 
                              "(Ljava/lang/String;)V", false);
            } 
            super.visitVarInsn(opcode, var);
        }
     
    }
}
