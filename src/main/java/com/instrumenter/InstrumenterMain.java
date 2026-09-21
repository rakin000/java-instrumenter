package com.instrumenter;

import com.instrumenter.core.BytecodeInstrumenter;
import com.instrumenter.core.JarInstrumenter;
import com.instrumenter.core.PatternBasedInstrumentationFilter;
import com.instrumenter.transformers.MethodTracerVisitor;
import com.instrumenter.transformers.FieldDefTracerVisitor;
import com.instrumenter.transformers.CombinedMethodAndFieldTracerVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Main entry point for the Java Instrumenter.
 * Can be used to instrument class files from the command line with optional filtering.
 */
public class InstrumenterMain {
    
    private static final Logger logger = LoggerFactory.getLogger(InstrumenterMain.class);
    
    public static void main(String[] args) {
        logger.info("Java Instrumenter v1.0.0");
        
        if (args.length < 2) {
            printUsage();
            System.exit(1);
        }
        
        String inputPath = args[0];
        String outputPath = args[1];
        
        // Parse filter options from remaining arguments
        FilterWrapper filterWrapper = parseFilterArguments(args);
        
        try {
            if (inputPath.endsWith(".jar")) {
                instrumentJarFile(inputPath, outputPath, filterWrapper);
            } else if (inputPath.endsWith(".class")) {
                instrumentClassFile(inputPath, outputPath, filterWrapper);
            } else {
                logger.error("Unsupported file type. Expected .class or .jar file");
                System.exit(1);
            }
            logger.info("Instrumentation completed successfully");
        } catch (IOException ex) {
            logger.error("Error during instrumentation", ex);
            System.exit(1);
        }
    }
    
    /**
     * Parse filter arguments from command line.
     * Supported options:
     *   --include-class <pattern>
     *   --exclude-class <pattern>
     *   --include-method <pattern>
     *   --exclude-method <pattern>
     *   --include-field <pattern>
     *   --exclude-field <pattern>
     * 
     * Returns a filter with a flag indicating if field filters are used.
     */
    private static FilterWrapper parseFilterArguments(String[] args) {
        if (args.length <= 2) {
            return new FilterWrapper(new PatternBasedInstrumentationFilter().allClass().allMethod().allField(), false);
        }
        PatternBasedInstrumentationFilter filter = new PatternBasedInstrumentationFilter(); // Start with empty patterns, will add based on args
        boolean hasFieldFilters = false;
        
        for (int i = 2; i < args.length; i++) {
            String arg = args[i];
            
            if (i + 1 >= args.length) {
                logger.warn("Filter option '{}' requires a pattern argument", arg);
                continue;
            }
            
            String pattern = args[i + 1];
            
            switch (arg) {
                case "--include-class":
                    filter.includeClass(pattern);
                    logger.debug("Added include class pattern: {}", pattern);
                    i++;
                    break;
                case "--exclude-class":
                    filter.excludeClass(pattern);
                    logger.debug("Added exclude class pattern: {}", pattern);
                    i++;
                    break;
                case "--include-method":
                    filter.includeMethod(pattern);
                    logger.debug("Added include method pattern: {}", pattern);
                    i++;
                    break;
                case "--exclude-method":
                    filter.excludeMethod(pattern);
                    logger.debug("Added exclude method pattern: {}", pattern);
                    i++;
                    break;
                case "--include-field":
                    filter.includeField(pattern);
                    logger.debug("Added include field pattern: {}", pattern);
                    hasFieldFilters = true;
                    i++;
                    break;
                case "--exclude-field":
                    filter.excludeField(pattern);
                    logger.debug("Added exclude field pattern: {}", pattern);
                    hasFieldFilters = true;
                    i++;
                    break;
                default:
                    logger.warn("Unknown filter option: {}", arg);
            }
        }
        
        return new FilterWrapper(filter, hasFieldFilters);
    }
    
    /**
     * Wrapper class to return both filter and a flag indicating field filters usage.
     */
    private static class FilterWrapper {
        PatternBasedInstrumentationFilter filter;
        boolean hasFieldFilters;
        
        FilterWrapper(PatternBasedInstrumentationFilter filter, boolean hasFieldFilters) {
            this.filter = filter;
            this.hasFieldFilters = hasFieldFilters;
        }
    }
   
