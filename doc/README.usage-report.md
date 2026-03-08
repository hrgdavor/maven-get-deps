# Dependency Size Reporting

The `maven-get-deps` tool can generate a Markdown report that attributes disk size to your direct dependencies. 

## Incremental Size Attribution

Unlike a simple `mvn dependency:list`, which just shows you what is there, this report helps you understand **why** your deployment is large.

It attributes size **incrementally**:
1. Each top-level dependency is charged for its own JAR.
2. It is then charged for all its unique transitive dependencies (those not already accounted for by a previous top-level dependency).
3. This process continues through the list, ensuring every byte is counted exactly once.

This approach pinpoints which specific direct dependencies are responsible for the most "weight" in your final artifact.

## Example Report

This example was generated using the `pom.xml` of the `maven-get-deps` project itself.

```bash
java -jar maven-get-deps-cli.jar --pom pom.xml --report report.md
```

### report.md

| Size (KB) | Dependency                                                      |
|----------:|:----------------------------------------------------------------|
|       156 | org.apache.maven.resolver:maven-resolver-api:1.9.18             |
|        53 | org.apache.maven.resolver:maven-resolver-spi:1.9.18             |
|       194 | org.apache.maven.resolver:maven-resolver-util:1.9.18            |
|       314 | org.apache.maven.resolver:maven-resolver-impl:1.9.18            |
|        43 | org.apache.maven.resolver:maven-resolver-connector-basic:1.9.18 |
|        60 | org.apache.maven.resolver:maven-resolver-transport-http:1.9.18  |
|        24 | org.apache.maven.resolver:maven-resolver-supplier:1.9.18        |
|        77 | org.apache.maven:maven-resolver-provider:3.9.6                  |
|       215 | org.apache.maven:maven-model:3.9.6                              |
|        58 | org.slf4j:slf4j-simple:1.7.36                                   |
> Total size: 1225143 bytes (1.17 MB)

## Using the Report in CI/CD

You can use this report to monitor dependency bloat over time. For example, in a GitHub Action:

```yaml
- name: Generate Size Report
  run: java -jar maven-get-deps-cli.jar --report size-report.md

- name: Comment on PR
  uses: peter-evans/create-or-update-comment@v4
  with:
    issue-number: ${{ github.event.pull_request.number }}
    body-path: size-report.md
```
