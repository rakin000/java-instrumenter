package com.instrumenter.transformers;

import org.objectweb.asm.Opcodes;   


public class OpcodeUtils {

   public static boolean isReturnOpcode(int opcode) {
        return opcode == Opcodes.RETURN ||
                opcode == Opcodes.IRETURN ||
                opcode == Opcodes.LRETURN ||
                opcode == Opcodes.FRETURN ||
                opcode == Opcodes.DRETURN ||
                opcode == Opcodes.ARETURN;
    }

    public static boolean isStore(int opcode) {
        return opcode == Opcodes.ISTORE ||
                opcode == Opcodes.LSTORE ||
                opcode == Opcodes.FSTORE ||
                opcode == Opcodes.DSTORE ||
                opcode == Opcodes.ASTORE;
        }

    public static boolean isLoad(int opcode) {
        return opcode == Opcodes.ILOAD ||
                opcode == Opcodes.LLOAD ||
                opcode == Opcodes.FLOAD ||
                opcode == Opcodes.DLOAD ||
                opcode == Opcodes.ALOAD;
    }
    
}
