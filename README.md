## Project Overview

Started as set of tools for managing Java dependencies, this project has evolved into a comprehensive toolset for streamlined deployment, version management, and security scanning.

Available for multiple enviroments so you can be flexible to do some of the tasks even without maven or java installed. Version management tool is zig app compiled as native executable that can be used independently from this project. Or at least you can manage your java backend and frontend versioning with this same tool.

> Maven repository is the backbone of java dependency mamagement regardless if you use `mvn`, `gradle`, `mvnd`, `ivy` ... And the [format that reliably maps artifact definition to folder name](doc/MAVEN_LAYOUT.md) in any maven repository is a great asset to working with java dependencies. The tools here follow that convention making the process compatible with your existing `~/.m2/repository` and maven central.


### 1. Core Java Suite

Built for deep analysis and Maven ecosystem integration.

- **maven-get-deps** (CLI / Maven Plugin)
    - Full **transitive dependency expansion**.
    - Dependency **size reporting** with incremental attribution.
    - Classpath generation (`.txt`, `.sh`, `.bat`).
    - **Copy dependencies** to a separate folder.
    - Local Maven cache filling (downloading missing artifacts).
- **cve12** (Focused CLI)
    - Vulnerability scanning using **OWASP Dependency-Check v12**.
    - Standalone tool removed from core to reduce bloat and conflicts.

### 2. Ultra-Fast Zig Utilities

Native binaries with zero dependencies, optimized for production runtime usage.

- **get_deps**
    - Instant path resolution and classpath generation.
    - Local Maven cache filling (downloading missing artifacts).
- **version_manager**
    - Zero-downtime deployment via **atomic symlink swaps**.
    - Maintains version history and revert markers.
- **gen_index**
    - Simple metadata generator for deployment versioning, that can be used as is, or as a reference to make your own.

---

<a name="guide-summary"></a>

