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
import { TELE_UI_JS } from './tele.js';
import { vapidPublic, addSub, takePending, notifyUser } from './push.js';
export { CarHub } from './hub.js';

const CLIENT_TOKEN_TTL_MS = 1000 * 60 * 60 * 24 * 30;  // 30 dias: cara de app, sem pedir senha toda hora
const PAIR_TTL_S = 600;                                 // código de vínculo vale 10 min
const RESET_TTL_S = 24 * 60 * 60;   // código de nova senha vale 24 h
const RESET_MAX_TRIES = 5;
const PAIR_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789'; // sem 0/O/1/I/L pra não confundir

function cleanUser(u){ return String(u || '').trim().toLowerCase(); }
function validUser(u){ return u.length >= 3 && u.length <= 60 && /^[a-z0-9._@+-]+$/.test(u); }
function cleanCode(c){ return String(c || '').toUpperCase().replace(/[^A-Z0-9]/g, ''); }

async function getMon(env, id){ return (await env.KV.get('mon:' + id, {type:'json'})) || {on:false, clients:[]}; }
// on = câmeras ao vivo · ctl = controles do carro (liberados separadamente). Qualquer um dos dois abre o vínculo e a conexão.
const monAny = (m) => !!(m && (m.on || m.ctl));
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
  if(p === '/api/tele'){
    if(!user) return H.json({error:'unauth'}, 401);
    if(!env.CAR_HUB) return H.json({last:null, rows:[]});
    const u = new URL(request.url), id = String(u.searchParams.get('id') || '');
    return env.CAR_HUB.get(env.CAR_HUB.idFromName(id)).fetch('https://hub/tele?h=' + encodeURIComponent(u.searchParams.get('h') || '24'));
  }
  if(!(p === '/api/monitor' || p === '/api/pair-code' || p === '/api/monitor-unlink')) return null;
  if(!user) return H.json({error:'unauth'}, 401);
  const url = new URL(request.url);

  if(p === '/api/monitor' && request.method === 'GET'){
    const id = String(url.searchParams.get('id') || '');
    const m = await getMon(env, id);
    // Carros liberados antes do campo c.mon existir: sincroniza ao abrir a ficha no painel.
    try{ const c = await H.getCar(env, id); if(c && (c.mon === true) !== monAny(m)){ c.mon = monAny(m); await H.putCar(env, c); } }catch(e){}
    const resets = {};
    for(const u of (m.clients || [])){ try{ const r = await env.KV.get('reset:' + u, {type:'json'}); if(r) resets[u] = {code: r.code, at: r.at}; }catch(e){} }
    return H.json({on: !!m.on, ctl: !!m.ctl, clients: m.clients || [], resets});
  }
  const b = await request.json().catch(() => ({}));
  const id = String(b.id || '').trim();
  const c = await H.getCar(env, id);
  if(!c) return H.json({error:'car'}, 404);
  const m = await getMon(env, id);

  if(p === '/api/monitor' && request.method === 'POST'){
    let acao = '';
    if(typeof b.on === 'boolean'){ m.on = b.on; acao = m.on ? 'Câmeras ao vivo liberadas' : 'Câmeras ao vivo desligadas'; }
    if(typeof b.ctl === 'boolean'){ m.ctl = b.ctl; acao = m.ctl ? 'Controles do carro liberados' : 'Controles do carro desligados'; }
    m.by = user; m.at = Date.now();
    await putMon(env, id, m);
    c.mon = monAny(m);   // vai junto no /api/register: o carro só conecta no hub se câmeras ou controles estiverem liberados
    if(acao) c.log.push({acao, by: user, when: H.fmt(Date.now()), cls: (b.on === true || b.ctl === true) ? 'a-act' : 'a-rev'});
    await H.putCar(env, c);
    return H.json({ok:true, on:!!m.on, ctl:!!m.ctl});
  }
  if(p === '/api/pair-code' && request.method === 'POST'){
    if(!monAny(m)) return H.json({error:'Monitoramento desligado para este carro'}, 400);
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
  if(!monAny(m)) return H.json({error:'Monitoramento desligado'}, 403);
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
    if(!monAny(m) || !car || car.status !== 'active') return H.json({error:'Monitoramento não liberado para este carro'}, 403);
    const rnd = new Uint8Array(4); crypto.getRandomValues(rnd);
    const vid = user + '~' + [...rnd].map(x => x.toString(16).padStart(2, '0')).join('');
    const hdr = new Headers(request.headers); hdr.set('X-Role', 'viewer'); hdr.set('X-Viewer', vid); hdr.set('X-Cam', m.on ? '1' : '0'); hdr.set('X-Ctl', m.ctl ? '1' : '0');
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
    if(!car || !monAny(m)) return H.json({error:'Monitoramento não está liberado para este carro'}, 400);
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

  // --- esqueci a senha: 1) pede (gera código p/ a equipe)  2) confere o código  3) grava a senha nova ---
  if(p === '/c/api/reset-request' && request.method === 'POST'){
    const b = await request.json().catch(() => ({}));
    const user = cleanUser(b.user);
    // Resposta sempre igual: não revela se o usuário existe.
    const cli = validUser(user) ? await getCli(env, user) : null;
    if(cli){
      const prev = await env.KV.get('reset:' + user, {type:'json'});
      // Evita gerar um código novo a cada toque (o anterior continua valendo por 24 h).
      if(!prev || Date.now() - (prev.at || 0) > 10 * 60 * 1000){
        const rnd = new Uint32Array(1); crypto.getRandomValues(rnd);
        const code = String(10000 + (rnd[0] % 90000));
        await env.KV.put('reset:' + user, JSON.stringify({code, at: Date.now(), tries: 0}), {expirationTtl: RESET_TTL_S});
        for(const id of (cli.cars || [])){
          try{ const c = await H.getCar(env, id); if(c){ c.log.push({acao: 'Cliente ' + user + ' pediu nova senha (código ' + code + ')', by: 'cliente', when: H.fmt(Date.now()), cls: 'a-rev'}); await H.putCar(env, c); } }catch(e){}
        }
      }
    }
    return H.json({ok: true});
  }
  if(p === '/c/api/reset-verify' && request.method === 'POST'){
    const b = await request.json().catch(() => ({}));
    const user = cleanUser(b.user), code = String(b.code || '').replace(/\D/g, '');
    const r = user ? await env.KV.get('reset:' + user, {type:'json'}) : null;
    if(!r) return H.json({error: 'Código inválido ou expirado. Peça um novo.'}, 400);
    if(r.code !== code){
      r.tries = (r.tries || 0) + 1;
      if(r.tries >= RESET_MAX_TRIES){ await env.KV.delete('reset:' + user); return H.json({error: 'Código errado muitas vezes. Peça um novo código.'}, 400); }
      await env.KV.put('reset:' + user, JSON.stringify(r), {expirationTtl: RESET_TTL_S});
      return H.json({error: 'Código incorreto. Restam ' + (RESET_MAX_TRIES - r.tries) + ' tentativas.'}, 400);
    }
    // Código certo: libera a troca por 15 min (token assinado, uso único junto com o código).
    const exp = Date.now() + 15 * 60 * 1000;
    const body = 'r|' + user + '|' + r.code + '|' + exp;
    return H.json({rt: btoa(body) + '.' + (await H.hmacHex(env.SECRET, body))});
  }
  if(p === '/c/api/reset-set' && request.method === 'POST'){
    const b = await request.json().catch(() => ({}));
    const pass = String(b.pass || '');
    let user = null, code = null;
    try{
      const parts = String(b.rt || '').split('.'); const body = atob(parts[0]);
      if(parts.length === 2 && parts[1] === await H.hmacHex(env.SECRET, body)){
        const f = body.split('|'); if(f[0] === 'r' && Number(f[3]) > Date.now()){ user = f[1]; code = f[2]; }
      }
    }catch(e){}
    if(!user) return H.json({error: 'O tempo para trocar a senha acabou. Comece de novo.'}, 400);
    const r = await env.KV.get('reset:' + user, {type:'json'});
    if(!r || r.code !== code) return H.json({error: 'Este código já foi usado. Peça um novo.'}, 400);
    if(pass.length < 8) return H.json({error: 'A senha precisa ter pelo menos 8 caracteres'}, 400);
    const cli = await getCli(env, user);
    if(!cli) return H.json({error: 'Usuário não encontrado'}, 400);
    cli.hash = await clientHash(H, env, user, pass); cli.resetAt = Date.now();
    await putCli(env, user, cli);
    await env.KV.delete('reset:' + user);
    for(const id of (cli.cars || [])){
      try{ const c = await H.getCar(env, id); if(c){ c.log.push({acao: 'Cliente ' + user + ' criou senha nova', by: 'cliente', when: H.fmt(Date.now()), cls: 'a-act'}); await H.putCar(env, c); } }catch(e){}
    }
    return H.json({token: await makeClientToken(H, env.SECRET, user), user: user});
  }

  if(p === '/c/api/login' && request.method === 'POST'){
    const b = await request.json().catch(() => ({}));
    const user = cleanUser(b.user), pass = String(b.pass || '');
    const cli = user ? await getCli(env, user) : null;
    if(!cli || cli.hash !== await clientHash(H, env, user, pass)) return H.json({error:'Usuário ou senha inválidos'}, 401);
    return H.json({token: await makeClientToken(H, env.SECRET, user), user: user});
  }

  // --- notificações: chave pública e mensagens pendentes (o service worker busca sem login, pela chave do endpoint) ---
  if(p === '/c/api/push-key' && request.method === 'GET') return H.json({key: await vapidPublic(env)});
  if(p === '/c/api/push-pending' && request.method === 'POST'){
    const b = await request.json().catch(() => ({}));
    return H.json({msgs: await takePending(env, b.endpoint)});
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
      let online = false, bat = null, ac = null;
      if(env.CAR_HUB && monAny(m)){ try{ const r = await env.CAR_HUB.get(env.CAR_HUB.idFromName(id)).fetch('https://hub/status'); const j = await r.json(); online = !!j.online; bat = j.bat || null; if(bat && j.vig) bat.vig = j.vig.s; ac = j.ac || null; }catch(e){} }
      cars.push({id:id, nome:c.nome, active: c.status === 'active', monitor: !!m.on, controls: !!m.ctl, seen: seen || c.seen || 0, online: online, bat: bat, ac: ac});
    }
    return H.json({user:user, cars:cars});
  }

  if(p === '/c/api/push-sub' && request.method === 'POST'){
    const b = await request.json().catch(() => ({}));
    if(!(await addSub(env, user, b.sub))) return H.json({error:'inscrição inválida'}, 400);
    await notifyUser(env, user, '🔔 Notificações ativadas', 'Você vai receber aqui os avisos de bateria 12V baixa e do modo guardião.', 'hello');
    return H.json({ok:true});
  }

  if(p === '/c/api/tele' && request.method === 'GET'){
    const u = new URL(request.url), id = String(u.searchParams.get('car') || '');
    const m = await getMon(env, id);
    if((cli.cars || []).indexOf(id) < 0 || (m.clients || []).indexOf(user) < 0) return H.json({error:'unauth'}, 401);
    if(!env.CAR_HUB) return H.json({last:null, rows:[]});
    return env.CAR_HUB.get(env.CAR_HUB.idFromName(id)).fetch('https://hub/tele?h=' + encodeURIComponent(u.searchParams.get('h') || '24'));
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
const V = 'ubyd-c-17';
const SHELL = ['/c/', '/c/manifest.webmanifest', '/c/icon-192.png', '/c/icon-512.png', '/c/apple-touch-icon.png'];
self.addEventListener('install', e => { e.waitUntil(caches.open(V).then(c => c.addAll(SHELL)).then(() => self.skipWaiting())); });
self.addEventListener('activate', e => { e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== V).map(k => caches.delete(k)))).then(() => self.clients.claim())); });
self.addEventListener('push', e => {
  e.waitUntil(self.registration.pushManager.getSubscription().then(s => s ? fetch('/c/api/push-pending', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({endpoint: s.endpoint})}).then(r => r.json()) : {msgs: []})
    .catch(() => ({msgs: []}))
    .then(d => { const ms = (d && d.msgs) || []; if(!ms.length) ms.push({title: 'Unlock BYD', body: 'Novo aviso do seu carro. Toque para abrir.', tag: 'ubyd'});
      return Promise.all(ms.map(m => self.registration.showNotification(m.title, {body: m.body, tag: m.tag + '-' + (m.ts || Date.now()), icon: '/c/icon-192.png', badge: '/c/icon-192.png', data: {url: '/c/'}}))); }));
});
self.addEventListener('notificationclick', e => {
  e.notification.close();
  e.waitUntil(self.clients.matchAll({type: 'window', includeUncontrolled: true}).then(ws => { for(const w of ws){ if('focus' in w) return w.focus(); } return self.clients.openWindow('/c/'); }));
});
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
.live.fs{position:fixed;inset:0;z-index:60;width:100vw;height:100vh;height:100dvh;aspect-ratio:auto;margin:0;border-radius:0}
.live canvas{position:absolute;inset:0;width:100%;height:100%;object-fit:contain;background:#000;display:none}
.fsbar{display:none;position:absolute;left:0;right:0;top:0;z-index:4;gap:6px;flex-wrap:wrap;align-items:center;padding:calc(env(safe-area-inset-top) + 8px) 10px 10px;background:linear-gradient(rgba(0,0,0,.75),rgba(0,0,0,0))}
.live.fs .fsbar{display:flex}
.fsx{margin-left:auto;background:rgba(0,0,0,.6);border:1px solid #444;color:#fff;border-radius:99px;padding:6px 12px;font-size:14px}
.fsbtn{position:absolute;right:8px;bottom:8px;z-index:3;background:rgba(0,0,0,.55);border:1px solid #444;color:#fff;border-radius:8px;padding:5px 9px;font-size:13px}
.live.fs .fsbtn{display:none}
.ov{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;text-align:center;padding:16px;color:#c9d1dc;font-size:13.5px;background:rgba(0,0,0,.6)}
.on{color:var(--gr)}.off{color:var(--fa)}
.ctl{margin-top:12px;border-top:1px solid rgba(128,128,128,.2);padding-top:10px}.ctlh{font-weight:700;margin-bottom:8px}.cg{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin:8px 0}
.cb{border:1px solid rgba(128,128,128,.35);background:rgba(128,128,128,.08);color:inherit;border-radius:12px;padding:11px 8px;font-size:14px;cursor:pointer}.cb:active{transform:scale(.97)}.cb.cp{background:#2bb0a6;border-color:#2bb0a6;color:#04201d;font-weight:700}
.cb.mic{width:100%;margin:10px 0 2px}.cb.mic.on,.fsx.on{background:#ef5350;border-color:#ef5350;color:#fff}
.cr{display:flex;justify-content:space-between;align-items:center;margin:8px 0}.st{display:flex;align-items:center;gap:10px}.st .cb{width:42px;padding:8px 0;font-size:18px}.st b{min-width:54px;text-align:center}
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
  <button class="link" style="color:var(--mu);padding-top:6px" onclick="startForgot()">Esqueci minha senha</button>
</div>

<!-- ESQUECI A SENHA -->
<div id="v-forgot" class="card hide">
  <h2>Nova senha</h2>
  <div id="fg1"><p class="sub">Digite o seu usuário. A Unlock BYD vai receber um código de 5 dígitos e te passar depois de confirmar que é você.</p>
    <label>Usuário (e-mail ou telefone)</label><input id="fu" type="text" autocomplete="username" autocapitalize="none" spellcheck="false">
    <button class="btn btn-p" id="fb1" onclick="fgRequest()">Pedir código</button></div>
  <div id="fg2" class="hide"><p class="sub">Pedido enviado. Fale com a Unlock BYD para receber o <b>código de 5 dígitos</b> e digite abaixo.</p>
    <label>Código</label><input id="fc" class="code" type="text" inputmode="numeric" maxlength="5" placeholder="00000">
    <button class="btn btn-p" id="fb2" onclick="fgVerify()">Confirmar código</button></div>
  <div id="fg3" class="hide"><p class="sub">Código confirmado. Crie a sua senha nova.</p>
    <label>Nova senha (mín. 8 caracteres)</label><input id="fp1" type="password" autocomplete="new-password">
    <label>Confirme a senha</label><input id="fp2" type="password" autocomplete="new-password">
    <button class="btn btn-p" id="fb3" onclick="fgSet()">Salvar senha nova</button></div>
  <div class="err" id="fe"></div>
  <button class="link" onclick="show('login')">Voltar para o login</button>
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
  <div class="card hide" id="pushcard" style="display:flex;align-items:center;gap:12px"><div style="flex:1"><b>🔔 Avisos no celular</b><div class="dot" id="pushtxt">Receba aviso de bateria 12V baixa e do modo guardião.</div></div><button class="btn btn-p" style="width:auto;padding:10px 14px;margin:0" id="pushbtn" onclick="enablePush()">Ativar</button></div>
  <div id="cars"></div>
  <button class="btn btn-g" onclick="show('pair')">+ Vincular outro carro</button>
  <button class="link" onclick="logout()">Sair</button>
  <p class="small" id="who"></p>
</div>
</div>
<script>
var T=null,U=null,CARS=[];var DIAG=/[?&]diag=1/.test(location.search);try{T=localStorage.getItem('ubyd_c_token');U=localStorage.getItem('ubyd_c_user');}catch(e){}
function $(i){return document.getElementById(i);}
function esc(s){return String(s==null?'':s).replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];});}
function show(v){['login','pair','home','forgot'].forEach(function(x){$('v-'+x).classList.toggle('hide',x!==v);});if(v==='home'){loadMe();pushState();startHomePoll();}else{stopHomePoll();}}
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
   var st=!c.active?'<span class="tag" style="color:var(--am)">Acesso suspenso</span>':(!(c.monitor||c.controls)?'<span class="tag">Monitoramento desligado</span>':'<span class="tag" style="color:var(--gr)">'+(c.monitor&&c.controls?'Câmeras e controles liberados':c.monitor?'Câmeras liberadas':'Controles liberados')+'</span>');
   var conn=c.online?'<span class="on">● Online agora</span>':'<span class="off">○ Desligado ou sem internet · último contato '+ago(c.seen)+'</span>';
   var can=c.active&&c.monitor,cc=c.active&&c.controls;
   return '<div class="card"><div class="car"><div class="ic">🚗</div><div style="flex:1"><b>'+esc(c.nome)+'</b><div class="dot" id="conn-'+i+'">'+conn+'</div></div></div>'+
    '<div style="margin-top:10px">'+st+'</div>'+
    (c.bat?teleAlert(c.bat,c.bat.vig):'')+(c.bat?('<div class="dot" style="margin-top:8px">🔋 <b style="color:var(--tx)">'+nb(c.bat.soc,1)+'%</b>'+(c.bat.v12!=null?' &nbsp;·&nbsp; 12V <b style="color:var(--tx)">'+nb(c.bat.v12,2)+' V</b>':'')+' <span style="color:var(--fa)">· '+ago(c.bat.ts)+'</span></div>'):'')+
    (cc?ctlPanel(i):'')+
    ((can||cc)?'<button class="link" style="padding-top:8px;text-align:left" onclick="toggleBat('+i+')">📈 Bateria e consumo</button><div id="bat-'+i+'" class="hide" style="margin-top:6px"></div>':'')+
    (can?('<div class="live" id="live-'+i+'"><video id="vid-'+i+'" playsinline muted autoplay onclick="openFull('+i+')"></video><canvas id="cv-'+i+'" onclick="openFull('+i+')"></canvas><div class="fsbar" id="fsb-'+i+'"></div><button class="fsbtn" onclick="openFull('+i+')">⛶</button><div class="ov" id="ov-'+i+'">📷 Toque em “Ver ao vivo”</div></div>'+
      '<div class="dot" id="inf-'+i+'" style="margin-top:6px;min-height:16px"></div>'+
      (DIAG?'<div class="dot" id="sta-'+i+'" style="min-height:16px"></div>':'')+
      '<div class="chips" id="cams-'+i+'"></div>'+
      '<button class="cb mic" id="mic-'+i+'" onclick="toggleMic('+i+')">🎤 Ouvir o carro</button>'+
      (DIAG?'<div class="dot" style="margin-top:8px">Resolução (teste): <select id="sz-'+i+'" onchange="pickCam('+i+',null)"><option value="auto">Padrão (720x576)</option><option>1280x720</option><option>1920x1080</option><option>1280x960</option><option>720x576</option><option>720x480</option><option>5120x960</option></select> Codec: <select id="cd-'+i+'" onchange="restartLive('+i+')"><option value="auto">H264 (padrão)</option><option>VP8</option></select></div>':'')+
      '<button class="btn btn-p" id="pb-'+i+'" onclick="toggleLive('+i+')">▶ Ver ao vivo</button>')
     :(cc?'':'<div class="live"><div class="ov">📷 Câmeras indisponíveis para este carro no momento.</div></div>'))+
    '<button class="link" style="color:var(--fa);font-size:12.5px" onclick="unlink('+i+')">Desvincular este carro</button></div>';
  }).join('');
 }).catch(function(x){$('cars').innerHTML='<div class="card"><div class="err">'+esc(x.message)+'</div><button class="btn btn-g" onclick="loadMe()">Tentar de novo</button></div>';});}
