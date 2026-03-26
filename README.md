## Philosophy: The Complexity of the Simple

On the surface, "running an application" is simple. You run a command, and the process starts. However, doing this **right**—with security in mind, automated deployment, atomic versioning, health tracking, and the ability to revert instantly—is a much more involved engineering task. 

This project is dedicated to mastering that complexity by providing tools that turn these "simple" requirements into robust, automated realities.

- browse [User Guides](#user-guides) for use cases and tool instructions
- use [Interactive Download Guide](https://hrgdavor.github.io/maven-get-deps/download.html) instead of the [GitHub Releases](https://github.com/hrgdavor/maven-get-deps/releases/latest) where there are many and it is confusing
- to dig even deeper go to [Build & Development](doc/README.dev.md) for build instructions, ZIG native,  GraalVM native image generation(and metadata maintenance).

## Why

This whole toolset started from looking to deploy a Java app to a remote server. You need your classes **and** all of your dependencies to start your app there. Usually, this means copying dependencies to a `lib/` folder alongside your JAR, or even worse, creating fat JARs. I find that kind of surrender to bloat unacceptable, I did it many times in the past and it just did not feel right.

With frameworks like Spring bloat and Hibernate, even a moderately sized app — say **500 KB** of *your* code — can carry **50–100 MB** of dependencies. That's a **200× size difference** every time you deploy a new version. And it gets worse with microservices: each service has less code but the same pile of libraries.

The tools are available for multiple environments so you can be flexible to do many of the tasks even without maven or java installed. 

> Maven repository is the backbone of java dependency management regardless if you use `mvn`, `gradle`, `mvnd`, `ivy` ... And has [convention that reliably maps artifact definition to folder path](doc/MAVEN_LAYOUT.md) in any maven repository. This convention is a great asset to working with java dependencies, and the tools here follow it, making the process compatible with your existing `~/.m2/repository` and maven central.


### You Really Should Optimize the Process

The smarter approach is to separate your **application code** from its **dependencies**, and manage them independently:

- Maintain a **shared dependency folder** (on a network drive, or synced via rsync/scp/git)
- Treat it as a local Maven repository — shared by all your deployed applications
- Deploy only the **thin JAR** (your code) + a small `dependencies.txt` manifest per release
- At startup, use `maven-get-deps` to assemble the `CLASSPATH` from the shared folder instantly

It will pay off in the long run, as it gives you **lean, fast releases**: a 500 KB JAR deploys in milliseconds over the network. Dependencies only need to be synced when they actually change, not on every release. Multiple app versions on the same server share the same library files on disk. Your version repository does not balloon in size once version start to pile, so you need less aggressive pruning.

---

### 1. Core Java Suite

Built for deep analysis and Maven ecosystem integration.

- **maven-get-deps** ([CLI](doc/README.cli.md) / [Maven Plugin](doc/README.maven-plugin.md))
    - Full **transitive dependency expansion**.
    - Dependency **size reporting** with incremental attribution [link](doc/README.usage-report.md).
    - Classpath generation (`.txt`, `.sh`, `.bat`) [link](doc/README.usage-classpath.md).
    - **Copy dependencies** to a separate folder.
    - Local Maven cache filling (downloading missing artifacts).
- **gradle plugin** [link](doc/gradle.plugin.md) | lightweight Gradle equivalent with `--extra-classpath` support.
- **cve12** (Focused CLI) [link](doc/README.usage-cve.md)
    - Vulnerability database download **OWASP Dependency-Check v12**.
    - **Automatic database reset** & **custom KEV feed** support.
    - Standalone tool removed from core to reduce bloat and conflicts.

### 2. Ultra-Fast Zig Utilities

Native binaries with zero dependencies, optimized for production runtime usage.

- **get_deps**
    - Instant path resolution and classpath generation. [link](doc/README.usage-classpath.md)
    - Local Maven cache filling (downloading missing artifacts).
- **version_manager**
    - Zero-downtime deployment via **atomic symlink swaps**.
    - Maintains version history and revert markers.
- **gen_index**
    - Simple metadata generator for deployment versioning, that can be used as is, or as a reference to make your own.

---

## Performance Benchmarks

Measured for `test/deps/complex1/core` (with reactor siblings in `test/deps/complex1`).
Environment: Java 21, Windows 11, `mvnd` 1.0.0-m4.

| Variant                                        | Cold Run | Warm Run (Granular) |
| :--------------------------------------------- | :------- | :------------------ |
| **Baseline 1 (`mvnd dependency:list`)**        | 1987ms   | -                   |
| **Baseline 2 (`mvnd dependency:copy-dep`)**    | 1905ms   | -                   |
| **Java Classic (Aether)**                      | 1889ms   | 1788ms              |
| **Java Mimic (Optimized)**                     | 966ms    | **637ms**           |
| **Zig Mimic**                                  | 199ms    | **74ms**            |

*Cold Run* for `maven-get-deps` variants means a fresh run with no resolution cache.
*Warm Run* means reusing granular resolution caches stored in the local `.m2` repository.
This demonstrates that the cache is **reusable across all tool implementations** and independent of the project structure.
Repository-based caching ensures that project reactor modules are always resolved fresh, while external dependencies are resolved from the repository-level cache.
All runs were measured externally by a Bun script (`bun run scripts/perf_test.js`).

---

## User Guides

| Guide                                                       | Description                                                          |
| ----------------------------------------------------------- | -------------------------------------------------------------------- |
| [Deployment Philosophy](doc/README.usage-deploy.md)         | Deploy thin JARs + a shared dep folder. Avoid fat JARs.              |
| [Systemd Daemon Guide](doc/README.systemd.md)               | Deploy a Java daemon with `systemd` and atomic versioning.           |
| [Docker Integration (Dynamic)](doc/README.docker.md)        | Thin Docker images with a shared Maven cache.                        |
| [Docker Integration (Static)](doc/README.static-docker.md)  | Bake fixed classpath into Docker at build time.                      |
| [Maven Artifact Layout](doc/MAVEN_LAYOUT.md)                | Standard directory patterns and regex conversion examples (Java/JS). |

