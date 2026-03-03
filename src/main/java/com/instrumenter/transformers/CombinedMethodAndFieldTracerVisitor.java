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
 * Combined visitor that instruments both methods and fields.
 * Useful when you want to trace both method calls and field access.
 */
public class CombinedMethodAndFieldTracerVisitor extends AbstractInstrumentationVisitor {
    
    private static final Logger logger = LoggerFactory.getLogger(CombinedMethodAndFieldTracerVisitor.class);
    
    public CombinedMethodAndFieldTracerVisitor(ClassVisitor classVisitor) {
        super(classVisitor);
    }
    
    public CombinedMethodAndFieldTracerVisitor(ClassVisitor classVisitor, InstrumentationFilter filter) {
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

        logger.debug("Instrumenting field: {}.{}{}", className, name, descriptor);
        return new FieldDefTracerVisitor.FieldDefTracingVisitor(fieldVisitor, className, name, descriptor, access);
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
        return new MethodTracerVisitor.MethodTracingVisitor(methodVisitor, className, name, descriptor, access);
    }
    
    private boolean isSyntheticOrSpecial(int access, String name) {
        return (access & Opcodes.ACC_SYNTHETIC) != 0 || 
               "<clinit>".equals(name) ||
               "<init>".equals(name);
    }
}
