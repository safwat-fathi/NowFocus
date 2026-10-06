// Single-file dashboard. All row data is rendered with textContent (feature requests are untrusted).
export const ADMIN_PAGE = /* html */ `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex"><title>NowFocus admin</title>
<style>
:root{--bg:#fff;--fg:#1a1a1a;--mute:#666;--line:#ddd;--acc:#2a5bd7}
@media(prefers-color-scheme:dark){:root{--bg:#141414;--fg:#eee;--mute:#999;--line:#333;--acc:#7aa2ff}}
body{margin:0;padding:16px;background:var(--bg);color:var(--fg);font:14px/1.45 system-ui,sans-serif}
input,select,button{font:inherit;padding:6px 10px;border:1px solid var(--line);border-radius:6px;background:var(--bg);color:var(--fg)}
button{cursor:pointer}a{color:var(--acc)}[hidden]{display:none!important}
.bar{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin:12px 0}
.table{overflow-x:auto}table{border-collapse:collapse;width:100%}
th,td{text-align:left;padding:8px;border-bottom:1px solid var(--line);vertical-align:top}
td.fr{max-width:420px;white-space:pre-wrap;word-break:break-word}.mute{color:var(--mute)}
</style></head><body>
<h1>NowFocus waitlist</h1>
<form id="login" class="bar"><input id="tok" type="password" placeholder="Admin token" autocomplete="current-password" required><button>Sign in</button><span id="err" class="mute"></span></form>
<div id="app" hidden>
<div class="bar">
<input id="q" placeholder="Filter email or text" type="search">
<select id="plat"><option value="">All platforms</option><option>android</option><option>windows</option><option>macos</option><option>ios</option></select>
<label><input id="fr" type="checkbox"> Feature requests only</label>
<button id="csv">Download CSV</button><button id="out">Sign out</button>
<span id="count" class="mute"></span></div>
<div class="table"><table><thead><tr><th>Date</th><th>Email</th><th>Platforms</th><th>Feature request</th><th>Issue</th><th></th></tr></thead><tbody id="rows"></tbody></table></div>
</div>
<script>
const $=id=>document.getElementById(id);let rows=[];
const tok=()=>{try{return localStorage.getItem('t')||''}catch{return ''}};
async function api(path,opt={}){const r=await fetch(path,{...opt,headers:{Authorization:'Bearer '+tok()}});
 if(r.status===401||r.status===404){try{localStorage.removeItem('t')}catch{};show(false);$('err').textContent=r.status===401?'Wrong token':'Admin is disabled';throw 0}
 if(r.status===429){$('err').textContent='Too many attempts, wait a minute';throw 0}
 if(!r.ok)throw 0;return r.status===204?null:r.json()}
function show(on){$('app').hidden=!on;$('login').hidden=on}
async function load(){rows=await api('/v1/admin/waitlist');show(true);draw()}
function view(){const q=$('q').value.toLowerCase(),p=$('plat').value,f=$('fr').checked;
 return rows.filter(r=>(!p||r.platforms.includes(p))&&(!f||r.featureRequest)&&(!q||(r.email+' '+(r.featureRequest||'')).toLowerCase().includes(q)))}
function td(tr,t){const c=tr.insertCell();if(t instanceof Node)c.append(t);else c.textContent=t;return c}
function draw(){const v=view(),b=$('rows');b.replaceChildren();
 $('count').textContent=v.length+' of '+rows.length+' ('+rows.filter(r=>r.featureRequest).length+' with feature requests)';
 for(const r of v){const tr=b.insertRow();td(tr,r.createdAt.slice(0,10));td(tr,r.email);td(tr,r.platforms.join(', ')).className='mute';
  td(tr,r.featureRequest||'').className='fr';
  let l='';if(r.githubIssueUrl&&/^https:\\/\\//.test(r.githubIssueUrl)){l=document.createElement('a');l.href=r.githubIssueUrl;l.textContent='issue';l.rel='noopener'}td(tr,l);
  const d=document.createElement('button');d.textContent='Delete';d.onclick=async()=>{if(!confirm('Delete '+r.email+'?'))return;
   await api('/v1/admin/waitlist/'+r.id,{method:'DELETE'});rows=rows.filter(x=>x!==r);draw()};td(tr,d)}}
const esc=s=>'"'+String(s??'').replace(/"/g,'""')+'"';
$('csv').onclick=()=>{const t=['date,email,platforms,feature_request,issue'].concat(view().map(r=>[r.createdAt,r.email,r.platforms.join(' '),r.featureRequest,r.githubIssueUrl].map(esc).join(','))).join('\\n');
 const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([t],{type:'text/csv'}));a.download='waitlist.csv';a.click()};
for(const id of['q','plat','fr'])$(id).oninput=draw;
$('out').onclick=()=>{try{localStorage.removeItem('t')}catch{};rows=[];show(false)};
$('login').onsubmit=async e=>{e.preventDefault();$('err').textContent='';
 const r=await fetch('/v1/admin/session',{method:'POST',headers:{Authorization:'Bearer '+$('tok').value}});$('tok').value='';
 if(r.status===401||r.status===404)return void($('err').textContent=r.status===401?'Wrong token':'Admin is disabled');
 if(r.status===429)return void($('err').textContent='Too many attempts, wait a minute');
 if(!r.ok)return;try{localStorage.setItem('t',(await r.json()).token)}catch{};load().catch(()=>{})};
if(tok())load().catch(()=>{});
</script></body></html>`;
