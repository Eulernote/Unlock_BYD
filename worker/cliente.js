// ============================================================================
//  App do CLIENTE (PWA em /c) + vínculo carro ↔ celular
//
//  KV:
//    mon:<carId>   {on, clients:[user], by, at}     monitoramento liberado pela equipe
//    cli:<user>    {hash, cars:[carId], reg, consentAt}
//    pair:<code>   {car, by, at}  (expira em 10 min, uso único)
//
//  O login do cliente é totalmente separado do login da equipe: o token tem outro formato
//  ("c|user|exp") e não passa no verifyToken do painel (que só aceita USERS).
//  As câmeras ao vivo entram depois do teste da v59 no carro; aqui fica a base (login + vínculo).
// ============================================================================
import { ICON_180, ICON_192, ICON_512 } from './cliente-icons.js';
export { CarHub } from './hub.js';

const CLIENT_TOKEN_TTL_MS = 1000 * 60 * 60 * 24 * 30;  // 30 dias: cara de app, sem pedir senha toda hora
const PAIR_TTL_S = 600;                                 // código de vínculo vale 10 min
const PAIR_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789'; // sem 0/O/1/I/L pra não confundir

function cleanUser(u){ return String(u || '').trim().toLowerCase(); }
function validUser(u){ return u.length >= 3 && u.length <= 60 && /^[a-z0-9._@+-]+$/.test(u); }
function cleanCode(c){ return String(c || '').toUpperCase().replace(/[^A-Z0-9]/g, ''); }

async function getMon(env, id){ return (await env.KV.get('mon:' + id, {type:'json'})) || {on:false, clients:[]}; }
async function putMon(env, id, m){ await env.KV.put('mon:' + id, JSON.stringify(m)); }
async function getCli(env, u){ return await env.KV.get('cli:' + u, {type:'json'}); }
async function putCli(env, u, c){ await env.KV.put('cli:' + u, JSON.stringify(c)); }

async function makeClientToken(H, secret, user){
  const body = 'c|' + user + '|' + (Date.now() + CLIENT_TOKEN_TTL_MS);
  return btoa(body) + '.' + (await H.hmacHex(secret, body));
}
async function verifyClientToken(H, secret, token){
  try{
    const parts = String(token || '').split('.');
    if(parts.length !== 2) return null;
    const body = atob(parts[0]);
    if(parts[1] !== await H.hmacHex(secret, body)) return null;
    const f = body.split('|');
    if(f.length !== 3 || f[0] !== 'c') return null;
    if(!(Number(f[2]) > Date.now())) return null;
    return f[1];
  }catch(e){ return null; }
}
async function clientUser(H, env, request){
  const a = request.headers.get('Authorization') || '';
  return await verifyClientToken(H, env.SECRET, a.replace(/^Bearer\s+/i, ''));
}
const clientHash = (H, env, user, pass) => H.hashPw(env.SECRET, 'c:' + user, pass);

function newCode(){
  const b = new Uint8Array(8); crypto.getRandomValues(b);
  let s = ''; for(const x of b) s += PAIR_ALPHABET[x % PAIR_ALPHABET.length];
  return s.slice(0, 4) + '-' + s.slice(4);
}

function b64png(b64){
  const bin = atob(b64); const u = new Uint8Array(bin.length);
  for(let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i);
  return new Response(u, {headers:{'content-type':'image/png', 'cache-control':'public, max-age=604800'}});
}

// ---------------------------------------------------------------------------
//  Rotas da EQUIPE (já autenticada no painel): liga monitoramento, gera código, remove celular
// ---------------------------------------------------------------------------
export async function handleMonitorStaff(request, env, p, user, H){
  if(!(p === '/api/monitor' || p === '/api/pair-code' || p === '/api/monitor-unlink')) return null;
  if(!user) return H.json({error:'unauth'}, 401);
  const url = new URL(request.url);

  if(p === '/api/monitor' && request.method === 'GET'){
    const id = String(url.searchParams.get('id') || '');
    const m = await getMon(env, id);
    // Carros liberados antes do campo c.mon existir: sincroniza ao abrir a ficha no painel.
    try{ const c = await H.getCar(env, id); if(c && (c.mon === true) !== !!m.on){ c.mon = !!m.on; await H.putCar(env, c); } }catch(e){}
    return H.json({on: !!m.on, clients: m.clients || []});
  }
  const b = await request.json().catch(() => ({}));
  const id = String(b.id || '').trim();
  const c = await H.getCar(env, id);
  if(!c) return H.json({error:'car'}, 404);
  const m = await getMon(env, id);

  if(p === '/api/monitor' && request.method === 'POST'){
    m.on = b.on === true; m.by = user; m.at = Date.now();
    await putMon(env, id, m);
    c.mon = m.on;   // vai junto no /api/register: o carro só abre a conexão das câmeras se estiver liberado
    c.log.push({acao: m.on ? 'Monitoramento liberado' : 'Monitoramento desligado', by: user, when: H.fmt(Date.now()), cls: m.on ? 'a-act' : 'a-rev'});
    await H.putCar(env, c);
    return H.json({ok:true, on:m.on});
  }
  if(p === '/api/pair-code' && request.method === 'POST'){
    if(!m.on) return H.json({error:'Monitoramento desligado para este carro'}, 400);
    const code = newCode();
    await env.KV.put('pair:' + cleanCode(code), JSON.stringify({car:id, by:user, at:Date.now()}), {expirationTtl: PAIR_TTL_S});
    return H.json({code: code, exp: Date.now() + PAIR_TTL_S * 1000});
  }
  if(p === '/api/monitor-unlink' && request.method === 'POST'){
    const u = cleanUser(b.user);
    m.clients = (m.clients || []).filter(x => x !== u);
    await putMon(env, id, m);
    const cli = await getCli(env, u);
    if(cli){ cli.cars = (cli.cars || []).filter(x => x !== id); await putCli(env, u, cli); }
    c.log.push({acao: 'Celular desvinculado: ' + u, by: user, when: H.fmt(Date.now()), cls: 'a-rev'});
    await H.putCar(env, c);
    return H.json({ok:true});
  }
  return H.json({error:'method'}, 405);
}

