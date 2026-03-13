# CVE Vulnerability Scanning

The Java CLI (built with the `-Pexecutable` Maven profile) includes an embedded OWASP Dependency-Check engine. It scans your dependencies against a local H2 database of NVD CVE records — **no network access** is required during the actual scan.

> **Note**: This feature is only available in the executable JAR / native binary, not the Maven plugin.

---

## Step 1: Populate / Update the Local CVE Database

The H2 database is stored at `~/.m2/dependency-check-data` by default.

```bash
# First-time setup (downloads ~330K+ NVD CVE records, may take several minutes)
java -jar maven-get-deps-cli.jar --cve-update

# Highly recommended: use a free NVD API key for 10x faster downloads
java -jar maven-get-deps-cli.jar --cve-update --nvd-api-key <YOUR_KEY>

# Custom database location
java -jar maven-get-deps-cli.jar --cve-update --cve-data /shared/cve-db
```

> **Get a free NVD API key** at [nvd.nist.gov/developers/request-an-api-key](https://nvd.nist.gov/developers/request-an-api-key).

### Scheduled Updates

Keep the database current by scheduling `--cve-update` as a recurring task:

```cron
# Linux/macOS: daily at 03:00
0 3 * * * java -jar /opt/maven-get-deps-cli.jar --cve-update --nvd-api-key $NVD_KEY
```

```powershell
schtasks /create /tn "CVE DB Update" /tr "java -jar C:\tools\maven-get-deps-cli.jar --cve-update" /sc daily /st 03:00
```

---

## Automated Maintenance (Dummy POM & Cron)

Using a "dummy" Maven project is the most reliable way to maintain your CVE database. It ensures the database format matches exactly what the `dependency-check-maven` plugin expects, and it's easy to automate with `cron`.

### 1. Simple Dummy `pom.xml`

Create a file named `pom-cve-update.xml`. This POM doesn't need any actual dependencies, just the plugin configuration.

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>hr.hrg.maven</groupId>
    <artifactId>cve-updater</artifactId>
    <version>1.0</version>
    <build>
        <plugins>
            <plugin>
                <groupId>org.owasp</groupId>
                <artifactId>dependency-check-maven</artifactId>
                <version>12.1.0</version> <!-- Use the version you want to maintain -->
                <configuration>
                    <nvdApiKey>${env.NVD_API_KEY}</nvdApiKey>
                    <dataDirectory>${env.CVE_DATA_DIR}</dataDirectory>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

### 2. Cron Setup (Linux/macOS)

You can maintain one or more database versions by running `mvn` in a cron job. Setting the `CVE_DATA_DIR` and `NVD_API_KEY` environment variables ensures the update goes to the right place securely.

```bash
# Edit your crontab with: crontab -e

# Update the main DB daily at 04:00
0 4 * * * export NVD_API_KEY="your-key-here"; export CVE_DATA_DIR="$HOME/.m2/dependency-check-data"; mvn -f /path/to/pom-cve-update.xml dependency-check:update
```

### 3. Transitioning / Multiple Versions

If you are upgrading to a new version of OWASP Dependency-Check that requires a different database schema, you can maintain both during the transition:

```bash
# Update legacy version (11.x)
0 3 * * * export CVE_DATA_DIR="/opt/cve/db-v11"; mvn -f /opt/cve/pom-v11.xml dependency-check:update

# Update new version (12.x)
0 4 * * * export CVE_DATA_DIR="/opt/cve/db-v12"; mvn -f /opt/cve/pom-v12.xml dependency-check:update
```

---

## Step 2: Generate a CVE Report

```bash
# Scan from a pom.xml (default database location used automatically)
java -jar maven-get-deps-cli.jar --pom pom.xml --cve-report report.md

# Scan from a pre-generated dependency list
java -jar maven-get-deps-cli.jar --input dependencies.txt --cve-report report.md

# Custom database location
java -jar maven-get-deps-cli.jar --pom pom.xml --cve-report report.md --cve-data /shared/cve-db
```

---

## Report Format

The output is a two-section markdown file:

**Section 1 — Summary table** (one row per direct dependency):
```
| Direct Dependency         | Status    | Transitive Issues |
|---------------------------|:---------:|:-----------------:|
| org.example:foo:1.0       | ✅ CLEAN  | —                 |
| log4j:log4j:1.2.17        | ⚠ CVE     | 0 with CVEs       |
```

**Section 2 — Details per dependency** (one block per direct dependency with issues):
```
| Artifact        | Version | CVEs                                                         | Nearest Clean |
|-----------------|---------|--------------------------------------------------------------|---------------|
| log4j:log4j     | 1.2.17  | [CVE-2019-17571](https://nvd.nist.gov/vuln/detail/...) ...  | 2.17.1        |
```

---

## Clean Version Search (`--cve-check-versions`)

When enabled, the tool queries Maven Central for all available versions of each vulnerable artifact and performs a bulk scan to identify the **nearest clean version** — the closest version in history that has no known CVEs.

```bash
java -jar maven-get-deps-cli.jar --pom pom.xml --cve-report report.md --cve-check-versions
```

The suggested clean version will appear in the **Nearest Clean** column of the report.

---

## Build Breaking / Severity Threshold (`--cve-severity-threshold`)

Use this in CI/CD pipelines to fail the build if vulnerabilities above a given severity are found (CVSS score 0.0 – 10.0, default: `8.0`).

```bash
# Exit code 1 if any CVE has score >= 7.5
java -jar maven-get-deps-cli.jar --pom pom.xml --cve-report report.md --cve-severity-threshold 7.5
```

If no CVEs meet the threshold, the tool exits with **code 0**. Otherwise, it prints a warning and exits with **code 1**, breaking the pipeline.

### Example CI Pipeline Step (GitHub Actions):
```yaml
- name: CVE Scan
  run: |
    java -jar maven-get-deps-cli.jar \
      --input target/dependencies.txt \
      --cve-report target/cve-report.md \
      --cve-severity-threshold 7.0 \
      --cve-data $HOME/.m2/dependency-check-data
- name: Upload CVE Report
  uses: actions/upload-artifact@v4
  if: always()
  with:
    name: cve-report
    path: target/cve-report.md
```

---

## Using OWASP Directly (without built-in scan)

For projects using the OWASP Maven plugin directly, you can configure it to run faster by skipping JAR analysis and relying on Maven's dependency graph (PURL-only lookup):

```xml
<configuration>
    <autoUpdate>false</autoUpdate>
    <dataDirectory>${user.home}/.m2/dependency-check-data</dataDirectory>
    <!-- Skip slow file analysis -->
    <archiveAnalyzerEnabled>false</archiveAnalyzerEnabled>
    <jarAnalyzerEnabled>false</jarAnalyzerEnabled>
    <!-- Rely strictly on Maven coordinates (very fast) -->
    <centralAnalyzerEnabled>true</centralAnalyzerEnabled>
</configuration>
```
