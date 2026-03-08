# maven-get-deps (Zig Edition)

The Zig version of `maven-get-deps` is a ultra-fast, zero-dependency companion tool designed for high-performance deployment environments, CI/CD pipelines, and scenarios where a JVM is not yet available or desired.

It is als a powerful bridge for those who may still need **Docker** but want to keep images thin by managing a shared Maven cache across containers.

- [See the Docker & Kubernetes Integration Guide](README.docker.md)

## Why use the Zig version?

1.  **Speed**: Execution time is often **5x-10x faster** than the Java version for metadata-only tasks (like format conversion or classpath generation).
2.  **Zero Dependencies**: It is a single standalone binary. You don't need Java installed to run it.
3.  **Efficiency**: Extremely low memory footprint, making it ideal for large-scale container deployments or resource-constrained environments.
4.  **Deployment Companion**: Perfect for generating `CLASSPATH` variables in startup scripts without the "cold start" overhead of a JVM.

## Features

-   **Format Conversion**: Convert between Maven Path format and Gradle-style Colon format.
-   **CLASSPATH Generation**: Instantly generate OS-specific classpath strings.
-   **Jar Downloading**: Automated retrieval of missing artifacts directly from Maven Central.

## Usage

### 1. Generate a CLASSPATH string
Perfect for use in bash/PowerShell scripts to inject dependencies into an environment variable.
```sh
# Linux/macOS
CP_STRING=$(maven_get_deps -i deps.txt -cf path --classpath --cache ~/.m2/repository)
export CLASSPATH="app.jar:$CP_STRING"

# Windows (PowerShell)
$CP_STRING = .\maven_get_deps.exe -i deps.txt -cf path --classpath --cache $HOME\.m2\repository
$env:CLASSPATH = "app.jar;" + $CP_STRING
```

### 2. Download missing Jars
The Zig tool can act as a lightweight "downloader" for your shared repository.
```powershell
maven_get_deps --input dependencies.txt --download --cache /opt/shared/lib
```
This will check `/opt/shared/lib` for each dependency in `dependencies.txt`. If a JAR is missing, it will be downloaded from Maven Central and placed in the correct Maven-compliant subdirectory.

### 3. Format Conversion
Convert a list of paths back to colon format for easier reading or comparison.
```powershell
maven_get_deps -i list_of_paths.txt -cf colon
```

## Argument Reference

-   `-i, --input <file>`: Input text file containing a list of dependencies.
-   `-cf, --convert-format <format>`: Target format for output (`colon` | `path`).
-   `-cp, --classpath`: Output a single OS-separated CLASSPATH string.
-   `-c, --cache <dir>`: Base directory for paths (defaults to `~/.m2/repository`).
-   `--download`: Automatically download missing JARs from Maven Central to the cache directory.

## Building from source

Ensure you have [Zig 0.13.0](https://ziglang.org/download/) installed.

```bash
zig build -Doptimize=ReleaseFast
```
The binary will be located in `zig-out/bin/`.
