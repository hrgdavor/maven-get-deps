# Comparison with mthmulders/mcs

This document explores the relationship between `maven-get-deps` and the [mthmulders/mcs](https://github.com/mthmulders/mcs) project, identifying overlaps and potential for mutual benefit.

## What is mthmulders/mcs?

`mcs` (Maven Central Search) is a fast, GraalVM-compiled command-line tool designed for searching the Maven Central Repository. It provides:
- **Wildcard Search**: Find artifacts by name.
- **Coordinate Search**: Get dependency snippets for various build tools (Maven, Gradle, etc.).
- **Class-name Search**: Identify which artifacts contain a specific class.
- **Vulnerability Scanning**: Integration with Sonatype OSS Index to report CVEs in search results.

## Overlap of Functions

The direct functional overlap is minimal, as the tools focus on different stages of the development lifecycle:
- **`mcs`**: Discovery phase (finding dependencies).
- **`maven-get-deps`**: Build/Deployment phase (resolving transitivity, downloading, classpath generation, and size auditing).

However, they share a common goal of providing a fast, standalone CLI experience for Maven-related tasks without the overhead of the full Maven JVM environment.

## Opportunity for maven-get-deps (Integration/PR ideas)

1.  **Vulnerability Scanning**: Leverage the resolved transitive dependency tree to provide a fast, accurate CLI vulnerability scanner for deployments.
2.  **Class-Name Reverse Search**: Integrate class search to help resolve `ClassNotFoundException` issues by identifying missing dependencies directly from the CLI.
3.  **Ad-Hoc Coordinate Resolution**: Allow resolving and downloading artifacts/transitives without a `pom.xml` (e.g., `maven-get-deps --artifact com.foo:bar:1.2.3`).

## Opportunity for mthmulders/mcs (Integration/PR ideas)

1.  **"True Weight" Transitive Size Calculation**: Use `maven-get-deps`' resolution logic to show the total transitive weight of a library during the discovery phase.
2.  **Instant Download & Classpath Generation**: An alternative to `mvn dependency:get` or Grape, allowing users to fetch a library and its transitives and instantly get a classpath string for local execution.
