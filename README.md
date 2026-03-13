# maven-get-deps

Started as set of tools for managing java dependencies. Then expanded to also simplify/streamline deployment of an application to a linux server using systemd with management of runtime versions using symlinks, and tracking whene version was active(deployed,replaced).

Available for multiple enviroments so you can be flexible to do some of the tasks even without maven or java installed. Version management tool is zig app compiled as native executable that can be used independently from this project. Or at least you can manage your java backend and frontend versioning with this same tool.

> Maven repository is the backbone of java dependency mamagement regardless if you use `mvn`, `gradle`, `mvnd`, `ivy` ... And the [format that reliably maps artifact definition to folder name](doc/MAVEN_LAYOUT.md) in any maven repository is a great asset to working with java dependencies. The tools here follow that convention making the process compatible with your existing `~/.m2/repository` and maven central.

This project contains a set of utilities that work together

**get_deps** (maven plugin, executable jar, native app)

- expand dependencies to include transient dependencies
- fill local maven cache with missing dependencies
- copy dependencies to separate folder
- generate classpath file (newline delimited)
- generate classpath suitable for enviroment CLASSPATH variable to simplify running java code

**version_manager**

- manage version deploy/revert 
- keeps version history with time and marker if it was reverted
- deploy will validate version from index file to avoid typo breaking deployment(stopping old instance and failing the new one)


**gen_index** native tool you can use if you have not yet decided on own way to collect versions

- generate index of available versions (one sample way here to get you started, that you can change to suit your needs)


| Guide | Description |
|---|---|
| [Deployment & Shared Library](doc/README.usage-deploy.md) | Deploy thin JARs + a shared dep folder. Avoid fat JARs entirely. |
| [Systemd Daemon Guide](doc/README.systemd.md) | Deploy a Java daemon with `systemd` and atomic versioning. |
| [Classpath Generation](doc/README.usage-classpath.md) | Generate `CLASSPATH` from a dep file or `pom.xml`. Includes multi-module `--extra-classpath` guide. |
| [CVE Vulnerability Scanning](doc/README.usage-cve.md) | Offline CVE scanning with OWASP, CI/CD build-breaking, and clean version search. |
| [Dependency Size Reporting](doc/README.usage-report.md) | Analyze artifact bloat with incremental size attribution. |
| [Zig Binary Guide](doc/README.zig.md) | Ultra-fast, zero-dependency binary. Deployment philosophy, SDKMAN!, JVM-vs-container. |
| [Docker Integration (Dynamic)](doc/README.docker.md) | Thin Docker images with a shared Maven cache. Includes K8s InitContainer pattern. |
| [Docker Integration (Static)](doc/README.static-docker.md) | Bake a fixed classpath into Docker at build time. Most secure, zero runtime tools. |
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

Manually download from [GitHub Releases](https://github.com/hrgdavor/maven-get-deps/releases).

| Artifact | Description |
|---|---|
| `maven-get-deps-linux-x64.tar.gz` | Native Linux binary |
| `maven-get-deps-windows-x64.zip` | Native Windows binary |
| `maven-get-deps-cli.jar` | Fat JAR — runs anywhere with `java -jar` |
| `maven-get-deps` (Zig) | Ultra-fast native binary with Revision Management |

> The Linux/Windows binaries are built with GraalVM native image for instant startup. For the high-performance Zig binary, see **[doc/README.zig.md](doc/README.zig.md)**.

To automate downloading the latest release, query the GitHub API:
`https://api.github.com/repos/hrgdavor/maven-get-deps/releases/latest`

---

## Quick Start

### Maven Plugin (Resolve & List Dependencies)

```bash
mvn hr.hrg:maven-get-deps:1.0.0:get-deps [-DdestDir=<PATH>] [-DcopyJars=true] [-DoutputFile=deps.txt]
```

### CLI (Generate Classpath)

```bash
# From pom.xml (default or explicit)
java -jar maven-get-deps-cli.jar
java -jar maven-get-deps-cli.jar pom.xml

# From Maven coordinates (ad-hoc)
java -jar maven-get-deps-cli.jar commons-lang:commons-lang:2.6

# From a dependency list file
java -jar maven-get-deps-cli.jar deps.txt --convert-format path --classpath

### Revision Management

The Zig version of the CLI includes a built-in Reconciler for managing application versions via atomic symlink swaps. See **[doc/README.zig.md](doc/README.zig.md#revision-management-zero-downtime-deployment)** for details.

### CLI (CVE Scan)

```bash
java -jar maven-get-deps-cli.jar --cve-update                     # First-time DB setup
java -jar maven-get-deps-cli.jar pom.xml --cve-report cve.md      # Scan pom
java -jar maven-get-deps-cli.jar deps.txt --cve-report cve.md     # Scan deps list
```

---

## CLI Arguments

| `[source]` | Positional argument: `pom.xml` OR Maven coordinates OR Input file. Default: `pom.xml` |
| `-o, --output <file>` | Path to save the dependency list. |
| `-d, --dest-dir <dir>` | Destination directory for JAR copies |
| `-n, --no-copy` | Disable JAR copying even if `--dest-dir` is set |
| `-c, --cache <dir>` | Local Maven repository (default: `~/.m2/repository`) |
| `-r, --report <file>` | Path to save the size report (markdown). |
| `-ex, --exclude-cp <G:A,path>` | Exclude specific artifacts or paths. |
| `-sc, --scopes <scopes>` | Comma-separated scopes (default: compile,runtime). |
| `-cp, --classpath` | Output as OS-separated `CLASSPATH` string |
| `-cf, --convert-format <fmt>` | Convert format: `colon` or `path` |
| `-ecp, --extra-classpath <file>` | File with extra classpath entries to append (one per line) |
| `-cr, --cve-report <file>` | Generate CVE Markdown report |
| `-cd, --cve-data <dir>` | OWASP H2 database directory |
| `-cu, --cve-update` | Download/update the local CVE database |
| `-nk, --nvd-api-key <key>` | NVD API key for faster updates |
| `-cv, --cve-check-versions` | Search for nearest clean version for vulnerable deps |
| `-ct, --cve-severity-threshold <val>` | CVSS threshold for build-breaking (default: `8.0`) |


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

