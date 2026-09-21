package com.instrumenter.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PatternBasedInstrumentationFilter.
 */
public class PatternBasedInstrumentationFilterTest {
    
    private PatternBasedInstrumentationFilter filter;
    
    @BeforeEach
    public void setUp() {
        filter = new PatternBasedInstrumentationFilter();
    }
    
    // ============ Constructor Tests ============
    
    @Test
    public void testDefaultConstructor() {
        PatternBasedInstrumentationFilter f = new PatternBasedInstrumentationFilter();
        assertNotNull(f, "Filter should be initialized");
    }
    
    @Test
    public void testConstructorWithPatterns() {
        PatternBasedInstrumentationFilter f = 
            new PatternBasedInstrumentationFilter("com/example/.*", "test.*", "field.*");
        assertNotNull(f, "Filter should be initialized with patterns");
        
        // Should match patterns
        assertTrue(f.shouldInstrumentClass("com/example/MyClass"));
        assertTrue(f.shouldInstrumentMethod("com/example/Test", "testMethod", "()V"));
        assertTrue(f.shouldInstrumentField("com/example/Test", "fieldName", "I"));
    }
    
    @Test
    public void testConstructorWithNullPatterns() {
        PatternBasedInstrumentationFilter f = 
            new PatternBasedInstrumentationFilter(null, null, null);
        assertNotNull(f, "Filter should handle null patterns");
    }
    
    @Test
    public void testConstructorWithEmptyPatterns() {
        PatternBasedInstrumentationFilter f = 
            new PatternBasedInstrumentationFilter("", "", "");
        assertNotNull(f, "Filter should handle empty patterns");
    }
    
    // ============ Class Instrumentation Tests ============
    
    @Test
    public void testIncludeClassWithRegexPattern() {
        filter.includeClass("com/example/.*");
        
        assertTrue(filter.shouldInstrumentClass("com/example/MyClass"));
        assertTrue(filter.shouldInstrumentClass("com/example/AnotherClass"));
        assertFalse(filter.shouldInstrumentClass("com/other/MyClass"));
    }
    
    @Test
    public void testIncludeClassWithExactMatch() {
        filter.includeClass("com/example/MyClass");
        
        assertTrue(filter.shouldInstrumentClass("com/example/MyClass"));
        assertFalse(filter.shouldInstrumentClass("com/example/OtherClass"));
    }
    
    @Test
    public void testExcludeClassExplicitly() {
        filter.includeClass("com/example/.*").excludeClass("com/example/MyClass");
        
        assertTrue(filter.shouldInstrumentClass("com/example/OtherClass"));
        assertFalse(filter.shouldInstrumentClass("com/example/MyClass"));
    }
    
    @Test
    public void testAllClassesPattern() {
        filter.allClass();
        
        assertTrue(filter.shouldInstrumentClass("com/example/MyClass"));
        assertTrue(filter.shouldInstrumentClass("org/test/TestClass"));
        assertTrue(filter.shouldInstrumentClass("java/util/HashMap"));
    }
    
    @Test
    public void testAllClassesWithExclusion() {
        filter.allClass().excludeClass("java/lang/String");
        
        assertTrue(filter.shouldInstrumentClass("com/example/MyClass"));
        assertFalse(filter.shouldInstrumentClass("java/lang/String"));
    }
    
    @Test
    public void testMultipleClassPatterns() {
        filter.includeClass("com/example/.*").includeClass("org/test/.*");
        
        assertTrue(filter.shouldInstrumentClass("com/example/MyClass"));
        assertTrue(filter.shouldInstrumentClass("org/test/TestClass"));
        assertFalse(filter.shouldInstrumentClass("com/other/MyClass"));
    }
    
    @Test
    public void testNoClassPatternMatches() {
        filter.includeClass("com/example/.*");
        
        assertFalse(filter.shouldInstrumentClass("org/test/MyClass"));
    }
    
    // ============ Method Instrumentation Tests ============
    
    @Test
    public void testIncludeMethodByName() {
        filter.includeMethod("test.*");
        
        assertTrue(filter.shouldInstrumentMethod("com/example/MyClass", "testMethod", "()V"));
        assertTrue(filter.shouldInstrumentMethod("com/example/MyClass", "testAnotherMethod", "(I)V"));
        assertFalse(filter.shouldInstrumentMethod("com/example/MyClass", "otherMethod", "()V"));
    }
    
    @Test
    public void testIncludeMethodByFullSpec() {
        filter.includeMethod("com/example/MyClass.testMethod");
        
        assertTrue(filter.shouldInstrumentMethod("com/example/MyClass", "testMethod", "()V"));
        assertFalse(filter.shouldInstrumentMethod("com/example/MyClass", "otherMethod", "()V"));
    }
    