function unlink(i){var c=CARS[i];if(!c||!confirm('Desvincular '+c.nome+' deste login?'))return;api('/c/api/unlink',{car:c.id}).then(loadMe).catch(function(x){alert(x.message);});}

/* notificações */
function b64uToU8(s){s=s.replace(/-/g,'+').replace(/_/g,'/');while(s.length%4)s+='=';var r=atob(s),a=new Uint8Array(r.length);for(var i=0;i<r.length;i++)a[i]=r.charCodeAt(i);return a;}
function pushState(){var c=$('pushcard');if(!c)return;if(!('serviceWorker' in navigator)||!('PushManager' in window)||!('Notification' in window)){c.classList.add('hide');return;}
 c.classList.remove('hide');
 navigator.serviceWorker.ready.then(function(reg){return reg.pushManager.getSubscription();}).then(function(s){
  if(s&&Notification.permission==='granted'){$('pushtxt').textContent='Ativado neste celular ✓ (12V baixa e modo guardião).';$('pushbtn').textContent='Testar';}
  else if(Notification.permission==='denied'){$('pushtxt').textContent='As notificações estão bloqueadas para este site. Libere nas configurações do navegador.';}
 }).catch(function(){});}
function enablePush(){var b=$('pushbtn');b.disabled=true;
 Notification.requestPermission().then(function(p){if(p!=='granted')throw new Error('Permissão negada');return Promise.all([navigator.serviceWorker.ready,api('/c/api/push-key')]);})
 .then(function(x){return x[0].pushManager.getSubscription().then(function(s){return s||x[0].pushManager.subscribe({userVisibleOnly:true,applicationServerKey:b64uToU8(x[1].key)});});})
 .then(function(sub){return api('/c/api/push-sub',{sub:sub.toJSON()});})
 .then(function(){$('pushtxt').textContent='Ativado neste celular ✓ — mandamos uma notificação de teste.';$('pushbtn').textContent='Testar';})
 .catch(function(e){$('pushtxt').textContent='Não consegui ativar: '+(e&&e.message||e)+(/iPhone|iPad/.test(navigator.userAgent)?' (no iPhone, adicione o app à Tela de Início antes)':'');})
 .then(function(){b.disabled=false;});}

