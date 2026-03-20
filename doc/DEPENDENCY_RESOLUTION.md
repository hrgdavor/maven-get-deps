# Maven Dependency Resolution Internals

This document provides a technical overview of how Maven (and its `mimic` implementations) resolves transitive dependencies, handles version conflicts, and applies management rules.

## 1. Conflict Resolution: "Nearest Wins"

Maven uses a **Breadth-First Search (BFS)** strategy to build the dependency tree. This leads to the "Nearest Wins" rule:

*   **Mechanism**: The version of a dependency that is at the shallowest depth in the tree is chosen.
*   **Tie-breaking**: If two versions are at the same depth, the one declared first in the `pom.xml` (or encountered first in the BFS queue) wins.
*   **Implication**: You can override any transitive dependency version by declaring it as a direct dependency in your project's `pom.xml`.

## 2. Precedence Hierarchy

When resolving a version for a given `groupId:artifactId`, Maven checks sources in the following order:

1.  **Direct Declaration**: Explicit version in the `<dependencies>` section of the project.
2.  **Root Project Management**: Versions defined in the project's `<dependencyManagement>` section take absolute precedence over all transitive versions.
3.  **Inherited Management**: Versions from parent POMs' `<dependencyManagement>`.
4.  **Imported BOMs**: Versions from POMs imported via `<scope>import</scope>` in `<dependencyManagement>`.
5.  **Transitive Mediation**: The "Nearest Wins" rule applied to the remaining graph.

## 3. Dependency Management & Import Scope

The `<dependencyManagement>` section acts as a version catalog.

### Import Scope (BOMs)
*   **Usage**: Used with `type="pom"` and `scope="import"`.
*   **Effect**: Incorporates the `<dependencyManagement>` of the target POM into the current POM's management section.
*   **Mimic Implementation**: The `MimicDependencyResolver` must download the BOM, parse its `dependencyManagement` section, and merge it into the current `PomContext`.

## 4. Exclusions

Exclusions are **recursive** and **propagated**.

*   **Logic**: If `A` depends on `B` and excludes `C`, then no transitive dependencies of `B` can pull in `C`.
*   **Implementation**: A "blacklist" (Set of `groupId:artifactId`) must be passed down each branch of the BFS traversal.

## 5. Implementation Differences (Java vs. Zig)

| Feature | Java Mimic | Zig Mimic (Target) |
| :--- | :--- | :--- |
| **Search Algorithm** | BFS (Queue) | BFS (Queue) |
| **Exclusions** | Recursive Set | **[TODO]** Implement recursive propagation |
| **Import Scope** | Recursive BOM loading | **[TODO]** Implement BOM merging |
| **Profiles** | Ignored | Ignored (by target) |
| **Properties** | System > Env > Project | **[TODO]** Align hierarchy |
| **Version Ranges** | Literal match | Literal match (remove heuristic) |

## 6. Practical Parity Goals

To achieve 100% parity with the Java `mimic` version:

1.  **Exclusions**: Every `Node` in the resolution queue must carry a copy of its parent's exclusions plus any new ones declared on the dependency itself.
2.  **BOMs**: `PomContext` must be updated to handle `import` scope by transitively loading managed versions.
3.  **Precedence**: Ensure the `root_ctx` management is checked before any transitive management.
