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

#### Update Cron Job (Linux / macOS)
```bash
# Update every Sunday at 3 AM with an API key and a 5-second delay to be safe
0 3 * * 0 /path/to/maven-get-deps --cve-update --nvd-api-key YOUR_KEY --nvd-api-delay 5000 --cve-data /var/lib/owasp-data
```

| Parameter | Description |
|---|---|
| `--cve-update` | Trigger the data download and exit. |
| `--nvd-api-key` | [Optional] API key for higher rate limits. |
| `--nvd-api-delay` | [Optional] Delay in milliseconds between NVD API requests. |
| `--cve-data` | Path to the directory for the H2 database. |

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

Running the OWASP Dependency-Check plugin directly within your project gives you the most control over the scanning process. This approach is highly efficient when combined with a pre-populated local database, as it skips expensive remote API calls and relies on local H2 data.

In CI/CD environments, the default `${user.home}/.m2/dependency-check-data` might be inaccessible or non-persistent. You can override the database directory on the command line using the `-DdataDirectory` property:

```bash
mvn dependency-check:check -DdataDirectory=/opt/shared/cve-db
```

For projects using the plugin directly, you can configure it for maximum speed by disabling remote lookup:

```xml
<configuration>
    <failBuildOnCVSS>7</failBuildOnCVSS>
    <suppressionFile>./pom.verify.supress</suppressionFile>
    <!-- ~/ will not work corss platform, but maven has builting variable for this purpose -->
    <dataDirectory>${user.home}/.m2/dependency-check-data</dataDirectory>

    <!-- KEEP LOCAL: Stop remote API calls -->
    <autoUpdate>false</autoUpdate>
    <ossindexAnalyzerEnabled>false</ossindexAnalyzerEnabled>
    <centralAnalyzerEnabled>false</centralAnalyzerEnabled>
    <nexusAnalyzerEnabled>false</nexusAnalyzerEnabled>

    <!-- enable the engines that find dependencies -->
    <jarAnalyzerEnabled>true</jarAnalyzerEnabled>
    <archiveAnalyzerEnabled>true</archiveAnalyzerEnabled>
</configuration>
```

# TIPS

Check path that is bringing your dependency into the project

```sh
mvn dependency:tree -Dverbose -Dincludes=org.apache.commons:commons-lang3
```

## dependency management

Instead of hunting for exclusions, use dependency management to define forced version (in parent pom if multi-module).

```xml
<!-- security override due to old version in some deps that we can not move up -->
<dependencyManagement>
    <dependencies>
        <!-- Force the newer commons-lang3 version globally -->
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-lang3</artifactId>
            <version>3.18.0</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```
