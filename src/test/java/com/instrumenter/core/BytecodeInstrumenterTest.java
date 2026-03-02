package com.instrumenter.core;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BytecodeInstrumenter.
 */
public class BytecodeInstrumenterTest {
    
    @Test
    public void testInstrumenterInitialization() {
        BytecodeInstrumenter instrumenter = 
            new BytecodeInstrumenter(org.objectweb.asm.Opcodes.ASM9);
        
        assertNotNull(instrumenter, "Instrumenter should be initialized");
    }
    
    @Test
    public void testNullBytecodeHandling() {
        BytecodeInstrumenter instrumenter = 
            new BytecodeInstrumenter(org.objectweb.asm.Opcodes.ASM9);
        
        assertThrows(Exception.class, () -> {
            instrumenter.instrument(null, cw -> new ClassVisitor(org.objectweb.asm.Opcodes.ASM9, cw) {});
        });
    }
}
