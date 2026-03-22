# Dependency Resolution Mimicry

This document details the nuances and challenges encountered while implementing a dependency resolution mimic that matches Maven's behavior exactly.

## Objectives
The primary goal was to achieve 100% parity with Maven's dependency resolution for the `test/deps/complex1/back` project, specifically aiming for an exact count of 166 artifacts in the `compile` scope. The example was extracted from real project and includes some version mismatch in the modules that was left here for to stress the code, but leter fixed in the original project.

## Key Nuances and Solutions

### 1. Breadth-First Search (BFS) and "Nearest Wins"
Maven's dependency resolution is functionally BFS. The first time an artifact (GroupId:ArtifactId) is encountered, its version is fixed. Any subsequent encounters at a deeper level are ignored (unless they are managed).

**Problem:** Initial implementations incorrectly allowed deeper versions to overwrite or re-evaluate subtrees.
**Solution:** Implement a strict `resolved_depths` map. If a GA is encountered at a depth greater than or equal to its current resolved depth, skip its registration and its transitive subtree expansion.

### 2. Scope Propagation
Scopes are inherited and transformed as you move down the dependency tree.

**Problem:** Transitive `provided` and `system` dependencies should NOT be included in the `compile` scope.
**Solution:** Implement a `propagateScope` function that correctly handles these transitions.
- `direct_compile` -> `child_compile` = `compile`
- `direct_compile` -> `child_provided` = `provided` (Ignore for transitive)
- `direct_compile` -> `child_runtime` = `runtime`
- **Nuance:** Transitive dependencies of a `test` scope dependency must also stay in `test` scope (and thus be excluded if target is `compile/runtime`).
- **Discovery:** `dependencyManagement` scope SHOULD NOT override a scope explicitly defined in a direct dependency. For example, if a POM says `<scope>test</scope>`, a BOM managing it to `compile` should be ignored.

### 3. BOM Property Inheritance
BOMs (Bill of Materials) are imported via `<dependencyManagement>`.
**Problem:** BOMs often use properties (e.g., `${jetty.version}`) that are defined in the project which imports the BOM, NOT inside the BOM itself.
**Solution:** Ensure that the property resolution context for an imported BOM includes the properties of the importing project (root project context).

### 4. Effective Scope Relevance
Not all propagated scopes are relevant for a given target scope (e.g., `compile`).

**Problem:** Initially, all non-test scopes were being included.
**Solution:** Implement a strict `isScopeRelevant` check.
- For `target=compile`: Include `compile`, `provided` (only direct), `system` (only direct).
- Transitive `provided` and `system` are excluded.
- `runtime` is excluded from `compile` resolution (it is used for execution, not compilation).
- **Implied Scopes**: If the target scope requires both execution and compilation contexts (e.g., `runtime`), the system must proactively include `compile` in its effective resolution scopes, because runtime artifacts fundamentally rely on compile-time artifacts.

### 5. Property Resolution
Maven POMs heavily use properties like `${project.version}` or `${spring-boot.version}`.

**Problem:** Missing property resolution leads to `NOT_FOUND` artifacts or incorrect version matching.
**Solution:** Pre-load all properties from the POM (including parents) into a context and recursively resolve strings.

### 6. Version Ranges
Some dependencies specify ranges like `[3.0, 4.0)`.

**Problem:** These strings are not valid versions for downloading from a repository.
**Solution:** Sanitize range strings by extracting the base (lower bound) version.

### 7. Exclusions
Exclusions (both GA and G:*) must be propagated down the tree.

**Problem:** Exclusions applied to a direct dependency must also apply to all its transitive children.
**Solution:** Pass an inherited set of exclusion patterns down each branch of the resolution tree. When using Aether, ensure `org.apache.maven.model.Exclusion` is explicitly mapped to `org.eclipse.aether.graph.Exclusion` in the `CollectRequest`.

