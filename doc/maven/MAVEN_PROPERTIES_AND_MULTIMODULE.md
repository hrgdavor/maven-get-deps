# Maven Properties and Multi-Module Projects

This document covers the prerequisite concepts that must be understood and correctly
implemented **before** any POM's dependency list can be computed.  The two pillars are:

1. **Property resolution** — turning `${...}` placeholders into concrete strings.
2. **Multi-module / reactor context** — knowing which POMs exist in the current build
   and how their values propagate.

Until both are handled correctly, every version string, every dependency coordinate,
and every managed-version lookup may contain unresolved placeholders or wrong values,
making the computed dependency list incorrect.

---

## Table of Contents

1. [The Effective POM and the Model Builder Pipeline](#1-the-effective-pom-and-the-model-builder-pipeline)
2. [Property Sources and Evaluation Order](#2-property-sources-and-evaluation-order)
3. [Automatic / Built-in Properties](#3-automatic--built-in-properties)
4. [Property Inheritance Through the Parent Chain](#4-property-inheritance-through-the-parent-chain)
5. [Multi-Module Projects — Structure and Terminology](#5-multi-module-projects--structure-and-terminology)
6. [Reactor Properties Propagation](#6-reactor-properties-propagation)
7. [BOM Import and Property Merging](#7-bom-import-and-property-merging)
8. [Deferred vs. Early Resolution](#8-deferred-vs-early-resolution)
9. [PomContext Constructor Order (Implementation Guide)](#9-pomcontext-constructor-order-implementation-guide)
10. [Property Precedence Summary Table](#10-property-precedence-summary-table)
11. [Common Pitfalls](#11-common-pitfalls)

---

## 1. The Effective POM and the Model Builder Pipeline

Maven never resolves dependencies directly from the raw XML.  It first constructs
an **effective POM** by running the raw model through a multi-phase pipeline
(implemented in `maven-model-builder`):

```
Phase 1 (per-module, bottom-up through parent chain):
  profile activation → raw model validation → model normalisation →
  profile injection → parent resolution → inheritance assembly →
  model interpolation → URL normalisation

Phase 2 (optional plugin processing):
  model path translation → plugin management injection →
  dependency management import →          ← BOM imports resolved here
  dependency management injection →
  default value injection → effective model validation
```

Key insight: **inheritance assembly happens before interpolation**.  This means
parent properties are merged into the child's property map *first*, and only then
are `${...}` tokens expanded.  Consequently, a child can override a parent
property, and that overridden value is what gets substituted everywhere.

---

## 2. Property Sources and Evaluation Order

Maven resolves `${name}` in the following priority order (highest wins):

| Priority | Source                          | Example                         |
|----------|---------------------------------|---------------------------------|
| 1 (highest) | CLI `-D` user properties     | `mvn ... -Dspring.version=6.2`  |
| 2        | Active profile properties       | `<properties>` inside `<profile>` |
| 3        | Child POM `<properties>`        | `<spring.version>6.1</spring.version>` |
| 4        | Parent POM `<properties>`       | same, inherited                 |
| 5        | Automatic POM model properties  | `${project.version}`, `${project.groupId}` |
| 6        | `settings.xml` properties       | `${settings.localRepository}`   |
| 7        | Java system properties          | `${user.home}`, `${java.home}`  |
| 8        | Environment variables           | `${env.PATH}`                   |

Rule: **Child beats parent; explicit beats implicit.**

Maven documentation note: *"variables are processed after inheritance — if a
parent project uses a variable, then its definition in the child, not the
parent, will be the one eventually used."*

---

## 3. Automatic / Built-in Properties

Maven automatically injects certain properties derived from the POM model itself.
These are not written by the developer; they must be synthesised by any
implementation that builds a property context.

### 3.1 Current module coordinate properties

| Property name(s)                          | Value                                       |
|-------------------------------------------|---------------------------------------------|
| `project.groupId` / `groupId` / `pom.groupId` | Effective `groupId` (own or inherited from parent) |
| `project.artifactId` / `artifactId` / `pom.artifactId` | Own `artifactId`                  |
| `project.version` / `version` / `pom.version` | Effective `version` (own or inherited)   |

Note: `groupId` and `version` are inherited from `<parent>` if omitted in the
child POM.  The bare forms (`groupId`, `version`) are **deprecated** in Maven 3
but still widely found in real POMs.

### 3.2 Parent coordinate properties

Populated from the `<parent>` section of the current POM:

| Property name(s)                                   | Value                          |
|----------------------------------------------------|--------------------------------|
| `project.parent.groupId` / `parent.groupId`        | `<parent>/<groupId>`           |
| `project.parent.artifactId` / `parent.artifactId`  | `<parent>/<artifactId>`        |
| `project.parent.version` / `parent.version` / `project.parent.version` | `<parent>/<version>` |

These are critical: BOMs and library parent POMs frequently use
`${project.parent.version}` to align sibling modules within the same release.

### 3.3 Filesystem properties

| Property               | Value                                    |
|------------------------|------------------------------------------|
| `project.basedir`      | Directory containing the POM file        |
| `project.baseUri`      | Same, as a URI                           |
| `maven.build.timestamp`| UTC start-of-build timestamp             |

### 3.4 Maven runtime properties

| Property              | Example value                |
|-----------------------|------------------------------|
| `maven.version`       | `3.9.9`                      |
| `maven.home`          | `/usr/share/maven`           |
| `maven.repo.local`    | `/home/user/.m2/repository`  |

---

## 4. Property Inheritance Through the Parent Chain

### 4.1 How the chain is built

Every POM may declare a `<parent>`:

```xml
<parent>
  <groupId>com.example</groupId>
  <artifactId>parent-pom</artifactId>
  <version>1.0.0</version>
  <relativePath>../pom.xml</relativePath>   <!-- optional; defaults to ../pom.xml -->
</parent>
```

The `<relativePath>` element is resolved relative to the child POM's directory:
- If it points to a `.xml` file, that file is used directly.
- If it points to a directory (or `../` without a filename), Maven appends
  `/pom.xml` automatically.
- If omitted, Maven looks one level up (`../pom.xml`) before falling back to
  the local repository.

The parent chain ends at the **Super POM** (Maven's built-in root), which
defines default values such as the Central repository URL.

### 4.2 Merging rules

When assembling the effective POM from a parent:

1. Start with an empty property map.
2. Copy **all** parent properties into it (recursively, grandparent first).
3. Apply the child's own `<properties>` — child keys **overwrite** parent keys.
4. Apply any inherited top-level / CLI properties last — these overwrite both.

This guarantees: **child property values always win over the same-named parent
property values**, and CLI `-D` flags always win over everything.

### 4.3 GroupId and version inheritance

A child POM that omits `<groupId>` or `<version>` inherits the corresponding
value from `<parent>`.  This is a model-level inheritance rule, not a property
substitution, but it means:
- `${project.groupId}` in a child that omits `<groupId>` correctly resolves to
  the parent's `groupId`.
- `${project.version}` resolves to the parent's version.

---

## 5. Multi-Module Projects — Structure and Terminology

### 5.1 Aggregator vs. inheritance

Maven distinguishes two related but independent concepts:

**Project Inheritance** — a child POM declares who its parent is via `<parent>`.
Properties, `<dependencyManagement>`, plugin config, etc. flow from parent to child.

**Project Aggregation** — a parent POM lists its modules via `<modules>`:

```xml
<packaging>pom</packaging>
<modules>
  <module>module-a</module>      <!-- relative path to module directory -->
  <module>../module-b</module>
</modules>
```

These two concepts are orthogonal: a module may have a parent without being
listed in that parent's `<modules>`, and vice versa.  In practice, most
multi-module projects use both simultaneously.

### 5.2 The Reactor

The **reactor** is the Maven component that:
1. Discovers all participating module POMs (by scanning `<modules>` recursively).
2. Sorts them into a dependency-respecting build order.
3. Builds them in that order.

The collection of all known module POMs is called the **reactor set**.  Each
entry is identified by `groupId:artifactId` (GA).

### 5.3 Reactor modules as local artifacts

During a reactor build, Maven's `WorkspaceReader` is configured so that
resolving a GA that matches a reactor module returns the local source POM
instead of downloading from a remote repository.  This means:
- A module's `<version>` is its actual source version, not whatever is published.
- Dependencies between sibling modules are always satisfied locally.

For any tool that computes transitive dependencies outside of a full Maven build,
the reactor set must be explicitly provided and checked before hitting remote
repositories.  Matching must be done on both `groupId` **and** `artifactId` to
avoid ambiguity.

### 5.4 Reactor module in `dependency:list` output

By default, `mvn dependency:list` **includes** sibling reactor modules in the
output if they appear as transitive dependencies.  Only with an explicit opt-out
flag (`-ss` / `--skip-siblings` in the mimic implementation) are siblings
excluded.  When implementing a mimic, the default must match Maven's default.

---

## 6. Reactor Properties Propagation

### 6.1 The problem: BOMs using properties not defined inside them

A frequent pattern in enterprise projects is:

```xml
<!-- root/pom.xml -->
<properties>
  <netty.version>4.1.118.Final</netty.version>
</properties>
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.netty</groupId>
      <artifactId>netty-bom</artifactId>
      <version>${netty.version}</version>   <!-- defined in root, not in BOM -->
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

The `netty-bom` POM itself may also use `${netty.version}` internally to version
its own entries.  When the BOM is loaded in isolation it cannot resolve
`${netty.version}` — that property lives in the consuming project.

### 6.2 Solution: pass importer properties when loading a BOM

When a BOM is imported, the importing project's current property map must be
passed as **inherited properties** to the BOM's `PomContext`.  Concretely:

```
BOM context properties = BOM's own <properties>
                        THEN overridden by importer's properties
```

This ensures:
- The BOM can resolve `${netty.version}` using the importing project's value.
- The BOM's own properties (e.g. internal re-exports) are available as fallback.

### 6.3 Root project as property authority

In multi-module builds, the reactor's root project acts as the ultimate property
authority.  All sibling modules that resolve transitive dependencies should pass
the root project's properties as `reactor_properties` (the zig term) or
`inheritedProperties` (the Java term) when constructing BOM contexts.

The lookup order for a property in any context is therefore:

```
1. Explicit properties of this POM context (child wins)
2. Parent POM context (recursive, up to root)
3. Reactor / inherited properties (root project)
```

### 6.4 Multi-pass resolution

Properties can reference other properties:

```xml
<properties>
  <spring-boot.version>3.4.4</spring-boot.version>
  <spring.version>${spring-boot.version}</spring.version>
</properties>
```

Or chain across parent boundaries:

```
root: <spring.version>${spring-boot.version}</spring.version>
root: <spring-boot.version>3.4.4</spring-boot.version>
```

Resolving `${spring.version}` requires two passes.  Maven's model builder
performs interpolation in a single pass over the fully merged property map, but
an independent implementation must loop until the string is stable or a maximum
depth (typically 8) is reached.

---

## 7. BOM Import and Property Merging

### 7.1 The `import` scope

A `<dependencyManagement>` entry with `<scope>import</scope>` and
`<type>pom</type>` is a **BOM import**:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-dependencies</artifactId>
      <version>${spring-boot.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

All `<dependencyManagement>` entries from the imported BOM are merged into the
importing project's management table.  The BOM's own transitive imports are
processed recursively.

### 7.2 Own entries win over imported entries

When merging BOM entries into the importing POM:
- An entry already present in the importer (from the importer's own
  `<dependencyManagement>` or an earlier import) **is not overwritten**.
- Entries from the BOM fill in only those GAs not yet present.

This implements the "nearest wins" principle for dependency management:
*the importer's explicit entries always beat imported BOM entries*.

### 7.3 Deferred version storage (raw strings)

BOM `<dependencyManagement>` entries often contain `${...}` version placeholders:

```xml
<dependency>
  <groupId>com.fasterxml.jackson.core</groupId>
  <artifactId>jackson-databind</artifactId>
  <version>${jackson-bom.version}</version>
</dependency>
```

If the version is resolved at BOM-load time using only the BOM's own properties,
it will be incorrect when `${jackson-bom.version}` is actually defined in the
importing project.

**Correct approach:** store the **raw** (unresolved) version string in the
management table.  Resolve the version only when it is actually needed — i.e.,
when a dependency is being evaluated during BFS traversal — using the consuming
project's full property context.

When merging BOM managed versions into the importer, resolve string using the
BOM's own context (which already has the importer's properties injected), not
the BOM's context without those properties.

### 7.4 Managed scope semantics

Managed scopes follow the same rules as managed versions with one important
distinction:

- A **missing** `<scope>` tag in a `<dependencyManagement>` entry means "no
  scope override".  It must **not** be defaulted to `compile` for the purpose
  of scope overrides.
- An **explicit** `<scope>compile</scope>` in management does override.

Only an explicitly stated scope in `<dependencyManagement>` should override the
propagated transitive scope.  Implicit `compile` (due to absence of the tag) is
non-authoritative for scope overrides.

---

## 8. Deferred vs. Early Resolution

### 8.1 What must be deferred

The following must NOT be resolved at POM-parse time; they must be resolved when
the value is actually consumed during dependency computation:

| Value                                | Why deferred                                          |
|--------------------------------------|-------------------------------------------------------|
| Version strings in `<dependencyManagement>` | May reference properties only defined in importing project |
| BOM-imported versions                | BOM context is built before importer's properties are fully known |
| Scope strings in `<dependencyManagement>` | Same reason; also null vs "compile" distinction matters |

### 8.2 What can be resolved eagerly

| Value                              | When                                    |
|------------------------------------|-----------------------------------------|
| `project.groupId` / `project.artifactId` / `project.version` | At POM-context construction |
| `project.parent.*` properties      | At POM-context construction             |
| Global `<properties>` entries      | After inheritance assembly is complete  |
| BOM import version (to fetch BOM file) | After importer's current properties are known |

### 8.3 Resolution algorithm

For each `${...}` placeholder:

1. Check the **current POM context's** property map (deepest child first).
2. Walk up the **parent** chain recursively.
3. Check **reactor / inherited** properties (root project).
4. If not found, leave the placeholder as-is (it may be resolved later or by
   the runtime plugin system, e.g. `${project.build.sourceDirectory}`).

Repeat until the string no longer changes or the iteration limit is reached.

---

## 9. PomContext Constructor Order (Implementation Guide)

The ordering within a `PomContext` constructor is critical and non-obvious.
Based on the Java and Zig mimic implementations, the correct sequence is:

```
1. Copy parent's properties into own property map.
2. Apply own POM's <properties> (child overrides parent).
3. Inject automatic properties:
   - project.groupId / project.artifactId / project.version
   - project.parent.groupId / .artifactId / .version
4. Apply inherited / reactor properties last
   (these override everything else, simulating CLI -D flags).
5. Copy parent's managedVersions and managedScopes tables.
6. Process own <dependencyManagement>:
   a. For scope=import+type=pom entries:
      - Resolve version using current property state.
      - Load BOM PomContext passing current properties as inheritedProperties.
      - Merge BOM managedVersions/scopes with putIfAbsent (own wins over BOM).
   b. For regular entries:
      - Resolve groupId and artifactId (needed as map key).
      - Store version RAW (unresolved) in managedVersions.
      - Store scope RAW if explicitly present; omit if absent.
      - Child PomContext entry overrides same key from parent (override, not putIfAbsent).
```

Step 4 is the most frequently misplaced.  If inherited / reactor properties are
applied before step 2, a parent POM of a library can override child values that
should have won.

---

## 10. Property Precedence Summary Table

| Scenario                                           | Winner               |
|----------------------------------------------------|----------------------|
| Child POM `<properties>` vs. parent POM `<properties>` | Child              |
| Own `<properties>` vs. inherited CLI `-D`          | CLI `-D` (inherited) |
| Own `<properties>` vs. reactor root's `<properties>` | Reactor root (passed as inherited) |
| BOM's `${x}` vs. importer's `${x}`                | Importer              |
| BOM A entry vs. BOM B entry (both imported)        | First import wins (putIfAbsent order) |
| Own `<dependencyManagement>` entry vs. BOM entry   | Own entry (putIfAbsent after own) |
| Parent `<dependencyManagement>` vs. child `<dependencyManagement>` | Child (override) |
| Explicit version in `<dependency>` vs. managed version | Explicit direct version |
| Managed version vs. transitive version             | Managed version       |
| `<scope>` absent in management vs. propagated transitive scope | Propagated transitive scope (absent does not override) |
| `<scope>` explicit in management                   | Managed scope         |
| Direct `<scope>` in `<dependency>` vs. managed scope | Direct scope          |

---

## 11. Common Pitfalls

### 11.1 Defaulting scope to `"compile"` in the data model

**Problem:** A `PomModel.Dependency` that defaults `scope` to `"compile"` when
no `<scope>` tag was present causes management entries to appear as if they
explicitly set `compile`, overriding valid `runtime` scopes transitively.

**Fix:** `scope` (and `type`) must be `null` in the model when not explicitly
declared.  Apply defaults only at the very end, when constructing the final
artifact descriptor.

### 11.2 Resolving BOM versions too early

**Problem:** Resolving `${jackson.version}` while loading the BOM — before the
importing project's properties have been injected into the BOM context — picks
up a wrong or missing value.

**Fix:** Pass the importer's current properties to the BOM context at
construction time.  Then record versions raw in the management table and resolve
them when actually needed.

### 11.3 Overwriting child properties with parent properties

**Problem:** Merging parent properties after child properties, or using
`putAll` unconditionally, lets the parent overwrite the child.

**Fix:** Always copy parent properties first, then apply child properties
(which overwrite parent).  Then apply reactor / inherited properties last.

### 11.4 Using `getOrDefault("scope", "compile")` during BOM merge

**Problem:** During BOM merge, treating `null` scope as `"compile"` and
storing it causes the same issue as pitfall 11.1 — it creates a spurious
explicit scope entry.

**Fix:** Only store a scope entry in `managedScopes` if the raw scope from the
BOM model is non-null.

### 11.5 Missing `project.parent.version` injection

**Problem:** A BOM entry such as `<version>${project.parent.version}</version>`
resolves to an empty string because the BOM context was not populated with parent
coordinate properties.

**Fix:** Explicitly inject `project.parent.groupId`, `project.parent.artifactId`,
`project.parent.version` (and their bare/`pom.` aliases) from the `<parent>`
element during context construction.

### 11.6 Matching reactor by artifactId alone

**Problem:** Two different groups can publish artifacts with the same `artifactId`.
Matching only on `artifactId` when checking the reactor may incorrectly treat an
external artifact as a sibling module.

**Fix:** Always match both `groupId` **and** `artifactId`.

### 11.7 Parsing `<dependency>` inside `<build>/<plugins>/<plugin>`

**Problem:** Plugin dependencies are declared inside
`project/build/plugins/plugin/dependencies`.  These are for the plugin's own
classpath and must not be treated as project dependencies.

**Fix:** Implement a path-aware parser that only collects dependencies from:
- `project/dependencies`
- `project/dependencyManagement/dependencies`
- `project/profiles/profile/dependencies`
- `project/profiles/profile/dependencyManagement/dependencies`

Any `<dependency>` with `plugins` or `plugin` in its ancestor path is ignored.

### 11.8 Resolving `<relativePath>` to a directory

**Problem:** `<relativePath>../</relativePath>` points to a directory, not a
file.  A naive concatenation to the current path yields a directory path, and
file open fails.

**Fix:** After resolving the relative path, check if the result is a directory.
If so, append `/pom.xml`.

---

## Related Documents

- [DEPENDENCY_RESOLUTION.md](DEPENDENCY_RESOLUTION.md) — detailed nuances of the
  BFS traversal algorithm, scope propagation, version mediation, and verification.
- [MAVEN_LAYOUT.md](MAVEN_LAYOUT.md) — local repository layout and artifact
  coordinate-to-path mapping.