// ---------------------------------------------------------------------------
//  Conexão do CARRO com o hub (sem login, como o /api/register): só se o monitoramento estiver liberado
// ---------------------------------------------------------------------------
export async function handleCarWs(request, env, p, H){
  if(p !== '/ws/car') return null;
  if(!env.CAR_HUB) return H.json({error:'hub indisponível'}, 503);
  if(request.headers.get('Upgrade') !== 'websocket') return H.json({error:'websocket'}, 426);
  const id = String(new URL(request.url).searchParams.get('id') || '').trim();
  const c = id ? await H.getCar(env, id) : null;
  if(!c || c.status !== 'active') return H.json({error:'car'}, 403);
  const m = await getMon(env, id);
  if(!m.on) return H.json({error:'Monitoramento desligado'}, 403);
  const hdr = new Headers(request.headers); hdr.set('X-Role', 'car');
  return env.CAR_HUB.get(env.CAR_HUB.idFromName(id)).fetch(new Request(request.url, {headers: hdr}));
}

// ---------------------------------------------------------------------------
//  Rotas do CLIENTE (/c...)
// ---------------------------------------------------------------------------
export async function handleClient(request, env, p, H){
  if(p !== '/c' && p.indexOf('/c/') !== 0) return null;
  const GET = request.method === 'GET' || request.method === 'HEAD';

  if(GET && (p === '/c' || p === '/c/' || p === '/c/index.html'))
    return new Response(CLIENT_HTML, {headers:{'content-type':'text/html; charset=utf-8', 'cache-control':'no-cache'}});
  if(GET && p === '/c/manifest.webmanifest')
    return new Response(JSON.stringify(MANIFEST), {headers:{'content-type':'application/manifest+json; charset=utf-8', 'cache-control':'no-cache'}});
  if(GET && p === '/c/sw.js')
    return new Response(SW_JS, {headers:{'content-type':'text/javascript; charset=utf-8', 'cache-control':'no-cache', 'service-worker-allowed':'/c/'}});
  if(GET && (p === '/c/apple-touch-icon.png' || p === '/c/icon-180.png')) return b64png(ICON_180);
  if(GET && p === '/c/icon-192.png') return b64png(ICON_192);
  if(GET && p === '/c/icon-512.png') return b64png(ICON_512);

  // --- câmeras ao vivo: celular conecta no hub do carro ---
  if(p === '/c/ws'){
    if(!env.CAR_HUB) return H.json({error:'hub indisponível'}, 503);
    if(request.headers.get('Upgrade') !== 'websocket') return H.json({error:'websocket'}, 426);
    const url = new URL(request.url);
    const user = await verifyClientToken(H, env.SECRET, url.searchParams.get('t') || '');
    const carId = String(url.searchParams.get('car') || '');
    if(!user) return H.json({error:'unauth'}, 401);
    const [cli, m, car] = await Promise.all([getCli(env, user), getMon(env, carId), H.getCar(env, carId)]);
    if(!cli || (cli.cars || []).indexOf(carId) < 0 || (m.clients || []).indexOf(user) < 0) return H.json({error:'unauth'}, 401);
    if(!m.on || !car || car.status !== 'active') return H.json({error:'Monitoramento não liberado para este carro'}, 403);
    const rnd = new Uint8Array(4); crypto.getRandomValues(rnd);
    const vid = user + '~' + [...rnd].map(x => x.toString(16).padStart(2, '0')).join('');
    const hdr = new Headers(request.headers); hdr.set('X-Role', 'viewer'); hdr.set('X-Viewer', vid);
    return env.CAR_HUB.get(env.CAR_HUB.idFromName(carId)).fetch(new Request(request.url, {headers: hdr}));
  }

  if(p.indexOf('/c/api/') !== 0) return H.json({error:'not found'}, 404);

  // --- vincular: código (gerado no painel) + cria/usa login ---
  if(p === '/c/api/pair' && request.method === 'POST'){
    const b = await request.json().catch(() => ({}));
    const code = cleanCode(b.code), user = cleanUser(b.user), pass = String(b.pass || '');
    if(code.length !== 8) return H.json({error:'Código inválido'}, 400);
    if(!validUser(user)) return H.json({error:'Usuário inválido (use e-mail ou telefone, sem espaços)'}, 400);
    if(pass.length < 8) return H.json({error:'A senha precisa ter pelo menos 8 caracteres'}, 400);
    if(b.consent !== true) return H.json({error:'É preciso aceitar o termo de privacidade'}, 400);
    const pr = await env.KV.get('pair:' + code, {type:'json'});
    if(!pr) return H.json({error:'Código inválido ou expirado. Peça um novo à Unlock BYD.'}, 400);
    const car = await H.getCar(env, pr.car);
    const m = await getMon(env, pr.car);
    if(!car || !m.on) return H.json({error:'Monitoramento não está liberado para este carro'}, 400);
    const h = await clientHash(H, env, user, pass);
    let cli = await getCli(env, user);
    if(cli && cli.hash !== h) return H.json({error:'Este usuário já existe e a senha não confere'}, 401);
    const now = Date.now();
    if(!cli) cli = {hash:h, cars:[], reg:now};
    if(cli.cars.indexOf(pr.car) < 0) cli.cars.push(pr.car);
    cli.consentAt = now;
    await putCli(env, user, cli);
    m.clients = m.clients || [];
    if(m.clients.indexOf(user) < 0) m.clients.push(user);
    await putMon(env, pr.car, m);
    await env.KV.delete('pair:' + code);
    try{
      car.log.push({acao:'Celular vinculado: ' + user, by:'cliente', when:H.fmt(now), cls:'a-act'});
      await H.putCar(env, car);
    }catch(e){}
    return H.json({token: await makeClientToken(H, env.SECRET, user), user: user});
  }

  if(p === '/c/api/login' && request.method === 'POST'){
    const b = await request.json().catch(() => ({}));
    const user = cleanUser(b.user), pass = String(b.pass || '');
    const cli = user ? await getCli(env, user) : null;
    if(!cli || cli.hash !== await clientHash(H, env, user, pass)) return H.json({error:'Usuário ou senha inválidos'}, 401);
    return H.json({token: await makeClientToken(H, env.SECRET, user), user: user});
  }

  const user = await clientUser(H, env, request);
  if(!user) return H.json({error:'unauth'}, 401);
  const cli = await getCli(env, user);
  if(!cli) return H.json({error:'unauth'}, 401);

  if(p === '/c/api/me' && request.method === 'GET'){
    const cars = [];
    for(const id of (cli.cars || [])){
      const [c, m, seen] = await Promise.all([H.getCar(env, id), getMon(env, id), H.getSeen(env, id)]);
      // Só mostra carros em que ele continua na lista (a equipe pode ter removido) e que estão ativos.
      if(!c || (m.clients || []).indexOf(user) < 0) continue;
      let online = false;
      if(env.CAR_HUB && m.on){ try{ const r = await env.CAR_HUB.get(env.CAR_HUB.idFromName(id)).fetch('https://hub/status'); online = !!(await r.json()).online; }catch(e){} }
      cars.push({id:id, nome:c.nome, active: c.status === 'active', monitor: !!m.on, seen: seen || c.seen || 0, online: online});
    }
    return H.json({user:user, cars:cars});
  }

  if(p === '/c/api/unlink' && request.method === 'POST'){
    const b = await request.json().catch(() => ({}));
    const id = String(b.car || '');
    cli.cars = (cli.cars || []).filter(x => x !== id);
    await putCli(env, user, cli);
    const m = await getMon(env, id);
    m.clients = (m.clients || []).filter(x => x !== user);
    await putMon(env, id, m);
    return H.json({ok:true});
  }

  return H.json({error:'not found'}, 404);
}