### 8. Conflict Resolution and BOMs
**Case Study: asm version mismatch**
- `jetty-project` imports `asm-bom` with version `${asm.version}`.
- `accessors-smart` explicitly defines `asm` with version `9.7.1`.
- Maven's BFS ("Nearest wins") decides the winner.
**Discovery:** Local `dependencyManagement` in the root project (or its parent chain) has the highest priority. If root's parent (e.g., `spring-boot-starter-parent`) manages a version, it overrides transitive versions even if they are closer.

### 9. Late Property Resolution
Properties like `${jackson.version}` or `${jetty.version}` are often defined in a parent POM or even in the root project.
**Solution:** Implement recursive property inheritance. Each `PomContext` includes properties from its parent and the root project context. Resolution must be multi-pass (up to 8 levels) to handle nested property definitions (e.g., `${a}` -> `${b}` -> `1.0`).


### 10. Managed Versions and Transitive Overrides
**Problem:** Some transitive dependencies (e.g., `logback-classic`) were resolving to newer versions (1.5.32) instead of the one managed by Spring Boot (1.5.11).
**Cause:** Incomplete `dependencyManagement` merging from the parent hierarchy or incorrect property resolution during BOM import.

### 11. Reactor and Multi-module Support
In multi-module projects, sibling modules must be resolved from the local workspace rather than the remote repository.

**Problem:** Matching only by `artifactId` in the `WorkspaceReader` can be ambiguous if multiple groups use the same ID.
**Solution:** Implement a `ReactorWorkspaceReader` that matches both `groupId` and `artifactId`. Ensure it scans subdirectories recursively to find all module POMs.

### 12. Automation and Verification Tools
Matching Maven 100% requires robust comparison tools.

**Problem:** Maven's output (especially `mvnd`) contains ANSI escape codes and formatting that break simple `diff` or `grep` checks.
**Solution:** 
- Use an `--extended` output flag in the CLI to produce `groupId:artifactId:type:version:scope` format.
- Use a verification script (e.g., in Bun/JS) that strips ANSI codes and sorts results before performing a set-based comparison.
- **Nuance:** When using `--extended` mode in the CLI, the output includes resolution paths in parentheses (e.g., `g:a:t:v:s (path -> path)`). The comparison script must strip these parentheses to match Maven's raw `g:a:t:v:s` format.
- Implement a generic tracing architecture (e.g., `--debug-match` flag) allowing dynamic tracking of specific GroupIDs/ArtifactIDs through the BFS tree to analyze scope drops or version overrides without relying on hardcoded debug traces.

### 13. Property Precedence and Shadowing
**Discovery:** Properties must follow a strict precedence: Top-level Overrides (e.g. CLI) > Child POM > Parent POM. 
- **Problem:** Initial `PomContext` blindly merged parent properties into child, allowing parents to sometimes shadow child properties if not handled carefully during recursion.
- **Solution:** Explicitly re-apply `inheritedProperties` (top-level) after loading parent and own properties.

