# Maven Dependency Resolution Algorithm

This document describes the complete algorithm for resolving a Maven project's
transitive dependency tree — the process of turning a POM's `<dependencies>`
section into a flat, fully-versioned, scoped list of artifacts.

**Prerequisite:** Before any version string or dependency coordinate can be
evaluated, all `${...}` property placeholders must be resolvable.  Read
[MAVEN_PROPERTIES_AND_MULTIMODULE.md](MAVEN_PROPERTIES_AND_MULTIMODULE.md)
first.  That document establishes how to build a `PomContext` with the correct
merged property map, from which every value in this document is derived.

---

## Table of Contents

1. [Overview and Input/Output](#1-overview-and-inputoutput)
2. [Step 0 — Build the PomContext (Properties First)](#2-step-0--build-the-pomcontext-properties-first)
3. [Effective Dependency List](#3-effective-dependency-list)
4. [BFS Traversal — "Nearest Wins"](#4-bfs-traversal--nearest-wins)
5. [Version Precedence Rules](#5-version-precedence-rules)
6. [Scope Propagation](#6-scope-propagation)
7. [Scope Relevance and Target Scope Filtering](#7-scope-relevance-and-target-scope-filtering)
8. [Scope Strength Mediation](#8-scope-strength-mediation)
9. [Optional Dependencies](#9-optional-dependencies)
10. [Exclusions](#10-exclusions)
11. [Multi-Module / Reactor Handling](#11-multi-module--reactor-handling)
12. [Version Range Resolution](#12-version-range-resolution)
13. [The Resolution Loop — Complete Pseudocode](#13-the-resolution-loop--complete-pseudocode)
14. [Post-Loop Scope Promotion Pass](#14-post-loop-scope-promotion-pass)
15. [Final Output Assembly](#15-final-output-assembly)
16. [Dependency Tree Cache](#16-dependency-tree-cache)

---

## 1. Overview and Input/Output

**Input:**
- The root project's POM file (or a list of root `ArtifactDescriptor`s).
- A `PomContext` for the root project (properties, `dependencyManagement`, parent chain).
- A reactor set: the map of `groupId:artifactId` → local POM path for multi-module builds.
- A local Maven repository path (`~/.m2/repository`).
- A list of *effective scopes* to resolve (e.g. `["compile","runtime"]`).

**Output:**
- A flat, ordered list of `ArtifactDescriptor` records: `groupId`, `artifactId`,
  `version`, `scope`, `type`, `classifier`, and a resolution path string.

**Core guarantee:** Every `groupId:artifactId` (GA) appears **at most once**
in the output.  The winning version is determined by the rules below.

---

## 2. Step 0 — Build the PomContext (Properties First)

Before the BFS loop begins, construct a `PomContext` for the root project by
following the procedure in
[MAVEN_PROPERTIES_AND_MULTIMODULE.md §9](MAVEN_PROPERTIES_AND_MULTIMODULE.md#9-pomcontext-constructor-order-implementation-guide).

Key outputs of this step:
- A fully merged **property map** (child overrides parent, inherited/CLI overrides
  all, automatic `project.*` properties injected).
- A **`managedVersions`** map (`GA → raw version string`).
- A **`managedScopes`** map (`GA → explicit scope string`).

These three maps are the root context and are consulted with highest priority
throughout the BFS traversal.

---

## 3. Effective Dependency List

For each artifact being expanded in the BFS queue, obtain its **effective
dependency list** before processing children:

1. Fetch the artifact's POM file from the local repository or download it.
2. Parse the POM into a `PomModel`.
3. Build a `PomContext` for that artifact (with the root project's properties
   passed as `inheritedProperties`).
4. Collect dependencies recursively:
   ```
   effectiveDependencies = parent.getEffectiveDependencies()   // recurse up
                         + own pom.dependencies
   ```
   Parent `<dependencies>` are inherited (this is a common source of bugs when
   only the artifact's own POM is scanned).

**Critical parsing rule:** Only collect `<dependency>` elements from:
- `project/dependencies`
- `project/dependencyManagement/dependencies`
- `project/profiles/profile/dependencies`
- `project/profiles/profile/dependencyManagement/dependencies`

Any `<dependency>` element whose ancestor path contains `<plugins>` or
`<plugin>` is a plugin dependency and must be **ignored** entirely.

---

## 4. BFS Traversal — "Nearest Wins"

Maven resolves the dependency graph using Breadth-First Search.  The first time
a GA is encountered, its version and scope are recorded and that path is
expanded.  Any re-encounter at a **greater depth** is skipped (with exceptions
for scope promotion, see §8).

### 4.1 BFS state

| Map / Set                | Key      | Value                                    |
|--------------------------|----------|------------------------------------------|
| `resolved`               | GA       | Winning `ArtifactDescriptor`             |
| `resolvedDepths`         | GA       | Depth at which the GA was first recorded |
| `resolvedOptionals`      | GA set   | GAs whose winning encounter is optional  |
| `queue`                  | ordered  | Nodes to process, FIFO                   |

### 4.2 Initial population

Pre-register all **direct** dependencies of the root project at depth 0 before
the queue loop begins:

```
for each direct dep d in root POM:
    ga = d.groupId + ":" + d.artifactId
    resolved[ga]      = ArtifactDescriptor(d, depth=0)
    resolvedDepths[ga] = 0
    queue.add(Node(d, depth=0))
```

Pre-registration ensures that a transitive encounter of the same artifact further
down the tree can never override a direct dependency, regardless of scope.

### 4.3 Skip conditions (nearest wins)

When dequeuing a node `(ad, depth)` for GA:

```
if resolvedDepths[ga] exists:
    oldDepth  = resolvedDepths[ga]
    oldAd     = resolved[ga]            // the previously registered descriptor
    oldScope  = oldAd.scope
    oldIsOpt  = resolvedOptionals.contains(ga)

    if depth > oldDepth:
        if oldDepth == 0: skip          // direct dep always wins
        if NOT (scopeStronger(ad.scope, oldScope)
             OR (oldIsOpt AND !ad.optional)):
            skip                        // deeper + no benefit → skip

    if depth == oldDepth AND oldAd IS NOT ad:
        // oldAd IS ad means this is the canonical first dequeue of a node
        // (e.g. a root dep enqueued and then dequeued exactly once).
        // In that case always proceed so children get expanded.
        if NOT (scopeStronger(ad.scope, oldScope)
             OR (oldIsOpt AND !ad.optional)):
            skip
```

Scope strength order: `compile` (3) > `runtime` (2) > `provided` (1) > `test` (0).

**Implementation note:** Pre-registering root deps in both `resolved` and the queue
means the same `ArtifactDescriptor` object is in `resolved[ga]` when the node is
dequeued.  The `oldAd IS NOT ad` object-identity guard prevents the equal-depth
check from accidentally skipping the root node's own first expansion.

### 4.4 Transitive cut-off for blocking scopes

If the current node has scope `provided` or `test` and the target resolution
does not include those scopes:

```
if (ad.scope in {"provided","test"}) AND
   ("provided" not in effectiveScopes AND "test" not in effectiveScopes):
    record in resolvedDepths (so it blocks deeper encounters)
    but do NOT enqueue children
```

This prevents hundreds of test-only transitive dependencies from leaking into
a `compile` or `runtime` resolution.

---

## 5. Version Precedence Rules

When determining which version to use for a dependency, Maven applies the
following strict hierarchy:

```
1. Explicit <version> in the direct dependency declaration of the root POM.
2. Root project's <dependencyManagement> (global override, any depth).
3. Library's own <dependencyManagement> (local to that artifact's POM).
4. Transitive / inherited version ("nearest wins" via BFS depth).
5. Declaration order (first-declared wins at equal depth, no management).
```

### 5.1 Root `dependencyManagement` as global override

The root context's `managedVersions` map is checked for every transitive
dependency, regardless of its depth in the tree:

```
v = root.getManagedVersion(gid, aid)
    ?? library.getManagedVersion(gid, aid)
    ?? dep.version (raw, resolved with library's property context)
```

This is checked **when building the child artifact's direct-dependency list**,
before the child is enqueued.

### 5.2 Direct version beats managed version

If a dependency in the root POM has an explicit `<version>`, that version takes
priority over any managed version.  The managed version is only consulted when
`<version>` is absent.

The same principle applies to **scope** for root-level direct dependencies: an
explicit `<scope>` in a root `<dependency>` element always wins over any scope
defined in `<dependencyManagement>`.  For transitive dependencies that are only
expanded during BFS (not declared in the root POM), the root project's managed
scope does override the library's declared scope (see §6.3 for the managed-scope
absence rule).

### 5.3 Child `dependencyManagement` overrides parent import

When building a PomContext, the child POM's own `<dependencyManagement>` entries
**override** the same GA from imported BOMs or parent `dependencyManagement`.
Within BOM merging, `putIfAbsent` is used so the importer always wins over the
imported BOM.

---

## 6. Scope Propagation

A dependency's effective scope when reached transitively is not its raw declared
scope — it is a function of the parent's propagated scope and the child's raw scope.

### 6.1 Propagation matrix

| Parent scope | Child raw scope | Propagated result |
|--------------|-----------------|-------------------|
| `compile`    | `compile`       | `compile`         |
| `compile`    | `runtime`       | `runtime`         |
| `compile`    | `provided`      | `null` (omit)     |
| `compile`    | `test`          | `null` (omit)     |
| `runtime`    | `compile`       | `runtime`         |
| `runtime`    | `runtime`       | `runtime`         |
| `runtime`    | `provided`      | `null` (omit)     |
| `runtime`    | `test`          | `null` (omit)     |
| `provided`   | `compile`       | `provided`        |
| `provided`   | `runtime`       | `provided`        |
| `provided`   | `provided`      | `null` (omit)     |
| `provided`   | `test`          | `null` (omit)     |
| `test`       | `compile`       | `test`            |
| `test`       | `runtime`       | `test`            |
| `test`       | `provided`      | `null` (omit)     |
| `test`       | `test`          | `null` (omit)     |

A `null` result means the dependency is **omitted** from the tree at that point.

### 6.2 When to apply propagation

Apply `propagateScope(parentScope, childRawScope)` after all version and scope
management lookups, just before deciding whether to enqueue the child node.
If the result is `null`, skip the child entirely.

### 6.3 Managed scope precedence

The managed scope is only applied if it was **explicitly declared** in
`<dependencyManagement>` (i.e. the `<scope>` tag was present in the XML).
An absent `<scope>` tag in management should not default to `compile` and
must not override a propagated transitive scope.

---

## 7. Scope Relevance and Target Scope Filtering

Not all scopes are relevant for a given target resolution request:

| Target      | Relevant at root (depth 0)               | Relevant transitively            |
|-------------|------------------------------------------|----------------------------------|
| `compile`   | `compile`, `provided`, `system`          | `compile` only                   |
| `runtime`   | `compile`, `runtime`, `provided`, `system` | `compile`, `runtime`           |
| `test`      | all                                      | `compile`, `runtime`, `test`     |

`provided` and `system` at depth 0 are included in output but their **children
are not expanded** when the target is `compile` or `runtime` (transitive cut-off,
§4.4).

---

## 8. Scope Strength Mediation

Maven does not strictly enforce "nearest wins" for scope — it can promote a
dependency to a stronger scope when a later encounter demands it.

**Rule:** If two BFS paths reach the same GA and the new encounter has a
**stronger** scope (higher `scopeStrength` value), the stronger scope wins,
even if the new encounter is at a greater depth.

```
scopeStrength: compile=3, runtime=2, provided=1, test=0
```

This is also applied at equal depths: if two `compile`-scope and `runtime`-scope
paths arrive at the same depth, `compile` wins.

**Root protection:** A depth-0 `provided` dependency must NOT be promoted to
`compile` or `runtime` by a deeper transitive encounter.  Once the root
explicitly declares `provided`, that is authoritative.

---

## 9. Optional Dependencies

Transitive dependencies marked `<optional>true</optional>` are excluded from
the resolution of projects that do not explicitly declare them.

### 9.1 Tracking

Maintain a `resolvedOptionals` set alongside `resolvedDepths`.  When a GA is
first encountered as optional, add it to this set.  If a later encounter of
the same GA is **not optional**, remove it from the set.

### 9.2 Optional vs. required priority

The "nearest wins" rule interacts with optionality:

- If the shallowest encounter is optional and a deeper encounter is required,
  the required encounter is allowed to proceed (it de-optionalizes the entry).
- Guard: `if (oldDepth < nextDepth && !oldIsOpt) continue;` — only skip
  deeper encounters when the shallower one was already required.

### 9.3 Depth-0 optionals

Optional **direct** dependencies (depth 0) are included when the consumer
explicitly lists them.  When used only as transitive dependencies they are
excluded.

---

## 10. Exclusions

Exclusions declared on a dependency propagate to **all transitive children**
of that dependency.

### 10.1 Format

Each `<exclusion>` is stored as a `"groupId:artifactId"` string.
Wildcard `<artifactId>*</artifactId>` is stored as `"groupId:*"`.

### 10.2 Propagation

Each BFS node carries an `exclusions` set.  When a child node is created, it
inherits the parent's exclusion set and adds any new exclusions declared on
the current dependency.

### 10.3 Check placement

The exclusion check happens **before** scope propagation and queue insertion:

```
if (exclusions.contains(childGa) || exclusions.contains(childGid + ":*")):
    skip child
```

---

## 11. Multi-Module / Reactor Handling

See [MAVEN_PROPERTIES_AND_MULTIMODULE.md §5](MAVEN_PROPERTIES_AND_MULTIMODULE.md#5-multi-module-projects--structure-and-terminology)
for how the reactor is discovered and why local-first resolution matters.

### 11.1 Reactor POM map

Before resolution begins, scan the project tree for all `pom.xml` files and
build a `reactorPomMap: GA → File`.  Matching must use both `groupId` and
`artifactId`.

### 11.2 Local-first artifact resolution

When `getOrDownload(groupId, artifactId, version)` is called:
1. Check `reactorPomMap[ga]`.  If found, return the local POM file directly
   (do not attempt remote download).
2. Check the local repository path using the standard Maven layout
   (`groupId`-with-dots-to-slashes + `/artifactId/version/`).
3. If not found locally, download from the configured remote repositories.

### 11.3 Reactor modules in output

By default, reactor sibling modules **are included** in the resolved list
(matching `mvn dependency:list` behaviour).  An explicit flag
(`--skip-siblings` / `-ss`) can exclude them.

### 11.4 Cache invalidation for reactor modules

Because reactor modules are under active development, **the dependency tree
cache must be bypassed** for any artifact whose POM is in the reactor set.
Always re-read and re-resolve reactor module POMs.

---

## 12. Version Range Resolution

Version ranges such as `[3.0, 4.0)` cannot be used directly to download an
artifact.  They must be resolved to a concrete version.

### 12.1 Resolution strategy

1. Parse the range string to extract lower bound, upper bound, and
   inclusivity flags.
2. List all version directories present for the artifact in the local
   repository: `localRepo/groupId-to-path/artifactId/`.
3. Sort versions descending using a semantic version comparator.
4. Return the first version that satisfies all range constraints.
5. Fallback: if no version satisfies the range or the directory is empty,
   return the lower-bound version string.

### 12.2 Range formats

| Notation     | Meaning                        |
|--------------|--------------------------------|
| `[1.0]`      | Exactly `1.0`                  |
| `[1.0, 2.0)` | `>= 1.0` and `< 2.0`          |
| `[1.0, 2.0]` | `>= 1.0` and `<= 2.0`         |
| `(1.0, 2.0)` | `> 1.0` and `< 2.0`           |
| `[1.0,)`     | `>= 1.0` (no upper bound)      |

---

## 13. The Resolution Loop — Complete Pseudocode

```
function resolve(rootDeps, effectiveScopes, rootCtx, reactorPomMap):

    # ── initialise tracking ──────────────────────────────────────────
    resolved       = {}          # GA → ArtifactDescriptor
    resolvedDepths = {}          # GA → int depth
    resolvedOpts   = {}          # GA set (optional encounters)
    strongestScopes = {}         # GA → strongest scope seen
    queue          = []          # BFS queue

    for dep in rootDeps:
        ga = dep.groupId + ":" + dep.artifactId
        resolvedDepths[ga] = 0
        resolved[ga]       = dep
        queue.append(Node(dep, depth=0, exclusions={}, path=dep.ga))

    # ── BFS loop ─────────────────────────────────────────────────────
    while queue not empty:
        current = queue.dequeue()
        ad      = current.ad
        ga      = ad.groupId + ":" + ad.artifactId

        # --- nearest-wins guard ---
        if ga in resolvedDepths:
            oldDepth  = resolvedDepths[ga]
            oldAd     = resolved[ga]
            oldScope  = oldAd.scope
            oldIsOpt  = ga in resolvedOpts
            stronger  = scopeStrength(ad.scope) > scopeStrength(oldScope)
            deOpt     = oldIsOpt and not ad.optional
            if current.depth > oldDepth:
                if oldDepth == 0: continue
                if not (stronger or deOpt): continue
            elif current.depth == oldDepth and oldAd is not ad:
                # oldAd is ad  →  canonical first dequeue of the node (e.g. root dep);
                # always allow to proceed so its children get expanded.
                if not (stronger or deOpt): continue
            # if we get here: proceed with re-registration and re-expansion

        # --- blocking-scope cut-off ---
        isBlocking = ad.scope in {"provided","test"} and
                     "provided" not in effectiveScopes and
                     "test"     not in effectiveScopes
        # still register depth (so it masks deeper encounters)
        resolvedDepths[ga] = min(current.depth, resolvedDepths.get(ga, ∞))
        resolved[ga]       = ArtifactDescriptor(ad, depth=current.depth)
        if ad.optional: resolvedOpts.add(ga)
        else:           resolvedOpts.discard(ga)

        if isBlocking: continue      # do not expand children

        # --- skip non-optional transitive optionals ---
        if ad.optional and current.depth > 0: continue

        # --- fetch POM and collect direct deps ---
        pomFile  = getOrDownload(ad, "pom")
        if pomFile is None: continue

        # cache hit (see §16)
        directDeps = loadCacheIfValid(pomFile, effectiveScopes)
        if directDeps is None:
            ctx        = loadPomContext(pomFile, rootCtx.properties)
            directDeps = buildDirectDepList(ctx, rootCtx)
            saveCacheIfNotReactor(pomFile, directDeps, effectiveScopes)

        # --- enqueue children ────────────────────────────────────────
        for cd in directDeps:
            childGa = cd.groupId + ":" + cd.artifactId

            # exclusion check
            if childGa in current.exclusions: continue
            if cd.groupId + ":*" in current.exclusions: continue

            # root management override
            v = rootCtx.getManagedVersion(cd.groupId, cd.artifactId)
                ?? cd.version
            v = resolveVersionRange(cd.groupId, cd.artifactId, v)

            sManaged = rootCtx.getManagedScope(cd.groupId, cd.artifactId)
            sRaw     = sManaged if sManaged is not None else cd.scope
            s        = propagateScope(ad.scope, sRaw)
            if s is None: continue              # omit

            # track strongest scope for post-loop promotion
            if scopeStrength(s) > scopeStrength(strongestScopes.get(childGa)):
                strongestScopes[childGa] = s

            nextDepth = current.depth + 1

            # nearest-wins pre-check before enqueueing
            if childGa in resolvedDepths:
                oldD    = resolvedDepths[childGa]
                oldS    = resolved[childGa].scope
                oldOpt  = childGa in resolvedOpts
                stronger = scopeStrength(s) > scopeStrength(oldS)
                deOpt    = oldOpt and not cd.optional
                if nextDepth > oldD:
                    if oldD == 0: continue
                    if not (stronger or deOpt): continue
                elif nextDepth == oldD:
                    if not (stronger or deOpt): continue

            nextExclusions = current.exclusions ∪ cd.exclusions
            queue.append(Node(
                ArtifactDescriptor(cd.groupId, cd.artifactId, v, s, ...),
                depth      = nextDepth,
                exclusions = nextExclusions,
                path       = current.path + " -> " + childGa + ":" + v
            ))

            resolvedDepths[childGa] = nextDepth
            resolved[childGa]       = ArtifactDescriptor(...)
            if cd.optional: resolvedOpts.add(childGa)
            else:           resolvedOpts.discard(childGa)

    return resolved, resolvedOpts, strongestScopes
```

---

## 14. Post-Loop Scope Promotion Pass

After the BFS loop, perform a single pass over the `resolved` map to apply
scope promotions gathered in `strongestScopes`:

```
for ga, ad in resolved:
    best = strongestScopes[ga]
    if best is None or scopeStrength(best) <= scopeStrength(ad.scope):
        continue
    # protect depth-0 provided/test
    if resolvedDepths[ga] == 0 and ad.scope in {"provided","test"}:
        continue
    # promote scope, preserve path from original descriptor
    resolved[ga] = ArtifactDescriptor(ad, scope=best, path=ad.path)
```

This pass handles the case where an artifact is first reached via a weaker-scope
path (e.g. `runtime`) and later via a stronger-scope path (e.g. `compile` via a
longer chain) — both paths contribute to `strongestScopes` during BFS but only
one descriptor ends up in `resolved`.

---

## 15. Final Output Assembly

```
result = []
for ga, ad in resolved:
    if ga in resolvedOpts:                             continue  # optional
    if skipSiblings and ga in reactorPomMap:           continue  # sibling opt-out
    if ad.scope not in effectiveScopes:                continue  # wrong scope
    result.append(ad)
```

The final list is in insertion order (LinkedHashMap / stable BFS order), which
reflects the traversal path and matches Maven's `dependency:list` output order.

**Artifact type mapping** applied at output time:
- `type=test-jar` → `extension=jar`, `classifier=tests`
- All other types map their type string directly to the file extension.

---

## 16. Dependency Tree Cache

Re-parsing and re-building `PomContext` for every transitive dependency is
expensive.  The cache stores the **direct dependency list** of each POM as a
flat text file next to the POM in the local repository.

### 16.1 Cache file location and naming

The cache file lives alongside the POM:

```
~/.m2/repository/{groupPath}/{artifactId}/{version}/
    {artifactId}-{version}.pom                  ← original POM
    {artifactId}-{version}.pom.{scopeKey}.get-deps.v2.cache
```

`scopeKey` is the sorted, comma-joined list of effective scopes
(e.g. `compile,runtime` or `compile,provided,runtime,test`).  Different
scope sets produce different cache files because managed-scope lookups depend
on which scopes are included.

### 16.2 Cache file format

```
# pomHash={wyhash64-of-pom-file-bytes}
{groupId}:{artifactId}:{version}:{classifier}:{type}:{scope}:{optional}:{exclusions}
...
```

| Field        | Notes                                                        |
|--------------|--------------------------------------------------------------|
| `groupId`    | Resolved (no `${...}` placeholders)                          |
| `artifactId` | Resolved                                                     |
| `version`    | Resolved concrete version (ranges already resolved)          |
| `classifier` | Empty string if absent                                       |
| `type`       | `jar` if absent                                              |
| `scope`      | `compile` if absent                                          |
| `optional`   | `true` or `false`                                            |
| `exclusions` | Comma-separated `gid:aid` strings; empty string if none      |

Each row represents one entry in the direct dependency list of the POM
(`getEffectiveDependencies()`), with all property substitution already applied
(root management overrides included).

### 16.3 Cache validity check

On cache read:
1. Compute the `wyhash64` of the current POM file bytes.
2. Compare with the stored `pomHash` header line.
3. If they differ (or the header is missing), treat as a cache miss and
   re-resolve.

A hash mismatch forces a full re-parse of the POM and rebuilding and re-saving
the cache file.

### 16.4 What is cached vs. what is not

| Cached                                       | Not cached                                  |
|----------------------------------------------|---------------------------------------------|
| Direct dependency list of each individual POM | The full resolved transitive tree           |
| Property-resolved coordinates                | Root project's own `<dependencies>`         |
| Root-managed version overrides               | Reactor module POMs (always re-resolved)    |
| Resolved version ranges                      | CLI-override properties                     |

The full transitive tree is not cached because it depends on depth context and
the root project's specific `dependencyManagement` and property set — both of
which vary per caller.  Caching per-POM direct deps is both safe and highly
effective since the overwhelming majority of resolved artifacts are third-party
library POMs that never change.

### 16.5 Cache bypass conditions

- The POM belongs to a reactor module (always bypass, see §11.4).
- `--no-cache` / `noCache=true` flag is set.
- A `pomHash` mismatch is detected (stale cache, auto-bypass and rebuild).

### 16.6 Performance characteristics

| Scenario                           | Typical count | Cache benefit       |
|------------------------------------|---------------|---------------------|
| Third-party library POM (stable)   | 100–500       | High (avoid network + parse) |
| BOM POM (stable)                   | 5–20          | High                |
| Root project POM                   | 1             | Not cached          |
| Reactor sibling POMs               | 1–50          | Not cached          |

In practice, cache hits eliminate 95%+ of POM parsing and `PomContext`
construction work for large projects on repeated runs.

---

## Related Documents

- [MAVEN_PROPERTIES_AND_MULTIMODULE.md](MAVEN_PROPERTIES_AND_MULTIMODULE.md)
  — **Read first.** Property resolution, multi-module structure, PomContext
  constructor order, BOM import merging.
- [DEPENDENCY_RESOLUTION.md](DEPENDENCY_RESOLUTION.md) — Implementation nuances
  and edge cases discovered while achieving 100% parity with Maven, numbered 1–48.
- [MAVEN_LAYOUT.md](MAVEN_LAYOUT.md) — Local repository directory layout and
  coordinate-to-path conversions.
