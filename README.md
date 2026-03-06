# Java Instrumenter

A bytecode instrumentation framework for Java applications built with [ASM](https://asm.ow2.io/)

## Features

- Runtime instrumentation using a Java agent
- Offline instrumentation from CLI
- Extensible visitor-based architecture
- Method and field tracing support
- Filter-based selective instrumentation

## Requirements

- Java 11+
- Gradle 7+ (or use the included Gradle wrapper)

## Installation

Clone and enter the project:

```bash
git clone <your-repository-url>
cd java-instrumenter
```

Optional: verify Java and Gradle.

```bash
java -version
./gradlew --version
```

## Building

Build all artifacts:

```bash
./gradlew clean build assemble
```

Expected output JARs in `build/libs/`:

- `java-instrumenter.jar` (CLI tool)
- `java-instrumenter-agent.jar` (Java agent)
- `java-instrumenter-fat.jar` (fat/standalone bundle)

Run tests:

```bash
./gradlew test
```

## Usage

### 1) Java Agent (runtime instrumentation)

Basic usage:

```bash
java -javaagent:build/libs/java-instrumenter-agent.jar \
    com.example.Main
```

With agent filters (comma-separated regex options):

```bash
java -javaagent:build/libs/java-instrumenter-agent.jar=\
--include-class=com/example/.*,\
--exclude-class=com/example/internal/.*,\
--exclude-method=(get|set).* \
     -jar example-app.jar
```

Supported filter options:

- `--include-class=<pattern>`
- `--exclude-class=<pattern>`
- `--include-method=<pattern>`
- `--exclude-method=<pattern>`
- `--include-field=<pattern>`
- `--exclude-field=<pattern>`

Logging is controlled via `src/main/resources/logback.xml`. By default all logs will be generated in the current working directory `instrumentation.log`

### 2) CLI (offline instrumentation)

Run from Gradle:

```bash
./gradlew run --args="path/to/Input.class path/to/Output.class --include-class=<pattern> --include-method=<pattern> --include-field=<pattern>"
```

Run from built JAR:

```bash
java -jar build/libs/java-instrumenter.jar path/to/Input.class path/to/Output.class
```

### 3) Programmatic API

```java
import com.instrumenter.core.BytecodeInstrumenter;
import com.instrumenter.transformers.MethodTracerVisitor;
import org.objectweb.asm.Opcodes;

byte[] instrumented = new BytecodeInstrumenter(Opcodes.ASM9)
    .pipeline(classBytes, next -> new MethodTracerVisitor(next));
```

## Project Structure

```
src/main/java/com/instrumenter/
├── agent/            # Java agent entrypoint
├── core/             # Instrumentation engine and filters
├── transformers/     # ASM visitors/transformers
└── InstrumenterMain.java  # CLI entrypoint for static instrumentation 
```

## Troubleshooting

### No traces shown

- Confirm you used `-javaagent:build/libs/java-instrumenter-agent.jar`
- Verify logging config in `src/main/resources/logback.xml`
- Check filter patterns are not excluding your target classes/methods

### Build failures

```bash
./gradlew clean build --refresh-dependencies
```

## References

- [ASM Documentation](https://asm.ow2.io/)
- [Java Instrumentation API](https://docs.oracle.com/javase/8/docs/technotes/guides/instrumentation/)
