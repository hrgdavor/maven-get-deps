import { spawn, file as bunFile } from "bun";
import { existsSync } from "node:fs";
import { join } from "node:path";

// Configuration
const WORKING_DIR = process.cwd();
const POM = "test/deps/complex1/back/pom.xml";
const REACTOR = "test/deps/complex1";
const BASELINE_CLEAN = "test/deps/complex1/back/back_baseline.txt";
const JAVA_JAR = "maven-get-deps-cli/target/maven-get-deps-cli-1.0.2-cli.jar";
const ZIG_EXE = "zig-out/bin/get_deps.exe";
const JAVA = "C:\\Program Files\\Java\\jdk-21\\bin\\java.exe";

async function runAndCapture(name, cmd, cwd = WORKING_DIR) {
    console.log(`\n[${name}] Running...`);
    console.log(`CMD: ${cmd.join(" ")}`);
    const proc = spawn(cmd, {
        cwd,
        stdout: "pipe",
        stderr: "inherit"
    });
    const stdout = await new Response(proc.stdout).text();
    const exitCode = await proc.exited;

    if (exitCode !== 0) {
        console.error(`${name} failed with exit code ${exitCode}`);
        return null;
    }
    return stdout;
}

function parseLine(line) {
    // Remove ANSI colors and trim
    line = line.replace(/\u001b\[[0-9;]*m/g, '').trim();
    if (!line) return null;

    // Extended CLI output appends the resolution path in parentheses.
    // Remove it before tokenizing the Maven coordinates.
    line = line.replace(/\s+\([^)]*\)\s*$/, '').trim();

    // Handle mangled lines where another line's ending bled in
    // e.g. "org.a:b:jar:1.0:compile -- module a.b (auto)core [auto]"
    line = line.replace(/\(auto\).+$/, '(auto)').trim();

    // Remove common prefixes
    line = line.replace(/^\[[A-Z0-9 -]+\]\s*/, '').trim();
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

function compare(name, baseline, target) {
    const all = Array.from(new Set([...baseline, ...target])).sort();
    let diffs = 0;
    const discrepancies = [];

    for (const d of all) {
        const inB = baseline.has(d);
        const inT = target.has(d);
        if (inB !== inT) {
            diffs++;
            discrepancies.push(inB ? `[Missing in ${name}] ${d}` : `[Extra in ${name}] ${d}`);
        }
    }

    if (diffs === 0) {
        console.log(`âœ… ${name}: 100% Match (${target.size} deps)`);
    } else {
        console.log(`âŒ ${name}: ${diffs} differences found!`);
        discrepancies.forEach(d => console.log(`   ${d}`));
    }
    return diffs;
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
        // Using outputFile to ensure clean output for parsing, as console output is often mangled by mvnd
        const TEMP_BASELINE_REL = "baseline_temp.txt";
        const TEMP_BASELINE_ABS = join(WORKING_DIR, REACTOR, "core", TEMP_BASELINE_REL);
        
        await runAndCapture("Maven Baseline", ["mvnd", "dependency:list", "-B", "-ntp", "-f", POM, "-DskipTests", "-DincludeScope=runtime", `-DoutputFile=${TEMP_BASELINE_REL}`]);
        
        if (!existsSync(TEMP_BASELINE_ABS)) {
            console.error(`Failed to generate baseline file: ${TEMP_BASELINE_ABS}`);
            return;
        }
        const content = await bunFile(TEMP_BASELINE_ABS).text();
        mavenDeps = loadDepsFromOutput(content);
        
        // Save as clean baseline for future runs if it's considered stable
        // await Bun.write(BASELINE_CLEAN, content); 
    }
    console.log(`Parsed ${mavenDeps.size} dependencies from Maven baseline.`);

    // 2. Java Classic
    const javaClassicOutput = await runAndCapture("Java Classic", [JAVA, "-jar", JAVA_JAR, "-E", "-p", POM, "-r", REACTOR]);
    if (javaClassicOutput) {
        const javaClassicDeps = loadDepsFromOutput(javaClassicOutput);
        compare("Java Classic", mavenDeps, javaClassicDeps);
    }

    // 3. Java Mimic
    const javaMimicOutput = await runAndCapture("Java Mimic", [JAVA, "-jar", JAVA_JAR, "-m", "-E", "-p", POM, "-r", REACTOR]);
    if (javaMimicOutput) {
        const javaMimicDeps = loadDepsFromOutput(javaMimicOutput);
        compare("Java Mimic", mavenDeps, javaMimicDeps);
    }

    // 4. Zig Mimic
    const zigOutput = await runAndCapture("Zig Mimic", [ZIG_EXE, "mimic", "-p", POM, "-r", REACTOR]);
    if (zigOutput) {
        const zigDeps = loadDepsFromOutput(zigOutput);
        compare("Zig Mimic", mavenDeps, zigDeps);
    }
}

main().catch(console.error);

