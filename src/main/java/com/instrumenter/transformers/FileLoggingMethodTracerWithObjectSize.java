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

            boolean isLongOrDouble = descriptor.equals("J") || descriptor.equals("D");
            int tempVarIndex = 100; // Use a high index to avoid conflicts

            if (opcode == Opcodes.GETFIELD) {
                // Do the GETFIELD to get the value on the stack
                super.visitFieldInsn(opcode, owner, name, descriptor);
                
                if (isLongOrDouble) {
                    // For long/double: store to temp variable, log, then restore
                    // Stack before: ..., long_value (2 slots)
                    if (descriptor.equals("J")) {
                        visitVarInsn(Opcodes.LSTORE, tempVarIndex);  // Stack: ...
                        visitVarInsn(Opcodes.LLOAD, tempVarIndex);   // Stack: ..., long_value
                        // Now log the value
                        visitVarInsn(Opcodes.LLOAD, tempVarIndex);   // Stack: ..., long_value, long_value
                        visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", 
                                      "(J)Ljava/lang/Long;", false);  // Stack: ..., long_value, Long
                        visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", 
                                      "(Ljava/lang/Object;)Ljava/lang/String;", false);  // Stack: ..., long_value, String
                        visitLdcInsn("[FIELD READ] " + owner + "." + name + " = ");      // Stack: ..., long_value, String, String
                        visitInsn(Opcodes.SWAP);                    // Stack: ..., long_value, String, String
                        visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", 
                                      "(Ljava/lang/String;)Ljava/lang/String;", false);  // Stack: ..., long_value, String
                        visitMethodInsn(Opcodes.INVOKESTATIC, "com/instrumenter/util/InstrumentationLogger", "logFieldAccess", 
                                      "(Ljava/lang/String;)V", false);  // Stack: ..., long_value
                    } else {
                        visitVarInsn(Opcodes.DSTORE, tempVarIndex);  // Stack: ...
                        visitVarInsn(Opcodes.DLOAD, tempVarIndex);   // Stack: ..., double_value
                        // Now log the value
                        visitVarInsn(Opcodes.DLOAD, tempVarIndex);   // Stack: ..., double_value, double_value
                        visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", 
                                      "(D)Ljava/lang/Double;", false);  // Stack: ..., double_value, Double
                        visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", 
                                      "(Ljava/lang/Object;)Ljava/lang/String;", false);  // Stack: ..., double_value, String
                        visitLdcInsn("[FIELD READ] " + owner + "." + name + " = ");      // Stack: ..., double_value, String, String
                        visitInsn(Opcodes.SWAP);                    // Stack: ..., double_value, String, String
                        visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", 
                                      "(Ljava/lang/String;)Ljava/lang/String;", false);  // Stack: ..., double_value, String
                        visitMethodInsn(Opcodes.INVOKESTATIC, "com/instrumenter/util/InstrumentationLogger", "logFieldAccess", 
                                      "(Ljava/lang/String;)V", false);  // Stack: ..., double_value
                    }
                } else {
                    // For other types: simple DUP and log
                    visitInsn(Opcodes.DUP);
                    boxAndConvertToString(descriptor);
                    visitLdcInsn("[FIELD READ] " + owner + "." + name + " = ");
                    visitInsn(Opcodes.SWAP);
                    visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", 
                                  "(Ljava/lang/String;)Ljava/lang/String;", false);
                    visitMethodInsn(Opcodes.INVOKESTATIC, "com/instrumenter/util/InstrumentationLogger", "logFieldAccess", 
                                  "(Ljava/lang/String;)V", false);
                }
            } else if (opcode == Opcodes.PUTFIELD) {
                // Stack before: ..., objectref, value
                if (isLongOrDouble) {
                    // Store objectref and value to temporary variables
                    if (descriptor.equals("J")) {
                        visitVarInsn(Opcodes.LSTORE, tempVarIndex);      // Stack: ..., objectref
                        visitVarInsn(Opcodes.ASTORE, tempVarIndex + 2);  // Stack: ...
                        
                        // Log the value
                        visitVarInsn(Opcodes.LLOAD, tempVarIndex);       // Stack: ..., long_value
                        visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", 
                                      "(J)Ljava/lang/Long;", false);     // Stack: ..., Long
                        visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", 
                                      "(Ljava/lang/Object;)Ljava/lang/String;", false);  // Stack: ..., String
                        visitLdcInsn("[FIELD WRITE] " + owner + "." + name + " = ");     // Stack: ..., String, String
                        visitInsn(Opcodes.SWAP);                         // Stack: ..., String, String
                        visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", 
                                      "(Ljava/lang/String;)Ljava/lang/String;", false);  // Stack: ..., String
                        visitMethodInsn(Opcodes.INVOKESTATIC, "com/instrumenter/util/InstrumentationLogger", "logFieldAccess", 
                                      "(Ljava/lang/String;)V", false);   // Stack: ...
                        
                        // Log object size and restore for PUTFIELD
                        visitVarInsn(Opcodes.ALOAD, tempVarIndex + 2);   // Stack: ..., objectref
                        visitInsn(Opcodes.DUP);                          // Stack: ..., objectref, objectref
                        visitMethodInsn(Opcodes.INVOKESTATIC, "com/instrumenter/util/InstrumentationLogger", "logObjectSize", 
                                      "(Ljava/lang/Object;)V", false);   // Stack: ..., objectref
                        
                        // Restore and do PUTFIELD
                        visitVarInsn(Opcodes.LLOAD, tempVarIndex);       // Stack: ..., objectref, long_value
                        super.visitFieldInsn(opcode, owner, name, descriptor);
                    } else {
                        visitVarInsn(Opcodes.DSTORE, tempVarIndex);      // Stack: ..., objectref
                        visitVarInsn(Opcodes.ASTORE, tempVarIndex + 2);  // Stack: ...
                        
                        // Log the value
                        visitVarInsn(Opcodes.DLOAD, tempVarIndex);       // Stack: ..., double_value
                        visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", 
                                      "(D)Ljava/lang/Double;", false);   // Stack: ..., Double
                        visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", 
                                      "(Ljava/lang/Object;)Ljava/lang/String;", false);  // Stack: ..., String
                        visitLdcInsn("[FIELD WRITE] " + owner + "." + name + " = ");     // Stack: ..., String, String
                        visitInsn(Opcodes.SWAP);                         // Stack: ..., String, String
                        visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", 
                                      "(Ljava/lang/String;)Ljava/lang/String;", false);  // Stack: ..., String
                        visitMethodInsn(Opcodes.INVOKESTATIC, "com/instrumenter/util/InstrumentationLogger", "logFieldAccess", 
                                      "(Ljava/lang/String;)V", false);   // Stack: ...
                        
                        // Log object size and restore for PUTFIELD
                        visitVarInsn(Opcodes.ALOAD, tempVarIndex + 2);   // Stack: ..., objectref
                        visitInsn(Opcodes.DUP);                          // Stack: ..., objectref, objectref
                        visitMethodInsn(Opcodes.INVOKESTATIC, "com/instrumenter/util/InstrumentationLogger", "logObjectSize", 
                                      "(Ljava/lang/Object;)V", false);   // Stack: ..., objectref
                        
                        // Restore and do PUTFIELD
                        visitVarInsn(Opcodes.DLOAD, tempVarIndex);       // Stack: ..., objectref, double_value
                        super.visitFieldInsn(opcode, owner, name, descriptor);
                    }
                } else {
                    // For non-long/double types: simple DUP and log
                    visitInsn(Opcodes.DUP);
                    boxAndConvertToString(descriptor);
                    visitLdcInsn("[FIELD WRITE] " + owner + "." + name + " = ");
                    visitInsn(Opcodes.SWAP);
                    visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", 
                                  "(Ljava/lang/String;)Ljava/lang/String;", false);
                    visitMethodInsn(Opcodes.INVOKESTATIC, "com/instrumenter/util/InstrumentationLogger", "logFieldAccess", 
                                  "(Ljava/lang/String;)V", false);
                    
                    // Log object size
                    // Stack: ..., objectref, value
                    visitInsn(Opcodes.SWAP);  // Stack: ..., value, objectref
                    visitInsn(Opcodes.DUP);   // Stack: ..., value, objectref, objectref
                    visitMethodInsn(Opcodes.INVOKESTATIC, "com/instrumenter/util/InstrumentationLogger", "logObjectSize", 
                                  "(Ljava/lang/Object;)V", false);  // Stack: ..., value, objectref
                    visitInsn(Opcodes.SWAP);  // Stack: ..., objectref, value
                    
                    // Do the actual PUTFIELD
                    super.visitFieldInsn(opcode, owner, name, descriptor);
                }
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