// ---------------------------------------------------------------------------
//  PWA
// ---------------------------------------------------------------------------
const MANIFEST = {
  name: 'Unlock BYD',
  short_name: 'Unlock BYD',
  description: 'Acompanhe o seu carro de qualquer lugar',
  id: '/c/',
  start_url: '/c/',
  scope: '/c/',
  display: 'standalone',
  orientation: 'portrait',
  background_color: '#0A0C10',
  theme_color: '#0A0C10',
  lang: 'pt-BR',
  icons: [
    {src:'/c/icon-192.png', sizes:'192x192', type:'image/png', purpose:'any'},
    {src:'/c/icon-512.png', sizes:'512x512', type:'image/png', purpose:'any'}
  ]
};

// Service worker: só guarda a "casca" (página + ícones) pra abrir rápido; a API sempre vai na rede.
const SW_JS = `
const V = 'ubyd-c-4';
const SHELL = ['/c/', '/c/manifest.webmanifest', '/c/icon-192.png', '/c/icon-512.png', '/c/apple-touch-icon.png'];
self.addEventListener('install', e => { e.waitUntil(caches.open(V).then(c => c.addAll(SHELL)).then(() => self.skipWaiting())); });
self.addEventListener('activate', e => { e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== V).map(k => caches.delete(k)))).then(() => self.clients.claim())); });
self.addEventListener('fetch', e => {
  const u = new URL(e.request.url);
  if (e.request.method !== 'GET' || u.pathname.indexOf('/c/api/') === 0) return;
  // rede primeiro (pega atualização na hora), cache se estiver sem internet
  e.respondWith(fetch(e.request).then(r => { const cp = r.clone(); caches.open(V).then(c => c.put(e.request, cp)); return r; }).catch(() => caches.match(e.request)));
});
`;

