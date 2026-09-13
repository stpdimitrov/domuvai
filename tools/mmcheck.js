const {chromium} = require('playwright');
const fs = require('fs');
const lib = fs.readFileSync('node_modules/mermaid/dist/mermaid.min.js','utf8');
const files = process.argv.slice(2);
(async () => {
  const b = await chromium.launch({executablePath:'/opt/pw-browsers/chromium'});
  for (const f of files) {
    const md = fs.readFileSync(f,'utf8');
    const blocks = [...md.matchAll(/```mermaid\n([\s\S]*?)```/g)].map(m=>m[1]);
    for (let i=0;i<blocks.length;i++) {
      const p = await b.newPage();
      await p.setContent(`<div id=o></div><script>${lib}<\/script>`);
      const r = await p.evaluate(async (src) => {
        mermaid.initialize({startOnLoad:false});
        try { await mermaid.parse(src); return 'OK'; }
        catch(e){ return 'ERR: ' + (e && e.message ? e.message : String(e)); }
      }, blocks[i]);
      if (r !== 'OK') {
        const head = blocks[i].split('\n')[0];
        console.log(`\n### ${f} block ${i+1}  [${head}]`);
        console.log(r.split('\n').slice(0,8).join('\n'));
      }
    }
    console.log(`${f}: ${blocks.length} blocks checked`);
  }
  await b.close();
})();