    @Test
    public void testIncludeMethodBySpecWithDescriptor() {
        filter.includeMethod("com/example/MyClass.testMethod:\\(\\)V");
        
        assertTrue(filter.shouldInstrumentMethod("com/example/MyClass", "testMethod", "()V"));
        assertFalse(filter.shouldInstrumentMethod("com/example/MyClass", "testMethod", "(I)V"));
    }
    
    @Test
    public void testExcludeMethodExplicitly() {
        filter.includeMethod(".*").excludeMethod("com/example/MyClass.sensitiveMethod");
        
        assertTrue(filter.shouldInstrumentMethod("com/example/MyClass", "normalMethod", "()V"));
        assertFalse(filter.shouldInstrumentMethod("com/example/MyClass", "sensitiveMethod", "()V"));
    }
    
    @Test
    public void testExcludeMethodWithDescriptor() {
        filter.includeMethod(".*").excludeMethod("com/example/MyClass\\.method:\\(\\)V");
        
        assertTrue(filter.shouldInstrumentMethod("com/example/MyClass", "method", "(I)V"));
        assertFalse(filter.shouldInstrumentMethod("com/example/MyClass", "method", "()V"));
    }
    
    @Test
    public void testAllMethodsPattern() {
        filter.allMethod();
        
        assertTrue(filter.shouldInstrumentMethod("com/example/MyClass", "anyMethod", "()V"));
        assertTrue(filter.shouldInstrumentMethod("org/test/TestClass", "testMethod", "(II)I"));
    }
    
    @Test
    public void testMultipleMethodPatterns() {
        filter.includeMethod("test.*").includeMethod("get.*");
        
        assertTrue(filter.shouldInstrumentMethod("com/example/MyClass", "testMethod", "()V"));
        assertTrue(filter.shouldInstrumentMethod("com/example/MyClass", "getValue", "()I"));
        assertFalse(filter.shouldInstrumentMethod("com/example/MyClass", "otherMethod", "()V"));
    }
    
    // ============ Field Instrumentation Tests ============
    
    @Test
    public void testIncludeFieldByName() {
        filter.includeField("my.*");
        
        assertTrue(filter.shouldInstrumentField("com/example/MyClass", "myField", "I"));
        assertTrue(filter.shouldInstrumentField("com/example/MyClass", "myValue", "Ljava/lang/String;"));
        assertFalse(filter.shouldInstrumentField("com/example/MyClass", "otherField", "I"));
    }
    
    @Test
    public void testIncludeFieldByFullSpec() {
        filter.includeField("com/example/MyClass.myField");
        
        assertTrue(filter.shouldInstrumentField("com/example/MyClass", "myField", "I"));
        assertFalse(filter.shouldInstrumentField("com/example/MyClass", "otherField", "I"));
    }
    
    @Test
    public void testExcludeFieldExplicitly() {
        filter.includeField(".*").excludeField("com/example/MyClass.sensitiveField");
        
        assertTrue(filter.shouldInstrumentField("com/example/MyClass", "normalField", "I"));
        assertFalse(filter.shouldInstrumentField("com/example/MyClass", "sensitiveField", "I"));
    }
    
    @Test
    public void testAllFieldsPattern() {
        filter.allField();
        
        assertTrue(filter.shouldInstrumentField("com/example/MyClass", "anyField", "I"));
        assertTrue(filter.shouldInstrumentField("org/test/TestClass", "testField", "Ljava/lang/String;"));
    }
    
    @Test
    public void testMultipleFieldPatterns() {
        filter.includeField("my.*").includeField("test.*");
        
        assertTrue(filter.shouldInstrumentField("com/example/MyClass", "myField", "I"));
        assertTrue(filter.shouldInstrumentField("com/example/MyClass", "testValue", "Ljava/lang/String;"));
        assertFalse(filter.shouldInstrumentField("com/example/MyClass", "otherField", "I"));
    }
    
    // ============ Combined Tests ============
    
    @Test
    public void testComplexFilteringScenario() {
        filter
            .includeClass("com/example/.*")
            .includeMethod("test.*")
            .includeField("my.*")
            .excludeClass("com/example/Internal")
            .excludeMethod("com/example/Test.debugMethod")
            .excludeField("com/example/Test.internalField");
        
        // Class filtering
        assertTrue(filter.shouldInstrumentClass("com/example/MyClass"));
        assertFalse(filter.shouldInstrumentClass("com/example/Internal"));
        assertFalse(filter.shouldInstrumentClass("org/other/MyClass"));
        
        // Method filtering
        assertTrue(filter.shouldInstrumentMethod("com/example/Test", "testMethod", "()V"));
        assertFalse(filter.shouldInstrumentMethod("com/example/Test", "debugMethod", "()V"));
        assertFalse(filter.shouldInstrumentMethod("com/example/Test", "otherMethod", "()V"));
        
        // Field filtering
        assertTrue(filter.shouldInstrumentField("com/example/Test", "myField", "I"));
        assertFalse(filter.shouldInstrumentField("com/example/Test", "internalField", "I"));
        assertFalse(filter.shouldInstrumentField("com/example/Test", "otherField", "I"));
    }
    
