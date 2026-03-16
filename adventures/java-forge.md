# 🔨 Adventure: The Java Forge - Crafting Arcane JARs

Welcome, Traveler! Your path continues. To build a lasting empire, you must first master the art of forging efficient artifacts. For this trial, you shall take up the **Architect's Mantle**.

> [!IMPORTANT]
> **Requirements**: [Scroll of Arcane Forging](./primers/forge.md).

> [!NOTE]
> This adventure is a narrative companion to the **[CLI Guide](../doc/README.cli.md)** and **[Maven Plugin Guide](../doc/README.maven-plugin.md)**.

## 💎 Rewards Summary
- **Seal of Mastery**: 🏛️ Seal of the Architect.
- **Tools Gained**: 🛠️ Architect's Hammer.
- **Primary Loot**: `maven-get-deps` (CLI & Maven Plugin).
- **Secondary Loot**: Thin JAR extraction rituals, Zig Dispatcher speed.
- **Experience**: +1000 Maven Mastery.


---

## 🏛️ Stage 1: The Gathering of Modules
*Goal: Understand the lineage of a multi-module empire.*

Explore the `examples/multi` directory. You will find:
-   **`pom.xml`**: The Great Charter (Parent POM) that defines the modules.
-   **`odt-lib`**: A library for manipulating the ODT format.
-   **`md-to-odt`**: A tool that uses the library to transform Markdown.

**Your Task**: Go to the root of the example and build all modules.
```bash
cd examples/multi
mvn clean install
```
*Wait for the forge to cool. You have now populated your local treasury with these artifacts.*

---

## ⚗️ Stage 2: The Alchemist's Extraction
*Goal: Create a Thin JAR and externalize its weight.*

The `md-to-odt` tool is powerful, but it relies on external libraries. Instead of creating a heavy "Fat JAR," we will distill it into a **Manifest** of its dependencies.

**Your Task**: Use `maven-get-deps` to resolve dependencies and generate a manifest.
```bash
# Generate a manifest of dependencies for the md-to-odt tool
java -jar ../../target/maven-get-deps-cli.jar md-to-odt/pom.xml --dest-dir dist/lib --output manifest.txt
```
*Observe `manifest.txt`. It contains the lineage (G:A:V) required for the tool to function.*

---

## ⚡ Stage 3: The Native Spirits
*Goal: Harness the speed of the Zig Resolver.*

When you have a manifest, you can use the **Zig** utility to fetch the artifacts with lightning speed.

**Your Task**: Use `get-deps` to consume the manifest and download missing artifacts.
```bash
# Consume the manifest and ensure all artifacts are in your local vault
get-deps deps --input manifest.txt --download
```
*Note the difference in speed. The Zig resolver is a lightweight scout that performs the same task in a fraction of the time.*

---

## 🛡️ Stage 4: Warding the Perimeter
*Goal: Scan your artifacts for hidden curses.*

Before you deploy your loot, you must ensure it is not carrying any CVE curses. The sentinel `cve12` also consumes the manifest.

**Your Task**: Run the sentinel `cve12` on the dependency manifest.
```bash
# Run cve12 to check for vulnerabilities
java -jar ../../cve12/target/cve12-cli.jar --input manifest.txt --report cve-report.md
```
*Observe `cve-report.md`. If it reveals dark vulnerabilities, you must cleanse them (update versions) before proceeding.*

---

**Next Step**: Return to the **[Telekinetic Nexus](./README.md)** or proceed to **[The Sentinel's Ritual](./sentinel-ritual.md)**.


