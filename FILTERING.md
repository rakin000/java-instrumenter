# Instrumentation Filtering Guide

## Overview

The Java Instrumenter now supports selective instrumentation using the `InstrumentationFilter` interface. This allows you to control which classes, methods, and fields should be instrumented during bytecode transformation.

## Quick Start

### Basic Usage

```java
import com.instrumenter.core.PatternBasedInstrumentationFilter;
import com.instrumenter.transformers.MethodTracerVisitor;

// Create a filter
PatternBasedInstrumentationFilter filter = new PatternBasedInstrumentationFilter()
    .includeClass("com/example/.*")
    .includeMethod("(get|set).*");

// Use the filter with a visitor
ClassVisitor visitor = new MethodTracerVisitor(classWriter, filter);
```

## Filter Patterns

### Class Patterns

**Include specific classes:**
```java
filter.includeClass("com/example/MyClass");           // Exact match
filter.includeClass("com/example/.*");                 // Regex: all in package
filter.includeClass("com/example/.*Service");          // Regex: classes ending with Service
```

**Exclude specific classes:**
```java
filter.excludeClass("com/example/util/Helper");
filter.excludeClass("com/example/Test.*");
```

### Method Patterns

**Include methods by name pattern:**
```java
filter.includeMethod("(get|set).*");                   // Getters and setters
filter.includeMethod("do.*");                          // Methods starting with 'do'
filter.includeMethod(".*Processor");                   // Methods ending with 'Processor'
filter.includeMethod("process");                       // Exact method name
```

**Include with class and method:**
```java
filter.includeMethod("com/example/Service.processRequest");
```

**Include with descriptor (signature):**
```java
filter.includeMethod("com/example/Service.processRequest:(Ljava/lang/String;)V");
```

**Exclude specific methods:**
```java
filter.excludeMethod("com/example/.*.toString");
filter.excludeMethod("com/example/.*.equals");
filter.excludeMethod("com/example/.*.hashCode");
```

### Field Patterns

**Include fields:**
```java
filter.includeField("cache.*");                        // Fields starting with 'cache'
filter.includeField("config.*");                       // Fields starting with 'config'
filter.includeField(".*_value");                       // Fields ending with '_value'
```

**Exclude fields:**
```java
filter.excludeField("com/example/.*.serialVersionUID");
filter.excludeField(".*_temp");
```

## Real-World Examples

### Example 1: Trace Only Service Classes

```java
PatternBasedInstrumentationFilter filter = new PatternBasedInstrumentationFilter()
    .includeClass("com/myapp/service/.*")
    .excludeClass("com/myapp/service/util/.*");
```

### Example 2: Trace Getters/Setters and Business Logic

```java
PatternBasedInstrumentationFilter filter = new PatternBasedInstrumentationFilter()
    .includeMethod("(get|set).*")
    .includeMethod("process.*")
    .includeMethod("execute.*")
    .excludeMethod(".*Test");
```

### Example 3: Track Configuration and Cache Fields

```java
PatternBasedInstrumentationFilter filter = new PatternBasedInstrumentationFilter()
    .includeClass("com/myapp/.*")
    .excludeClass("com/myapp/test/.*")
    .includeField("cache.*")
    .includeField("config.*")
    .includeField("state.*");
```

### Example 4: Production Trace (Minimal Overhead)

```java
PatternBasedInstrumentationFilter filter = new PatternBasedInstrumentationFilter()
    .includeClass("com/myapp/core/.*")
    .includeMethod("(request|response|process).*")
    .excludeClass("com/myapp/core/util/.*")
    .excludeClass("com/myapp/core/config/.*");
```

## Default Behavior

If no filters are configured:
- **Classes**: All classes are instrumented (except those in excluded list)
- **Methods**: All methods are instrumented except special methods (`<init>`, `<clinit>`) and synthetic methods
- **Fields**: All fields are instrumented (except synthetic fields)

## Creating Custom Filters

Implement the `InstrumentationFilter` interface for custom logic:

```java
public class CustomFilter implements InstrumentationFilter {
    @Override
    public boolean shouldInstrumentClass(String className) {
        // Custom logic
        return !className.contains("Test");
    }
    
    @Override
    public boolean shouldInstrumentMethod(String className, String methodName, String methodDescriptor) {
        // Custom logic
        return methodName.startsWith("process");
    }
    
    @Override
    public boolean shouldInstrumentField(String className, String fieldName, String fieldDescriptor) {
        // Custom logic
        return !fieldName.startsWith("_");
    }
}
```

## Pattern Syntax

Patterns use **Java regex** syntax:
- `.` matches any single character
- `*` matches zero or more occurrences of the preceding element (needs `.*`)
- `.*` matches any string
- `(pattern1|pattern2)` matches either pattern1 or pattern2
- `[abc]` matches a, b, or c
- `[a-z]` matches any lowercase letter

### Common Regex Patterns

| Pattern | Matches |
|---------|---------|
| `.*` | Everything |
| `com/example/.*` | All classes in com.example package |
| `com/example/service/.*` | All classes in com.example.service |
| `.*Service` | Classes ending with "Service" |
| `Test.*` | Classes starting with "Test" |
| `(get\|set).*` | Methods starting with "get" or "set" |
| `.*Handler` | Methods ending with "Handler" |

## Performance Considerations

- **Filtering at bytecode level**: Filtering happens during class transformation, reducing memory footprint
- **Pattern matching**: Complex regex patterns may have slight performance impact; prefer simple patterns
- **Exclude over include**: Using exclude patterns is typically more efficient than many include patterns

## Integration with Command Line

Currently, filtering must be configured programmatically. To use filters from the command line, you could:

1. Create a wrapper script that builds the filter configuration
2. Use a configuration file (JSON/YAML) to define filters
3. Pass filter patterns as command-line arguments

Example of future enhancement:
```bash
java -jar instrumenter.jar \
  --include-class "com/myapp/.*" \
  --exclude-class "com/myapp/test/.*" \
  --include-method "(get|set).*" \
  input.jar output.jar
```
