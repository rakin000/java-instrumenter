# Java Instrumenter

A powerful bytecode instrumentation framework for Java applications using ASM library and Gradle build system.

## Features

- **Bytecode Manipulation**: Instrument Java bytecode using the ASM library
- **Method Tracing**: Automatically trace method entry and exit points
- **Java Agent**: Deploy as a Java agent for runtime instrumentation
- **Command-line Tool**: Standalone tool for offline class file instrumentation
- **Extensible Architecture**: Easy to create custom instrumentation transformers

## Project Structure

```
java-instrumenter/
├── src/main/java/com/instrumenter/
│   ├── core/
│   │   ├── BytecodeInstrumenter.java          # Main instrumentation engine
│   │   └── AbstractInstrumentationVisitor.java # Base class for visitors
│   ├── transformers/
│   │   └── MethodTracerVisitor.java           # Method tracing implementation
│   ├── agent/
│   │   └── InstrumenterAgent.java             # Java agent entry point
│   ├── example/
│   │   └── SampleApplication.java             # Example app
│   └── InstrumenterMain.java                  # CLI entry point
├── src/test/java/com/instrumenter/
│   └── core/
│       └── BytecodeInstrumenterTest.java      # Unit tests
├── build.gradle                                # Gradle build configuration
├── settings.gradle                             # Gradle settings
└── gradle.properties                           # Gradle properties
```

## Requirements

- Java 11 or higher
- Gradle 7.0 or higher

## Building the Project

```bash
cd java-instrumenter

# Build the project
gradle build

# Build with method tracing output
gradle build -v
```

### Build Outputs

The build creates multiple JAR files:

1. **java-instrumenter.jar** - Main CLI tool for offline instrumentation
2. **java-instrumenter-agent.jar** - Java agent JAR for runtime instrumentation
3. **java-instrumenter-fat.jar** - Fat JAR with all dependencies included

## Usage

### 1. As a Java Agent (Runtime Instrumentation)

Load the agent when starting your application:

```bash
java -javaagent:java-instrumenter-agent.jar YourApplication
```

The agent will automatically instrument methods from your application classes and trace their execution.

### 2. Command-line Tool (Offline Instrumentation)

Instrument a class file offline:

```bash
# Basic usage
gradle run --args="path/to/YourClass.class path/to/output/YourClass.class"

# Or using the built JAR
java -jar build/libs/java-instrumenter.jar path/to/YourClass.class path/to/output/YourClass.class
```

### 3. Programmatic Usage

```java
import com.instrumenter.core.BytecodeInstrumenter;
import com.instrumenter.transformers.MethodTracerVisitor;
import java.nio.file.Files;
import java.nio.file.Paths;

// Load your class bytecode
byte[] classBytes = Files.readAllBytes(Paths.get("MyClass.class"));

// Create instrumenter
BytecodeInstrumenter instrumenter = new BytecodeInstrumenter(Opcodes.ASM9);

// Apply method tracing
byte[] instrumentedBytes = instrumenter.pipeline(classBytes,
    next -> new MethodTracerVisitor(next));

// Write result
Files.write(Paths.get("MyClass.instrumented.class"), instrumentedBytes);
```

## Core Components

### BytecodeInstrumenter

The main engine for bytecode transformation:

- `instrument(byte[], ClassVisitor)` - Main instrumentation method
- `pipeline(byte[], ClassVisitorFactory)` - Fluent pipeline interface
- `instrumentFile(Path, ClassVisitor)` - Instruments from file
- `writeToFile(Path, byte[])` - Writes instrumented bytecode

### MethodTracerVisitor

Instruments methods to log entry and exit points:

- Adds logging at method entry
- Adds logging at all return points
- Skips synthetic methods and constructors
- Supports all return types (void, primitive, object)

### InstrumenterAgent

Java agent for runtime instrumentation:

- Premain entry point for `-javaagent` loading
- Agentmain entry point for dynamic attachment
- Filters out standard library classes
- Configurable instrumentation rules

## Creating Custom Instrumenters

Extend `AbstractInstrumentationVisitor` to create custom transformations:

```java
public class CustomTransformer extends AbstractInstrumentationVisitor {
    
    public CustomTransformer(ClassVisitor classVisitor) {
        super(classVisitor);
    }
    
    @Override
    protected MethodVisitor createMethodVisitor(MethodVisitor methodVisitor,
                                               int access, String name, String descriptor) {
        // Return custom MethodVisitor implementation
        return new CustomMethodVisitor(methodVisitor, className, name, descriptor);
    }
}
```

## Dependencies

- **org.ow2.asm:asm:9.5** - ASM bytecode framework
- **org.ow2.asm:asm-commons:9.5** - ASM common utilities
- **org.ow2.asm:asm-util:9.5** - ASM utilities
- **org.slf4j:slf4j-api:2.0.5** - SLF4J logging API
- **ch.qos.logback:logback-classic:1.4.6** - Logback implementation

## Testing

Run the test suite:

```bash
gradle test
```

## Examples

### Example 1: Trace a Sample Application

```bash
# Build the project
gradle build

# Run the sample application with tracing
java -javaagent:build/libs/java-instrumenter-agent.jar \
     -cp build/libs/java-instrumenter.jar \
     com.instrumenter.example.SampleApplication
```

### Example 2: Offline Instrumentation

```bash
# Compile a test class
javac SomeClass.java

# Instrument it
gradle run --args="SomeClass.class SomeClass.instrumented.class"

# Run it (make sure to have the dependencies in classpath)
java SomeClass
```

## Configuration

Edit `gradle.properties` for build configuration:

```properties
org.gradle.jvmargs=-Xmx2g          # Maximum heap size
org.gradle.parallel=true            # Parallel builds
org.gradle.caching=true             # Build cache
```

## Troubleshooting

### ClassNotFoundException when running instrumented classes

Ensure all ASM and logging dependencies are in the classpath:

```bash
java -cp build/libs/java-instrumenter-fat.jar MyInstrumentedClass
```

### Method traces not appearing

Check:
1. Logging is configured (check logback configuration)
2. The class is not in the exclusion list (see `shouldInstrument` in agent)
3. You're using the correct JAR (agent vs. main)

### Build errors

```bash
# Clean and rebuild
gradle clean build

# Force dependency update
gradle build --refresh-dependencies
```

## Architecture

```
ClassFileTransformer (Java Agent)
    ↓
BytecodeInstrumenter
    ↓
ClassReader (ASM)
    ↓
Custom ClassVisitor Chain
    ↓
ClassWriter (ASM)
    ↓
Instrumented Bytecode
```

## Performance Considerations

- Method tracing adds minimal overhead (~1-5% depending on method frequency)
- Agent startup time is negligible
- Use class filters to reduce instrumentation scope
- Consider using sampling or conditional instrumentation for high-load scenarios

## Future Enhancements

- [ ] Configuration file support for selective instrumentation
- [ ] Performance metrics collection
- [ ] Multiple instrumentation strategies (decorator, logging, profiling)
- [ ] Plugin system for custom instrumentation
- [ ] Web-based analysis dashboard
- [ ] Integration with APM tools

## License

This project is provided as-is for educational and development purposes.

## Contributing

To extend the instrumenter:

1. Create a new `ClassVisitor` implementation
2. Add it to the appropriate package
3. Update the agent or main to use it
4. Add unit tests
5. Document the functionality

## References

- [ASM Documentation](https://asm.ow2.io/)
- [Java Instrumentation API](https://docs.oracle.com/javase/8/docs/technotes/guides/instrumentation/)
- [Java Virtual Machine Specification](https://docs.oracle.com/javase/specs/)
