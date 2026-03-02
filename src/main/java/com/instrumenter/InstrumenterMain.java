package com.instrumenter;

import com.instrumenter.core.BytecodeInstrumenter;
import com.instrumenter.core.JarInstrumenter;
import com.instrumenter.transformers.MethodTracerVisitor;
import com.instrumenter.transformers.FieldDefTracerVisitor;
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
            if (inputPath.endsWith(".jar")) {
                instrumentJarFile(inputPath, outputPath);
            } else if (inputPath.endsWith(".class")) {
                instrumentClassFile(inputPath, outputPath);
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
   
    private static void instrumentJarFile(String inputJarPath, String outputJarPath) throws IOException {
        Path inputJar = Paths.get(inputJarPath);
        Path outputJar = Paths.get(outputJarPath);
        
        if (!Files.exists(inputJar)) {
            throw new IOException("Input JAR file not found: " + inputJarPath);
        }
        
        logger.info("Instrumenting JAR file: {}", inputJarPath);
        
        JarInstrumenter instrumenter = 
            new JarInstrumenter(org.objectweb.asm.Opcodes.ASM9);
        
        instrumenter.instrumentJar(inputJar, outputJar, 
            (classWriter) -> new MethodTracerVisitor(classWriter));
        
        logger.info("Instrumented JAR file written to: {}", outputJarPath);
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

    private static void instrumentClassFileWithFieldTracing(String inputPath, String outputPath) throws IOException {
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
            (classWriter) -> new FieldDefTracerVisitor(classWriter));
        
        // Create output directory if it doesn't exist
        Path outputDir = outputFile.getParent();
        if (outputDir != null && !Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
        }
        
        instrumenter.writeToFile(outputFile, instrumentedBytecode);
        logger.info("Instrumented class file with field tracing written to: {}", outputPath);
    }
    
    private static void printUsage() {
        System.out.println("Usage: java -jar java-instrumenter.jar <input-file> <output-file>");
        System.out.println("");
        System.out.println("Supported input types: .class files or .jar files");
        System.out.println("");
        System.out.println("Examples:");
        System.out.println("  java -jar java-instrumenter.jar MyClass.class MyClass.instrumented.class");
        System.out.println("  java -jar java-instrumenter.jar build/MyClass.class dist/MyClass.class");
        System.out.println("  java -jar java-instrumenter.jar app.jar app-instrumented.jar");
    }
}
