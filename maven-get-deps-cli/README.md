# Maven Get Deps

A fast, lightweight tool to resolve Maven dependencies from local cache and generate classpaths.

## Modules

- **maven-get-deps-api**: Core API definitions for dependency resolution.
- **maven-get-deps-maven**: Implementation using standard Maven Resolver (Aether).
- **maven-get-deps-mimic**: Optimized implementation that mimics Maven resolution using Jackson and manual tree traversal.
- **maven-get-deps-cli**: CLI tool to use both implementations.

## Performance & Accuracy (Reference Project: `fgks-back`)

The `Mimic` implementation is designed for speed and local-only resolution, avoiding the overhead of Aether's session management and remote checks.

| Implementation | Dependencies | Cold (ms) | Hot (ms) | No-Cache* (ms) | Speedup (Hot) |
|---|---|---|---|---|---|
| **Maven (Aether)** | 191 | 1635 | 218 | 952 | 1.0x |
| **Mimic (Jackson)** | 189 | 1342 | **169** | **423** | **1.3x** |

\* *No-Cache: Internal caches disabled, but OS Disk Cache is warm.*

### Artifact Downloading
`Mimic` now supports automatic downloading of missing artifacts and POMs. It will:
1. Search for artifacts in the local repository and reactor siblings.
2. If not found, attempt to download from Maven Central or any `<repositories>` defined in the project's POM hierarchy.
3. Populate the local cache (`.m2/repository`) on-the-fly to enable subsequent resolutions.

### Alignment Results
- **CLI Project**: 100% (32/32)
- **Large Project (`fgks-back`)**: 99% (189/191)
    - *Note: Mimic prunes 2 additional dependencies due to stricter scope management in the AWS SDK tree (avoiding test-leaks).* 

## Usage

```bash
java -jar maven-get-deps-cli.jar --mimic -p path/to/pom.xml -s runtime -r path/to/reactor/root
```

- `--mimic`: Use the optimized mimic implementation.
- `-p`: Path to the root `pom.xml`.
- `-s`: Comma-separated scopes (e.g., `compile,runtime`).
- `-r`: Root path for scanning sibling modules in a multi-module project.