const CLIENT_HTML = `<!doctype html>
<html lang="pt-BR"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover,user-scalable=no">
<title>Unlock BYD</title>
<meta name="theme-color" content="#0A0C10">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<meta name="apple-mobile-web-app-title" content="Unlock BYD">
<link rel="manifest" href="/c/manifest.webmanifest">
<link rel="apple-touch-icon" href="/c/apple-touch-icon.png">
<link rel="icon" type="image/png" href="/c/icon-192.png">
<style>
:root{--bg:#0A0C10;--s1:#141922;--s2:#1A2029;--bd:#232B38;--tx:#E8ECF3;--mu:#8793A6;--fa:#5d687a;--ac:#2BB0A6;--gr:#27C08A;--rd:#EF5350;--am:#F2C25B}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{margin:0;background:var(--bg);color:var(--tx);font:15px/1.45 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;min-height:100%}
body{padding:calc(env(safe-area-inset-top) + 18px) 18px calc(env(safe-area-inset-bottom) + 24px)}
.wrap{max-width:460px;margin:0 auto}
.brand{display:flex;align-items:center;gap:12px;margin:6px 0 22px}
.brand img{width:44px;height:44px;border-radius:12px;background:#fff}
.brand b{font-size:18px;display:block}.brand span{font-size:12.5px;color:var(--mu)}
.card{background:var(--s1);border:1px solid var(--bd);border-radius:16px;padding:18px;margin-bottom:14px}
h2{font-size:19px;margin:0 0 4px}.sub{color:var(--mu);font-size:13.5px;margin:0 0 16px}
label{display:block;font-size:12.5px;color:var(--mu);margin:12px 0 5px}
input[type=text],input[type=password],input[type=email]{width:100%;background:var(--s2);border:1px solid var(--bd);border-radius:11px;color:var(--tx);font-size:16px;padding:13px 14px;outline:none}
input:focus{border-color:var(--ac)}
.code{text-transform:uppercase;letter-spacing:3px;font-family:ui-monospace,Menlo,monospace;font-weight:700;text-align:center;font-size:20px!important}
.btn{display:block;width:100%;border:0;border-radius:12px;padding:14px;font-size:15.5px;font-weight:650;cursor:pointer;margin-top:16px}
.btn-p{background:var(--ac);color:#04201D}.btn-g{background:var(--s2);color:var(--tx);border:1px solid var(--bd)}
.btn:disabled{opacity:.5}
.link{background:none;border:0;color:var(--ac);font-size:14px;padding:12px 0 0;cursor:pointer;width:100%}
.err{color:var(--rd);font-size:13px;min-height:18px;margin-top:10px}
.chk{display:flex;gap:10px;align-items:flex-start;font-size:13px;color:var(--mu);margin-top:14px}
.chk input{margin-top:3px;width:18px;height:18px;accent-color:var(--ac)}
.car{display:flex;align-items:center;gap:14px}
.car .ic{width:46px;height:46px;border-radius:12px;background:var(--s2);display:flex;align-items:center;justify-content:center;font-size:22px}
.car b{font-size:16px}.dot{font-size:12.5px;color:var(--mu)}
.live{margin-top:14px;background:#000;border-radius:12px;aspect-ratio:16/9;position:relative;overflow:hidden}
.live video{width:100%;height:100%;object-fit:contain;display:block;background:#000}
.ov{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;text-align:center;padding:16px;color:#c9d1dc;font-size:13.5px;background:rgba(0,0,0,.6)}
.on{color:var(--gr)}.off{color:var(--fa)}
.chips{display:flex;gap:6px;flex-wrap:wrap;margin-top:8px}.chip{background:var(--s2);border:1px solid var(--bd);color:var(--tx);border-radius:99px;padding:6px 12px;font-size:13px}.chip.sel{background:var(--ac);color:#04201D;border-color:var(--ac);font-weight:700}
select{background:var(--s2);color:var(--tx);border:1px solid var(--bd);border-radius:10px;padding:6px 8px;font-size:13px}
.tag{display:inline-block;font-size:11px;font-weight:700;padding:2px 8px;border-radius:99px;background:var(--s2);border:1px solid var(--bd);color:var(--mu)}
.hint{background:#10251f;border:1px solid #1f4d43;color:#bfe9e3;border-radius:14px;padding:14px;font-size:13.5px;margin-bottom:14px;position:relative}
.hint b{color:#fff}.hint .x{position:absolute;top:6px;right:10px;background:none;border:0;color:#8fbfb8;font-size:20px}
.small{font-size:12px;color:var(--fa);margin-top:16px;text-align:center}
.hide{display:none}
</style></head><body><div class="wrap">
<div class="brand"><img src="/c/icon-192.png" alt=""><div><b>Unlock BYD</b><span>O seu carro, de qualquer lugar</span></div></div>

<div id="install" class="hint hide"><button class="x" onclick="hideInstall()">×</button><span id="install-txt"></span></div>

<!-- LOGIN -->
<div id="v-login" class="card hide">
  <h2>Entrar</h2><p class="sub">Use o login criado quando você vinculou o carro.</p>
  <form onsubmit="return doLogin(event)">
    <label>Usuário (e-mail ou telefone)</label><input id="lu" type="text" autocomplete="username" autocapitalize="none" spellcheck="false">
    <label>Senha</label><input id="lp" type="password" autocomplete="current-password">
    <button class="btn btn-p" id="lb">Entrar</button>
  </form>
  <div class="err" id="le"></div>
  <button class="link" onclick="show('pair')">Primeiro acesso? Vincular meu carro</button>
</div>

<!-- VINCULAR -->
<div id="v-pair" class="card hide">
  <h2>Vincular meu carro</h2><p class="sub">Digite o código que a Unlock BYD te passou e crie o seu login.</p>
  <form onsubmit="return doPair(event)">
    <label>Código de vínculo</label><input id="pc" class="code" type="text" maxlength="9" placeholder="XXXX-XXXX" autocapitalize="characters" spellcheck="false" oninput="fmtCode(this)">
    <label>Seu usuário (e-mail ou telefone)</label><input id="pu" type="text" autocomplete="username" autocapitalize="none" spellcheck="false">
    <label>Crie uma senha (mín. 8 caracteres)</label><input id="pp" type="password" autocomplete="new-password">
    <div class="chk"><input id="pk" type="checkbox"><span>Sou o responsável pelo veículo e autorizo a exibição das câmeras dele neste app. As imagens são transmitidas ao vivo, só quando eu abrir o app, e não são gravadas pela Unlock BYD.</span></div>
    <button class="btn btn-p" id="pb">Vincular</button>
  </form>
  <div class="err" id="pe"></div>
  <p class="small">Já tem login e quer adicionar outro carro? Use o mesmo usuário e senha.</p>
  <button class="link" onclick="show('login')">Já tenho login</button>
</div>

<!-- HOME -->
<div id="v-home" class="hide">
  <div id="cars"></div>
  <button class="btn btn-g" onclick="show('pair')">+ Vincular outro carro</button>
  <button class="link" onclick="logout()">Sair</button>
  <p class="small" id="who"></p>
</div>
</div>
<script>
var T=null,U=null,CARS=[];try{T=localStorage.getItem('ubyd_c_token');U=localStorage.getItem('ubyd_c_user');}catch(e){}
function $(i){return document.getElementById(i);}
function esc(s){return String(s==null?'':s).replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];});}
function show(v){['login','pair','home'].forEach(function(x){$('v-'+x).classList.toggle('hide',x!==v);});if(v==='home')loadMe();}
function save(t,u){T=t;U=u;try{localStorage.setItem('ubyd_c_token',t);localStorage.setItem('ubyd_c_user',u);}catch(e){}}
function logout(){T=null;U=null;try{localStorage.removeItem('ubyd_c_token');localStorage.removeItem('ubyd_c_user');}catch(e){}show('login');}
function api(path,body){return fetch(path,{method:body?'POST':'GET',headers:Object.assign({'Content-Type':'application/json'},T?{'Authorization':'Bearer '+T}:{}),body:body?JSON.stringify(body):undefined})
 .then(function(r){return r.json().catch(function(){return {};}).then(function(j){if(r.status===401&&T&&path.indexOf('/login')<0&&path.indexOf('/pair')<0){logout();}if(!r.ok)throw new Error(j.error||'Falha de conexão');return j;});});}
function fmtCode(el){var v=el.value.toUpperCase().replace(/[^A-Z0-9]/g,'').slice(0,8);el.value=v.length>4?v.slice(0,4)+'-'+v.slice(4):v;}
function busy(b,on,txt){b.disabled=on;b.textContent=txt;}
function doLogin(e){e.preventDefault();var b=$('lb');$('le').textContent='';busy(b,true,'Entrando…');
 api('/c/api/login',{user:$('lu').value,pass:$('lp').value}).then(function(r){save(r.token,r.user);$('lp').value='';show('home');})
 .catch(function(x){$('le').textContent=x.message;}).then(function(){busy(b,false,'Entrar');});return false;}
function doPair(e){e.preventDefault();var b=$('pb');$('pe').textContent='';
 if(!$('pk').checked){$('pe').textContent='Marque a autorização para continuar.';return false;}
 busy(b,true,'Vinculando…');
 api('/c/api/pair',{code:$('pc').value,user:$('pu').value,pass:$('pp').value,consent:true}).then(function(r){save(r.token,r.user);$('pp').value='';$('pc').value='';show('home');})
 .catch(function(x){$('pe').textContent=x.message;}).then(function(){busy(b,false,'Vincular');});return false;}
function ago(ms){if(!ms)return 'sem registro';var m=Math.round((Date.now()-ms)/60000);if(m<2)return 'agora há pouco';if(m<60)return 'há '+m+' min';var h=Math.round(m/60);if(h<24)return 'há '+h+' h';return 'há '+Math.round(h/24)+' dia(s)';}
function loadMe(){if(LIVE)stopLive();$('cars').innerHTML='<div class="card" style="color:var(--mu)">Carregando…</div>';$('who').textContent=U?('Conectado como '+U):'';
 api('/c/api/me').then(function(r){
  if(!r.cars.length){$('cars').innerHTML='<div class="card"><h2>Nenhum carro vinculado</h2><p class="sub" style="margin:0">Peça um código de vínculo à Unlock BYD e toque em "Vincular outro carro".</p></div>';return;}
  CARS=r.cars;$('cars').innerHTML=r.cars.map(function(c,i){
   var st=!c.active?'<span class="tag" style="color:var(--am)">Acesso suspenso</span>':(!c.monitor?'<span class="tag">Monitoramento desligado</span>':'<span class="tag" style="color:var(--gr)">Monitoramento liberado</span>');
   var conn=c.online?'<span class="on">● Online agora</span>':'<span class="off">○ Desligado ou sem internet · último contato '+ago(c.seen)+'</span>';
   var can=c.active&&c.monitor;
   return '<div class="card"><div class="car"><div class="ic">🚗</div><div style="flex:1"><b>'+esc(c.nome)+'</b><div class="dot">'+conn+'</div></div></div>'+
    '<div style="margin-top:10px">'+st+'</div>'+
    (can?('<div class="live"><video id="vid-'+i+'" playsinline muted autoplay onclick="fullV(this)"></video><div class="ov" id="ov-'+i+'">📷 Toque em “Ver ao vivo”</div></div>'+
      '<div class="dot" id="inf-'+i+'" style="margin-top:6px;min-height:16px"></div>'+
      '<div class="dot" id="sta-'+i+'" style="min-height:16px"></div>'+
      '<div class="chips" id="cams-'+i+'"></div>'+
      '<div class="dot" style="margin-top:8px">Resolução (teste): <select id="sz-'+i+'" onchange="pickCam('+i+',null)"><option value="auto">Automática</option><option>1280x720</option><option>1920x1080</option><option>1280x960</option><option>720x576</option><option>720x480</option><option>5120x960</option></select> Codec: <select id="cd-'+i+'" onchange="restartLive('+i+')"><option value="auto">Auto</option><option>H264</option><option>VP8</option></select></div>'+
      '<button class="btn btn-p" id="pb-'+i+'" onclick="toggleLive('+i+')">▶ Ver ao vivo</button>')
     :'<div class="live"><div class="ov">📷 Câmeras indisponíveis para este carro no momento.</div></div>')+
    '<button class="link" style="color:var(--fa);font-size:12.5px" onclick="unlink('+i+')">Desvincular este carro</button></div>';
  }).join('');
 }).catch(function(x){$('cars').innerHTML='<div class="card"><div class="err">'+esc(x.message)+'</div><button class="btn btn-g" onclick="loadMe()">Tentar de novo</button></div>';});}
function unlink(i){var c=CARS[i];if(!c||!confirm('Desvincular '+c.nome+' deste login?'))return;api('/c/api/unlink',{car:c.id}).then(loadMe).catch(function(x){alert(x.message);});}

/* câmeras ao vivo (WebRTC) */
var LIVE=null;
function ov(i,t){var o=$('ov-'+i);if(!o)return;o.textContent=t||'';o.style.display=t?'flex':'none';}
function fullV(v){try{if(v.webkitEnterFullscreen)v.webkitEnterFullscreen();else if(v.requestFullscreen)v.requestFullscreen();}catch(e){}}
function toggleLive(i){if(LIVE&&LIVE.i===i){stopLive('Transmissão encerrada.');return;}if(LIVE)stopLive();startLive(i);}
function startLive(i){var c=CARS[i];if(!c)return;var L={i:i,done:false,ice:[]};LIVE=L;
 $('pb-'+i).textContent='■ Parar';$('inf-'+i).textContent='';ov(i,'Conectando ao carro…');
 var ws=new WebSocket((location.protocol==='https:'?'wss:':'ws:')+'//'+location.host+'/c/ws?car='+encodeURIComponent(c.id)+'&t='+encodeURIComponent(T));L.ws=ws;
 var start=function(){try{ws.send(JSON.stringify({t:'start',cam:L.want||'auto',size:($('sz-'+i)||{}).value||'auto',codec:($('cd-'+i)||{}).value||'auto'}));}catch(e){}};
 L.stats=setInterval(function(){statsTick(L);},2000);
 ws.onmessage=function(e){if(L.done)return;var m;try{m=JSON.parse(e.data);}catch(x){return;}
  if(m.t==='hello'){L.ice=m.ice||[];if(m.online){ov(i,'Ligando a câmera…');start();}else ov(i,'O carro está desligado ou sem internet. Pode esperar aqui: quando ele conectar, a imagem começa.');}
  else if(m.t==='online'){if(!L.pc){ov(i,'O carro conectou. Ligando a câmera…');start();}}
  else if(m.t==='offline'){dropPc(L);ov(i,'O carro desconectou (desligado ou sem internet). Esperando ele voltar…');}
  else if(m.t==='offer'){onOffer(L,m.sdp);}
  else if(m.t==='info'){L.cam=m.cam;$('inf-'+i).textContent='Câmera: '+(m.cam||'?')+(m.w?(' · '+m.w+'×'+m.h):'')+(m.def?(' · padrão da câmera '+m.def):'');
   var cs=m.cams||[];L.cams=cs;$('cams-'+i).innerHTML=cs.map(function(k,j){return '<button class="chip'+(k===m.cam?' sel':'')+'" onclick="pickCam('+i+','+j+')">'+camName(k)+'</button>';}).join('');
   var sz=$('sz-'+i);if(sz&&m.size)sz.value=m.size;}
  else if(m.t==='stats'){L.car=m;}
  else if(m.t==='error'){ov(i,'O carro avisou: '+(m.msg||'erro'));}
 };
 ws.onclose=function(ev){if(!L.done)stopLive(ev.code===1006?'Sem conexão com o servidor. Toque em Ver ao vivo para tentar de novo.':'Conexão encerrada. Toque em Ver ao vivo para tentar de novo.');};
 L.timer=setTimeout(function(){stopLive('Sessão encerrada após 5 minutos (para economizar dados). Toque para ver de novo.');},5*60*1000);
}
function gathered(pc,ms){return new Promise(function(res){if(pc.iceGatheringState==='complete')return res();var t=setTimeout(res,ms);
 pc.addEventListener('icegatheringstatechange',function(){if(pc.iceGatheringState==='complete'){clearTimeout(t);res();}});});}
function onOffer(L,sdp){dropPc(L);var pc=new RTCPeerConnection({iceServers:L.ice});L.pc=pc;var v=$('vid-'+L.i);
 pc.ontrack=function(e){if(!v)return;v.srcObject=(e.streams&&e.streams[0])?e.streams[0]:new MediaStream([e.track]);var p=v.play();if(p&&p.catch)p.catch(function(){});};
 pc.onconnectionstatechange=function(){if(L.pc!==pc)return;var s=pc.connectionState;
  if(s==='connected')ov(L.i,'');else if(s==='failed')ov(L.i,'Não consegui receber o vídeo nesta rede. Tente de novo (ou em outra rede).');else if(s==='disconnected')ov(L.i,'Sinal instável…');};
 pc.setRemoteDescription({type:'offer',sdp:sdp}).then(function(){return pc.createAnswer();}).then(function(a){return pc.setLocalDescription(a);})
  .then(function(){return gathered(pc,2500);}).then(function(){if(L.done||L.pc!==pc)return;L.ws.send(JSON.stringify({t:'answer',sdp:pc.localDescription.sdp}));ov(L.i,'Conectando o vídeo…');})
  .catch(function(e){ov(L.i,'Erro ao abrir o vídeo: '+(e&&e.message||e));});}
var CAMN={rvs:'Ré',rear:'Traseira',front:'Frontal',pano_h:'360° (alta)',pano_l:'360° (baixa)',byd_apa:'360° APA',apa:'APA',rf:'Direita',dms:'Motorista',face:'Rosto'};
function camName(k){return CAMN[k]||(k.indexOf('id:')===0?('Câmera '+k.slice(3)):k);}
function pickCam(i,j){var L=LIVE;if(!L||L.i!==i)return;if(j!=null&&L.cams)L.want=L.cams[j];var sz=($('sz-'+i)||{}).value||'auto';
 try{L.ws.send(JSON.stringify({t:'cam',cam:L.want||L.cam||'auto',size:sz}));}catch(e){}ov(i,'Trocando de câmera…');setTimeout(function(){if(LIVE===L&&L.pc&&L.pc.connectionState==='connected')ov(i,'');},2500);}
function statsTick(L){var el=$('sta-'+L.i);if(!el)return;var c=L.car;
 var car=c?('Carro: câmera '+c.fps+' q/s'+(c.ow?(' · envia '+c.ow+'×'+c.oh+' '+Math.round(c.ofps||0)+' q/s'):'')+(c.kbps!=null?(' '+c.kbps+' kbps'):'')+(c.codec?(' · '+String(c.codec).replace('video/','')):'')+(c.enc?(' ('+c.enc+')'):'')+(c.lim&&c.lim!=='none'?(' · limitado por: '+(c.lim==='bandwidth'?'internet':c.lim==='cpu'?'processador':c.lim)):'')+(c.aob?(' · banda '+Math.round(c.aob)+' kbps'):'')+(c.net?(' · rede '+c.net):'')):'Carro: aguardando…';
 if(!L.pc){el.textContent=car;return;}
 L.pc.getStats().then(function(r){var t='';r.forEach(function(s){if(s.type==='inbound-rtp'&&s.kind==='video'){var kb=L.lastB!=null?Math.round((s.bytesReceived-L.lastB)*8/2000):0;L.lastB=s.bytesReceived;
   t=' — Celular: '+(s.framesPerSecond||0)+' q/s'+(s.frameWidth?(', '+s.frameWidth+'×'+s.frameHeight):'')+', '+kb+' kbps';}});el.textContent=car+t;}).catch(function(){});}
function restartLive(i){if(LIVE&&LIVE.i===i){stopLive();startLive(i);}}
function dropPc(L){if(L.pc){try{L.pc.close();}catch(e){}L.pc=null;}var v=$('vid-'+L.i);if(v)v.srcObject=null;}
function stopLive(msg){var L=LIVE;if(!L)return;L.done=true;LIVE=null;clearTimeout(L.timer);clearInterval(L.stats);var se=$('sta-'+L.i);if(se)se.textContent='';
 try{L.ws.send(JSON.stringify({t:'stop'}));}catch(e){}try{L.ws.close();}catch(e){}dropPc(L);
 var b=$('pb-'+L.i);if(b)b.textContent='▶ Ver ao vivo';ov(L.i,msg||'📷 Toque em “Ver ao vivo”');}
document.addEventListener('visibilitychange',function(){if(document.hidden&&LIVE)stopLive('Pausado ao sair do app. Toque para ver de novo.');});

/* instalar na tela de início */
var deferred=null;
function standalone(){return (window.matchMedia&&matchMedia('(display-mode: standalone)').matches)||navigator.standalone===true;}
function hideInstall(){$('install').classList.add('hide');try{localStorage.setItem('ubyd_c_nohint','1');}catch(e){}}
function maybeHint(){var off=false;try{off=localStorage.getItem('ubyd_c_nohint')==='1';}catch(e){}if(standalone()||off)return;
 var ua=navigator.userAgent||'';var ios=/iPhone|iPad|iPod/.test(ua)||(navigator.platform==='MacIntel'&&navigator.maxTouchPoints>1);
 if(ios){$('install-txt').innerHTML='<b>Instale como app:</b> toque em <b>Compartilhar</b> (o quadrado com a seta ⬆) e depois em <b>“Adicionar à Tela de Início”</b>.'+(/CriOS|FxiOS|EdgiOS/.test(ua)?'<br>Abra este endereço no <b>Safari</b> para conseguir instalar.':'');$('install').classList.remove('hide');}}
window.addEventListener('beforeinstallprompt',function(e){e.preventDefault();deferred=e;if(standalone())return;
 $('install-txt').innerHTML='<b>Instale como app</b> para abrir direto da tela inicial.<button class="btn btn-p" style="margin-top:10px;padding:11px" onclick="doInstall()">Instalar app</button>';$('install').classList.remove('hide');});
function doInstall(){if(!deferred)return;deferred.prompt();deferred.userChoice.then(function(){deferred=null;$('install').classList.add('hide');});}
if('serviceWorker' in navigator){navigator.serviceWorker.register('/c/sw.js',{scope:'/c/'}).catch(function(){});}
maybeHint();
show(T?'home':'login');
</script></body></html>`;
