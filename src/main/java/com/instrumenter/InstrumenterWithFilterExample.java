package com.instrumenter;

import com.instrumenter.core.PatternBasedInstrumentationFilter;
import com.instrumenter.transformers.MethodTracerVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Example demonstrating how to use instrumentation filters.
 * Shows various filtering patterns for classes, methods, and fields.
 */
public class InstrumenterWithFilterExample {
    
    private static final Logger logger = LoggerFactory.getLogger(InstrumenterWithFilterExample.class);
    
    public static void main(String[] args) {
        if (args.length < 2) {
            printUsage();
            System.exit(1);
        }
        
        String inputPath = args[0];
        String outputPath = args[1];
        
        try {
            // Example 1: Instrument only methods starting with "get" or "set"
            PatternBasedInstrumentationFilter filterGettersSetters = 
                new PatternBasedInstrumentationFilter()
                    .includeClass("com/example/.*")
                    .includeMethod("(get|set).*");
            
            // Example 2: Instrument all classes except utilities, skip specific methods
            PatternBasedInstrumentationFilter filterExcludeUtilities = 
                new PatternBasedInstrumentationFilter()
                    .excludeClass("com/example/util.*")
                    .excludeMethod("com/example/.*.toString")
                    .excludeMethod("com/example/.*.hashCode");
            
            // Example 3: Instrument only service classes and their doXxx methods
            PatternBasedInstrumentationFilter filterServicesOnly = 
                new PatternBasedInstrumentationFilter()
                    .includeClass("com/example/.*Service")
                    .includeMethod("do.*");
            
            // Example 4: Instrument fields matching patterns and specific methods
            PatternBasedInstrumentationFilter filterComplex = 
                new PatternBasedInstrumentationFilter()
                    .includeClass("com/example/model/.*")
                    .includeMethod(".*Processor")
                    .excludeMethod(".*Test")
                    .includeField("cache.*")
                    .includeField("config.*");
            
            // Use one of the filters above
            // For now, use the filterExcludeUtilities example
            // instrumentWithFilter(inputPath, outputPath, filterExcludeUtilities);
            
            System.out.println("Filter examples created. Use PatternBasedInstrumentationFilter");
            System.out.println("in your code to apply selective instrumentation.");
            
        } catch (Exception ex) {
            logger.error("Error", ex);
            System.exit(1);
        } 

        
    }
    
    private static void printUsage() {
        System.out.println("Instrumentation Filter Examples:");
        System.out.println("");
        System.out.println("Include classes by pattern:");
        System.out.println("  filter.includeClass(\"com/example/.*\")");
        System.out.println("  filter.includeClass(\"com/example/.*Service\")");
        System.out.println("");
        System.out.println("Include methods by pattern:");
        System.out.println("  filter.includeMethod(\"(get|set).*\")");
        System.out.println("  filter.includeMethod(\"do.*\")");
        System.out.println("  filter.includeMethod(\".*Processor\")");
        System.out.println("");
        System.out.println("Include fields by pattern:");
        System.out.println("  filter.includeField(\"cache.*\")");
        System.out.println("  filter.includeField(\"config.*\")");
        System.out.println("");
        System.out.println("Exclude specific classes:");
        System.out.println("  filter.excludeClass(\"com/example/util.*\")");
        System.out.println("  filter.excludeClass(\"com/example/Test.*\")");
        System.out.println("");
        System.out.println("Exclude specific methods:");
        System.out.println("  filter.excludeMethod(\"com/example/.*.toString\")");
        System.out.println("  filter.excludeMethod(\"com/example/.*.equals\")");
        System.out.println("");
        System.out.println("Exclude specific fields:");
        System.out.println("  filter.excludeField(\"com/example/.*.serialVersionUID\")");
    }
}
