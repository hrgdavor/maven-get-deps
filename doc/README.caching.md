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

## Benefits
- **Speed**: Subsequent resolutions are extremely fast. 
    - Small project: ~2s -> ~500ms (4x speedup)
    - Larger project (189 dependencies): ~9s -> ~1.6s (5.6x speedup)
- **Offline Support**: Once the cache is populated, resolution can happen entirely without network access or even Maven repository metadata.
- **Shared across projects**: Reusing the same local repository automatically benefits from the work done in earlier projects.
