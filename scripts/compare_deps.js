#!/usr/bin/env node
/**
 * Enhanced dependency comparison tool.
 * Supports Maven dependency:list/tree output and maven-get-deps-cli output.
 * Can compare by full GAV or just by filename.
 */
import { readFileSync, existsSync } from 'fs';
import { basename } from 'path';

function parseLine(line) {
    // Remove ANSI colors and trim
    line = line.replace(/\u001b\[[0-9;]*m/g, '').trim();
    if (!line) return null;

    // Remove common prefixes like [INFO], [DEBUG], etc.
    line = line.replace(/^\[[A-Z0-9 -]+\]\s*/, '').trim();
    if (!line) return null;

    // Handle "GAV -> Path" format (commonly used in our CLI output)
    if (line.includes(' -> ')) {
        const [gav, path] = line.split(' -> ').map(s => s.trim());
        if (path === 'NOT FOUND') return { gav, path: null };
        return { gav, path };
    }

    // Handle "GAV -- module ..." format (maven dependency:tree or info lines)
    if (line.includes(' -- ')) {
        const gav = line.split(' -- ')[0].trim();
        // Maven tree prefix like "+- " or "|  "
        const cleanGav = gav.replace(/^[|+\-\s*]+/, '').trim();
        return { gav: cleanGav, path: null };
    }

    // Handle repo path format (org/group/id/ver/id-ver.jar)
    // Detect if it looks like a path and not a GAV
    if (line.includes('/') && !line.includes(' -- ') && !line.includes(' -> ') && (line.endsWith('.jar') || line.endsWith('.pom'))) {
        return { gav: null, path: line };
    }

    // Handle raw GAV format (gid:aid:type:ver:scope or gid:aid:ver)
    if (line.split(':').length >= 3) {
        // Remove tree prefixes even if no " -- " is present
        const cleanGav = line.replace(/^[|+\-\s*]+/, '').trim();
        return { gav: cleanGav, path: null };
    }

    return null;
}

function getGavInfo(gav) {
    if (!gav) return null;
    const parts = gav.split(':').map(p => p.trim()).filter(p => p.length > 0);
    // Standard Maven GAV: gid:aid:type[:classifier]:version:scope
    // simplified for comparison: 
    // 3 parts: gid:aid:ver
    // 4 parts: gid:aid:type:ver
    // 5 parts: gid:aid:type:ver:scope
    // 6 parts: gid:aid:type:classifier:ver:scope
    
    let info = {
        gid: parts[0],
        aid: parts[1],
        type: 'jar',
        classifier: '',
        ver: '',
        scope: 'compile'
    };

    if (parts.length === 3) {
        info.ver = parts[2];
    } else if (parts.length === 4) {
        info.type = parts[2];
        info.ver = parts[3];
    } else if (parts.length === 5) {
        info.type = parts[2];
        info.ver = parts[3];
        info.scope = parts[4];
    } else if (parts.length >= 6) {
        info.type = parts[2];
        info.classifier = parts[3];
        info.ver = parts[4];
        info.scope = parts[5];
    }
    return info;
}

function getFilename(gav, path) {
    if (path) {
        const p = path.replace(/\\/g, '/');
        return basename(p);
    }
    const info = getGavInfo(gav);
    if (!info) return null;
    // aid-ver[-classifier].type
    const classifierStr = info.classifier ? `-${info.classifier}` : '';
    return `${info.aid}-${info.ver}${classifierStr}.${info.type}`;
}

function loadDeps(filename, useFilenames = false, filterScope = null) {
    const deps = new Set();
    try {
        const content = readFileSync(filename, 'utf-8');
        const lines = content.split(/\r?\n/);
        for (let line of lines) {
            const parsed = parseLine(line);
            if (!parsed) continue;

            const info = getGavInfo(parsed.gav);
            if (filterScope && info && info.scope !== filterScope) continue;

            if (useFilenames) {
                const fn = getFilename(parsed.gav, parsed.path);
                if (fn) deps.add(fn);
            } else if (parsed.gav) {
                // Normalize GAV: gid:aid:type:classifier:ver:scope
                if (info) {
                    deps.add(`${info.gid}:${info.aid}:${info.type}:${info.classifier}:${info.ver}:${info.scope}`);
                }
            } else if (parsed.path) {
                // If only path is available, use it as identity
                deps.add(parsed.path);
            }
        }
    } catch (e) {
        console.error(`Error reading ${filename}: ${e.message}`);
    }
    return deps;
}

const args = process.argv.slice(2);
const options = {
    filenames: args.includes('--file'),
    scope: null
};

const scopeIdx = args.indexOf('--scope');
if (scopeIdx !== -1 && args[scopeIdx + 1]) {
    options.scope = args[scopeIdx + 1];
}

const files = args.filter(a => !a.startsWith('--') && (a !== options.scope));

if (files.length < 2) {
    console.log("Usage: node scripts/compare_deps.js <file1> <file2> [--file] [--scope <scope>]");
    console.log("  --file: Compare by filenames instead of GAVs (e.g. artifactId-version.jar)");
    console.log("  --scope: Filter by scope (compile, runtime, provided, test, etc.)");
    process.exit(1);
}

const [file1, file2] = files;
if (!existsSync(file1) || !existsSync(file2)) {
    if (!existsSync(file1)) console.error(`File not found: ${file1}`);
    if (!existsSync(file2)) console.error(`File not found: ${file2}`);
    process.exit(1);
}

const deps1 = loadDeps(file1, options.filenames, options.scope);
const deps2 = loadDeps(file2, options.filenames, options.scope);

console.log(`${file1} count: ${deps1.size}`);
console.log(`${file2} count: ${deps2.size}`);

const all = Array.from(new Set([...deps1, ...deps2])).sort();
let diffs = 0;

console.log("\nDiscrepancies:");
for (const d of all) {
    const in1 = deps1.has(d);
    const in2 = deps2.has(d);
    if (in1 !== in2) {
        diffs++;
        const status = in1 ? `[Only in ${file1}]` : `[Only in ${file2}]`;
        console.log(`${status} ${d}`);
    }
}

if (diffs === 0) {
    console.log("\nSuccess: No differences found! 100% match.");
} else {
    console.log(`\nFound ${diffs} differences.`);
}
