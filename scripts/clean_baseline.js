import { file as bunFile } from "bun";

const args = process.argv.slice(2);

async function main() {
    let content = "";
    if (args.length > 0) {
        content = await bunFile(args[0]).text();
    } else {
        // Read from stdin
        for await (const chunk of process.stdin) {
            content += new TextDecoder().decode(chunk);
        }
    }

    if (!content) {
        process.exit(0);
    }

    const lines = content.split(/\r?\n/);
    const deps = new Set();

    for (let line of lines) {
        const parsed = parseLine(line);
        if (!parsed) continue;
        const info = getGavInfo(parsed.gav);
        if (info) {
            // Output format: gid:aid:version:scope (as requested)
            // or full GAV if we want parity with verify_variants.js
            // The user asked for gid:aid:version:scope
            deps.add(`${info.gid}:${info.aid}:${info.ver}:${info.scope}`);
        }
    }

    const sorted = Array.from(deps).sort();
    for (const d of sorted) {
        console.log(d);
    }
}

function parseLine(line) {
    // Remove ANSI colors and trim
    line = line.replace(/\u001b\[[0-9;]*m/g, '').trim();
    if (!line) return null;

    // Handle mangled lines where another line's ending bled in
    line = line.replace(/\(auto\).+$/, '(auto)').trim();

    // Remove common prefixes
    line = line.replace(/^\[[A-Z0-9 -]+\]\s*/, '').trim();
    if (!line) return null;

    if (line.includes(' -> ')) {
        const [gav, _] = line.split(' -> ').map(s => s.trim());
        return { gav, path: null };
    }

    if (line.includes(' -- ')) {
        const gav = line.split(' -- ')[0].trim();
        const cleanGav = gav.replace(/^[|+\-\s*]+/, '').trim();
        return { gav: cleanGav, path: null };
    }

    // raw GAV or list output
    if (line.split(':').length >= 3) {
        const cleanGav = line.replace(/^[|+\-\s*]+/, '').trim();
        const parts = cleanGav.split(':');
        if (parts.length > 6) {
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

main().catch(console.error);
