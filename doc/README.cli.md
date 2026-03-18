# maven-get-deps CLI Reference

The `maven-get-deps` CLI is the core Java-based implementation for resolving and managing dependencies. It can be run as a standard JAR or as a high-performance native binary compiled with GraalVM.

## 🚀 Quick Start

```bash
# Generate classpath from pom.xml
java -jar maven-get-deps-cli.jar

# Generate size report for an ad-hoc artifact
java -jar maven-get-deps-cli.jar commons-lang:commons-lang:2.6 --report report.md

# Copy dependencies to a 'lib' folder
java -jar maven-get-deps-cli.jar pom.xml --dest-dir lib
```

## 🛠️ Usage

### Running via JAR
```bash
java -jar maven-get-deps-cli.jar [source] [options]
```

### Running via Native Binary (GraalVM)
If you have compiled the tool with GraalVM (see [README.dev.md](README.dev.md)), you can run it with instant startup:
```bash
./maven-get-deps [source] [options]
```

## ⚙️ CLI Arguments

| arg | description |
|:---|:---|
| `[source]` | Positional: `pom.xml`, Maven coordinates, or deps file. Default: `pom.xml` |
| `-o, --output <file>` | Path to save the dependency list. |
| `-d, --dest-dir <dir>` | Destination directory for JAR copies. |
| `-n, --no-copy` | Disable JAR copying even if `--dest-dir` is set. |
| `-c, --cache <dir>` | Local Maven repository (default: `~/.m2/repository`). |
| `-r, --report <file>` | Path to save the dependency size report (Markdown). |
| `-ex, --exclude-cp <G:A>` | Comma-separated list of artifact IDs or paths to exclude. |
| `-s, --scopes <scopes>` | Comma-separated list of scopes (default: `runtime`). |
| `-cp, --classpath` | Output as a valid OS-specific `CLASSPATH` string. |
| `-cf, --convert-format <fmt>` | Convert input file format: `colon` or `path`. |
| `-ecp, --extra-classpath <f>` | File containing additional classpath entries to append. |
| `-es, --exclude-siblings` | Exclude artifacts from the same reactor (default: `false`). |
| `-v, --version` | Show tool version. |

## 📖 Key Features

### Transitive Dependency Resolution
Resolves the full dependency tree for any project or ad-hoc artifact.

### Dependency Size Report
Provides a detailed breakdown of artifact sizes with incremental attribution (who brought in what).
```bash
java -jar maven-get-deps-cli.jar pom.xml --report report.md
```

### Classpath Assembly
Generates environment-ready `CLASSPATH` strings for scripts (`.sh`, `.bat`).

### Artifact Copying
Streamlines deployment by gathering all required JARs into a single directory.

## 📝 Input File Format
The CLI supports a `dependencies.txt` file as a source:
```text
# Local application JAR
./app.jar

# Maven dependencies
org.slf4j:slf4j-api:2.0.7
com.fasterxml.jackson.core:jackson-databind:2.15.2
```

## 🏗️ How It Works

- **Source (Cache)**: Your local Maven repository (`~/.m2/repository`) is used as the primary source for JARs and POMs.
- **Destination (`destDir`)**: Optional standalone folder. If `copyJars=true`, artifacts are copied from Source to Destination.
- **Interchangeable Paths**: Relative paths generated follow the standard Maven layout (`group/artifact/version/file.jar`).

## ⚡ Performance Tip: GraalVM
While the [Zig version](README.get_deps.md) is the fastest, the Java CLI compiled with **GraalVM native-image** offers a balanced approach — providing full Maven-compatible logic with near-instant startup (~60ms vs 300ms for JIT).

See [README.dev.md](README.dev.md) for build instructions.
