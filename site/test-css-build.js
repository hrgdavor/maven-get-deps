import path from 'path';

const src = 'test.css';
const out = 'dist';

await Bun.write(src, '.test { color: red; }');

const res = await Bun.build({
    entrypoints: [src],
    outdir: out,
    sourcemap: 'external'
});

console.log('Success:', res.success);
if (res.success) {
    const files = Array.from(res.outputs).map(o => o.path);
    console.log('Outputs:', files);
}
