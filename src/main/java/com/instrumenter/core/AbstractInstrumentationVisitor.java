package com.instrumenter.core;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class for instrumentation visitors.
 * Provides common functionality for all bytecode transformations.
 */
public abstract class AbstractInstrumentationVisitor extends ClassVisitor {
    
    protected static final Logger logger = LoggerFactory.getLogger(AbstractInstrumentationVisitor.class);
    
    protected String className;
    
    public AbstractInstrumentationVisitor(ClassVisitor classVisitor) {
        super(Opcodes.ASM9, classVisitor);
    }
    
    @Override
    public void visit(int version, int access, String name, String signature, 
                      String superName, String[] interfaces) {
        this.className = name;
        logger.debug("Visiting class: {}", name);
        super.visit(version, access, name, signature, superName, interfaces);
    }
    
    /**
     * Create the appropriate MethodVisitor for this class's methods.
     */
    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, 
                                     String signature, String[] exceptions) {
        MethodVisitor methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);
        return createMethodVisitor(methodVisitor, access, name, descriptor);
    }
    
    /**
     * Subclasses should implement this to provide their specific method instrumentation.
     */
    protected abstract MethodVisitor createMethodVisitor(MethodVisitor methodVisitor, 
                                                         int access, String name, String descriptor);
    
    /**
     * Helper method to get a delegate ClassVisitor.
     */
    public ClassVisitor getDelegate(ClassVisitor classVisitor) {
        return new AbstractInstrumentationVisitor(classVisitor) {
            @Override
            protected MethodVisitor createMethodVisitor(MethodVisitor methodVisitor, 
                                                       int access, String name, String descriptor) {
                return methodVisitor;
            }
        };
    }
}
