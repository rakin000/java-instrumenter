# Java Instrumenter

A bytecode instrumentation framework for Java applications built with [ASM](https://asm.ow2.io/)

## Features

- Runtime instrumentation using a Java agent
- Offline instrumentation from CLI
- Extensible visitor-based architecture
- Method and field tracing support
- Filter-based selective instrumentation

## Object Tracker Agent

A second, independent Java agent (sources in `src/objecttracker`, packages `otrack.*`; it does not touch
`com.instrumenter.*`). It tracks object allocation/liveness per class and can log per-object field values.

```bash
./gradlew objectTrackerJars        # also part of `./gradlew build`
# -> build/libs/object-tracker-agent.jar + object-tracker-boot.jar (keep them in the same directory)

java -javaagent:build/libs/object-tracker-agent.jar="classes=+a.B;fields=*;events=/tmp/ev.jsonl" MyApp
java -cp build/libs/object-tracker-agent.jar otrack.agent.Attach <pid|name> classes=a.B   # attach to a running JVM
```

Options (`key=value` joined by `;`):

| Option | Meaning |
| --- | --- |
| `cmd=start\|add\|stop\|report` | default `start` |
| `classes=a.B,+c.D` | classes to track; `+` also tracks subclasses/implementors |
| `classesFile=path` | one class per line, `#` comments |
| `classesJson=path` | JSON object `{"a.B": "YES", "c.D": "NO"}`; only `YES` entries are instrumented |
| `sample=N` | keep 1 in N objects (default 1) |
| `stacks=true\|false`, `depth=N` | record allocation stacks (expensive); `depth` also limits `set` event stacks (default 8) |
| `interval=SECONDS` | statistics report period (default 10) |
| `out=path` | statistics JSONL |
| `fields=*\|a,b` | log field values: `new` / `set` / `free` events (first start only); `set` events include the writer's call stack as `"at"` |
| `events=path` | event trace JSONL |
| `logFile=path` | write the agent's own `[otrack]` messages to a file instead of stderr |

`fields=` mode adds work (including a stack walk) to every write of a tracked field and drops events when its 64K queue is full
(the count is in the final `end` event), so combine it with a narrow class list, named fields and/or `sample=N`.

Design: the agent jar is a thin loader; the tracker runtime, ASM and Gson (relocated under `otrack.shaded` by
`tools/Shade.java`) live in `object-tracker-boot.jar`, which is appended to the bootstrap class path so
instrumented classes from any class loader can call it and the bundled libraries cannot clash with the target's.
See [OTRACK.md](OTRACK.md) for how the agent loads.

Demos: `examples/objecttracker/run-demo.sh` and `examples/objecttracker/run-json-log.sh`.

Tests: `./gradlew otrackTest` (also part of `./gradlew check`/`build`). Sources are in `src/objecttracker/test`:
unit tests in `otrack`, plus end-to-end tests that run `fixture.Main` in a separate JVM under the built agent jars,
both with `-javaagent` and by attaching.

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