/* controles do carro */
var CTL={};
function acOf(i){var c=CARS[i];return (c&&c.ac)||null;}
function ctlState(i){var s=CTL[i];if(!s){s=CTL[i]={temp:22,fan:3,on:false,touched:0};}
 var a=acOf(i);if(a&&Date.now()-s.touched>3000){if(a.temp!=null)s.temp=a.temp;if(a.fan!=null)s.fan=a.fan;if(a.on!=null)s.on=a.on;}
 return s;}
function ctlPanel(i){var s=ctlState(i);
 var b=function(cmd,n,t,cls){return '<button class="cb'+(cls?' '+cls:'')+'" onclick="ctl('+i+',\\''+cmd+'\\','+(n==null?'null':n)+')">'+t+'</button>';};
 return '<div class="ctl"><div class="ctlh">❄ Ar-condicionado</div>'+
  '<div class="cg">'+
    '<button class="cb'+(s.on?' cp':'')+'" id="ac-on-'+i+'" onclick="ctl('+i+',\\'ac_on\\',null)">🟢 Ligar ar</button>'+
    '<button class="cb'+(!s.on?' cp':'')+'" id="ac-off-'+i+'" onclick="ctl('+i+',\\'ac_off\\',null)">⏹ Desligar ar</button>'+
  '</div>'+
  '<div class="cr"><span>🌡 Temperatura</span><span class="st">'+b('temp',-1,'−')+'<b id="ct-'+i+'">'+s.temp+' °C</b>'+b('temp',1,'+')+'</span></div>'+
  '<div class="cr"><span>🌀 Velocidade</span><span class="st">'+b('fan',-1,'−')+'<b id="cf-'+i+'">'+s.fan+'</b>'+b('fan',1,'+')+'</span></div>'+
  '<div class="ctlh" style="margin-top:14px">🎛 Comandos do veículo</div>'+
  '<div class="cg">'+b('car_on',null,'🚗 Ligar veículo')+b('car_off',null,'⏻ Desligar veículo')+'</div>'+
  '<div class="cg">'+b('lock',null,'🔒 Trancar')+b('unlock',null,'🔓 Destrancar')+'</div>'+
  '<div class="cg">'+b('flash',null,'💡 Piscar')+b('find',null,'📣 Achar o carro')+'</div>'+
  '<div class="cg">'+b('win',1,'⬇ Abrir janelas')+b('win',2,'⬆ Fechar janelas')+'</div>'+
  '<div class="dot" id="cs-'+i+'" style="min-height:18px;margin-top:6px"></div></div>';}
