# maven_get_deps: Java CLI & Maven Plugin

The `maven_get_deps` tool is the original Java-based implementation. It can be used as a Maven plugin or a standalone CLI (runnable via JAR or a GraalVM native binary).

## 1. Maven Plugin Usage

The plugin is used to resolve and manage project dependencies directly from your `pom.xml`.

```bash
mvn hr.hrg:maven-get-deps:1.0.0:get-deps [-DdestDir=<PATH>] [-DcopyJars=true] [-DoutputFile=deps.txt]
```

### Parameters
| Parameter | Description |
|---|---|
| `destDir` | Directory for listing/copying artifacts |
| `copyJars` | Copy JARs to `destDir` (default: `false`) |
| `outputFile` | Save dependency list to file |
| `scopes` | Scopes to include (default: `compile,runtime`) |
| `reportFile` | Markdown dependency-size report |
| `classpath` | Output as OS-separated `CLASSPATH` string (default: `false`) |
| `cache` | Override local Maven repository path |

---

## 2. CLI Usage (Java/Native)

The CLI provides advanced features like size reporting and CVE scanning.

### Running via JAR
```bash
java -jar maven-get-deps-cli.jar [source] [options]
```

### Running via Native Binary (GraalVM)
If you have compiled the tool with GraalVM (see [README.dev.md](README.dev.md)), you can run it with instant startup:
```bash
./maven-get-deps [source] [options]
```

### Key Features
- **CVE Scan**: Offline scanning with OWASP database (`--cve-report`).
  - Use `--cve-update` to refresh the database.
  - Use `--nvd-api-key` and `--nvd-api-delay` to optimize updates.
- **Size Report**: Detailed breakdown of dependency sizes (`--report`).
- **Classpath Assembly**: Generate environment-ready CLASSPATH strings (`--classpath`).

## 3. Input Files
Both the Java and Zig versions support the same `dependencies.txt` format:
```text
# Local application JAR
./app.jar

# Maven dependencies
org.slf4j:slf4j-api:2.0.7
com.fasterxml.jackson.core:jackson-databind:2.15.2
```

---

## Performance Tip: GraalVM
While the [Zig version](README.get_deps.md) is the fastest, the Java CLI compiled with **GraalVM native-image** offers a middle ground — providing full Maven-compatible logic with near-instant startup (~60ms vs 300ms for JIT).

See [README.dev.md](README.dev.md) for build instructions.
