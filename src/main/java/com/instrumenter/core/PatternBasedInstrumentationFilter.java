package com.instrumenter.core;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Configurable implementation of InstrumentationFilter using pattern matching.
 * Supports including/excluding classes, methods, and fields using regex patterns or exact matches.
 */
public class PatternBasedInstrumentationFilter implements InstrumentationFilter {
    
    private Set<Pattern> classPatterns = new HashSet<>();
    private Set<Pattern> methodPatterns = new HashSet<>();
    private Set<Pattern> fieldPatterns = new HashSet<>();
    
    private Set<String> excludedClasses = new HashSet<>();
    private Set<String> excludedMethods = new HashSet<>();
    private Set<String> excludedFields = new HashSet<>();
    
    
    public PatternBasedInstrumentationFilter(String classPattern, String methodPattern, String fieldPattern) {
        if (classPattern != null && !classPattern.isEmpty()) {
            classPatterns.add(Pattern.compile(classPattern));
        }
        if (methodPattern != null && !methodPattern.isEmpty()) {
            methodPatterns.add(Pattern.compile(methodPattern));
        }
        if (fieldPattern != null && !fieldPattern.isEmpty()) {
            fieldPatterns.add(Pattern.compile(fieldPattern));
        }
    }

    public PatternBasedInstrumentationFilter() {

    }

    public PatternBasedInstrumentationFilter allClass() {
        // Default: instrument everything (except explicitly excluded)
        classPatterns.add(Pattern.compile(".*"));
        return this;
    }

    public PatternBasedInstrumentationFilter allMethod() {
        // Default: instrument everything (except explicitly excluded and special methods)
        methodPatterns.add(Pattern.compile(".*"));
        return this;
    }

    public PatternBasedInstrumentationFilter allField() {
        // Default: instrument everything (except explicitly excluded)
        fieldPatterns.add(Pattern.compile(".*"));
        return this;
    }
    /**
     * Add a pattern to include classes for instrumentation.
     * Pattern should be a regex (e.g., "com/example/.*" or ".*Service")
     * 
     * @param pattern regex pattern for class names
     * @return this instance for method chaining
     */
    public PatternBasedInstrumentationFilter includeClass(String pattern) {
        classPatterns.add(Pattern.compile(pattern));
        return this;
    }
    
    /**
     * Add a pattern to include methods for instrumentation.
     * Pattern format: "methodName" or "methodName:descriptor" or regex patterns
     * 
     * @param pattern pattern for method names
     * @return this instance for method chaining
     */
    public PatternBasedInstrumentationFilter includeMethod(String pattern) {
        methodPatterns.add(Pattern.compile(pattern));
        return this;
    }
    
    /**
     * Add a pattern to include fields for instrumentation.
     * Pattern should be a regex (e.g., "my.*Field" or "field.*")
     * 
     * @param pattern regex pattern for field names
     * @return this instance for method chaining
     */
    public PatternBasedInstrumentationFilter includeField(String pattern) {
        fieldPatterns.add(Pattern.compile(pattern));
        return this;
    }
    
    /**
     * Exclude a specific class from instrumentation.
     * 
     * @param className fully qualified class name
     * @return this instance for method chaining
     */
    public PatternBasedInstrumentationFilter excludeClass(String className) {
        excludedClasses.add(className);
        return this;
    }
    
    /**
     * Exclude a specific method from instrumentation.
     * Format: "className.methodName" or "className.methodName:descriptor"
     * 
     * @param methodSpec method specification
     * @return this instance for method chaining
     */
    public PatternBasedInstrumentationFilter excludeMethod(String methodSpec) {
        excludedMethods.add(methodSpec);
        return this;
    }
    
    /**
     * Exclude a specific field from instrumentation.
     * Format: "className.fieldName"
     * 
     * @param fieldSpec field specification
     * @return this instance for method chaining
     */
    public PatternBasedInstrumentationFilter excludeField(String fieldSpec) {
        excludedFields.add(fieldSpec);
        return this;
    }
    
    @Override
    public boolean shouldInstrumentClass(String className) {
        // Check if explicitly excluded
        if (excludedClasses.contains(className)) {
            return false;
        }
        
        // If no patterns set, instrument all classes (except excluded ones)
        // if (classPatterns.isEmpty()) {
        //     return true;
        // }
        
        // Check if matches any include pattern
        return classPatterns.stream().anyMatch(p -> p.matcher(className).matches());
    }
    
    @Override
    public boolean shouldInstrumentMethod(String className, String methodName, String methodDescriptor) {
        String methodSpec = className + "." + methodName;
        String methodSpecWithDesc = methodSpec + ":" + methodDescriptor;
        
        // Check if explicitly excluded
        if (excludedMethods.contains(methodSpec) || excludedMethods.contains(methodSpecWithDesc)) {
            return false;
        }
        
        // If no patterns set, instrument all methods (except excluded ones and special methods)
        // if (methodPatterns.isEmpty()) {
        //     return !isSpecialMethod(methodName);
        // }
        
        // Check if matches any include pattern
        return methodPatterns.stream().anyMatch(p -> 
            p.matcher(methodName).matches() || 
            p.matcher(methodSpec).matches() ||
            p.matcher(methodSpecWithDesc).matches()
        );
    }
    
    @Override
    public boolean shouldInstrumentField(String className, String fieldName, String fieldDescriptor) {
        String fieldSpec = className + "." + fieldName;
        
        // Check if explicitly excluded
        if (excludedFields.contains(fieldSpec)) {
            return false;
        }
        
        // If no patterns set, instrument all fields (except excluded ones)
        // if (fieldPatterns.isEmpty()) {
        //     return true;
        // }
        
        // Check if matches any include pattern
        return fieldPatterns.stream().anyMatch(p -> 
            p.matcher(fieldName).matches() || 
            p.matcher(fieldSpec).matches()
        );
    }
    
    private boolean isSpecialMethod(String methodName) {
        return "<init>".equals(methodName) || "<clinit>".equals(methodName);
    }
}
