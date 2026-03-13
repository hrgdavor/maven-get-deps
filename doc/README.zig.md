# maven-get-deps (Zig Edition)

The Zig version of `maven-get-deps` consists of three ultra-fast, zero-dependency companion tools designed for high-performance deployment environments and CI/CD pipelines.

## Tools Overview

### 1. get_deps
Focused on dependency resolution, classpath generation, and artifact downloading.
- [Usage & Examples](README.get_deps.md) (Zig version) / [Java version](README.maven_get_deps.md)

### 2. version_manager
Focused on zero-downtime application updates via atomic symlink swaps and version tracking.
- [Usage & Deployment Guide](README.version_manager.md)

### 3. gen_index
Focused on automating the discovery of application versions and generating an index for the version manager.
- [Index Generation Guide](README.gen_index.md)

## Why use the Zig tools?

1.  **Speed**: Execution time is often **5x-10x faster** than the Java version.
2.  **Zero Dependencies**: Single standalone binaries. No JVM required.
3.  **Efficiency**: Extremely low memory footprint.
4.  **Zero-Downtime**: Atomic symlink swaps prevent partially seen deployments. (for version _namager)

## Production Integration
- [See the Docker & Kubernetes Integration Guide](README.docker.md)
- [Java & Systemd Production Example](README.deps.md#production-example-java--systemd)

## Building from source

Ensure you have [Zig 0.15.2](https://ziglang.org/download/) installed.

```bash
zig build -Doptimize=ReleaseSafe
```
The binaries (`get_deps`, `version_manager`, and `gen_index`) will be located in `zig-out/bin/`.
