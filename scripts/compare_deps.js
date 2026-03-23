import { readFileSync } from 'fs';

function parseMaven(filename) {
    const deps = new Set();
    try {
        const content = readFileSync(filename, 'utf-8');
        const lines = content.split(/\r?\n/);
        for (let line of lines) {
            line = line.replace(/\u001b\[[0-9;]*m/g, '').trim();
            if (!line) continue;
            
            // Remove [INFO] etc.
            const cleanLine = line.replace(/^\[[A-Z]+\]\s*/, '').trim();
            if (!cleanLine) continue;

            const depPart = cleanLine.split(' -- ')[0].trim();
            const parts = depPart.split(':').map(p => p.trim());
            if (parts.length >= 5) {
                // groupId:artifactId:type:version:scope
                deps.add(parts.slice(0, 5).join(':'));
            }
        }
    } catch (e) {
        console.error(`Error reading ${filename}: ${e.message}`);
    }
    return deps;
}

function parseCli(filename) {
    const deps = new Set();
    try {
        const content = readFileSync(filename, 'utf-8');
        const lines = content.split(/\r?\n/);
        for (let line of lines) {
            line = line.trim().replace(/\u001b\[[0-9;]*m/g, '');
            if (!line || line.includes('Resolved Dependencies') || line.includes('mimic')) continue;
            
            // Remove potential [DEBUG] etc.
            const cleanLine = line.replace(/^\[[A-Z-]+\]\s*/, '').trim();

            const depPart = cleanLine.split(' (')[0].trim();
            const parts = depPart.split(':').map(p => p.trim()).filter(p => p.length > 0);
            if (parts.length >= 5) {
                deps.add(parts.slice(0, 5).join(':'));
            }
        }
    } catch (e) {
        console.error(`Error reading ${filename}: ${e.message}`);
    }
    return deps;
}

const mavenBaseline = process.argv[2] || 'maven_baseline.txt';
const cliOutput = process.argv[3] || 'cli_output_extended.txt';

const mavenDeps = parseMaven(mavenBaseline);
const cliDeps = parseCli(cliOutput);

console.log(`Maven deps count: ${mavenDeps.size}`);
console.log(`CLI deps count: ${cliDeps.size}`);

if (mavenDeps.size > 0 && cliDeps.size > 0) {
    const m1 = Array.from(mavenDeps).sort()[0];
    const c1 = Array.from(cliDeps).find(x => x.includes(m1.split(':')[1])) || Array.from(cliDeps).sort()[0];
    console.log(`Maven[0]: "${m1}" (${m1.length} chars) Hex: ${Buffer.from(m1).toString('hex')}`);
    console.log(`CLI[?]  : "${c1}" (${c1.length} chars) Hex: ${Buffer.from(c1).toString('hex')}`);
}

const allDeps = Array.from(new Set([...mavenDeps, ...cliDeps])).sort();
let diffFound = false;

console.log("\nDiscrepancies:");
for (const d of allDeps) {
    const inMaven = mavenDeps.has(d);
    const inCli = cliDeps.has(d);
    if (inMaven !== inCli) {
        diffFound = true;
        const status = inMaven ? "In Maven only" : "In CLI only";
        console.log(`[${status}] ${d}`);
    }
}

if (!diffFound) {
    console.log("Zero differences found! 100% match.");
}
