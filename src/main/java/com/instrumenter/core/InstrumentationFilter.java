package com.instrumenter.core;

/**
 * Filter interface to control which classes, methods, and fields should be instrumented.
 */
public interface InstrumentationFilter {
    
    /**
     * Determine if a class should be instrumented.
     * 
     * @param className the fully qualified class name (e.g., "com/example/MyClass")
     * @return true if the class should be instrumented, false otherwise
     */
    boolean shouldInstrumentClass(String className);
    
    /**
     * Determine if a method should be instrumented.
     * 
     * @param className the fully qualified class name
     * @param methodName the method name
     * @param methodDescriptor the method descriptor (e.g., "(I)V")
     * @return true if the method should be instrumented, false otherwise
     */
    boolean shouldInstrumentMethod(String className, String methodName, String methodDescriptor);
    
    /**
     * Determine if a field should be instrumented.
     * 
     * @param className the fully qualified class name
     * @param fieldName the field name
     * @param fieldDescriptor the field descriptor (e.g., "I", "Ljava/lang/String;")
     * @return true if the field should be instrumented, false otherwise
     */
    boolean shouldInstrumentField(String className, String fieldName, String fieldDescriptor);
}
