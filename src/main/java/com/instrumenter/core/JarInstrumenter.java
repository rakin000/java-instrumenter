package com.instrumenter.core;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.function.Function;

/**
 * Utility class for instrumenting JAR files.
 * Handles reading, transforming, and writing JAR archives.
 */
public class JarInstrumenter {
    
    private static final Logger logger = LoggerFactory.getLogger(JarInstrumenter.class);
    private int asmVersion;
    
    public JarInstrumenter(int asmVersion) {
        this.asmVersion = asmVersion;
    }
    
    /**
     * Instruments all class files within a JAR archive.
     * 
     * @param inputJar path to the input JAR file
     * @param outputJar path to the output JAR file
     * @param visitorFactory function that creates a ClassVisitor for instrumentation
     * @throws IOException if JAR file operations fail
     */
    public void instrumentJar(Path inputJar, Path outputJar, 
                              Function<ClassWriter, ClassVisitor> visitorFactory) throws IOException {
        try (JarInputStream jarIn = new JarInputStream(Files.newInputStream(inputJar))) {
            try (JarOutputStream jarOut = new JarOutputStream(Files.newOutputStream(outputJar), jarIn.getManifest())) {
                JarEntry entry;
                while ((entry = jarIn.getNextJarEntry()) != null) {
                    // Skip manifest file as it's handled separately
                    if (entry.getName().equals("META-INF/MANIFEST.MF")) {
                        continue;
                    }
                    
                    if (entry.isDirectory()) {
                        jarOut.putNextEntry(new JarEntry(entry.getName()));
                        jarOut.closeEntry();
                    } else if (entry.getName().endsWith(".class")) {
                        // Instrument class files
                        byte[] classBytes = readAllBytes(jarIn);
                        byte[] instrumentedBytes = instrumentClass(classBytes, visitorFactory);
                        
                        JarEntry newEntry = new JarEntry(entry.getName());
                        jarOut.putNextEntry(newEntry);
                        jarOut.write(instrumentedBytes);
                        jarOut.closeEntry();
                        
                        logger.debug("Instrumented class: {}", entry.getName());
                    } else {
                        // Copy non-class files as-is
                        byte[] entryBytes = readAllBytes(jarIn);
                        jarOut.putNextEntry(new JarEntry(entry.getName()));
                        jarOut.write(entryBytes);
                        jarOut.closeEntry();
                    }
                }
            }
        }
        
        logger.info("JAR instrumentation complete: {}", outputJar);
    }
    
    /**
     * Instruments a single class file bytecode.
     * 
     * @param classBytes the raw class bytecode
     * @param visitorFactory function that creates a ClassVisitor for instrumentation
     * @return the instrumented class bytecode
     */
    private byte[] instrumentClass(byte[] classBytes, 
                                    Function<ClassWriter, ClassVisitor> visitorFactory) {
        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        ClassVisitor visitor = visitorFactory.apply(writer);
        
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        
        return writer.toByteArray();
    }
    
    /**
     * Reads all bytes from an InputStream without closing it.
     * 
     * @param in the input stream
     * @return the bytes read
     * @throws IOException if read fails
     */
    private byte[] readAllBytes(InputStream in) throws IOException {
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
        }
        return out.toByteArray();
    }
}