    @Test
    public void testMethodChaining() {
        PatternBasedInstrumentationFilter result = filter
            .includeClass("com/example/.*")
            .includeMethod("test.*")
            .includeField("my.*");
        
        assertSame(filter, result, "Method chaining should return the same filter instance");
    }
    
    // ============ Edge Cases ============
    
    @Test
    public void testEmptyFilterDoesNotMatch() {
        // Filter with no patterns configured
        assertFalse(filter.shouldInstrumentClass("com/example/MyClass"));
        assertFalse(filter.shouldInstrumentMethod("com/example/MyClass", "method", "()V"));
        assertFalse(filter.shouldInstrumentField("com/example/MyClass", "field", "I"));
    }
    
    @Test
    public void testRegexSpecialCharactersInPattern() {
        filter.includeClass("com/example/.*");
        filter.includeMethod("get[A-Z].*");
        
        assertTrue(filter.shouldInstrumentClass("com/example/MyClass"));
        assertTrue(filter.shouldInstrumentMethod("com/example/MyClass", "getValue", "()I"));
        assertTrue(filter.shouldInstrumentMethod("com/example/MyClass", "getName", "()Ljava/lang/String;"));
        assertFalse(filter.shouldInstrumentMethod("com/example/MyClass", "getvalue", "()I"));
    }
    
    @Test
    public void testClassNameWithSlashes() {
        filter.includeClass("com/example/outer/Inner");
        
        assertTrue(filter.shouldInstrumentClass("com/example/outer/Inner"));
        assertFalse(filter.shouldInstrumentClass("com/example/outer/Other"));
    }
    
    @Test
    public void testComplexMethodDescriptor() {
        filter.includeMethod("complexMethod");
        
        assertTrue(filter.shouldInstrumentMethod("com/example/MyClass", "complexMethod", "(ILjava/lang/String;Z)Ljava/util/List;"));
    }
    
    @Test
    public void testPrimitiveFieldDescriptors() {
        filter.includeField(".*");
        
        assertTrue(filter.shouldInstrumentField("com/example/MyClass", "intField", "I"));
        assertTrue(filter.shouldInstrumentField("com/example/MyClass", "doubleField", "D"));
        assertTrue(filter.shouldInstrumentField("com/example/MyClass", "boolField", "Z"));
        assertTrue(filter.shouldInstrumentField("com/example/MyClass", "refField", "Ljava/lang/Object;"));
    }
    
    @Test
    public void testDotInPatterns() {
        filter.includeClass("com\\.example\\..*");
        
        // Note: This test demonstrates that dots in the pattern need to be escaped
        // depending on the actual implementation expectations
        assertFalse(filter.shouldInstrumentClass("com/example/MyClass"));
    } 

    @Test
    public void testComplexJavaClassExclusions() {
        filter = new PatternBasedInstrumentationFilter()
            .allClass()
            .allMethod()
            .allField()
            .excludeClass("java/.*") 
            .excludeClass("javax/.*")
            .excludeClass("sun/.*")
            .excludeClass("com/sun/.*")
            .excludeClass("com/instrumenter/.*")
            .excludeClass("org/slf4j/.*")
            .excludeClass("org/ow2/asm/.*")
            .excludeClass("ch/qos/logback/.*"); 


        assertFalse(filter.shouldInstrumentClass("java/lang/Shutdown"), "Should exclude java/lang/Shutdown");
        assertFalse(filter.shouldInstrumentClass("javax/swing/JButton"), "Should exclude javax/swing/JButton");
        assertFalse(filter.shouldInstrumentClass("sun/misc/Unsafe"), "Should exclude sun/misc/Unsafe");
        assertFalse(filter.shouldInstrumentClass("com/sun/tools/javac/Main"), "Should exclude com/sun/tools/javac/Main");
        assertFalse(filter.shouldInstrumentClass("com/instrumenter/agent/InstrumenterAgent"), "Should exclude com/instrumenter/agent/InstrumenterAgent");
        assertFalse(filter.shouldInstrumentClass("org/slf4j/Logger"), "Should exclude org/slf4j/Logger");
        assertFalse(filter.shouldInstrumentClass("org/ow2/asm/ClassVisitor"), "Should exclude org/ow2/asm/ClassVisitor");
        assertFalse(filter.shouldInstrumentClass("ch/qos/logback/classic/Logger"), "Should exclude ch/qos/logback/classic/Logger");
        assertFalse(filter.shouldInstrumentClass("java/util/IdentityHashMap"), "Should exclude java/util/IdentityHashMap");
        assertTrue(filter.shouldInstrumentClass("com/example/MyClass"), "Should include com/example/MyClass");      
    
    }
}