function applyAc(i,ac){if(!ac)return;var c=CARS[i];if(c)c.ac=ac;var s=CTL[i];if(!s)s=ctlState(i);if(Date.now()-s.touched<3000)return;
 if(ac.temp!=null){s.temp=ac.temp;var e=$('ct-'+i);if(e)e.textContent=ac.temp+' °C';}
 if(ac.fan!=null){s.fan=ac.fan;var f=$('cf-'+i);if(f)f.textContent=ac.fan;}
 if(ac.on!=null){s.on=!!ac.on;var on=$('ac-on-'+i),off=$('ac-off-'+i);if(on)on.classList.toggle('cp',s.on);if(off)off.classList.toggle('cp',!s.on);}}
var CTLN={ac_on:'Ligando o ar',ac_off:'Desligando o ar',car_on:'Ligando o veículo',car_off:'Desligando o veículo',temp:'Ajustando a temperatura',fan:'Ajustando a velocidade',lock:'Trancando',unlock:'Destrancando',flash:'Piscando os faróis',find:'Piscando e buzinando',win:'Movendo as janelas'};
function ctl(i,cmd,n){var c=CARS[i],s=ctlState(i);if(!c)return;var st=$('cs-'+i);s.touched=Date.now();
 if(cmd==='ac_on'||cmd==='ac_off'){s.on=(cmd==='ac_on');var on=$('ac-on-'+i),off=$('ac-off-'+i);if(on)on.classList.toggle('cp',s.on);if(off)off.classList.toggle('cp',!s.on);}
 if(cmd==='temp'){s.temp=Math.max(17,Math.min(33,s.temp+n));n=s.temp;$('ct-'+i).textContent=n+' °C';}
 if(cmd==='fan'){s.fan=Math.max(1,Math.min(7,s.fan+n));n=s.fan;$('cf-'+i).textContent=n;}
 if(cmd==='win'&&n===1&&!confirm('Abrir as janelas do carro?'))return;
 clearTimeout(s.deb);s.deb=setTimeout(function(){
  st.textContent='⏳ '+CTLN[cmd]+'…';st.style.color='var(--mu)';
  var done=false,ws=new WebSocket((location.protocol==='https:'?'wss:':'ws:')+'//'+location.host+'/c/ws?car='+encodeURIComponent(c.id)+'&t='+encodeURIComponent(T));
  var end=function(t,ok){if(done)return;done=true;clearTimeout(to);st.textContent=t;st.style.color=ok?'var(--gr)':'var(--am)';try{ws.close();}catch(e){}};
  var to=setTimeout(function(){end('✕ O carro não respondeu. Tente de novo.',false);},30000);
  ws.onmessage=function(e){var m;try{m=JSON.parse(e.data);}catch(x){return;}
   if(m.t==='hello'){if(!m.online)end('✕ O carro está desligado da internet agora.',false);else ws.send(JSON.stringify({t:'ctl',cmd:cmd,n:n}));}
   else if(m.t==='ctlres')end(m.ok?'✓ Feito':'✕ '+(m.msg||'Não funcionou neste carro'),m.ok);
   else if(m.t==='ac'){applyAc(i,m);}
   else if(m.t==='offline')end('✕ O carro está desligado da internet agora.',false);};
  ws.onerror=function(){end('✕ Sem conexão com o servidor.',false);};
 },(cmd==='temp'||cmd==='fan')?700:0);}
