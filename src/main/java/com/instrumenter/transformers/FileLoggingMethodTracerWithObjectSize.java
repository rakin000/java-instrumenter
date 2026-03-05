package com.instrumenter.transformers;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.instrumenter.core.AbstractInstrumentationVisitor;
import com.instrumenter.core.InstrumentationFilter;

/**
 * FileLoggingMethodTracerWithObjectSize instruments methods to log entry and exit points to a file,
 * and additionally logs the size of "this" object each time a field is written.
 * Uses SLF4J with logback to write logs to instrumentation.log file.
 */
public class FileLoggingMethodTracerWithObjectSize extends AbstractInstrumentationVisitor {
    
    private static final Logger logger = LoggerFactory.getLogger(FileLoggingMethodTracerWithObjectSize.class);
    
    public FileLoggingMethodTracerWithObjectSize(ClassVisitor classVisitor) {
        super(classVisitor);
    }
    
    public FileLoggingMethodTracerWithObjectSize(ClassVisitor classVisitor, InstrumentationFilter filter) {
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
        return new FileLoggingMethodTracingVisitorWithObjectSize(methodVisitor, className, name, descriptor, access, filter);
    }
    
    private boolean isSyntheticOrSpecial(int access, String name) {
        return (access & Opcodes.ACC_SYNTHETIC) != 0 || 
               "<clinit>".equals(name) ||
               "<init>".equals(name);
    }
    
    /**
     * Inner class that instruments individual methods with file logging and object size tracking.
     */
    public static class FileLoggingMethodTracingVisitorWithObjectSize extends MethodVisitor {
        
        private final String className;
        private final String methodName;
        private final String descriptor;
        private final int access;
        private final InstrumentationFilter filter;

        public FileLoggingMethodTracingVisitorWithObjectSize(MethodVisitor methodVisitor, String className, 
                                   String methodName, String descriptor, int access) {
            super(Opcodes.ASM9, methodVisitor);
            this.className = className;
            this.methodName = methodName;
            this.descriptor = descriptor;
            this.access = access;
            this.filter = null;
        }

        public FileLoggingMethodTracingVisitorWithObjectSize(MethodVisitor methodVisitor, String className, 
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
            if (filter != null && !filter.shouldInstrumentMethod(className, methodName, descriptor)) {
                super.visitCode();
                return;
            }
            
            // Call InstrumentationLogger.logEntry()
            visitLdcInsn("[ENTRY] " + className + "." + methodName + descriptor);
            visitMethodInsn(Opcodes.INVOKESTATIC, "com/instrumenter/util/InstrumentationLogger", "logEntry", 
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
                visitLdcInsn("[EXIT] " + className + "." + methodName + descriptor);
                visitMethodInsn(Opcodes.INVOKESTATIC, "com/instrumenter/util/InstrumentationLogger", "logExit", 
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

            if (opcode == Opcodes.GETFIELD) {
                // First, do the GETFIELD to get the value on the stack
                super.visitFieldInsn(opcode, owner, name, descriptor);
                // Now duplicate the value for logging
                visitInsn(Opcodes.DUP);
                // Box the value if primitive and convert to String
                boxAndConvertToString(descriptor);
                visitLdcInsn("[FIELD READ] " + owner + "." + name + " = ");
                visitInsn(Opcodes.SWAP);
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", 
                              "(Ljava/lang/String;)Ljava/lang/String;", false);
                visitMethodInsn(Opcodes.INVOKESTATIC, "com/instrumenter/util/InstrumentationLogger", "logFieldAccess", 
                              "(Ljava/lang/String;)V", false);
            } else if (opcode == Opcodes.PUTFIELD) {
                // Stack before: ..., objectref, value
                // DUP the value to keep a copy for logging
                visitInsn(Opcodes.DUP);
                // Box the value if primitive and convert to String
                boxAndConvertToString(descriptor);
                // Construct log message: "[FIELD WRITE] owner.name = value"
                visitLdcInsn("[FIELD WRITE] " + owner + "." + name + " = ");
                visitInsn(Opcodes.SWAP);
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", 
                              "(Ljava/lang/String;)Ljava/lang/String;", false);
                // Log the message
                visitMethodInsn(Opcodes.INVOKESTATIC, "com/instrumenter/util/InstrumentationLogger", "logFieldAccess", 
                              "(Ljava/lang/String;)V", false);
                
                // Now log the size of "this" object
                // Stack at this point: ..., objectref, value
                // SWAP to get objectref on top: ..., value, objectref
                visitInsn(Opcodes.SWAP);
                // DUP objectref to keep a copy for PUTFIELD: ..., value, objectref, objectref
                visitInsn(Opcodes.DUP);
                // Call logObjectSize with one copy: ..., value, objectref
                visitMethodInsn(Opcodes.INVOKESTATIC, "com/instrumenter/util/InstrumentationLogger", "logObjectSize", 
                              "(Ljava/lang/Object;)V", false);
                // SWAP back to original order: ..., objectref, value
                visitInsn(Opcodes.SWAP);
                
                // Now do the actual PUTFIELD with correct stack
                super.visitFieldInsn(opcode, owner, name, descriptor);
            } else {
                super.visitFieldInsn(opcode, owner, name, descriptor);
            }
        }
        
        private void boxAndConvertToString(String descriptor) {
            if (descriptor.length() == 1) {
                // Primitive type
                char type = descriptor.charAt(0);
                switch (type) {
                    case 'Z': // boolean
                        visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", 
                                      "(Z)Ljava/lang/Boolean;", false);
                        break;
                    case 'B': // byte
                        visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Byte", "valueOf", 
                                      "(B)Ljava/lang/Byte;", false);
                        break;
                    case 'C': // char
                        visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Character", "valueOf", 
                                      "(C)Ljava/lang/Character;", false);
                        break;
                    case 'S': // short
                        visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Short", "valueOf", 
                                      "(S)Ljava/lang/Short;", false);
                        break;
                    case 'I': // int
                        visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", 
                                      "(I)Ljava/lang/Integer;", false);
                        break;
                    case 'J': // long
                        visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", 
                                      "(J)Ljava/lang/Long;", false);
                        break;
                    case 'F': // float
                        visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", 
                                      "(F)Ljava/lang/Float;", false);
                        break;
                    case 'D': // double
                        visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", 
                                      "(D)Ljava/lang/Double;", false);
                        break;
                }
            }
            // For object types, just convert to String
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", 
                          "(Ljava/lang/Object;)Ljava/lang/String;", false);
        }

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
