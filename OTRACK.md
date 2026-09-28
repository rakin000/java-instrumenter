# Object Tracker agent: how it loads

This document explains how the object tracker agent starts up, focusing on
[Agent.java](src/objecttracker/agent/otrack/agent/Agent.java). The key parts are how it puts the tracker
runtime on the bootstrap class path and how it then hands each command to `otrack.Controller`.

## Two-jar layout

The build produces two jars, and they must sit in the same directory (see [build.gradle](build.gradle), tasks
`otrackAgentJar` and `otrackBootJar`):

| Jar | Contents | Loaded by |
|---|---|---|
| `object-tracker-agent.jar` | Only `otrack.agent.Agent` and `otrack.agent.Attach`, no dependencies | The system (app) class loader, because it is the `-javaagent` jar |
| `object-tracker-boot.jar` | The tracker runtime (`Controller`, `Options`, `Target`, `TargetMatcher`, `Transformer`, `Tracker`, `Events`, `Reporter`, `Log`), plus ASM and Gson renamed to `otrack.shaded.asm` / `otrack.shaded.gson` | The bootstrap class loader, appended at runtime by `Agent` |

## Entry points

There are two ways in, and both end up in `agentmain`:

- **`premain`**: runs when the JVM starts with `-javaagent:object-tracker-agent.jar=...`. It just calls `agentmain`.
- **`agentmain`**: runs when you attach to a JVM that is already running.
  [Attach.java](src/objecttracker/agent/otrack/agent/Attach.java) calls `vm.loadAgent(jar, args)`, which makes the
  target JVM call `Agent.agentmain(args, inst)`.

`agentmain` can run many times in one JVM, once per attach (`cmd=start`, then `cmd=add`, `cmd=report`,
`cmd=stop`). The JVM loads the `Agent` class only once, so the static `bootAppended` flag survives between calls.
Together with `synchronized`, that makes sure the boot jar is appended exactly once.

```java
public static synchronized void agentmain(String args, Instrumentation inst) throws Exception {
    if (!bootAppended) {
        File self = new File(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        File boot = new File(self.getParentFile(), "object-tracker-boot.jar");
        inst.appendToBootstrapClassLoaderSearch(new JarFile(boot));
        bootAppended = true;
    }
    Class<?> c = Class.forName("otrack.Controller", true, null); // null = bootstrap loader
    Method m = c.getMethod("command", String.class, Instrumentation.class);
    m.invoke(null, args, inst);
}
```

## Step 1: find and append the boot jar

```java
File self = new File(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
File boot = new File(self.getParentFile(), "object-tracker-boot.jar");
inst.appendToBootstrapClassLoaderSearch(new JarFile(boot));
```

The first two lines find the agent's own jar on disk. The chain
`ProtectionDomain → CodeSource → location URL → URI → File` gives the path of `object-tracker-agent.jar`. The code
then looks for `object-tracker-boot.jar` next to it. If that file is missing, `new JarFile(...)` throws, and the
agent fails to start.

`appendToBootstrapClassLoaderSearch` adds the jar to the list of places the bootstrap class loader searches.

### Why the bootstrap loader

Java class loaders delegate to their parent first, and every chain ends at the bootstrap loader. The bootstrap
loader is the root loader that loads `java.lang.*`; in Java code it shows up as `null`. That means **a class
defined by the bootstrap loader is visible from every class loader in the JVM**.

The tracker needs that visibility. `Transformer` rewrites target classes to insert calls such as:

```
INVOKESTATIC otrack/Tracker.onNew(...)
INVOKESTATIC otrack/Tracker.onSet(...)
```

When an instrumented class runs that instruction, the JVM looks up `otrack/Tracker` through **the class loader
that defined the instrumented class**, not the agent's loader. Suppose that class was loaded by an Elasticsearch
plugin loader, an isolated child-first loader, or the bootstrap loader itself. Those loaders may never reach the
app class path, so the call would fail with `NoClassDefFoundError`. With `Tracker` on the bootstrap path, every
loader finds it, because every loader eventually asks the bootstrap loader.

### How the appended jar behaves

- **Checked last.** The bootstrap loader searches the appended jar only after its own runtime image, so the jar
  cannot replace JDK classes.
