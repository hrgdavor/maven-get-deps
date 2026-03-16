const fs = require('fs');
const path = require('path');

const rootDir = path.join(__dirname, '..');
const srcDir = __dirname;
const destDir = path.join(rootDir, 'docs');

// Create destination if it doesn't exist
if (!fs.existsSync(destDir)) {
    fs.mkdirSync(destDir, { recursive: true });
}

console.log("🛠️ Starting modular project site build...");

/**
 * Helper to copy a file to the destination
 */
function copyFile(srcName, destSubDir = '') {
    const srcPath = path.join(srcDir, srcName);
    const destPath = path.join(destDir, destSubDir, path.basename(srcName));
    
    const targetDir = path.dirname(destPath);
    if (!fs.existsSync(targetDir)) {
        fs.mkdirSync(targetDir, { recursive: true });
    }
    
    fs.copyFileSync(srcPath, destPath);
    console.log(`  📎 Copied ${srcName} -> ${path.relative(destDir, destPath)}`);
}

/**
 * Helper to build a portal (copies HTML and optionally injects data)
 */
function buildPortal(templateName, outputName, data = null) {
    let html = fs.readFileSync(path.join(srcDir, templateName), 'utf8');

    if (data) {
        const dataJson = JSON.stringify(data);
        // We still inject the data bundle directly as it's portal-specific
        html = html.replace('<script id="nexus-data" type="application/json">{}</script>', 
                           () => `<script id="nexus-data" type="application/json">${dataJson}</script>`);
    }

    fs.writeFileSync(path.join(destDir, outputName), html);
    console.log(`✅ Processed ${outputName}`);
}

// 1. Copy Shared Assets
console.log("🚚 Copying shared assets...");
copyFile('style.css');
copyFile('app.js');
copyFile('nexus.css');
copyFile('nexus.js');
copyFile('lib/marked.min.js', 'lib');
copyFile('lib/highlight.min.js', 'lib');
copyFile('lib/github-dark.min.css', 'lib');

// 2. Build Portals
buildPortal('download.html', 'download.html');

console.log("📦 Aggregating documentation for Nexus...");
const bundle = { docs: {}, adventures: {} };
const extractTitle = (c) => (c.match(/^# (.*)/) || [, 'Untitled'])[1];
const extractRewards = (c) => {
    const rewards = [];
    const sm = c.match(/- \*\*Seal of Mastery\*\*: (.*)/);
    if (sm) rewards.push(sm[1].replace(/.*(Seal of the [^.]+).*/, '$1').trim());
    const tm = c.match(/- \*\*(Tools|Loot|Weapons) Gained\*\*: (.*)/);
    if (tm) rewards.push(tm[2].replace(/[.!?]/g, '').replace(/[\u2700-\u27BF]|[\uE000-\uF8FF]|\uD83C[\uDC00-\uDFFF]|\uD83D[\uDC00-\uDFFF]|[\u2011-\u26FF]|\uD83E[\uDD10-\uDDFF]/g, '').split('(')[0].trim());
    return rewards;
};

// Map Docs
fs.readdirSync(path.join(rootDir, 'doc'))
    .filter(f => f.endsWith('.md'))
    .forEach(file => {
        const content = fs.readFileSync(path.join(rootDir, 'doc', file), 'utf8');
        bundle.docs[file.replace('.md', '')] = { title: extractTitle(content), content };
    });

// Map Adventures
fs.readdirSync(path.join(rootDir, 'adventures'))
    .filter(f => f.endsWith('.md') && f !== 'README.md')
    .forEach(file => {
        const content = fs.readFileSync(path.join(rootDir, 'adventures', file), 'utf8');
        bundle.adventures[file.replace('.md', '')] = {
            title: extractTitle(content).replace('Adventure: ', ''),
            content,
            rewards: extractRewards(content)
        };
    });

buildPortal('nexus.html', 'nexus.html', bundle);

console.log(`🚀 Build complete. Assets available in ${destDir}`);