### 14. Library-Level `dependencyManagement` Inheritance
**Discovery:** When resolving transitive dependencies of a library (e.g., `netty-codec-http`), Maven honors that library's own `dependencyManagement` section (and its parent hierarchy) if the child dependency lacks an explicit scope or version.
- **Example:** `netty-parent` manages `mockito-core` to `test` scope. `netty-codec-http` depends on `mockito-core` with no scope. For `netty-codec-http`, it is a `test` dependency.
- **Solution:** The resolution context for a library must include its own managed versions and scopes as a secondary priority (overridden only by the root project's management).

### 15. Automatic Parent Properties
**Discovery:** Maven automatically populates properties like `${project.parent.version}`, `${parent.groupId}`, and `${project.parent.artifactId}` based on the `<parent>` tag.
- **Problem:** BOMs often use `${project.parent.version}` to align sister modules.
- **Solution:** Explicitly inject these properties into the `PomContext` during parent detection.

### 16. Dynamic Version Range Resolution
**Discovery:** Version ranges (e.g., `[3.0, 4.0)`) are not just static strings; they must resolve to the **HIGHEST** matching version currently available in the local repository or remote.
- **Solution:** Implement a `resolveVersionRange` helper that scans the local repository directory for an artifact, sorts versions descending, and picks the first one that satisfies the range boundaries.

### 17. Deferred Resolution for Managed Dependencies
**Discovery:** Version strings in `<dependencyManagement>` (BOMs) must be stored in their **RAW** form (e.g., `${jackson.version}`) and resolved later using the consuming project's full property context.
- **Problem:** Resolving `${...}` too early in the parent/BOM context uses the BOM's own properties, missing overrides defined in the project.
- **Solution:** Store raw strings in `managedVersions` and call `resolveProperty()` only when the version is actually needed for a dependency.

### 18. BOM Import Property Merging
**Discovery:** When importing a BOM, the importer's properties must be passed as `inheritedProperties` to the BOM's context.
- **Solution:** In `PomContext` constructor, pass `this.properties` when calling `loadPomContext` for an imported BOM. Resolve managed versions from the BOM using the BOM's context before merging them.

### 19. Smart Range Matching in Local Repository
**Discovery:** Maven version ranges like `[3.0, 4.0)` should be resolved to the HIGHEST matching version available in the local repository.
- **Solution:** Implement a best-guess matcher in `getOrDownload` that sorts local versions descending and picks the first one starting with the same major version prefix.

## Verification
Parity is verified by comparing against `mvnd dependency:list -Dscope=runtime`.
- **Target:** 183 artifacts (for `test/deps/complex1/core` runtime scope).
- **Mimic Status:**
  - **100% parity achieved!** The mimic now outputs exactly the same 183 artifacts with matching versions and scopes.
  - **Complex1/Core Project**: 100% parity achieved.
    - Verified match against provided `dependencies.txt` (183 artifacts).
  - **Complex1/Back Project**: 100% parity achieved.
    - Verified match against provided `dependencies.txt` (191 artifacts).
    - Corrected "Reactor Module Exclusion" nuance to instead include reactor siblings to match `dependency:list` output.
  - Logback matched exactly (`1.5.11`).
  - AWS SDK transition tree fully matched (20+ artifacts).
  - Bugsnag range correctly resolved (`3.8.0`).
  - Jackson variables resolved using Project Properties.
  - `amqp-client` correctly resolved to `5.21.0` (Direct version wins over Managed).
  - `spring-websocket` correctly resolved to `6.2.11` (Direct version wins over Managed).
  - Test and Provided scopes do not leak into runtime.

## Advanced Resolution Nuances

### 20. Standard Version Precedence
Maven follows a strict hierarchy for version selection:
1. **Explicit Direct Version**: The version tag inside a `<dependency>` in the current POM.
2. **Managed Version**: Versions defined in `<dependencyManagement>` of the current POM or its parents.
3. **Transitive Version**: Versions defined in the POMs of dependencies ("nearest wins").

### 21. Managed Transitive Overrides
A version specified in `<dependencyManagement>` ALWAYS overrides any version encountered transitively, even if the transitive encounter is "closer" to the root. However, it DOES NOT override an explicit version in a direct dependency of the current project.

### 22. Scope Propagation Matrix
When resolving transitively, the scope of a child dependency is filtered by the scope of its parent:
- **Parent: Compile** -> Child: Compile=Compile, Runtime=Runtime, Provided=null, Test=null.
- **Parent: Runtime** -> Child: Compile=Runtime, Runtime=Runtime, Provided=null, Test=null.
- **Parent: Provided** -> Child: Compile=Provided, Runtime=Provided, Provided=null, Test=null.
- **Parent: Test** -> Child: Compile=Test, Runtime=Test, Provided=null, Test=null.
*Note: 'null' means the dependency is omitted from the transitive tree.*

### 23. Mediation by Declaration Order
If two versions of the same artifact are at the same depth and neither is managed, the version from the dependency that was declared FIRST in the POM (top-to-bottom) wins.

### 24. Managed Scope Precedence
Similar to versions, `dependencyManagement` can provide a default scope if it's missing in the dependency declaration. However, an explicit `<scope>` in a direct dependency always wins over the managed scope. สำหรับ transitive dependencies, both regulated version AND managed scope from the project's `dependencyManagement` should be applied if present.

### 25. Scope Strength Mediation (Tie Breaking)
**Discovery:** When Maven traverses the dependency tree, it usually follows "nearest wins" (BFS). However, if it encounters the *exact same* dependency at the *exact same depth* but with different scopes, it performs "Scope Strength Mediation".
- **Example:** `jakarta.inject-api` reached at depth 1 via `hibernate-core` as `runtime`, and later at depth 4 via `jakarta.enterprise.cdi-api` as `compile`. Standard BFS would pick `runtime`. Maven upgrades it to `compile`.
- **Solution:** In the `resolvedDepths` check, if the new encounter is at the *same* depth as existing one, or even if it's deeper but has a stronger scope, allowed it to overwrite.
- **Implementation Detail:** Use a `scopeStrength` helper: `compile` (3) > `runtime` (2) > `provided` (1) > `test` (0).

### 26. Root Node Expansion and "Nearest Wins"
**Discovery:** Direct dependencies defined in the root POM are at depth 0.
- **Problem:** If root dependencies are not explicitly registered at depth 0 in the BFS tracker before resolution begins, a transitive dependency at depth `N` could overwrite the root dependency's scope (e.g., explicitly defining `test` scope at root overridden by `compile` transitively). 
- **Solution:** Pre-load all direct dependencies into the depth tracker at depth 0. Additionally, when expanding nodes during BFS, ensure that root nodes are allowed to expand even if another path reaches them at the same depth.

### 27. Ghost Defaults in POM Models
**Discovery:** Internal representations of POM models must distinguish between "not specified" and "default value".
- **Problem:** The `PomModel` class initially defaulted all `<dependency>` objects to `scope="compile"` and `type="jar"`. When a BOM defined a dependency in `<dependencyManagement>` without explicitly defining a scope, the model saved it as `compile`. This incorrectly forced all transitive resolutions of that dependency to `compile`, overriding their natural `runtime` scope.
- **Solution:** `scope` and `type` must default to `null` in the data model. Defaults (like `"compile"` and `"jar"`) should only be applied at the very end of the resolution process when constructing the final `ArtifactDescriptor`, allowing `dependencyManagement` to only override what it explicitly defines.

### 28. Scope Masking by Skipped Dependencies
**Discovery:** Dependencies with scopes that are NOT in the target resolution list (e.g., `test` or `provided` when resolving `compile`) still participate in "Nearest Wins".
- **Problem:** If a library is encountered at depth 1 as `test` scope, it should mask any subsequent encounters of that library at depth 2 (e.g., as `compile`). Initially, skipped scopes were entirely ignored, allowing deeper `compile` dependencies to bleed into the final resolution.
- **Solution:** In the resolution loop, the encounter of a dependency must be recorded in the `resolvedDepths` tracking map *before* deciding whether its scope qualifies it to be added to the queue or final list.

### 29. Effective Propagated Scope Recording
**Discovery:** The scope assigned to a transitive dependency in the final resolution must be its *propagated* effective scope, not its raw declaration scope.
- **Problem:** A dependency declared as `runtime` inside a parent that was resolved as `compile` was being recorded as `runtime` instead of `compile`.
- **Solution:** Apply the `propagateScope(parentScope, childRawScope)` function, and use the *result* (`s`) when creating the `ArtifactDescriptor` to be saved in the `resolved` map.

### 30. Optional Dependency Handling
**Discovery:** Transitive dependencies marked as `<optional>true</optional>` are skipped by Maven during resolution (unless they are explicitly requested as direct dependencies).
- **Problem:** If a library (e.g., `netty-codec-http2`) has a dependency on `brotli4j` marked as optional, it should not appear in the final resolution of a project consuming Netty.
- **Solution:** Maintain a `resolvedOptionals` set. If a dependency is encountered as optional, record it in this set. If a later encounter at the same or closer depth is NOT optional, remove it from the set. Exclude all members of this set from the final result list.

### 31. Transitive Expansion Guard (Nearest Wins Tie-Breaking)
**Discovery:** The BFS queue must skip expanding subtrees if a "better" (closer or stronger) version of a node is already processed.
- **Refinement:** The guard check `if (current.depth == oldDepth && oldAd != ad && scopeStrength(ad.scope()) < scopeStrength(oldAd.scope())) continue;` ensures that if we have two paths to the same artifact at the same depth, the one with the stronger scope (e.g., `compile` vs `runtime`) is allowed to proceed and expand its children with that stronger propagated potential.

### 32. Reactor Module Inclusion (Revised)
**Discovery:** While some specific resolution targets might require excluding sibling modules, the standard `mvn dependency:list` output for a sub-module DOES include its reactor siblings if they are dependencies.
- **Problem:** The mimic was previously excluding siblings like `org.complex1:core`, leading to discrepancies.
- **Solution:** Implemented `skipSiblings` as a parameter (defaulting to `false`). When `false`, all siblings are included in the final output, matching the baseline for `complex1/back`.
- **CLI Option:** Use `-ss` or `--skip-siblings` to explicitly skip these modules if needed.

### 33. Optional vs. Required Conflict (Nearest Wins Nuance)
**Discovery:** Maven's "Nearest Wins" principle for version resolution interacts with optionality in a non-obvious way. If the nearest encounter is optional, it is skipped. However, this SHOULD NOT block a deeper encounter that is REQUIRED.
- **Problem:** The mimic was recording the shallowest encounter (which was optional) in `resolvedDepths`, and then the BFS guard was blocking any deeper encounter for the same artifact, even if the deeper one was required.
- **Solution:** Refined the BFS guard to allow an encounter to proceed if it is NON-OPTIONAL even when a shallower OPTIONAL encounter already exists.

### 34. Parent Dependency Inheritance
**Discovery:** Maven children (including transitive dependencies) inherit the `<dependencies>` section from their parent POM chain.
- **Problem:** Libraries like AWS SDK often define common runtime dependencies (e.g., `apache-client`, `netty-nio-client`) in a shared parent `services-*.pom`. Initially, the mimic only expanded dependencies from the artifact's own POM, missing these inherited ones.
- **Solution:** In `PomContext.getEffectiveDependencies()`, recursively collect dependencies from the parent context before adding the current artifact's own dependencies.

### 35. Scope Propagation Guard Placement
**Discovery:** The guard `if (s == null) continue;` (which filters out non-relevant `test`/`provided` scopes) must be placed BEFORE any calls to `resolvedDepths.put` or `resolved.put`.
- **Problem:** If a dependency is encountered later in the tree with a `test` or `provided` scope, it should be ignored. If the guard is missing or misplaced, this encounter can overwrite a previous valid `compile` or `runtime` resolution in the map with a `null` scope, effectively deleting the artifact from the final output.
- **Solution:** Ensure `propagateScope` is called early, and the `null` result is used to skip early.

### 36. Artifact Path Preservation during Promotion
**Discovery:** When promoting the scope of an already resolved artifact (see #23 and #29), the resolution path reported by Maven remains the path of the **FIRST** (shallowest) encounter.
- **Problem:** If we create a completely new `ArtifactDescriptor` during promotion, we might lose the original path string (e.g., `hibernate-core -> jakarta.inject-api`), replacing it with the path of the deeper promotion source.
- **Solution:** When performing scope promotion, ensure the `ArtifactDescriptor` is updated only for the `scope` field, while copying the `path` field from the EXISTING descriptor.
- **Implementation:** `ArtifactDescriptor promoted = new ArtifactDescriptor(oldAd.groupId(), ..., s, ..., oldAd.path());`

### 37. Optional vs. Required Intersection (Deeper Wins)
**Discovery:** In addition to "Nearest Wins" for versions, optionality follows a specific rule. If an artifact is encountered as `optional` at a shallower depth, it should NOT block a `required` encounter at a deeper level.
- **Bug Fixed:** The mimic was blocking `log4j-api` because it first saw it as optional via `netty-common`, skipping its required encounter via `spring-boot-starter-logging`.
- **Solution:** Refined the BFS guard: `if (oldDepth < nextDepth && !oldIsOpt) continue;`. If the shallower encounter was optional, allow the deeper required one to proceed.

### 38. Root Provided Scope Protection
Discovery: Direct dependencies defined in the root POM with provided scope should NOT be promoted to compile or runtime through transitive encounters, as this would incorrectly include them in the final artifact listing.
- Problem: jakarta.websocket-api was being promoted from provided to compile due to a deeper encounter via Jetty, leading to its inclusion in the compile,runtime resolve list.
- Solution: In the scope promotion logic, add a check to prevent promotion if the existing dependency is at depth 0 and has provided scope.

### 39. Reactor Module Exclusion (Refined)
**Discovery:** In multi-module projects, sibling modules (reactor modules) are used to resolve transitive paths but must be excluded from the final output list of dependencies.
- **Problem:** Siblings like `org.complex1:core` were appearing in the final result list of the `back` project.
- **Solution:** Filter the final result list using the `reactorPomMap` to exclude any artifact that belongs to the current reactor.

### 40. Provided/Test Root Expansion
**Discovery:** Direct `provided` and `test` dependencies must be expanded into the BFS tree if they are part of the requested target scopes.
- **Problem:** The mimic was hard-skipping `provided` and `test` dependencies at depth 0, meaning their transitive dependencies were never explored.
- **Solution:** Allow all root dependencies to be added to the BFS queue if their scope is in the `effectiveScopes` list.

### 41. Test-Jar Type Mapping
**Discovery:** The Maven dependency type `test-jar` does not map directly to a file extension of the same name.
- **Solution:** Explicitly map `type="test-jar"` to `extension="jar"` and `classifier="tests"` during the artifact resolution process.

### 42. Root Path Traceability
**Discovery:** For consistent reporting in extended mode (`-E`), root dependencies should report their own `groupId:artifactId` as their resolution path.
- **Problem:** Root dependencies were reporting `(null)` in the extended output.
- **Solution:** Set the `path` field of the `ArtifactDescriptor` to the G:A string during root node initialization.

### 43. Transitive Block Scope Masking
**Discovery:** Scopes like `provided` and `test` block their transitive dependencies from being resolved if the top-level resolution target is `compile` or `runtime`.
- **Problem:** In the Zig mimic, `jakarta.websocket-api` and hundreds of other artifacts were incorrectly included because the system allowed `provided`-scope dependencies to expand their transitive children, heavily bloating the resolution tree (producing 900+ artifacts instead of 183).
- **Solution:** Ensure that when a dependency is encountered and resolved as `provided` (or `test`), its transitive subtree is NOT traversed if the target resolution is `compile` or `runtime`. This acts as a strict cut-off for the BFS graph expansion.

### 44. Global `dependencyManagement` Application
**Discovery:** The root project's `dependencyManagement` section acts as a global version override for ALL transitive dependencies anywhere in the tree, regardless of depth, unless a closer explicit direct version exists.
- **Problem:** Transitive dependencies like `log4j-api` were resolving to mismatched versions because the root `dependencyManagement` was only being checked at shallower levels, and deeper transitive imports were bypassing it.
- **Solution:** Pass the root project's `PomContext` (or its managed versions map) down the entire BFS traversal and evaluate it for every encountered transitive dependency before applying "nearest-wins" resolution.