var HOMEPOLL=null;
function startHomePoll(){stopHomePoll();HOMEPOLL=setInterval(refreshState,5000);}
function stopHomePoll(){if(HOMEPOLL){clearInterval(HOMEPOLL);HOMEPOLL=null;}}
function refreshState(){if(!T||document.hidden||!CARS.length)return;api('/c/api/me').then(function(r){(r.cars||[]).forEach(function(c){var i=-1;for(var k=0;k<CARS.length;k++){if(CARS[k].id===c.id){i=k;break;}}if(i<0)return;
 var cn=$('conn-'+i);if(cn)cn.innerHTML=c.online?'<span class="on">● Online agora</span>':'<span class="off">○ Desligado ou sem internet · último contato '+ago(c.seen)+'</span>';
 if(c.ac)applyAc(i,c.ac);});}).catch(function(){});}

/* bateria (telemetria) */
function nb(v,d){return v==null?'—':Number(v).toLocaleString('pt-BR',{minimumFractionDigits:d,maximumFractionDigits:d});}
function toggleBat(i){var el=$('bat-'+i),c=CARS[i];if(!el||!c)return;var open=el.classList.toggle('hide')===false;
 if(open&&!el._w)el._w=teleWidget(el,function(h){return api('/c/api/tele?car='+encodeURIComponent(c.id)+'&h='+h);},{noAlert:true});}

