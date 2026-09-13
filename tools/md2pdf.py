import re, sys, html, json, pathlib
import markdown

src = sys.argv[1]
out = sys.argv[2]
title = sys.argv[3]

text = pathlib.Path(src).read_text(encoding='utf-8')
# pull mermaid blocks out, replace with placeholders
blocks = []
def grab(m):
    blocks.append(m.group(1))
    return f"\n@@MERMAID{len(blocks)-1}@@\n"
text = re.sub(r"```mermaid\n(.*?)```", grab, text, flags=re.S)

# --- preprocessing runs AFTER mermaid blocks are removed ---
text = re.sub(r'^---\n.*?\n---\n', '', text, flags=re.S)          # YAML frontmatter
text = re.sub(r'\[\[([^\]|]+)\|([^\]]+)\]\]', r'\2', text)        # [[a|b]] -> b
text = re.sub(r'\[\[([^\]]+)\]\]', r'*\1*', text)                 # [[a]]   -> *a*
# Obsidian callouts -> fenced div
def _callout(m):
    kind = m.group(1).lower(); title = (m.group(2) or '').strip()
    body = '\n'.join(l[2:] if l.startswith('> ') else l.lstrip('>') for l in m.group(3).split('\n'))
    head = f'<p class="ct">{title or kind.upper()}</p>' if (title or kind) else ''
    return f'\n<div class="cal {kind}" markdown="1">{head}\n\n{body}\n</div>\n'
text = re.sub(r'^> \[!(\w+)\][+-]?\s*(.*)\n((?:>.*\n?)*)', _callout, text, flags=re.M)


body = markdown.markdown(text, extensions=['tables','fenced_code','attr_list','md_in_html','sane_lists'])

for i, b in enumerate(blocks):
    body = body.replace(f"<p>@@MERMAID{i}@@</p>",
        f'<div class="dg"><pre class="mermaid">{html.escape(b)}</pre></div>')
    body = body.replace(f"@@MERMAID{i}@@",
        f'<div class="dg"><pre class="mermaid">{html.escape(b)}</pre></div>')

lib = pathlib.Path('node_modules/mermaid/dist/mermaid.min.js').read_text(encoding='utf-8') if blocks else 'window.__done=true;'

page = """<!doctype html><html><head><meta charset="utf-8"><title>%s</title>
<style>
@page { size: A4 portrait; margin: 16mm 14mm; }
@page wide { size: A4 landscape; margin: 11mm 12mm; }
body{font:11pt/1.55 -apple-system,"Segoe UI",Helvetica,Arial,sans-serif;color:#15181d;margin:0}
h1{font-size:21pt;margin:0 0 4pt;letter-spacing:-.4pt;page-break-after:avoid}
h2{font-size:14pt;margin:20pt 0 6pt;padding-top:8pt;border-top:1px solid #d8dde3;page-break-after:avoid}
h3{font-size:11.5pt;margin:14pt 0 4pt;page-break-after:avoid}
h4{font-size:10.5pt;margin:11pt 0 3pt;color:#444;page-break-after:avoid}
p,li{orphans:3;widows:3}
ul,ol{padding-left:18pt;margin:6pt 0}
li{margin:2pt 0}
code{font-family:ui-monospace,"SF Mono",Menlo,monospace;font-size:9.2pt;background:#f1f3f5;padding:1pt 3pt;border-radius:3px}
pre{background:#f6f8fa;border:1px solid #e3e7ec;border-radius:5px;padding:8pt;font-size:8.6pt;overflow:hidden;white-space:pre-wrap;page-break-inside:avoid}
pre code{background:none;padding:0}
table{border-collapse:collapse;width:100%%;margin:8pt 0;font-size:9.2pt;page-break-inside:avoid}
th,td{border:1px solid #d8dde3;padding:4pt 6pt;text-align:left;vertical-align:top}
th{background:#f1f3f5;font-weight:600}
blockquote{border-left:3px solid #c8ced6;margin:8pt 0;padding:2pt 0 2pt 10pt;color:#444}
hr{border:0;border-top:1px solid #d8dde3;margin:16pt 0}
.dg{break-inside:avoid;margin:12pt 0;text-align:center}
.dg svg{max-width:100%%;height:auto;max-height:225mm}
.dg.wide{page:wide;break-before:page;break-after:page;margin:0}
.dg.wide svg{max-height:178mm}
.dgcap{font-size:9pt;color:#667;margin-top:4pt}
.cal{border-left:3px solid #8a93a0;background:#f4f6f8;border-radius:0 5px 5px 0;padding:8pt 11pt;margin:10pt 0;break-inside:avoid}
.cal p{margin:4pt 0}
.cal .ct{font-weight:650;font-size:9.5pt;letter-spacing:.3pt;text-transform:uppercase;color:#4a5260;margin:0 0 4pt}
.cal.warning,.cal.danger{border-left-color:#b4552a;background:#fbf3ef}
.cal.warning .ct,.cal.danger .ct{color:#8f4321}
.cal.important{border-left-color:#3d6b9e;background:#eff4fa}
.cal.important .ct{color:#2f5580}
.cal.todo{border-left-color:#5a8a5a;background:#f0f6f0}
.cal.todo .ct{color:#3f6b3f}
.cal ul{margin:4pt 0}
h2+p,h3+p{margin-top:4pt}
strong{font-weight:600}
</style></head><body>
%s
<script>%s</script>
<script>
if (window.mermaid) {
mermaid.initialize({startOnLoad:false,theme:'neutral',
 flowchart:{useMaxWidth:true,htmlLabels:true},
 sequence:{useMaxWidth:true,width:130},
 themeVariables:{fontSize:'13px'}});
mermaid.run().then(()=>{window.__done=true}).catch(e=>{window.__done=true;window.__err=String(e)});
} else { window.__done = true; }
</script></body></html>""" % (html.escape(title), body, lib)

pathlib.Path(out).write_text(page, encoding='utf-8')
print("wrote", out, len(page), "blocks:", len(blocks))
