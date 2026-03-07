# maven-get-deps

A standalone tool to resolve and download Maven dependencies to a local folder, and generate classpath files for scripting. Uses the standard Maven repository structure, making it compatible with your existing `~/.m2/repository`.

> **Note**: Looking at integration with [mcs](https://github.com/mthmulders/mcs)? See the [development notes](README.dev.md).

## Why

To deploy a Java app to a remote server you need your classes **and** all of your dependencies. Usually, this means copying dependencies to a `lib/` folder alongside your JAR, or even worse, creating fat JARs.

With frameworks like Spring and Hibernate, even a moderately sized app — say **500 KB** of *your* code — can carry **50–100 MB** of dependencies. That's a **200× size difference** every time you deploy a new version. And it gets worse with microservices: each service has less code but the same pile of libraries.

### You Really Should Optimize the Process

The smarter approach is to separate your **application code** from its **dependencies**, and manage them independently:

- Maintain a **shared dependency folder** (on a network drive, or synced via rsync/scp/git)
- Treat it as a local Maven repository — shared by all your deployed applications
- Deploy only the **thin JAR** (your code) + a small `dependencies.txt` manifest per release
- At startup, use `maven-get-deps` to assemble the `CLASSPATH` from the shared folder instantly

This gives you **lean, fast releases**: a 500 KB JAR deploys in milliseconds over the network. Dependencies only need to be synced when they actually change, not on every release. Multiple app versions on the same server share the same library files on disk.

For a detailed guide on setting this up, see **[README.usage-deploy.md](README.usage-deploy.md)**.


## Download

Manually download from [GitHub Releases](https://github.com/hrgdavor/maven-get-deps/releases).

| Artifact | Description |
|---|---|
| `maven-get-deps-linux-x64.tar.gz` | Native Linux binary |
| `maven-get-deps-windows-x64.zip` | Native Windows binary |
| `maven-get-deps-cli.jar` | Fat JAR — runs anywhere with `java -jar` |

> The Linux/Windows binaries are built with GraalVM native image for instant startup. For the high-performance Zig binary, see **[README.zig.md](README.zig.md)**.

To automate downloading the latest release, query the GitHub API:
`https://api.github.com/repos/hrgdavor/maven-get-deps/releases/latest`

---

## Use Cases

| Guide | Description |
|---|---|
| [Deployment & Shared Library](README.usage-deploy.md) | Deploy thin JARs + a shared dep folder. Avoid fat JARs entirely. |
| [Classpath Generation](README.usage-classpath.md) | Generate `CLASSPATH` from a dep file or `pom.xml`. Includes multi-module `--extra-classpath` guide. |
| [CVE Vulnerability Scanning](README.usage-cve.md) | Offline CVE scanning with OWASP, CI/CD build-breaking, and clean version search. |
| [Zig Binary Guide](README.zig.md) | Ultra-fast, zero-dependency binary. Deployment philosophy, SDKMAN!, JVM-vs-container. |
| [Docker Integration (Dynamic)](README.docker.md) | Thin Docker images with a shared Maven cache. Includes K8s InitContainer pattern. |
| [Docker Integration (Static)](README.static-docker.md) | Bake a fixed classpath into Docker at build time. Most secure, zero runtime tools. |
| [Build & Development](README.dev.md) | Building the project, GraalVM native images, and MetadataMerger. |

---

## Quick Start

### Maven Plugin (Resolve & List Dependencies)

```bash
mvn io.github.hrgdavor:maven-get-deps:1.0.0:get-deps [-DdestDir=<PATH>] [-DcopyJars=true] [-DoutputFile=deps.txt]
```

### CLI (Generate Classpath)

```bash
# From pom.xml
java -jar maven-get-deps-cli.jar --pom pom.xml --classpath

# From a dependency list file
java -jar maven-get-deps-cli.jar --input deps.txt --convert-format path --classpath
```

### CLI (CVE Scan)

```bash
java -jar maven-get-deps-cli.jar --cve-update                     # First-time DB setup
java -jar maven-get-deps-cli.jar --pom pom.xml --cve-report cve.md  # Scan
```

---

## CLI Arguments

| Flag | Description |
|---|---|
| `-p, --pom <file>` | Path to pom.xml (default: `pom.xml`) |
| `-i, --input <file>` | Input dependency list file |
| `-o, --output <file>` | Save output to file |
| `-d, --dest-dir <dir>` | Destination directory for JAR copies |
| `-n, --no-copy` | Disable JAR copying even if `--dest-dir` is set |
| `-c, --cache <dir>` | Local Maven repository (default: `~/.m2/repository`) |
| `-s, --scopes <list>` | Comma-separated scopes (default: `compile,runtime`) |
| `--report <file>` | Generate a Markdown dependency-size report |
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
java -jar maven-get-deps-cli.jar --pom pom.xml --report report.md
```

Attributes size "incrementally" — each top-level dependency is charged for itself plus all its unique transitives (not counted by any earlier dependency). This pinpoints which direct dependencies are responsible for the most "weight."

---

## Build & Development

See [README.dev.md](README.dev.md) for build instructions, GraalVM native image generation, and metadata maintenance.
