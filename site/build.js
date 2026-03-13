const fs = require('fs');
const path = require('path');

const srcDir = __dirname;
const destFile = path.join(srcDir, '..', 'docs', 'download.html');

// Create docs directory if it doesn't exist
if (!fs.existsSync(path.dirname(destFile))) {
    fs.mkdirSync(path.dirname(destFile), { recursive: true });
}

let html = fs.readFileSync(path.join(srcDir, 'index.html'), 'utf8');
const css = fs.readFileSync(path.join(srcDir, 'style.css'), 'utf8');
const js = fs.readFileSync(path.join(srcDir, 'app.js'), 'utf8');

// Inline CSS
html = html.replace('<link rel="stylesheet" href="style.css">', `<style>${css}</style>`);

// Inline JS
html = html.replace('<script src="app.js"></script>', `<script>${js}</script>`);

fs.writeFileSync(destFile, html);

console.log(`Successfully bundled to ${destFile}`);