    private static void instrumentJarFile(String inputJarPath, String outputJarPath, 
                                          FilterWrapper filterWrapper) throws IOException {
        Path inputJar = Paths.get(inputJarPath);
        Path outputJar = Paths.get(outputJarPath);
        
        if (!Files.exists(inputJar)) {
            throw new IOException("Input JAR file not found: " + inputJarPath);
        }
        
        logger.info("Instrumenting JAR file: {}", inputJarPath);
        
        JarInstrumenter instrumenter = 
            new JarInstrumenter(org.objectweb.asm.Opcodes.ASM9);
        
        // Use combined visitor if field filters are present, otherwise use method-only visitor
        logger.info("Using combined method and field tracer");
        instrumenter.instrumentJar(inputJar, outputJar, 
            (classWriter) -> new MethodTracerVisitor(classWriter, filterWrapper.filter));
        
        logger.info("Instrumented JAR file written to: {}", outputJarPath);
    }    


    private static void instrumentClassFile(String inputPath, String outputPath,
                                             FilterWrapper filterWrapper) throws IOException {
        Path classFile = Paths.get(inputPath);
        Path outputFile = Paths.get(outputPath);
        
        if (!Files.exists(classFile)) {
            throw new IOException("Input class file not found: " + inputPath);
        }
        
        logger.info("Instrumenting class file: {}", inputPath);
        
        BytecodeInstrumenter instrumenter = 
            new BytecodeInstrumenter(org.objectweb.asm.Opcodes.ASM9);
        
        byte[] bytecode = Files.readAllBytes(classFile);
        
        // Use combined visitor if field filters are present, otherwise use method-only visitor
        byte[] instrumentedBytecode;
        instrumentedBytecode = instrumenter.instrument(bytecode,
            (classWriter) -> new MethodTracerVisitor(classWriter, filterWrapper.filter));
        
        // Create output directory if it doesn't exist
        Path outputDir = outputFile.getParent();
        if (outputDir != null && !Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
        }
        
        instrumenter.writeToFile(outputFile, instrumentedBytecode);
        logger.info("Instrumented class file written to: {}", outputPath);
    } 

    private static void instrumentClassFileWithFieldTracing(String inputPath, String outputPath,
                                                            FilterWrapper filterWrapper) throws IOException {
        Path classFile = Paths.get(inputPath);
        Path outputFile = Paths.get(outputPath);
        
        if (!Files.exists(classFile)) {
            throw new IOException("Input class file not found: " + inputPath);
        }
        
        logger.info("Instrumenting class file with field tracing: {}", inputPath);
        
        BytecodeInstrumenter instrumenter = 
            new BytecodeInstrumenter(org.objectweb.asm.Opcodes.ASM9);
        
        byte[] bytecode = Files.readAllBytes(classFile);
        byte[] instrumentedBytecode = instrumenter.instrument(bytecode,
            (classWriter) -> new FieldDefTracerVisitor(classWriter, filterWrapper.filter));
        
        // Create output directory if it doesn't exist
        Path outputDir = outputFile.getParent();
        if (outputDir != null && !Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
        }
        
        instrumenter.writeToFile(outputFile, instrumentedBytecode);
        logger.info("Instrumented class file with field tracing written to: {}", outputPath);
    }
    
    private static void printUsage() {
        System.out.println("Usage: java -jar java-instrumenter.jar <input-file> <output-file> [options]");
        System.out.println("");
        System.out.println("Supported input types: .class files or .jar files");
        System.out.println("");
        System.out.println("Filter Options:");
        System.out.println("  --include-class <pattern>    Include classes matching pattern (regex)");
        System.out.println("  --exclude-class <pattern>    Exclude classes matching pattern");
        System.out.println("  --include-method <pattern>   Include methods matching pattern");
        System.out.println("  --exclude-method <pattern>   Exclude methods matching pattern");
        System.out.println("  --include-field <pattern>    Include fields matching pattern");
        System.out.println("  --exclude-field <pattern>    Exclude fields matching pattern");
        System.out.println("");
        System.out.println("Examples:");
        System.out.println("  # Instrument all classes");
        System.out.println("  java -jar java-instrumenter.jar app.jar app-instrumented.jar");
        System.out.println("");
        System.out.println("  # Instrument only service classes");
        System.out.println("  java -jar java-instrumenter.jar app.jar app-instrumented.jar \\");
        System.out.println("    --include-class 'com/myapp/service/.*'");
        System.out.println("");
        System.out.println("  # Instrument getters/setters, exclude tests");
        System.out.println("  java -jar java-instrumenter.jar app.jar app-instrumented.jar \\");
        System.out.println("    --include-method '(get|set).*' \\");
        System.out.println("    --exclude-class '.*Test.*'");
        System.out.println("");
        System.out.println("  # Instrument with multiple filters");
        System.out.println("  java -jar java-instrumenter.jar app.jar app-instrumented.jar \\");
        System.out.println("    --include-class 'com/myapp/.*' \\");
        System.out.println("    --exclude-class 'com/myapp/util/.*' \\");
        System.out.println("    --include-method '(process|execute).*'");
    }
}