/* esqueci a senha */
var FG={u:'',rt:''};
function fgStep(n){[1,2,3].forEach(function(k){$('fg'+k).classList.toggle('hide',k!==n);});$('fe').textContent='';}
function startForgot(){FG={u:'',rt:''};$('fu').value=$('lu').value||'';$('fc').value='';$('fp1').value='';$('fp2').value='';fgStep(1);show('forgot');}
function fgRequest(){var u=($('fu').value||'').trim().toLowerCase();if(u.length<3){$('fe').textContent='Digite o seu usuário.';return;}var b=$('fb1');busy(b,true,'Enviando…');
 api('/c/api/reset-request',{user:u}).then(function(){FG.u=u;fgStep(2);}).catch(function(x){$('fe').textContent=x.message;}).then(function(){busy(b,false,'Pedir código');});}
function fgVerify(){var c=($('fc').value||'').replace(/[^0-9]/g,'');if(c.length!==5){$('fe').textContent='O código tem 5 dígitos.';return;}var b=$('fb2');busy(b,true,'Conferindo…');
 api('/c/api/reset-verify',{user:FG.u,code:c}).then(function(r){FG.rt=r.rt;fgStep(3);}).catch(function(x){$('fe').textContent=x.message;}).then(function(){busy(b,false,'Confirmar código');});}
function fgSet(){var p1=$('fp1').value,p2=$('fp2').value;if(p1.length<8){$('fe').textContent='A senha precisa ter pelo menos 8 caracteres.';return;}if(p1!==p2){$('fe').textContent='As duas senhas não são iguais.';return;}
 var b=$('fb3');busy(b,true,'Salvando…');
 api('/c/api/reset-set',{rt:FG.rt,pass:p1}).then(function(r){save(r.token,r.user);$('fp1').value='';$('fp2').value='';show('home');}).catch(function(x){$('fe').textContent=x.message;}).then(function(){busy(b,false,'Salvar senha nova');});}