- **Permanent.** An appended jar cannot be removed. That is one reason for the `bootAppended` guard.
- **Bootstrap classes cannot see app classes.** Delegation only goes upward, so nothing in the boot jar may
  depend on classes from the app loader.
- **Bundled libraries are renamed.** Any class that asks for `org.objectweb.asm.*` or `com.google.gson.*` asks
  the bootstrap loader first. Unrenamed copies in the boot jar would override the host application's own
  versions. The `shadeOtrackBoot` task ([tools/Shade.java](tools/Shade.java)) renames them to
  `otrack.shaded.asm` and `otrack.shaded.gson` to avoid that clash. A new bundled library needs an entry there.
- **Modules.** Appended classes go into the bootstrap loader's *unnamed module*. For classes in named modules,
  the JVM automatically makes the module of any transformed class able to read that unnamed module (documented
  in the `java.lang.instrument` package javadoc). That is what lets injected calls link from modular code.
- **One copy only.** A boot class must not also be in `object-tracker-agent.jar`, or you could end up with two
  different `Controller`/`Tracker` classes with separate static state. That is why the agent jar contains only
  `Agent` and `Attach`.

## Step 2: call into the boot jar

```java
Class<?> c = Class.forName("otrack.Controller", true, null); // null = bootstrap loader
Method m = c.getMethod("command", String.class, Instrumentation.class);
m.invoke(null, args, inst);
```

### `Class.forName(name, initialize, loader)`

- `loader = null` tells it to load the class through the bootstrap loader. That finds `otrack.Controller` in the
  jar appended in step 1.
- `initialize = true` runs `Controller`'s static initializers (`static {}` blocks and static fields) now, if they
  have not already run.
- **Why reflection instead of calling `Controller.command(...)` directly?** `Agent` is compiled in a separate
  source set (`otrackAgent`) that does not have the boot classes on its compile class path, so a direct call would
  not compile. Reflection keeps the thin jar free of any compile-time dependency. It also makes explicit that the
  `Controller` being used is the bootstrap one, the same class the injected `Tracker` calls share state with.

### `getMethod("command", String.class, Instrumentation.class)`

- This finds the public method `Controller.command(String, Instrumentation)` in
  [Controller.java](src/objecttracker/boot/otrack/Controller.java) by its name and parameter types.
- The parameter types match because `String` and `Instrumentation` are both loaded by the bootstrap loader. So
  `Instrumentation.class` seen from `Agent` is the same `Class` object that `Controller` sees. If you tried to pass
  a type defined by the app loader, the bootstrap-loaded `Controller` could not reference it at all. Only JDK types
  can cross this boundary.

### `invoke(null, args, inst)`

- The first argument is `null` because `command` is static, so there is no instance.
- `args` is the agent argument string, for example `"cmd=start;classes=+a.B;fields=*"`. `Options.parse` splits
  it on `;` and `=`. See the `Controller` class javadoc for the full list of keys.
- `inst` is the `Instrumentation` handle the JVM gave the agent. `Controller` stores it and uses it to register
  `Transformer` and to retransform classes that are already loaded. The agent manifest enables this with
  `Can-Retransform-Classes: true`.
- Unlike step 1, this runs on **every** call. It is how later attaches send `add`, `report` or `stop` to the same
  `Controller`, whose static fields (`transformer`, `reporter`) still hold the state from earlier commands.
- If `command` throws, the exception comes back wrapped in `InvocationTargetException`. In practice
  `Controller.command` catches `Throwable` itself and logs it, so this rarely happens. If it does, a failure in
  `premain` stops JVM startup, and a failure in `agentmain` shows up as `AgentInitializationException` in the
  attaching `Attach` process.

## Full flow

```
java -javaagent:agent.jar=...        or   Attach <pid> cmd=...  →  vm.loadAgent()
        │                                           │
    premain ───────────────►  agentmain  ◄──────────┘
                                 │ (first time only)
                                 ├─ append object-tracker-boot.jar to the bootstrap search path
                                 │
                                 └─ Class.forName("otrack.Controller", true, null)   ← bootstrap copy
                                    .command(args, inst)
                                          │
                                          └─ registers Transformer → injects INVOKESTATIC otrack/Tracker.*
                                             into target classes (any loader can resolve it, because it is in bootstrap)
```
