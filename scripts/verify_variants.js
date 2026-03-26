import { spawn, file as bunFile } from "bun";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

// Configuration
const WORKING_DIR = process.cwd();

function getArg(param, defaultValue) {
    const argKey = `--${param}=`;
    const arg = process.argv.find(a => a.startsWith(argKey));
    return arg ? arg.substring(argKey.length) : defaultValue;
}

const POM = getArg("pom", "test/deps/complex1/core/pom.xml");
const REACTOR = getArg("reactor", "test/deps/complex1");
const BASELINE_CLEAN = getArg("baseline", "test/deps/complex1/core/baseline-clean.txt");

function getCliVersion() {
    const pomPath = join(WORKING_DIR, "maven-get-deps-cli", "pom.xml");
    if (!existsSync(pomPath)) {
        return "1.0.2";
    }
    const content = readFileSync(pomPath, "utf8");

    // project direct version likely missing (inherited from parent), so check parent version.
    const parentBlock = content.match(/<parent>([\s\S]*?)<\/parent>/);
    if (parentBlock && parentBlock[1]) {
        const parentVersionMatch = parentBlock[1].match(/<version>\s*([^<]+)\s*<\/version>/);
        if (parentVersionMatch && parentVersionMatch[1]) {
            const parentVersion = parentVersionMatch[1].trim();
            const propMatch = parentVersion.match(/^\$\{([^}]+)\}$/);
            if (propMatch) {
                const prop = propMatch[1];
                const rootPomPath = join(WORKING_DIR, "pom.xml");
                if (existsSync(rootPomPath)) {
                    const rootContent = readFileSync(rootPomPath, "utf8");
                    const propRegex = new RegExp(`<${prop}>\\s*([^<]+)\\s*<\\/${prop}>`);
                    const resolved = rootContent.match(propRegex);
                    if (resolved && resolved[1]) {
                        return resolved[1].trim();
                    }
                }
            } else {
                return parentVersion;
            }
        }
    }

    return "1.0.2";
}

const CLI_VERSION = getCliVersion();
console.log('Detected CLI version:', CLI_VERSION);
const JAVA_JAR = getArg("javaJar", `maven-get-deps-cli/target/maven-get-deps-cli-${CLI_VERSION}-cli.jar`);
const ZIG_EXE = getArg("zigExe", "zig-out/bin/get_deps.exe");
const JAVA = getArg("java", "C:\\Program Files\\Java\\jdk-21\\bin\\java.exe");
const MVND = "D:\\programs\\mvnd\\bin\\mvnd.exe";

async function runAndCapture(name, cmd, cwd = WORKING_DIR) {
    const proc = spawn(cmd, {
        cwd,
        stdout: "pipe",
        stderr: "pipe"
    });
    const stdout = await new Response(proc.stdout).text();
    const stderr = await new Response(proc.stderr).text();
    const exitCode = await proc.exited;

    if (exitCode !== 0) {
        console.error(`${name} failed with exit code ${exitCode}`);
        if (stderr) console.error(`${name} stderr:\n${stderr}`);
        return null;
    }
    return stdout;
}

function parseLine(line) {
    // Remove ANSI colors and trim
    line = line.replace(/\u001b\[[0-9;]*m/g, '').trim();
    if (!line) return null;

    // Handle mangled lines where another line's ending bled in
    // e.g. "org.a:b:jar:1.0:compile -- module a.b (auto)core [auto]"
    line = line.replace(/\(auto\).+$/, '(auto)').trim();

    // Remove common prefixes
    line = line.replace(/^\[[A-Z0-9 -]+\]\s*/, '').trim();
    if (!line) return null;

    // Remove optional trailing text in parentheses e.g. " (null)" from extended output
    line = line.replace(/\s*\([^)]*\)$/, '').trim();
    if (!line) return null;

    if (line.includes(' -> ')) {
        const [gav, path] = line.split(' -> ').map(s => s.trim());
        if (path === 'NOT FOUND') return { gav, path: null };
        return { gav, path };
    }

    if (line.includes(' -- ')) {
        const gav = line.split(' -- ')[0].trim();
        const cleanGav = gav.replace(/^[|+\-\s*]+/, '').trim();
        return { gav: cleanGav, path: null };
    }

    // raw GAV or list output
    if (line.split(':').length >= 3) {
        const cleanGav = line.replace(/^[|+\-\s*]+/, '').trim();
        // Remove trailing info if present (sometimes happens in list output)
        const parts = cleanGav.split(':');
        if (parts.length > 6) {
            // likely mangled or has trailing info, try to reconstruct
            return { gav: parts.slice(0, 6).join(':'), path: null };
        }
        return { gav: cleanGav, path: null };
    }
    return null;
}

function getGavInfo(gav) {
    if (!gav) return null;
    const parts = gav.split(':').map(p => p.trim()).filter(p => p.length > 0);
    // Standard Maven GAV: gid:aid:type[:classifier]:version:scope
    let info = {
        gid: parts[0], aid: parts[1], type: 'jar', classifier: '', ver: '', scope: 'compile'
    };

    if (parts.length === 3) {
        info.ver = parts[2];
    } else if (parts.length === 4) {
        info.type = parts[2]; info.ver = parts[3];
    } else if (parts.length === 5) {
        info.type = parts[2]; info.ver = parts[3]; info.scope = parts[4];
    } else if (parts.length >= 6) {
        info.type = parts[2]; info.classifier = parts[3]; info.ver = parts[4]; info.scope = parts[5];
    }
    return info;
}

