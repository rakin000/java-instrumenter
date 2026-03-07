package com.instrumenter.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.instrumenter.core.BytecodeInstrumenter;
import com.instrumenter.core.InstrumentationFilter;
import com.instrumenter.core.PatternBasedInstrumentationFilter;
import com.instrumenter.transformers.FieldDefTracerVisitor;
import com.instrumenter.transformers.MethodTracerVisitor; 
import com.instrumenter.transformers.FileLoggingMethodTracerVisitor;
import com.instrumenter.transformers.FileLoggingMethodTracerWithObjectSize;
import com.instrumenter.util.InstrumentationLogger;

/**
 * Java agent for runtime bytecode instrumentation.
 * Can be loaded with: java -javaagent:java-instrumenter-agent.jar MyApplication
 */
public class InstrumenterAgent {
    
    //private static final Logger logger = LoggerFactory.getLogger(InstrumenterAgent.class);
    private static final Logger logger = LoggerFactory.getLogger("Instrumentation");
    
    /**
     * Premain method called when agent is loaded with -javaagent flag.
     */
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        // Set the Instrumentation instance for use in InstrumentationLogger
        InstrumentationLogger.setInstrumentation(instrumentation);

        
        
        logger.info("Java Instrumenter Agent loaded");
        logger.info("Agent arguments: {}", agentArgs); 
        
        if (agentArgs == null ) {
            instrumentation.addTransformer(new MethodTracingTransformer(), false);
            logger.info("Method tracing instrumentation enabled");  
        } 
        else {
            String[] args = agentArgs.split(",");
            InstrumentationFilter filter = parseFilterArguments(args); 
            logger.info("Using instrumentation filter: {}", filter);
            instrumentation.addTransformer(new MethodTracingTransformer(filter), false);
            logger.info("Method tracing instrumentation enabled with filter: {}", agentArgs);
        }
        
