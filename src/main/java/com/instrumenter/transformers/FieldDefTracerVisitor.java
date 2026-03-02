package com.instrumenter.transformers;

import com.instrumenter.core.AbstractInstrumentationVisitor;
import com.instrumenter.core.InstrumentationFilter;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FieldDefTracerVisitor instruments field definitions to log their details.
 * Useful for tracking field usage and understanding class structure.
 */
public class FieldDefTracerVisitor extends AbstractInstrumentationVisitor {
    
    
    private static final Logger logger = LoggerFactory.getLogger(FieldDefTracerVisitor.class);

    public FieldDefTracerVisitor(ClassVisitor classVisitor) {
        super(classVisitor);
    }
    
    public FieldDefTracerVisitor(ClassVisitor classVisitor, InstrumentationFilter filter) {
        super(classVisitor, filter);
    }
    
    @Override
    public FieldVisitor visitField(int access, String name, String descriptor, 
                                   String signature, Object value) {
        FieldVisitor fieldVisitor = super.visitField(access, name, descriptor, signature, value);
        
        // Skip synthetic fields
        if ((access & Opcodes.ACC_SYNTHETIC) != 0) {
            return fieldVisitor;
        }
        
        // Check filter
        if (!filter.shouldInstrumentField(className, name, descriptor)) {
            logger.debug("Skipping field (filtered): {}.{}{}", className, name, descriptor);
            return fieldVisitor;
        }

        logger.debug("Instrumenting field in: {}.{}{}", className, name, descriptor);
        return new FieldDefTracingVisitor(fieldVisitor, className, name, descriptor, access);
    }
    
    @Override
    protected MethodVisitor createMethodVisitor(MethodVisitor methodVisitor, 
                                                int access, String name, String descriptor) {
        // This class focuses on field instrumentation, so just return the base visitor
        return methodVisitor;
    }
    
    /**
     * Inner class that instruments individual fields.
     */
    private static class FieldDefTracingVisitor extends FieldVisitor {
        
        private final String className;
        private final String fieldName;
        private final String descriptor;
        private final int access;
        
        public FieldDefTracingVisitor(FieldVisitor fieldVisitor, String className, 
                                      String fieldName, String descriptor, int access) {
            super(Opcodes.ASM9, fieldVisitor);
            this.className = className;
            this.fieldName = fieldName;
            this.descriptor = descriptor;
            this.access = access;
        }
        
        @Override
        public void visitEnd() {
            // Here you can add instrumentation code to log field definitions
            // For example, you could log the field name and type
            if ((access & Opcodes.ACC_SYNTHETIC) == 0) {
                // Log field definition (this is just a placeholder, replace with actual logging)
                System.out.println("Field defined: " + this.access + " " + className + "." + fieldName + " : " + descriptor);
//               visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
//               visitLdcInsn("[EXIT] " + className + "." + fieldName + descriptor);
//               visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
         
                // instrument to log field value at runtime (this is just a placeholder, replace with actual instrumentation)

            }
            super.visitEnd();
        }



    }
    
}