function loadDepsFromOutput(content) {
    const deps = new Set();
    const lines = content.split(/\r?\n/);
    for (let line of lines) {
        const parsed = parseLine(line);
        if (!parsed) continue;
        const info = getGavInfo(parsed.gav);
        if (info) {
            // Normalize: gid:aid:type:classifier:ver:scope
            deps.add(`${info.gid}:${info.aid}:${info.type}:${info.classifier}:${info.ver}:${info.scope}`);
        }
    }
    return deps;
}

function resolveDependencyKey(gav) {
    if (!gav) return null;
    const parts = gav.split(':');
    // gid:aid:type:classifier:version:scope
    if (parts.length < 6) return null;
    return `${parts[0]}:${parts[1]}:${parts[2]}:${parts[3]}:${parts[5]}`;
}

function compareBaselineAndTarget(baseline, target) {
    const baselineKeys = new Map();
    const targetKeys = new Map();

    for (const dep of baseline) {
        const key = resolveDependencyKey(dep);
        if (!key) continue;
        baselineKeys.set(key, dep.split(':')[4]);
    }
    for (const dep of target) {
        const key = resolveDependencyKey(dep);
        if (!key) continue;
        targetKeys.set(key, dep.split(':')[4]);
    }

    let missingCount = 0;
    let extraCount = 0;
    const versionDiffs = [];

    const allKeys = new Set([...baselineKeys.keys(), ...targetKeys.keys()]);
    for (const key of allKeys) {
        const baseVer = baselineKeys.get(key);
        const targetVer = targetKeys.get(key);

        if (baseVer === undefined) {
            extraCount++;
        } else if (targetVer === undefined) {
            missingCount++;
        } else if (baseVer !== targetVer) {
            versionDiffs.push({ key, baseline: baseVer, target: targetVer });
        }
    }

    return { missingCount, extraCount, versionDiffs };
}

async function saveToVerify(name, deps) {
    const dir = join(WORKING_DIR, "target", "verify");
    if (!existsSync(dir)) {
        const { mkdirSync } = await import("node:fs");
        mkdirSync(dir, { recursive: true });
    }
    const filename = name.toLowerCase().replace(/\s+/g, "_") + ".txt";
    const sorted = Array.from(deps).sort();
    await Bun.write(join(dir, filename), sorted.join("\n") + "\n");
    console.log(`   [Saved to target/verify/${filename}]`);
}

async function main() {
    console.log("Variant Verification Tool");
    console.log("=========================");

    let mavenDeps;
    if (existsSync(BASELINE_CLEAN)) {
        console.log(`Using existing baseline: ${BASELINE_CLEAN}`);
        const content = await bunFile(BASELINE_CLEAN).text();
        mavenDeps = loadDepsFromOutput(content);
    } else {
        // 1. Maven Baseline
        const TEMP_BASELINE_REL = "baseline_temp.txt";
        const TEMP_BASELINE_ABS = join(WORKING_DIR, REACTOR, "core", TEMP_BASELINE_REL);
        
        await runAndCapture("Maven Baseline", [MVND, "dependency:list", "-B", "-ntp", "-f", POM, "-DskipTests", "-DincludeScope=runtime", `-DoutputFile=${TEMP_BASELINE_REL}`]);
        
        if (!existsSync(TEMP_BASELINE_ABS)) {
            console.error(`Failed to generate baseline file: ${TEMP_BASELINE_ABS}`);
            return;
        }
        const content = await bunFile(TEMP_BASELINE_ABS).text();
        mavenDeps = loadDepsFromOutput(content);
    }
    console.log(`Parsed ${mavenDeps.size} dependencies from Maven baseline.`);
    await saveToVerify("Baseline", mavenDeps);

    const results = [];

    async function checkVariant(name, output) {
        const deps = loadDepsFromOutput(output);
        await saveToVerify(name, deps);
        const cmp = compareBaselineAndTarget(mavenDeps, deps);
        results.push({ name, count: deps.size, ...cmp });
    }

    // 2. Java Classic
    const javaClassicOutput = await runAndCapture("Java Classic", [JAVA, "-jar", JAVA_JAR, "-E", "-p", POM, "-r", REACTOR]);
    if (javaClassicOutput) await checkVariant("Java Classic", javaClassicOutput);

    // 3. Java Mimic
    const javaMimicOutput = await runAndCapture("Java Mimic", [JAVA, "-jar", JAVA_JAR, "-E", "-m", "-p", POM, "-r", REACTOR]);
    if (javaMimicOutput) await checkVariant("Java Mimic", javaMimicOutput);

    // 4. Zig Mimic
    const zigOutput = await runAndCapture("Zig Mimic", [ZIG_EXE, "mimic", "-p", POM, "-r", REACTOR]);
    if (zigOutput) await checkVariant("Zig Mimic", zigOutput);

    console.log("\nSummary");
    console.log("=======");
    console.log(`Baseline deps: ${mavenDeps.size}`);
    for (const r of results) {
        const hasDiff = r.missingCount + r.extraCount + r.versionDiffs.length > 0;
        console.log(`- ${r.name}: ${r.count} deps (${hasDiff ? "DIFFERENCES" : "no differences"})`);
        if (r.versionDiffs.length > 0) {
            console.log(`    version differences (${r.versionDiffs.length}):`);
            r.versionDiffs.slice(0, 20).forEach(v => {
                console.log(`       ${v.key} baseline=${v.baseline} target=${v.target}`);
            });
            if (r.versionDiffs.length > 20) {
                console.log(`       ... ${r.versionDiffs.length - 20} more`);
            }
        }
    }
}

main().catch(console.error);
