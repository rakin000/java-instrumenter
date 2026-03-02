package com.instrumenter.core;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Main bytecode instrumenter class using ASM library.
 * Provides functionality to read, modify, and write Java bytecode.
 */
public class BytecodeInstrumenter {
    
    private int asmVersion;
    
    public BytecodeInstrumenter(int asmVersion) {
        this.asmVersion = asmVersion;
    }
    
    /**
     * Instruments a byte array of bytecode using a visitor factory.
     * The factory receives a ClassWriter and should return a ClassVisitor
     * that is chained to that ClassWriter.
     * 
     * @param bytecode the original bytecode
     * @param visitorFactory a function that creates a ClassVisitor given a ClassWriter
     * @return the instrumented bytecode
     */
    public byte[] instrument(byte[] bytecode, Function<ClassWriter, ClassVisitor> visitorFactory) {
        return instrument(bytecode, visitorFactory, true);
    }
    
    /**
     * Instruments a byte array of bytecode using a visitor factory.
     * 
     * @param bytecode the original bytecode
     * @param visitorFactory a function that creates a ClassVisitor given a ClassWriter
     * @param computeFrames whether to compute stack frames
     * @return the instrumented bytecode
     */
    public byte[] instrument(byte[] bytecode, Function<ClassWriter, ClassVisitor> visitorFactory, boolean computeFrames) {
        ClassReader classReader = new ClassReader(bytecode);
        int flags = computeFrames ? ClassWriter.COMPUTE_FRAMES : 0;
        ClassWriter classWriter = new ClassWriter(flags);
        ClassVisitor visitor = visitorFactory.apply(classWriter);
        
        classReader.accept(visitor, ClassReader.EXPAND_FRAMES);
        
        return classWriter.toByteArray();
    }
    
    /**
     * Instruments a class file from disk.
     * 
     * @param classFilePath path to the class file
     * @param visitorFactory a function that creates a ClassVisitor given a ClassWriter
     * @return the instrumented bytecode
     * @throws IOException if file operations fail
     */
    public byte[] instrumentFile(Path classFilePath, Function<ClassWriter, ClassVisitor> visitorFactory) throws IOException {
        byte[] bytecode = Files.readAllBytes(classFilePath);
        return instrument(bytecode, visitorFactory);
    }
    
    /**
     * Writes instrumented bytecode to a file.
     * 
     * @param outputPath path where to write the class file
     * @param bytecode the instrumented bytecode
     * @throws IOException if file operations fail
     */
    public void writeToFile(Path outputPath, byte[] bytecode) throws IOException {
        Files.write(outputPath, bytecode);
    }
}
