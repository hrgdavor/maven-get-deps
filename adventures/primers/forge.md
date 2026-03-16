# 📜 Scroll of Arcane Forging
*A Primer on Maven Structures*

To master the Java Forge, you must understand the language of the Great Charter (POM).

## 🧩 The G:A:V Lineage
Every artifact in the realm has a unique identifier:
- **Group (G)**: The family name (e.g., `hr.hrg.maven.getdeps`).
- **Artifact (A)**: The personal name (e.g., `maven-get-deps-cli`).
- **Version (V)**: The era of its birth (e.g., `1.0.0`).

## 🧱 The Parent POM
A multi-module structure uses a **Parent POM** to coordinate its children. This allows for unified builds and shared configuration.

## 🪶 Thin vs Fat
- **Fat JAR**: Contains all dependencies inside it. Heavy, hard to transport.
- **Thin JAR**: Only contains your code. Light, requires a **Manifest** to find its allies (dependencies).

*You are now prepared to enter the Forge.*