/* câmeras ao vivo (WebRTC) */
var LIVE=null;
function ov(i,t){var o=$('ov-'+i);if(!o)return;o.textContent=t||'';o.style.display=t?'flex':'none';}
function fullV(v){try{if(v.webkitEnterFullscreen)v.webkitEnterFullscreen();else if(v.requestFullscreen)v.requestFullscreen();}catch(e){}}
function toggleLive(i){if(LIVE&&LIVE.i===i){stopLive('Transmissão encerrada.');return;}if(LIVE)stopLive();startLive(i);}
function startLive(i){var c=CARS[i];if(!c)return;var L={i:i,done:false,ice:[]};LIVE=L;
 $('pb-'+i).textContent='■ Parar';$('inf-'+i).textContent='';ov(i,'Conectando ao carro…');
 var ws=new WebSocket((location.protocol==='https:'?'wss:':'ws:')+'//'+location.host+'/c/ws?car='+encodeURIComponent(c.id)+'&t='+encodeURIComponent(T));L.ws=ws;
 var start=function(){clearTimeout(L.wd);L.wd=setTimeout(function(){if(L.done||L.got)return;L.tries=(L.tries||0)+1;if(L.tries<2){ov(i,'O carro está demorando para responder… tentando de novo');start();}else ov(i,'O carro não respondeu. Ele pode ter trocado de rede (Wi-Fi/dados) e está reconectando: toque em Parar e tente de novo em 1 minuto.');},12000);try{ws.send(JSON.stringify({t:'start',cam:L.want||'auto',size:($('sz-'+i)||{}).value||'auto',codec:($('cd-'+i)||{}).value||'auto'}));}catch(e){}};
 L.stats=setInterval(function(){statsTick(L);},2000);
 ws.onmessage=function(e){if(L.done)return;var m;try{m=JSON.parse(e.data);}catch(x){return;}
  if(m.t==='hello'){L.ice=m.ice||[];if(DIAG){var urls=[];L.ice.forEach(function(x){urls=urls.concat(x.urls||[]);});var turn=urls.filter(function(u){return /^turns?:/.test(u);}).length;$('inf-'+i).textContent=turn?('✓ TURN ativo ('+turn+' endereços de retransmissão)'):('✕ TURN não ativo — '+(m.iceErr||'motivo desconhecido'));}if(m.online){ov(i,'Ligando a câmera…');start();}else ov(i,'O carro está desligado ou sem internet. Pode esperar aqui: quando ele conectar, a imagem começa.');}
  else if(m.t==='online'){if(!L.pc){ov(i,'O carro conectou. Ligando a câmera…');start();}}
  else if(m.t==='offline'){dropPc(L);ov(i,'O carro desconectou (desligado ou sem internet). Esperando ele voltar…');}
  else if(m.t==='offer'){L.got=true;clearTimeout(L.wd);onOffer(L,m.sdp);}
  else if(m.t==='ac'){applyAc(i,m);}
  else if(m.t==='micretry'){AUTOMIC={i:i,tries:(AUTOMIC&&AUTOMIC.i===i?AUTOMIC.tries:0)+1};$('inf-'+i).textContent='🎤 Ajustando o microfone deste carro…';if(AUTOMIC.tries<=7){stopLive();setTimeout(function(){startLive(i);},1500);}else{AUTOMIC=null;micUi(i,false);}}
  else if(m.t==='mic'){if(!m.ok){L.mic=false;AUTOMIC=null;var vv=$('vid-'+i);if(vv)vv.muted=true;micUi(i,false);$('inf-'+i).textContent='🎤 Microfone: '+(m.msg||'não funcionou neste carro');}else{AUTOMIC=null;micUi(i,m.on);$('inf-'+i).textContent='';}}
  else if(m.t==='info'){L.cam=m.cam;$('inf-'+i).textContent=DIAG?('Câmera: '+(m.cam||'?')+(m.real&&m.real!==m.cam?(' ('+m.real+')'):'')+(m.w?(' · '+m.w+'×'+m.h):'')+(m.def?(' · padrão da câmera '+m.def):'')):('📷 '+camName(m.cam||''));
   var cs=(m.cams||[]).filter(function(k){return DIAG||k.indexOf('id:')!==0;});L.cams=cs;
   var chips=cs.map(function(k,j){return '<button class="chip'+(k===m.cam?' sel':'')+'" onclick="pickCam('+i+','+j+',true)">'+camName(k)+'</button>';}).join('');
   $('cams-'+i).innerHTML=cs.length<2&&!DIAG?'':chips;
   if(AUTOMIC&&AUTOMIC.i===i&&!L.mic)setTimeout(function(){if(LIVE&&LIVE.i===i&&!LIVE.mic)toggleMic(i);},900);
   $('fsb-'+i).innerHTML=(cs.length<2?'':chips)+'<button class="fsx" id="micf-'+i+'" onclick="toggleMic('+i+')">'+(LIVE&&LIVE.mic?'🔇 Parar de ouvir':'🎤 Ouvir o carro')+'</button><button class="fsx" onclick="closeFull()">✕ Sair</button>';
   setGrid(L,m.layout==='strip4'?m.tiles:null);
   var sz=$('sz-'+i);if(sz&&m.size)sz.value=m.size;}
  else if(m.t==='stats'){L.car=m;}
  else if(m.t==='error'){L.got=true;clearTimeout(L.wd);ov(i,m.code==='lowbat'?'🛡 Câmeras indisponíveis: bateria de tração abaixo de 5%. Carregue o carro para voltar a ver as câmeras.':'O carro avisou: '+(m.msg||'erro'));if(m.code==='lowbat')stopLive();}
 };
 ws.onclose=function(ev){if(!L.done)stopLive(ev.code===1006?'Sem conexão com o servidor. Toque em Ver ao vivo para tentar de novo.':'Conexão encerrada. Toque em Ver ao vivo para tentar de novo.');};
 L.timer=setTimeout(function(){stopLive('Sessão encerrada após 5 minutos (para economizar dados). Toque para ver de novo.');},5*60*1000);
}
function gathered(pc,ms){return new Promise(function(res){if(pc.iceGatheringState==='complete')return res();var t=setTimeout(res,ms);
 pc.addEventListener('icegatheringstatechange',function(){if(pc.iceGatheringState==='complete'){clearTimeout(t);res();}});});}
function onOffer(L,sdp){dropPc(L);var pc=new RTCPeerConnection({iceServers:L.ice});L.pc=pc;var v=$('vid-'+L.i);
 pc.ontrack=function(e){if(!v)return;L.ms=L.ms||new MediaStream();if(L.ms.getTracks().indexOf(e.track)<0)L.ms.addTrack(e.track);if(v.srcObject!==L.ms)v.srcObject=L.ms;v.muted=!L.mic;var p=v.play();if(p&&p.catch)p.catch(function(){});};
 pc.onconnectionstatechange=function(){if(L.pc!==pc)return;var s=pc.connectionState;
  if(s==='connected')ov(L.i,'');else if(s==='failed')ov(L.i,'Não consegui receber o vídeo nesta rede. Tente de novo (ou em outra rede).');else if(s==='disconnected')ov(L.i,'Sinal instável…');};
 pc.setRemoteDescription({type:'offer',sdp:sdp}).then(function(){return pc.createAnswer();}).then(function(a){return pc.setLocalDescription(a);})
  .then(function(){return gathered(pc,2500);}).then(function(){if(L.done||L.pc!==pc)return;L.ws.send(JSON.stringify({t:'answer',sdp:pc.localDescription.sdp}));ov(L.i,'Conectando o vídeo…');})
  .catch(function(e){ov(L.i,'Erro ao abrir o vídeo: '+(e&&e.message||e));});}
var CAMN={'v:360':'360° (4 câmeras)','v:front':'Frente','v:rear':'Ré','v:left':'Esquerda','v:right':'Direita',rvs:'Câmera de ré',rear:'Traseira',front:'Frontal',pano_h:'360° (alta)',pano_l:'360° (baixa)',byd_apa:'360° APA',apa:'APA',rf:'Direita',dms:'Motorista',face:'Rosto'};
function camName(k){return CAMN[k]||(k.indexOf('id:')===0?('Câmera '+k.slice(3)):k);}
function pickCam(i,j,full){var L=LIVE;if(!L||L.i!==i)return;if(full)openFull(i);if(j!=null&&L.cams)L.want=L.cams[j];var sz=($('sz-'+i)||{}).value||'auto';
 try{L.ws.send(JSON.stringify({t:'cam',cam:L.want||L.cam||'auto',size:sz}));}catch(e){}ov(i,'Trocando de câmera…');setTimeout(function(){if(LIVE===L&&L.pc&&L.pc.connectionState==='connected')ov(i,'');},2500);}
