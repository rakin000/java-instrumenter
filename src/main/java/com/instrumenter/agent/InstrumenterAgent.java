package com.instrumenter.agent;

import com.instrumenter.core.BytecodeInstrumenter;
import com.instrumenter.transformers.MethodTracerVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * Java agent for runtime bytecode instrumentation.
 * Can be loaded with: java -javaagent:java-instrumenter-agent.jar MyApplication
 */
public class InstrumenterAgent {
    
    private static final Logger logger = LoggerFactory.getLogger(InstrumenterAgent.class);
    
    /**
     * Premain method called when agent is loaded with -javaagent flag.
     */
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        logger.info("Java Instrumenter Agent loaded");
        logger.info("Agent arguments: {}", agentArgs);
        
        // Register the class file transformer
        instrumentation.addTransformer(new MethodTracingTransformer(), false);
        
        logger.info("Method tracing instrumentation enabled");
    }
    
    /**
     * Agentmain method called when agent is attached dynamically.
     */
    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        logger.info("Java Instrumenter Agent attached dynamically");
        instrumentation.addTransformer(new MethodTracingTransformer());
    }
    
    /**
     * ClassFileTransformer that applies method tracing instrumentation.
     */
    private static class MethodTracingTransformer implements ClassFileTransformer {
        
        private final BytecodeInstrumenter instrumenter = 
            new BytecodeInstrumenter(org.objectweb.asm.Opcodes.ASM9);
        
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                              ProtectionDomain protectionDomain, byte[] classfileBuffer)
                throws IllegalClassFormatException {
            
            try {
                // Skip Java standard library and instrumenter classes
                if (shouldInstrument(className)) {
                    logger.debug("Instrumenting class: {}", className);
                    return instrumenter.instrument(classfileBuffer, 
                        (classWriter) -> new MethodTracerVisitor(classWriter));
                }
            } catch (Exception ex) {
                logger.error("Error instrumenting class: {}", className, ex);
            }
            
            return classfileBuffer;
        }
        
        private boolean shouldInstrument(String className) {
            // Skip Java internals and agent itself
            if (className == null) return false;
            if (className.startsWith("java/")) return false;
            if (className.startsWith("javax/")) return false;
            if (className.startsWith("sun/")) return false;
            if (className.startsWith("com/sun/")) return false;
            if (className.startsWith("com/instrumenter/")) return false;
            if (className.startsWith("org/slf4j/")) return false;
            if (className.startsWith("org/ow2/asm/")) return false;
            if (className.startsWith("ch/qos/logback/")) return false;
            
            return true;
        }
    }
}