| Guide | Description |
|---|---|
| **[Download Guide](https://hrgdavor.github.io/maven-get-deps/download.html)** | **Central location for latest releases and examples.** |
| [Deployment Philosophy](doc/README.usage-deploy.md) | Deploy thin JARs + a shared dep folder. Avoid fat JARs. |
| [Systemd Daemon Guide](doc/README.systemd.md) | Deploy a Java daemon with `systemd` and atomic versioning. |
| [Classpath Generation](doc/README.usage-classpath.md) | Generate `CLASSPATH` from a dep file or `pom.xml`. |
| [Dependency Size Reporting](doc/README.usage-report.md) | Analyze artifact bloat with incremental size attribution. |
| [Docker Integration (Dynamic)](doc/README.docker.md) | Thin Docker images with a shared Maven cache. |
| [Docker Integration (Static)](doc/README.static-docker.md) | Bake fixed classpath into Docker at build time. |
| [Build & Development](doc/README.dev.md) | Building the project, GraalVM native images, and MetadataMerger. |
| [Gradle Plugin Guide](doc/gradle.plugin.md) | Create a lightweight Gradle equivalent with `--extra-classpath` support. |
| [Maven Artifact Layout](doc/MAVEN_LAYOUT.md) | Standard directory patterns and regex conversion examples (Java/JS). |


## Why

This whole toolset started from looking to deploy a Java app to a remote server. You need your classes **and** all of your dependencies to start your app there. Usually, this means copying dependencies to a `lib/` folder alongside your JAR, or even worse, creating fat JARs. I find that surrender to bloat unacceptable.

With frameworks like Spring bloat and Hibernate, even a moderately sized app — say **500 KB** of *your* code — can carry **50–100 MB** of dependencies. That's a **200× size difference** every time you deploy a new version. And it gets worse with microservices: each service has less code but the same pile of libraries.

### You Really Should Optimize the Process

The smarter approach is to separate your **application code** from its **dependencies**, and manage them independently:

- Maintain a **shared dependency folder** (on a network drive, or synced via rsync/scp/git)
- Treat it as a local Maven repository — shared by all your deployed applications
- Deploy only the **thin JAR** (your code) + a small `dependencies.txt` manifest per release
- At startup, use `maven-get-deps` to assemble the `CLASSPATH` from the shared folder instantly

It will pay off in the long run, as it gives you **lean, fast releases**: a 500 KB JAR deploys in milliseconds over the network. Dependencies only need to be synced when they actually change, not on every release. Multiple app versions on the same server share the same library files on disk. Your version repository does not baloon in size once version start to pile, so you need less agressive pruning.

## Deployment Philosophy

While modern development often defaults to **Docker** and **Fat JARs**, I believe that for many deployments, a shared dependency repository combined with thin application JARs is a more efficient and transparent model.

### The JVM is already a Container
The **Java Virtual Machine (JVM)** is, by definition, a virtual machine. It provides the isolation and portability that many seek in Docker, but with much less overhead:
- **Multi-Version Coexistence**: You can easily install multiple JDK versions on a single host (using tools like [SDKMAN!](https://sdkman.io/)) and run different applications with different versions side-by-side. 
- **Fewer Abstraction Layers**: Deploying directly to a host or a simple VM allows for easier inspection. You can use standard OS tools (`top`, `lsof`, `jstack`) without digging through Docker layers, namespaces, or cgroups.
- **Improved Observability**: Logs, heap dumps, and configuration files are directly accessible on the file system, simplifying backup paths and monitoring agents.
- **Resource Efficiency**: You avoid the storage and bandwidth cost of pushing large image layers. A "Thin JAR" is often only a few hundred kilobytes, and dependencies are shared across all instances on the host.

For a detailed guide on setting this up, see **[doc/README.usage-deploy.md](doc/README.usage-deploy.md)**.

## Download

**[Click here to generate custom download commands](https://hrgdavor.github.io/maven-get-deps/download.html)** based on your tool and platform or 
get the latest binaries from the [GitHub Releases](https://github.com/hrgdavor/maven-get-deps/releases/latest) or from 

---

## Quick Start

### 1. Dependency Resolution (`maven-get-deps`)

```bash
# Generate classpath from pom.xml
java -jar maven-get-deps-cli.jar

# Generate size report for an ad-hoc artifact
java -jar maven-get-deps-cli.jar commons-lang:commons-lang:2.6 --report report.md

# Copy dependencies to a 'lib' folder
java -jar maven-get-deps-cli.jar pom.xml --dest-dir lib
```

### 2. CVE Scanning (`cve12`)

```bash
# First-time DB setup (downloads ~250MB)
java -jar cve12-cli.jar --cve-update --nvd-api-key YOUR_KEY

# Scan a dependency list generated by maven-get-deps
java -jar cve12-cli.jar --input deps.txt --report cve.md
```

### 3. Revision Management (Zig)

Managed via atomic symlink swaps. See **[doc/README.get_deps.md](doc/README.get_deps.md)** and **[doc/README.version_manager.md](doc/README.version_manager.md)**.

---

## CLI Arguments (`maven-get-deps`)

| arg | description |
|:---|:---|
| `[source]` | Positional: `pom.xml`, Maven coordinates, or deps file. Default: `pom.xml` |
| `-o, --output <file>` | Path to save the dependency list. |
| `-d, --dest-dir <dir>` | Destination directory for JAR copies. |
| `-n, --no-copy` | Disable JAR copying even if `--dest-dir` is set. |
| `-c, --cache <dir>` | Local Maven repository (default: `~/.m2/repository`). |
| `-r, --report <file>` | Path to save the dependency size report (Markdown). |
| `-ex, --exclude-cp <G:A>` | Comma-separated list of artifact IDs or paths to exclude. |
| `-s, --scopes <scopes>` | Comma-separated list of scopes (default: `compile,runtime`). |
| `-cp, --classpath` | Output as a valid OS-specific `CLASSPATH` string. |
| `-cf, --convert-format <fmt>` | Convert input file format: `colon` or `path`. |
| `-ecp, --extra-classpath <f>` | File containing additional classpath entries to append. |
| `-v, --version` | Show tool version. |

> **Note**: CVE scanning arguments have been moved to the **[cve12 scanner](doc/README.usage-cve.md)**.


### Maven Plugin Parameters

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

## How It Works

- **Source (Cache)**: Your local Maven repository (`~/.m2/repository`). Used as the primary source for JARs and POMs.
- **Destination (`destDir`)**: Optional standalone folder. If `copyJars=true`, artifacts are copied from Source to Destination.
- Relative paths are **interchangeable** — they follow the standard Maven layout (`group/artifact/version/file.jar`).

## Dependency Size Report

```bash
java -jar maven-get-deps-cli.jar pom.xml --report report.md
# Or for an ad-hoc artifact
java -jar maven-get-deps-cli.jar commons-lang:commons-lang:2.6 --report report.md
```
More details in [Dependency Size Reporting](doc/README.usage-report.md) readme.

---


---

### Gradle Plugin

The `maven-get-deps-gradle-plugin` allows you to export dependencies directly from your Gradle projects.

See [maven-get-deps-gradle-plugin/README.md](maven-get-deps-gradle-plugin/README.md) for installation and usage instructions.

---

## Build & Development

See [doc/README.dev.md](doc/README.dev.md) for build instructions, GraalVM native image generation, and metadata maintenance.

> **Note**See [Comparison with mthmulders/mcs](doc/README.mcs.md) for potential integration and functional overlaps.