        // Inject instrumentation into JOL's InstrumentationSupport so GraphLayout.parseInstance()
        // works correctly without needing dynamic attach or -Djdk.attach.allowAttachSelf
        // try {
        //     Class<?> jolSupport = Class.forName("org.openjdk.jol.vm.InstrumentationSupport");
        //     java.lang.reflect.Method jolPremain = jolSupport.getDeclaredMethod("premain", String.class, Instrumentation.class);
        //     jolPremain.setAccessible(true);
        //     jolPremain.invoke(null, null, instrumentation);
        //     logger.info("JOL instrumentation injected successfully");
        // } catch (Exception e) {
        //     logger.warn("Could not inject instrumentation into JOL (deep size will use fallback): {}", e.getMessage());
        // }
    }
    
    /**
     * Agentmain method called when agent is attached dynamically.
     */
    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        // Set the Instrumentation instance for use in InstrumentationLogger
        InstrumentationLogger.setInstrumentation(instrumentation);

       
        logger.info("Java Instrumenter Agent attached dynamically");
        logger.info("Agent arguments: {}", agentArgs);

        if (agentArgs == null ) {
            instrumentation.addTransformer(new MethodTracingTransformer(), false);
            logger.info("Method tracing instrumentation enabled");  
        } 
        else {
            String[] args = agentArgs.split(",") ;
            InstrumentationFilter filter = parseFilterArguments(args); 
            instrumentation.addTransformer(new MethodTracingTransformer(filter), false);
            logger.info("Method tracing instrumentation enabled with filter: {}", agentArgs);
        }

        // Inject instrumentation into JOL's InstrumentationSupport
        // try {
        //     Class<?> jolSupport = Class.forName("org.openjdk.jol.vm.InstrumentationSupport");
        //     java.lang.reflect.Method jolPremain = jolSupport.getDeclaredMethod("agentmain", String.class, Instrumentation.class);
        //     jolPremain.setAccessible(true);
        //     jolPremain.invoke(null, null, instrumentation);
        //     logger.info("JOL instrumentation injected successfully");
        // } catch (Exception e) {
        //     logger.warn("Could not inject instrumentation into JOL (deep size will use fallback): {}", e.getMessage());
        // }
 
    }
    

    private static InstrumentationFilter parseFilterArguments(String[] args) {
        if (args.length < 1) {
            return defaultRuntimeFilter();
        }
      //  logger.debug("Parsing filter arguments: {}", String.join(" ", args));
        PatternBasedInstrumentationFilter filter = defaultExclude(); // new PatternBasedInstrumentationFilter(); // Start with empty patterns, will add based on args
        
        for (String arg: args) {
//            logger.debug("Processing argument: {}", arg);
            String splitArgs[] = arg.split("=");
            String option = splitArgs[0]; 
            
            if (splitArgs.length < 2) {
                logger.warn("Filter option '{}' requires a pattern argument", arg);
                continue;
            }
            
            String pattern = splitArgs[1];
           
 //           logger.debug("Processing filter argument: {} with pattern: {}", arg, pattern);
            switch (option) {
                case "--include-class":
                    filter.includeClass(pattern);
                    logger.debug("Added include class pattern: {}", pattern);
                    break;
                case "--exclude-class":
                    filter.excludeClass(pattern);
                    logger.debug("Added exclude class pattern: {}", pattern);
                    break;
                case "--include-method":
                    filter.includeMethod(pattern);
                    logger.debug("Added include method pattern: {}", pattern);
                    break;
                case "--exclude-method":
                    filter.excludeMethod(pattern);
                    logger.debug("Added exclude method pattern: {}", pattern);
                    break;
                case "--include-field":
                    filter.includeField(pattern);
                    logger.debug("Added include field pattern: {}", pattern);
                    break;
                case "--exclude-field":
                    filter.excludeField(pattern);
                    logger.debug("Added exclude field pattern: {}", pattern);
                    break;
                default:
                    logger.warn("Unknown filter option: {}", arg);
            }
        }
        
        return filter; 
    } 


    private static PatternBasedInstrumentationFilter defaultExclude() { 
        return new PatternBasedInstrumentationFilter()
            .excludeClass("java/.*") 
            .excludeClass("javax/.*")
            .excludeClass("sun/.*")
            .excludeClass("com/sun/.*")
            .excludeClass("com/instrumenter/.*")
            .excludeClass("org/slf4j/.*")
            .excludeClass("org/ow2/asm/.*")
            .excludeClass("ch/qos/logback/.*")
            .excludeClass("jdk/.*");
    }
    private static InstrumentationFilter defaultRuntimeFilter() {
        return new PatternBasedInstrumentationFilter()
            .allClass()
            .allMethod()
            .allField()
            .excludeClass("java/.*") 
            .excludeClass("javax/.*")
            .excludeClass("sun/.*")
            .excludeClass("com/sun/.*")
            .excludeClass("com/instrumenter/.*")
            .excludeClass("org/slf4j/.*")
            .excludeClass("org/ow2/asm/.*")
            .excludeClass("ch/qos/logback/.*");
    }
   
    private static void printUsage() {
        System.out.println("Usage: java -javaagent:java-instrumenter-agent.jar=filterOptions MyApplication");
        System.out.println("Filter options:");
        System.out.println("  --include-class <pattern>   Include classes matching the regex pattern");
        System.out.println("  --exclude-class <pattern>   Exclude classes matching the regex pattern");
        System.out.println("  --include-method <pattern>  Include methods matching the regex pattern");
        System.out.println("  --exclude-method <pattern>  Exclude methods matching the regex pattern");
        System.out.println("  --include-field <pattern>   Include fields matching the regex pattern");
        System.out.println("  --exclude-field <pattern>   Exclude fields matching the regex pattern");
        System.out.println("");
        System.out.println("Example:");
        System.out.println("  java -javaagent:java-instrumenter-agent.jar=\"--include-class com/example/.* --exclude-method com/example/.*\\.toString\" MyApplication");
    }
    /**
     * ClassFileTransformer that applies method tracing instrumentation.
     */
    private static class MethodTracingTransformer implements ClassFileTransformer {
        

        private final BytecodeInstrumenter instrumenter = 
            new BytecodeInstrumenter(org.objectweb.asm.Opcodes.ASM9);
        
        private InstrumentationFilter filter;        


        public MethodTracingTransformer() {
            // Default constructor with default filter
            this.filter = new PatternBasedInstrumentationFilter() ; 
        }
        

        public MethodTracingTransformer(InstrumentationFilter filter) {
            // Constructor with filter (not used in this example)
            this.filter = filter ; 
        }

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                              ProtectionDomain protectionDomain, byte[] classfileBuffer)
                throws IllegalClassFormatException {
            
            try {
                // Skip Java standard library and instrumenter classes
                if (filter.shouldInstrumentClass(className)) {
                    logger.debug("Instrumenting class: {}", className);
                    return instrumenter.instrument(classfileBuffer, 
                        (classWriter) -> new FileLoggingMethodTracerWithObjectSize(classWriter, filter));
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

    public static class FieldDefTracingTransformer implements ClassFileTransformer {
        
        private final BytecodeInstrumenter instrumenter = 
            new BytecodeInstrumenter(org.objectweb.asm.Opcodes.ASM9);
        
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                              ProtectionDomain protectionDomain, byte[] classfileBuffer)
                throws IllegalClassFormatException {
            
            try {
                // Skip Java standard library and instrumenter classes
                if (shouldInstrument(className)) {
                    logger.debug("Instrumenting class for field definitions: {}", className);
                    return instrumenter.instrument(classfileBuffer, 
                        (classWriter) -> new FieldDefTracerVisitor(classWriter));
                }
            } catch (Exception ex) {
                logger.error("Error instrumenting class for field definitions: {}", className, ex);
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
