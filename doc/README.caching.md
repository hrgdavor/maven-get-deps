# Granular Per-dependency Caching

To significantly speed up the dependency resolution process, `maven-get-deps` implements a granular caching strategy that stores the transitive closure of every resolved dependency directly in the local Maven repository (`.m2/repository`).

## Strategy Overview

Instead of resolving the entire project's dependency tree in one go (which can involve expensive Maven metadata lookups and network latency for remote repositories), the tool resolves each direct dependency individually.

1.  **Isolated Resolution**: For each direct dependency, the tool checks its local storage in `.m2`.
2.  **Cache Hit**: If a valid cache file exists, the tool loads the list of transitive dependencies from it.
3.  **Cache Miss**: If no cache is found, the tool performs a standard Maven resolution for that single dependency, extracts the transitive closure, and caches it for future use.
4.  **Stitching**: All individual closures are combined and deduplicated to build the final resolution result for the project.

This approach ensures that a dependency resolved once is never resolved again, even if it's used in a different project.

## Cache File Details

### Location
Cache files are stored alongside the `.pom` file in the local Maven repository.
`~/.m2/repository/{groupId}/{artifactId}/{version}/{artifactId}-{version}.pom.{scopeKey}.get-deps.cache`

### Scope Key
Since resolution depends on the requested scopes (e.g., `runtime`, `compile`, `all`), the cache filename includes a scope key to prevent incorrect reuse across different resolution modes. The scope key is a sorted, comma-separated list of scopes, or `all` if no scopes are specified.

### File Format Example
The cache file is a simple text-based format containing metadata as comments and the transitive closure as a list of coordinates (`groupId:artifactId:classifier:extension:version`).

**Example: `slf4j-simple-1.7.36.pom.runtime.get-deps.cache`**
```text
# root=org.slf4j:slf4j-simple:1.7.36::jar
# scopes=runtime
org.slf4j:slf4j-simple::jar:1.7.36
org.slf4j:slf4j-api::jar:1.7.36
```

## Performance Optimization Strategy

The tool achieves its high performance (~450ms for 190+ dependencies) through several layered optimization techniques:

1. **Strict Local Resolution**: The Maven Resolver is configured to favor the local repository and avoid slow remote metadata checks or snapshot updates.
2. **Granular Per-Dependency Caching**: Instead of caching the whole project tree, we cache the transitive closure of *each direct dependency* individually. This ensures the cache is highly reusable across different projects using the same library.
3. **Sibling Module Caching with Hash Validation**: Modules within the same Maven reactor (siblings) are also cached. To be CI-friendly, their cache is validated using a **SHA-256 hash** of their source `pom.xml`. If the hash matches the one stored in the cache header (`# pomHash=`), the cache is used; otherwise, it is re-resolved.
4. **Parallelized Fragment Resolution**: The resolution of individual direct dependency closures (cache checks and Aether collections) is performed in parallel using a thread pool. This is particularly effective during cache misses or when reading many small cache files.
5. **Batched Final Pathing**: All transitive coordinates are gathered into a single batched request to Aether for final path and size verification, minimizing redundant filesystem operations.

## Benefits
- **Speed**: Subsequent resolutions are extremely fast. 
    - Small project: ~2s -> ~500ms (4x speedup)
    - Larger project (189 dependencies): ~9s -> ~450ms (20x speedup)
- **Sibling Cache Impact**: On multimodule projects, the first resolution of siblings (before they are cached) can take ~2.8s. Subsequent resolutions using the sibling cache drop this to ~450ms, a saving of ~2.2s per run.
- **Offline Support**: Once the cache is populated, resolution can happen entirely without network access or even Maven repository metadata.
- **Shared across projects**: Reusing the same local repository automatically benefits from the work done in earlier projects.
