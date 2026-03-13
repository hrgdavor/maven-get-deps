# CVE Vulnerability Scanning

The `cve12` CLI tool (built with the `cve12` Maven project) includes an embedded OWASP Dependency-Check engine. It scans your dependencies against a local H2 database of NVD CVE records — **no network access** is required during the actual scan.

---

## Step 1: Populate / Update the Local CVE Database

The H2 database is stored at `~/.m2/dependency-check-data` by default.

```bash
# First-time setup (downloads ~330K+ NVD CVE records, may take several minutes)
java -jar cve12.jar --update

# Highly recommended: use a free NVD API key for 10x faster downloads
java -jar cve12.jar --update --key <YOUR_KEY>

# Custom database location
java -jar cve12.jar --update --data /shared/cve-db
```

> **Get a free NVD API key** at [nvd.nist.gov/developers/request-an-api-key](https://nvd.nist.gov/developers/request-an-api-key).

### Scheduled Updates

Keep the database current by scheduling `--update` as a recurring task:

```cron
# Linux/macOS: daily at 03:00
0 3 * * * java -jar /opt/cve12.jar --update --key $NVD_KEY
```

```powershell
schtasks /create /tn "CVE DB Update" /tr "java -jar C:\tools\cve12.jar --update" /sc daily /st 03:00
```

#### Update Cron Job (Linux / macOS)
```bash
# Update every Sunday at 3 AM with an API key and a 5-second delay to be safe
0 3 * * 0 /path/to/cve12 --update --key YOUR_KEY --nd 5000 --data /var/lib/owasp-data
```

| Parameter | Short | Description |
|---|---|---|
| `--update` | `-u` | Trigger the data download and exit. |
| `--key` | `-k` | [Optional] API key for higher rate limits. |
| `--nd` | `-nd` | [Optional] Delay in milliseconds between NVD API requests. |
| `--data` | `-d` | Path to the directory for the H2 database. |

---

## Step 2: Generate a CVE Report

The `cve12` tool expects an input file containing a list of dependencies (one per line, in `G:A:V` format).

```bash
# Generate dependency list first using maven-get-deps
java -jar maven-get-deps.jar --pom pom.xml --output deps.txt

# Scan from the generated list
java -jar cve12.jar --input deps.txt --report report.md

# Custom database location
java -jar cve12.jar --input deps.txt --report report.md --data /shared/cve-db
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
| log4j:log4j     | 1.2.17  | [CVE-2019-17571](https://nvd.nist.gov/vuln/detail/...) ...  | —             |
```

---

## Build Breaking / Severity Threshold (`--threshold`)

Use this in CI/CD pipelines to fail the build if vulnerabilities above a given severity are found (CVSS score 0.0 – 10.0, default: `8.0`).

```bash
# Exit code 1 if any CVE has score >= 7.5
java -jar cve12.jar --input deps.txt --report report.md --threshold 7.5
```

If no CVEs meet the threshold, the tool exits with **code 0**. Otherwise, it prints a warning and exits with **code 1**, breaking the pipeline.

### Example CI Pipeline Step (GitHub Actions):
```yaml
- name: CVE Scan
  run: |
    # 1. Get deps
    java -jar maven-get-deps.jar --pom pom.xml --output target/deps.txt
    # 2. Scan
    java -jar cve12.jar \
      --input target/deps.txt \
      --report target/cve-report.md \
      --threshold 7.0 \
      --data $HOME/.m2/dependency-check-data
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