function statsTick(L){var el=$('sta-'+L.i);if(!el)return;var c=L.car;
 var car=c?('Carro: câmera '+c.fps+' q/s'+(c.ow?(' · envia '+c.ow+'×'+c.oh+' '+Math.round(c.ofps||0)+' q/s'):'')+(c.kbps!=null?(' '+c.kbps+' kbps'):'')+(c.codec?(' · '+String(c.codec).replace('video/','')):'')+(c.enc?(' ('+c.enc+')'):'')+(c.lim&&c.lim!=='none'?(' · limitado por: '+(c.lim==='bandwidth'?'internet':c.lim==='cpu'?'processador':c.lim)):'')+(c.aob?(' · banda '+Math.round(c.aob)+' kbps'):'')+(c.net?(' · rede '+c.net):'')):'Carro: aguardando…';
 if(!L.pc){el.textContent=car;return;}
 L.pc.getStats().then(function(r){var t='';r.forEach(function(s){if(s.type==='inbound-rtp'&&s.kind==='video'){var kb=L.lastB!=null?Math.round((s.bytesReceived-L.lastB)*8/2000):0;L.lastB=s.bytesReceived;
   t=' — Celular: '+(s.framesPerSecond||0)+' q/s'+(s.frameWidth?(', '+s.frameWidth+'×'+s.frameHeight):'')+', '+kb+' kbps';}});el.textContent=car+t;}).catch(function(){});}
function restartLive(i){if(LIVE&&LIVE.i===i){stopLive();startLive(i);}}
/* 360: a faixa com as 4 câmeras vira uma grade 2x2 (Frente | Ré / Esquerda | Direita) */
var GRID_ORDER=[['v:front','Frente'],['v:rear','Ré'],['v:left','Esquerda'],['v:right','Direita']];
function setGrid(L,tiles){L.tiles=tiles;var cv=$('cv-'+L.i);if(!cv)return;cv.style.display=tiles?'block':'none';if(tiles&&!L.raf)gridLoop(L);}
function gridLoop(L){L.raf=0;if(L.done||!L.tiles||LIVE!==L)return;var v=$('vid-'+L.i),cv=$('cv-'+L.i);
 if(v&&cv&&v.videoWidth){var vw=v.videoWidth,vh=v.videoHeight,tw=Math.floor(vw/4);
  if(cv.width!==tw*2||cv.height!==vh*2){cv.width=tw*2;cv.height=vh*2;}
  var g=cv.getContext('2d');g.fillStyle='#000';g.fillRect(0,0,cv.width,cv.height);
  GRID_ORDER.forEach(function(o,k){var off=L.tiles[o[0]];if(off==null)return;var dx=(k%2)*tw,dy=Math.floor(k/2)*vh;
   g.drawImage(v,Math.round(off*vw),0,tw,vh,dx,dy,tw,vh);
   var fs=Math.max(14,Math.round(vh/14));g.font='600 '+fs+'px -apple-system,Roboto,sans-serif';var tw2=g.measureText(o[1]).width;
   g.fillStyle='rgba(0,0,0,.55)';g.fillRect(dx+8,dy+8,tw2+fs,fs*1.6);g.fillStyle='#fff';g.fillText(o[1],dx+8+fs/2,dy+8+fs*1.15);});
  g.fillStyle='#000';g.fillRect(tw-1,0,2,cv.height);g.fillRect(0,vh-1,cv.width,2);}
 L.raf=requestAnimationFrame(function(){gridLoop(L);});}
/* tela cheia: o quadro do vídeo cobre a tela; no Android tenta tela cheia de verdade e deitar a tela */
var FS=null;
function openFull(i){var el=$('live-'+i);if(!el||!LIVE||LIVE.i!==i)return;if(FS===i)return;FS=i;el.classList.add('fs');document.body.style.overflow='hidden';
 try{if(el.requestFullscreen){var pr=el.requestFullscreen();if(pr&&pr.then)pr.then(function(){try{screen.orientation.lock('landscape').catch(function(){});}catch(e){}}).catch(function(){});}}catch(e){}}
function closeFull(){if(FS==null)return;var el=$('live-'+FS);FS=null;if(el)el.classList.remove('fs');document.body.style.overflow='';
 try{if(screen.orientation&&screen.orientation.unlock)screen.orientation.unlock();}catch(e){}
 try{if(document.fullscreenElement)document.exitFullscreen();}catch(e){}}
document.addEventListener('fullscreenchange',function(){if(!document.fullscreenElement&&FS!=null)closeFull();});
function dropPc(L){if(L.pc){try{L.pc.close();}catch(e){}L.pc=null;}L.ms=null;L.mic=false;micUi(L.i,false);var v=$('vid-'+L.i);if(v){v.srcObject=null;v.muted=true;}}
/* microfone do carro */
var AUTOMIC=null;
function micUi(i,on,busy){['mic-'+i,'micf-'+i].forEach(function(id){var b=$(id);if(!b)return;b.textContent=busy?'⏳ Ligando o microfone…':(on?'🔇 Parar de ouvir':'🎤 Ouvir o carro');b.classList.toggle('on',!!on);});}
function toggleMic(i){var L=LIVE;if(!L||L.i!==i||!L.ws||L.ws.readyState!==1){alert('Abra a câmera ao vivo primeiro.');return;}
 var on=!L.mic;L.mic=on;var v=$('vid-'+i);if(v){v.muted=!on;var p=v.play();if(p&&p.catch)p.catch(function(){});}
 micUi(i,on,on);L.ws.send(JSON.stringify({t:'mic',on:on}));}
function stopLive(msg){var L=LIVE;if(!L)return;closeFull();if(L.raf)cancelAnimationFrame(L.raf);var cv=$('cv-'+L.i);if(cv)cv.style.display='none';L.done=true;LIVE=null;clearTimeout(L.timer);clearTimeout(L.wd);clearInterval(L.stats);var se=$('sta-'+L.i);if(se)se.textContent='';
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
</script><script>${TELE_UI_JS}</script></body></html>`;
