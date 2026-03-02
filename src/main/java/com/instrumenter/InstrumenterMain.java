package com.instrumenter;

import com.instrumenter.core.BytecodeInstrumenter;
import com.instrumenter.transformers.MethodTracerVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Main entry point for the Java Instrumenter.
 * Can be used to instrument class files from the command line.
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
        
        try {
            instrumentClassFile(inputPath, outputPath);
            logger.info("Instrumentation completed successfully");
        } catch (IOException ex) {
            logger.error("Error during instrumentation", ex);
            System.exit(1);
        }
    }
    
    private static void instrumentClassFile(String inputPath, String outputPath) throws IOException {
        Path classFile = Paths.get(inputPath);
        Path outputFile = Paths.get(outputPath);
        
        if (!Files.exists(classFile)) {
            throw new IOException("Input class file not found: " + inputPath);
        }
        
        logger.info("Instrumenting class file: {}", inputPath);
        
        BytecodeInstrumenter instrumenter = 
            new BytecodeInstrumenter(org.objectweb.asm.Opcodes.ASM9);
        
        byte[] bytecode = Files.readAllBytes(classFile);
        byte[] instrumentedBytecode = instrumenter.instrument(bytecode,
            (classWriter) -> new MethodTracerVisitor(classWriter));
        
        // Create output directory if it doesn't exist
        Path outputDir = outputFile.getParent();
        if (outputDir != null && !Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
        }
        
        instrumenter.writeToFile(outputFile, instrumentedBytecode);
        logger.info("Instrumented class file written to: {}", outputPath);
    }
    
    private static void printUsage() {
        System.out.println("Usage: java -jar java-instrumenter.jar <input-class> <output-class>");
        System.out.println("");
        System.out.println("Examples:");
        System.out.println("  java -jar java-instrumenter.jar MyClass.class MyClass.instrumented.class");
        System.out.println("  java -jar java-instrumenter.jar build/MyClass.class dist/MyClass.class");
    }
}
