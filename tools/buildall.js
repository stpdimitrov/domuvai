const {chromium} = require('playwright');
const JOBS = JSON.parse(process.argv[2]);
let FAIL = false;
(async () => {
  const b = await chromium.launch({executablePath:'/opt/pw-browsers/chromium'});
  for (const [src, out] of JOBS) {
    const p = await b.newPage({viewport:{width:1100,height:1400}});
    p.on('pageerror',e=>console.log('  [err]',String(e).slice(0,160)));
    await p.goto('file://'+src,{waitUntil:'load'});
    await p.waitForFunction('window.__done === true',null,{timeout:120000});
    await p.evaluate(() => {
      document.querySelectorAll('.dg').forEach(d => {
        const svg=d.querySelector('svg'); if(!svg) return;
        const vb=(svg.getAttribute('viewBox')||'').split(/[\s,]+/).map(Number);
        const r = vb.length===4 && vb[3] ? vb[2]/vb[3] : 1;
        if (r > 1.7) d.classList.add('wide');
      });
    });
    const n = await p.evaluate('document.querySelectorAll(".dg svg").length');
    const bad = await p.evaluate(() =>
      [...document.querySelectorAll('.dg')]
        .map((d,i)=>({i:i+1, e:/Syntax error/i.test(d.textContent||'')}))
        .filter(x=>x.e).map(x=>x.i));
    if (bad.length) { console.error('  ✗ SYNTAX ERROR in ' + out + ' diagram(s) ' + bad.join(',')); FAIL = true; }
    await p.emulateMedia({media:'print'});
    await p.pdf({path:out, printBackground:true, preferCSSPageSize:true,
      margin:{top:'16mm',bottom:'16mm',left:'14mm',right:'14mm'}});
    console.log('  ✓', out, '· diagrams:', n);
    await p.close();
  }
  await b.close();
  if (FAIL) { console.error('\nBUILD FAILED — a diagram rendered as an error box'); process.exit(1); }
})();
