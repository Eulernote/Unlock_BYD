/*  Unlock BYD — Painel de Ativação + API + Arquivos  (Cloudflare Worker)
 *  Bindings (ver wrangler.toml):
 *    - KV namespace  -> variável "KV"
 *    - R2 bucket     -> variável "FILES"   (catálogo, ícones e APKs)
 *    - Secret        -> variável "SECRET"  (qualquer texto longo e aleatório)
 *  Usuários permitidos: euler, luis, richard (senha definida no 1º login).
 *  Aba "Arquivos" (subir/apagar no R2): só FILE_ADMINS.
 */

const USERS = ['euler', 'luis', 'richard'];
const FILE_ADMINS = ['euler', 'luis'];
const PLANS = ['basico', 'intermediario', 'premium'];
const GROUPS = ['byd_simples', 'byd_google', 'chery', 'geely'];
const TOKEN_TTL_MS = 1000 * 60 * 60 * 12; // 12h
const SEEN_MIN_MS = 1000 * 60 * 4;        // só regrava o "visto" se passou 4 min (poupa gravações no KV)
function planLabel(p){ return p==='premium' ? 'Premium' : p==='intermediario' ? 'Intermediário' : 'Básico'; }
function grupoLabel(g){ return g==='byd_google' ? 'BYD · Google' : g==='chery' ? 'Chery' : g==='geely' ? 'Geely' : 'BYD · Simples'; }

/* ---------- cripto ---------- */
function toHex(buf){ return [...new Uint8Array(buf)].map(b=>b.toString(16).padStart(2,'0')).join(''); }
async function sha256Hex(str){
  const d = new TextEncoder().encode(str);
  return toHex(await crypto.subtle.digest('SHA-256', d));
}
async function hmacHex(secret, msg){
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(secret), {name:'HMAC',hash:'SHA-256'}, false, ['sign']);
  const sig = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(msg));
  return toHex(sig);
}
async function hashPw(secret, user, pass){ return sha256Hex(secret + ':' + user + ':' + pass); }

async function makeToken(secret, user){
  const body = user + '.' + (Date.now() + TOKEN_TTL_MS);
  const sig = await hmacHex(secret, body);
  return btoa(body) + '.' + sig;
}
async function verifyToken(secret, token){
  try{
    const parts = (token||'').split('.');
    if(parts.length !== 2) return null;
    const body = atob(parts[0]);
    const sig = await hmacHex(secret, body);
    if(sig !== parts[1]) return null;
    const i = body.lastIndexOf('.');
    const user = body.slice(0, i);
    const exp = Number(body.slice(i+1));
    if(!exp || Date.now() > exp) return null;
    if(USERS.indexOf(user) < 0) return null;
    return user;
  }catch(e){ return null; }
}
function cap(u){ return u.charAt(0).toUpperCase() + u.slice(1); }

/* ---------- util ---------- */
function json(obj, status){ return new Response(JSON.stringify(obj), {status: status||200, headers:{'content-type':'application/json; charset=utf-8'}}); }
function fmt(ms){
  try{ return new Date(ms).toLocaleString('pt-BR', {timeZone:'America/Sao_Paulo', day:'2-digit', month:'2-digit', year:'numeric', hour:'2-digit', minute:'2-digit'}); }
  catch(e){ return new Date(ms).toISOString(); }
}
async function getCar(env, id){ return await env.KV.get('car:'+id, {type:'json'}); }
async function putCar(env, c){ await env.KV.put('car:'+c.id, JSON.stringify(c)); }
// "visto por último" fica numa chave separada, pra o ping do carro nunca reescrever o cadastro.
async function putSeen(env, id, ts){ await env.KV.put('seen:'+id, String(ts)); }
async function getSeen(env, id){ const v = await env.KV.get('seen:'+id); return v ? parseInt(v) : 0; }
async function touchSeen(env, id, now){ const last = await getSeen(env, id); if(now - last >= SEEN_MIN_MS) await putSeen(env, id, now); }
// Versão do app e do Android de cada carro (só grava quando muda).
async function getVer(env, id){ return await env.KV.get('ver:'+id, {type:'json'}); }
async function touchVer(env, id, vc, sdk){
  if(!vc) return;
  const cur = await getVer(env, id);
  if(!cur || cur.vc !== vc || cur.sdk !== sdk) await env.KV.put('ver:'+id, JSON.stringify({vc:vc, sdk:sdk, at:Date.now()}));
}
// Última versão publicada do app (lida do app_version.json no R2).
async function latestVc(env){
  try{
    if(!env.FILES) return 0;
    const o = await env.FILES.get('app_version.json');
    if(!o) return 0;
    const j = JSON.parse(await o.text());
    return parseInt(j.versionCode) || 0;
  }catch(e){ return 0; }
}

async function authUser(env, request){
  const h = request.headers.get('Authorization') || '';
  const t = h.indexOf('Bearer ') === 0 ? h.slice(7) : '';
  return await verifyToken(env.SECRET, t);
}

/* ---------- arquivos (R2) ---------- */
const MIME = {apk:'application/vnd.android.package-archive', xapk:'application/octet-stream', apks:'application/octet-stream',
  json:'application/json; charset=utf-8', png:'image/png', jpg:'image/jpeg', jpeg:'image/jpeg', webp:'image/webp', zip:'application/zip'};
function mimeFor(key){ const ext = key.split('.').pop().toLowerCase(); return MIME[ext] || 'application/octet-stream'; }
function cleanKey(k){
  k = String(k||'').replace(/^\/+/, '');
  if(!k || k.length > 900 || k.indexOf('..') >= 0 || k.indexOf('\\') >= 0) return null;
  return k;
}
function objHeaders(obj, key){
  const h = new Headers();
  h.set('Content-Type', (obj.httpMetadata && obj.httpMetadata.contentType) || mimeFor(key));
  h.set('Accept-Ranges', 'bytes');
  if(obj.httpEtag) h.set('ETag', obj.httpEtag);
  const live = key === 'catalog.json' || key === 'app_version.json';
  h.set('Cache-Control', live ? 'no-cache' : 'public, max-age=300');
  return h;
}
async function serveR2(env, key, request){
  if(!env.FILES) return json({error:'R2 não configurado'}, 503);
  key = cleanKey(key);
  if(!key) return json({error:'not found'}, 404);
  if(request.method === 'HEAD'){
    const hd = await env.FILES.head(key);
    if(!hd) return new Response(null, {status:404});
    const h = objHeaders(hd, key); h.set('Content-Length', String(hd.size));
    return new Response(null, {headers:h});
  }
  const range = request.headers.get('Range');
  const obj = await env.FILES.get(key, range ? {range: request.headers} : {});
  if(!obj) return new Response('not found', {status:404});
  const h = objHeaders(obj, key);
  if(range && obj.range){
    const r = obj.range; let off, len;
    if('suffix' in r){ len = Math.min(r.suffix, obj.size); off = obj.size - len; }
    else { off = r.offset || 0; len = (r.length != null) ? r.length : (obj.size - off); }
    h.set('Content-Range', 'bytes ' + off + '-' + (off + len - 1) + '/' + obj.size);
    return new Response(obj.body, {status:206, headers:h});
  }
  return new Response(obj.body, {headers:h});
}

/* ---------- handler ---------- */
export default {
  async fetch(request, env){
    const url = new URL(request.url);
    const p = url.pathname;

    // arquivos públicos (o carro baixa daqui; não exige login)
    if(request.method === 'GET' || request.method === 'HEAD'){
      if(p === '/catalog.json' || p === '/app_version.json') return serveR2(env, p.slice(1), request);
      if(p.indexOf('/f/') === 0){
        let k; try{ k = decodeURIComponent(p.slice(3)); }catch(e){ return json({error:'not found'}, 404); }
        return serveR2(env, k, request);
      }
    }

    if(!env.SECRET) return json({error:'SECRET não configurado no Worker'}, 500);

    // painel
    if(p === '/' || p === '/index.html') return new Response(HTML, {headers:{'content-type':'text/html; charset=utf-8'}});

    // --- login: primeiro acesso define a senha ---
    if(p === '/api/login' && request.method === 'POST'){
      const b = await request.json().catch(()=>({}));
      const user = String(b.user||'').trim().toLowerCase();
      const pass = String(b.pass||'');
      if(USERS.indexOf(user) < 0 || !pass) return json({error:'invalid'}, 401);
      const rec = await env.KV.get('user:'+user, {type:'json'});
      const h = await hashPw(env.SECRET, user, pass);
      if(!rec){ await env.KV.put('user:'+user, JSON.stringify({hash:h})); }      // cadastra
      else if(rec.hash !== h){ return json({error:'invalid'}, 401); }
      const token = await makeToken(env.SECRET, user);
      return json({token: token, user: cap(user)});
    }

    // --- registro / heartbeat do carro (sem login) ---
    if(p === '/api/register' && request.method === 'POST'){
      const b = await request.json().catch(()=>({}));
      const id = String(b.id||'').trim();
      if(!id) return json({error:'id'}, 400);
      const vc = parseInt(b.vc) || 0;     // versionCode do app (v13+). Versões antigas não mandam.
      const sdk = parseInt(b.sdk) || 0;   // API do Android (29 = Android 10, 30 = 11, 34 = 14...)
      let c = await getCar(env, id);
      const now = Date.now();
      if(!c){
        // Só cria cadastro quando é o registro inicial (vindo do onboarding).
        if(b.register === true){
          const grupo = GROUPS.indexOf(String(b.grupo)) >= 0 ? String(b.grupo) : 'byd_simples';
          c = {id:id, nome: (b.nome ? String(b.nome) : ('Veículo '+id)), status:'pending',
               owner:null, plan:'basico', grupo:grupo, ask: b.ask===true, wipe:false, reg:now, seen:now, log:[], allowRemove:false, allowRemoveBy:null};
          await putCar(env, c);
          await putSeen(env, id, now);
          await touchVer(env, id, vc, sdk);
          return json({status:'pending', plan:'basico', grupo:grupo, wipe:false});
        }
        // Cadastro ausente durante o polling = veículo foi excluído do painel.
        return json({status:'deleted'});
      }
      // Carro já cadastrado: grava SÓ o "visto por último" e a versão, nunca reescreve car:<id>.
      await touchSeen(env, id, now);
      await touchVer(env, id, vc, sdk);
      return json({status:c.status, wipe: c.status==='revoked' ? !!c.wipe : false, plan: c.plan||'basico', grupo: c.grupo||'byd_simples'});
    }

    // --- pedido de remoção de Device Owner pelo carro (sem login) ---
    if(p === '/api/removal' && request.method === 'POST'){
      const b = await request.json().catch(()=>({}));
      const c = await getCar(env, String(b.id||''));
      if(!c) return json({allowed:false});
      if(c.allowRemove){
        c.allowRemove = false;
        const by = c.allowRemoveBy || 'sistema';
        c.allowRemoveBy = null;
        c.log.push({acao:'Device Owner removido', by:by, when:fmt(Date.now()), cls:'a-rev'});
        await putCar(env, c);
        return json({allowed:true});
      }
      return json({allowed:false});
    }

    // --- endpoints do painel (exigem login) ---
    const user = await authUser(env, request);

    if(p === '/api/cars' && request.method === 'GET'){
      if(!user) return json({error:'unauth'}, 401);
      const list = await env.KV.list({prefix:'car:'});
      const cars = [];
      for(const k of list.keys){
        const c = await env.KV.get(k.name, {type:'json'});
        if(c){
          const sv = await getSeen(env, c.id);
          const ver = await getVer(env, c.id);
          cars.push({
            id:c.id, nome:c.nome, status:c.status, owner:c.owner, plan: c.plan||'basico', grupo: c.grupo||'byd_simples', ask: !!c.ask, wipe:!!c.wipe,
            reg: fmt(c.reg), seen: fmt(sv || c.seen || c.reg), log: c.log||[], allowRemove: !!c.allowRemove,
            vc: ver ? ver.vc : 0, sdk: ver ? ver.sdk : 0
          });
        }
      }
      return json({cars: cars, me: cap(user), latest: await latestVc(env), files: FILE_ADMINS.indexOf(user) >= 0});
    }

    if(p === '/api/approve' && request.method === 'POST'){
      if(!user) return json({error:'unauth'}, 401);
      const b = await request.json().catch(()=>({}));
      const c = await getCar(env, String(b.id||''));
      if(!c) return json({error:'not found'}, 404);
      const who = cap(user);
      const plan = PLANS.indexOf(String(b.plan)) >= 0 ? String(b.plan) : (c.plan || 'basico');
      const grupo = GROUPS.indexOf(String(b.grupo)) >= 0 ? String(b.grupo) : (c.grupo || 'byd_simples');
      const planChanged = c.status === 'active' && (c.plan||'basico') !== plan;
      const grupoChanged = c.status === 'active' && (c.grupo||'byd_simples') !== grupo;
      if(c.status !== 'active'){
        if(!c.owner){ c.owner = who; c.log.push({acao:'Ativado · '+planLabel(plan)+' · '+grupoLabel(grupo), by:who, when:fmt(Date.now()), cls:'a-act'}); }
        else { c.log.push({acao:'Reativado · '+planLabel(plan)+' · '+grupoLabel(grupo), by:who, when:fmt(Date.now()), cls:'a-react'}); }
      } else if(planChanged || grupoChanged){
        c.log.push({acao:'Alterado p/ '+planLabel(plan)+' · '+grupoLabel(grupo), by:who, when:fmt(Date.now()), cls:'a-react'});
      }
      c.status = 'active'; c.wipe = false; c.plan = plan; c.grupo = grupo;
      await putCar(env, c);
      return json({ok:true});
    }

    if(p === '/api/delete' && request.method === 'POST'){
      if(!user) return json({error:'unauth'}, 401);
      const b = await request.json().catch(()=>({}));
      const id = String(b.id||'');
      const c = await getCar(env, id);
      if(!c) return json({error:'not found'}, 404);
      await env.KV.delete('car:'+id);
      return json({ok:true});
    }

    if(p === '/api/revoke' && request.method === 'POST'){
      if(!user) return json({error:'unauth'}, 401);
      const b = await request.json().catch(()=>({}));
      const c = await getCar(env, String(b.id||''));
      if(!c) return json({error:'not found'}, 404);
      const wipe = !!b.wipe; const who = cap(user);
      c.status = 'revoked'; c.wipe = wipe;
      c.log.push({acao: wipe?'Revogado (apps apagados)':'Revogado', by:who, when:fmt(Date.now()), cls:'a-rev'});
      await putCar(env, c);
      return json({ok:true});
    }

    if(p === '/api/allow-removal' && request.method === 'POST'){
      if(!user) return json({error:'unauth'}, 401);
      const b = await request.json().catch(()=>({}));
      const c = await getCar(env, String(b.id||''));
      if(!c) return json({error:'not found'}, 404);
      c.allowRemove = true; c.allowRemoveBy = cap(user);
      c.log.push({acao:'Remoção de DO autorizada', by:cap(user), when:fmt(Date.now()), cls:'a-react'});
      await putCar(env, c);
      return json({ok:true});
    }

    // --- arquivos no R2 (só FILE_ADMINS) ---
    if(p.indexOf('/api/files') === 0){
      if(!user) return json({error:'unauth'}, 401);
      if(FILE_ADMINS.indexOf(user) < 0) return json({error:'forbidden'}, 403);
      if(!env.FILES) return json({error:'R2 não configurado'}, 503);

      if(p === '/api/files' && request.method === 'GET'){
        const out = []; let cursor; let n = 0;
        do{
          const r = await env.FILES.list({cursor: cursor, limit: 1000});
          for(const o of r.objects) out.push({key:o.key, size:o.size, uploaded: fmt(o.uploaded.getTime())});
          cursor = r.truncated ? r.cursor : undefined; n++;
        }while(cursor && n < 10);
        out.sort(function(a,b){ return a.key < b.key ? -1 : 1; });
        return json({files: out});
      }
      if(p === '/api/files/delete' && request.method === 'POST'){
        const b = await request.json().catch(()=>({}));
        const key = cleanKey(b.key);
        if(!key) return json({error:'key'}, 400);
        await env.FILES.delete(key);
        return json({ok:true});
      }
      // Upload em partes (multipart) — permite arquivos de vários GB pelo navegador.
      if(p === '/api/files/mpu/start' && request.method === 'POST'){
        const b = await request.json().catch(()=>({}));
        const key = cleanKey(b.key);
        if(!key) return json({error:'key'}, 400);
        const mpu = await env.FILES.createMultipartUpload(key, {httpMetadata:{contentType: mimeFor(key)}});
        return json({uploadId: mpu.uploadId, key: key});
      }
      if(p === '/api/files/mpu/part' && request.method === 'PUT'){
        const key = cleanKey(url.searchParams.get('key'));
        const uploadId = url.searchParams.get('uploadId') || '';
        const n = parseInt(url.searchParams.get('n')) || 0;
        if(!key || !uploadId || n < 1 || !request.body) return json({error:'params'}, 400);
        const mpu = env.FILES.resumeMultipartUpload(key, uploadId);
        const part = await mpu.uploadPart(n, request.body);
        return json({partNumber: part.partNumber, etag: part.etag});
      }
      if(p === '/api/files/mpu/complete' && request.method === 'POST'){
        const b = await request.json().catch(()=>({}));
        const key = cleanKey(b.key);
        if(!key || !b.uploadId || !Array.isArray(b.parts)) return json({error:'params'}, 400);
        const mpu = env.FILES.resumeMultipartUpload(key, String(b.uploadId));
        const obj = await mpu.complete(b.parts);
        return json({ok:true, key: obj.key, size: obj.size});
      }
      if(p === '/api/files/mpu/abort' && request.method === 'POST'){
        const b = await request.json().catch(()=>({}));
        const key = cleanKey(b.key);
        if(key && b.uploadId){ try{ await env.FILES.resumeMultipartUpload(key, String(b.uploadId)).abort(); }catch(e){} }
        return json({ok:true});
      }
    }

    return json({error:'not found'}, 404);
  }
};

/* ======================================================================= */
/*  PAINEL (HTML) — mesma tela aprovada, agora ligada à API                */
/* ======================================================================= */
const HTML = `<!DOCTYPE html>
<html lang="pt-BR"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Unlock BYD — Painel de Ativação</title>
<style>
:root{--bg:#0a0c10;--bg2:#0e1117;--surface:#141922;--surface2:#1a2029;--border:#232b38;--border2:#2d3747;--text:#e8ecf3;--muted:#8793a6;--faint:#5b6577;--accent:#2bb0a6;--amber:#f5a623;--amber-bg:#3a2d0f;--green:#27c08a;--green-bg:#0f2e23;--red:#ef5350;--red-bg:#331717;--shadow:0 10px 30px rgba(0,0,0,.45)}
*{box-sizing:border-box}html,body{margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;background:radial-gradient(1200px 600px at 80% -10%,#121a24 0%,var(--bg) 55%) fixed,var(--bg);color:var(--text);min-height:100vh;-webkit-font-smoothing:antialiased}
.wrap{max-width:1040px;margin:0 auto;padding:20px 16px 60px}
.topbar{display:flex;align-items:center;gap:12px;padding:6px 2px 18px}
.logo{display:flex;align-items:center;gap:10px}
.logo .mark{width:38px;height:38px;border-radius:10px;background:linear-gradient(145deg,var(--accent),#146b64);display:flex;align-items:center;justify-content:center;box-shadow:var(--shadow)}
.logo .mark svg{width:20px;height:20px}
.logo h1{font-size:15px;line-height:1;margin:0;font-weight:700;letter-spacing:.3px}
.logo span{display:block;font-size:11px;color:var(--muted);margin-top:3px;font-weight:500}
.spacer{flex:1}
.userbox{display:flex;align-items:center;gap:9px}
.userbox .av{width:28px;height:28px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-size:12px;font-weight:700;color:#04201d}
.userbox .nm{font-size:13px;font-weight:650}
.btn-ghost{background:transparent;border:1px solid var(--border2);color:var(--muted);padding:8px 12px;border-radius:9px;font-size:12.5px;cursor:pointer;font-weight:600}
.btn-ghost:hover{color:var(--text);border-color:#3a4658}
.stats{display:grid;grid-template-columns:repeat(5,1fr);gap:12px;margin-bottom:22px}
.stat{background:var(--surface);border:1px solid var(--border);border-radius:14px;padding:14px 16px}
.stat .n{font-size:26px;font-weight:750;letter-spacing:-.5px}
.stat .l{font-size:12px;color:var(--muted);margin-top:2px;font-weight:600}
.stat.pend .n{color:var(--amber)}.stat.act .n{color:var(--green)}.stat.rev .n{color:var(--red)}.stat.old .n{color:var(--amber)}.stat.old.ok .n{color:var(--green)}
.toolbar{display:flex;gap:10px;align-items:center;margin-bottom:14px;flex-wrap:wrap}
.search{flex:1;min-width:170px;position:relative}
.search input{width:100%;background:var(--surface);border:1px solid var(--border);color:var(--text);padding:11px 12px 11px 36px;border-radius:10px;font-size:14px}
.search input::placeholder{color:var(--faint)}
.search svg{position:absolute;left:11px;top:11px;width:16px;height:16px;opacity:.5}
.filters{display:flex;gap:6px;flex-wrap:wrap}
.chip{background:var(--surface);border:1px solid var(--border);color:var(--muted);padding:9px 13px;border-radius:999px;font-size:12.5px;cursor:pointer;font-weight:600;white-space:nowrap}
.chip.on{background:var(--accent);border-color:var(--accent);color:#04201d}
.sec-title{font-size:12.5px;text-transform:uppercase;letter-spacing:.8px;color:var(--faint);margin:22px 2px 10px;font-weight:700}
.list{display:flex;flex-direction:column;gap:10px}
.car{background:var(--surface);border:1px solid var(--border);border-radius:14px;padding:14px 16px;display:flex;align-items:center;gap:14px;cursor:pointer;transition:border-color .15s}
.car:hover{border-color:var(--border2)}.car.pending{border-color:#4a3c12}.car.pending:hover{border-color:#6a5618}
.car .ico{width:42px;height:42px;border-radius:11px;background:var(--surface2);display:flex;align-items:center;justify-content:center;flex-shrink:0;border:1px solid var(--border)}
.car .ico svg{width:21px;height:21px;opacity:.85}
.car .info{flex:1;min-width:0}
.car .name{font-weight:650;font-size:14.5px;display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.car .meta{font-size:12px;color:var(--muted);margin-top:5px;display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.car .meta code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;color:var(--text);background:var(--bg2);padding:1px 6px;border-radius:5px;font-size:11.5px;border:1px solid var(--border)}
.sep{color:var(--faint)}.wiped{color:var(--red);font-weight:600}
.badge{font-size:11px;font-weight:700;padding:3px 9px;border-radius:999px;letter-spacing:.2px}
.badge.pending{color:var(--amber);background:var(--amber-bg)}.badge.active{color:var(--green);background:var(--green-bg)}.badge.revoked{color:var(--red);background:var(--red-bg)}
.resp{font-size:11px;font-weight:650;padding:2px 9px 2px 7px;border-radius:999px;display:inline-flex;align-items:center;gap:5px}
.resp .dot{width:6px;height:6px;border-radius:50%}
.actions{display:flex;gap:8px;flex-shrink:0}
.btn{border:none;padding:9px 15px;border-radius:9px;font-size:13px;font-weight:700;cursor:pointer;white-space:nowrap}
.btn-approve{background:var(--green);color:#042016}.btn-approve:hover{background:#2fd79a}
.btn-revoke{background:transparent;border:1px solid var(--red);color:var(--red)}.btn-revoke:hover{background:var(--red-bg)}
.btn-reapprove{background:var(--accent);color:#04201d}
.planbtn{flex:1;background:var(--bg2);border:1px solid var(--border);color:var(--muted);padding:10px;border-radius:10px;font-size:12.5px;font-weight:700;cursor:pointer}
.planbtn.on{background:var(--accent);border-color:var(--accent);color:#04201d}
.pbadge{font-size:10.5px;font-weight:700;padding:3px 8px;border-radius:999px;letter-spacing:.2px}
.empty{color:var(--faint);font-size:13px;padding:18px;text-align:center;border:1px dashed var(--border2);border-radius:12px}
#toast{position:fixed;left:50%;bottom:26px;transform:translateX(-50%) translateY(20px);background:var(--surface2);border:1px solid var(--border2);color:var(--text);padding:12px 18px;border-radius:11px;font-size:13.5px;font-weight:600;opacity:0;pointer-events:none;transition:.25s;box-shadow:var(--shadow);z-index:200;max-width:88vw;text-align:center}
#toast.show{opacity:1;transform:translateX(-50%) translateY(0)}
.modal-ov{position:fixed;inset:0;background:rgba(0,0,0,.62);display:flex;align-items:flex-start;justify-content:center;z-index:120;padding:20px;overflow-y:auto;-webkit-overflow-scrolling:touch}
.modal{width:100%;max-width:420px;background:var(--surface);border:1px solid var(--border2);border-radius:17px;padding:22px;box-shadow:var(--shadow)}
.modal.wide{max-width:720px}
.modal .mtop{display:flex;gap:11px;align-items:flex-start;margin-bottom:14px}
.modal .warn{width:38px;height:38px;border-radius:10px;background:var(--red-bg);display:flex;align-items:center;justify-content:center;flex-shrink:0}
.modal .warn svg{width:20px;height:20px;stroke:var(--red)}
.modal h3{margin:0 0 3px;font-size:16.5px}.modal .sub{margin:0;color:var(--muted);font-size:12.5px}
.modal-x{background:transparent;border:none;color:var(--muted);font-size:15px;cursor:pointer;line-height:1;padding:4px}
.resp-warn{display:none;background:var(--amber-bg);border:1px solid #5a470f;color:#f2c25b;font-size:12.5px;line-height:1.45;padding:10px 12px;border-radius:10px;margin-bottom:14px}
.opt{display:block;width:100%;text-align:left;background:var(--bg2);border:1px solid var(--border);border-radius:12px;padding:13px 15px;margin-bottom:10px;cursor:pointer;color:var(--text)}
.opt:hover{border-color:var(--accent)}.opt.danger:hover{border-color:var(--red)}
.opt-t{display:block;font-weight:700;font-size:14px}.opt.danger .opt-t{color:var(--red)}
.opt-d{display:block;font-size:12px;color:var(--muted);margin-top:4px;line-height:1.45}
.modal-cancel{width:100%;background:transparent;border:none;color:var(--muted);padding:11px;font-size:13px;font-weight:600;cursor:pointer;margin-top:2px}
.modal-cancel:hover{color:var(--text)}
.logrow{display:flex;gap:9px;align-items:baseline;padding:10px 0;border-bottom:1px solid var(--border)}
.logrow:last-child{border-bottom:none}
.logact{font-weight:700;font-size:12px;padding:2px 8px;border-radius:6px;white-space:nowrap}
.logact.a-act{color:var(--green);background:var(--green-bg)}.logact.a-rev{color:var(--red);background:var(--red-bg)}.logact.a-react{color:var(--accent);background:#0f2b29}
.logby{font-size:12.5px;color:var(--muted);flex:1}.logwhen{font-size:11.5px;color:var(--faint);white-space:nowrap}
.frow{display:flex;gap:10px;align-items:center;padding:10px 0;border-bottom:1px solid var(--border)}
.frow:last-child{border-bottom:none}
.frow .fk{flex:1;min-width:0;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12.5px;word-break:break-all}
.frow .fs{font-size:11.5px;color:var(--faint);white-space:nowrap}
.fbtn{background:var(--bg2);border:1px solid var(--border);color:var(--muted);padding:6px 10px;border-radius:8px;font-size:12px;font-weight:600;cursor:pointer;white-space:nowrap}
.fbtn:hover{color:var(--text)}.fbtn.del:hover{color:var(--red);border-color:var(--red)}
.ftabs{display:flex;gap:6px;flex-wrap:wrap;margin-bottom:12px}
.ftabs .chip .ct{opacity:.7;font-weight:700;margin-left:4px}
.fgrp{font-size:11.5px;text-transform:uppercase;letter-spacing:.7px;color:var(--accent);font-weight:700;margin:14px 0 2px}
.fnote{background:var(--bg2);border:1px solid var(--border);border-radius:12px;padding:12px 14px;margin-bottom:14px;font-size:12.5px;color:var(--muted);line-height:1.5}
.upbox{background:var(--bg2);border:1px dashed var(--border2);border-radius:12px;padding:14px;margin-bottom:14px}
.upbox select,.upbox input[type=text]{background:var(--surface);border:1px solid var(--border);color:var(--text);padding:9px 10px;border-radius:9px;font-size:13px}
.bar{height:8px;background:var(--surface2);border-radius:99px;overflow:hidden;margin-top:10px;display:none}
.bar div{height:100%;width:0;background:var(--accent);transition:width .2s}
#login{position:fixed;inset:0;background:var(--bg);display:flex;align-items:center;justify-content:center;z-index:100;padding:20px}
.card-login{width:100%;max-width:360px;background:var(--surface);border:1px solid var(--border);border-radius:18px;padding:30px 26px;box-shadow:var(--shadow)}
.card-login .mark{width:52px;height:52px;border-radius:14px;margin:0 auto 16px;background:linear-gradient(145deg,var(--accent),#146b64);display:flex;align-items:center;justify-content:center}
.card-login .mark svg{width:27px;height:27px}
.card-login h2{text-align:center;margin:0 0 4px;font-size:18px}
.card-login p{text-align:center;color:var(--muted);font-size:12.5px;margin:0 0 22px}
.field{margin-bottom:12px}.field label{display:block;font-size:12px;color:var(--muted);margin-bottom:6px;font-weight:600}
.field input{width:100%;background:var(--bg2);border:1px solid var(--border);color:var(--text);padding:11px 12px;border-radius:10px;font-size:14px}
#login-err{font-size:12px;min-height:15px;margin:8px 2px 2px;color:var(--red);font-weight:600}
.btn-login{width:100%;background:var(--accent);color:#04201d;border:none;padding:12px;border-radius:10px;font-size:14.5px;font-weight:700;cursor:pointer;margin-top:6px}
.btn-login:hover{background:#2fd79a}
@media(max-width:620px){.stats{grid-template-columns:repeat(2,1fr)}.car{flex-wrap:wrap}.car .info{flex-basis:calc(100% - 56px)}.actions{flex-basis:100%;margin-left:0;margin-top:8px;width:100%}.actions .btn{flex:1}.topbar .logo h1{font-size:14px}.frow{flex-wrap:wrap}.frow .fk{flex-basis:100%}}
</style></head><body>
<div id="login"><form class="card-login" onsubmit="doLogin(event)">
<div class="mark"><svg viewBox="0 0 24 24" fill="none" stroke="#04201d" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg></div>
<h2>Unlock BYD</h2><p>Painel de ativação de veículos</p>
<div class="field"><label>Usuário</label><input id="lu" type="text" placeholder="Digite aqui o seu usuário" autocomplete="off"></div>
<div class="field"><label>Senha</label><input id="lp" type="password" placeholder="Digite a sua senha" autocomplete="current-password"></div>
<div id="login-err"></div><button class="btn-login" type="submit">Entrar</button>
</form></div>
<div class="wrap">
<div class="topbar"><div class="logo"><div class="mark"><svg viewBox="0 0 24 24" fill="none" stroke="#04201d" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg></div>
<div><h1>Painel de Ativação</h1><span>Unlock BYD</span></div></div><div class="spacer"></div>
<div class="userbox"><button class="btn-ghost" id="files-btn" style="display:none" onclick="openFiles()">Arquivos</button><div class="av" id="whoav"></div><div class="nm" id="whoami"></div><button class="btn-ghost" onclick="logout()">Sair</button></div></div>
<div class="stats"><div class="stat"><div class="n" id="s-total">0</div><div class="l">Veículos</div></div>
<div class="stat pend"><div class="n" id="s-pend">0</div><div class="l">Pendentes</div></div>
<div class="stat act"><div class="n" id="s-act">0</div><div class="l">Ativos</div></div>
<div class="stat rev"><div class="n" id="s-rev">0</div><div class="l">Revogados</div></div>
<div class="stat old" id="s-old-box"><div class="n" id="s-old">0</div><div class="l" id="s-old-l">Desatualizados</div></div></div>
<div class="toolbar"><div class="search"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="7"/><path d="m21 21-4.3-4.3"/></svg>
<input id="q" placeholder="Buscar por apelido, ID ou responsável..." oninput="render()"></div>
<div class="filters"><div class="chip on" data-f="all" onclick="setF(this)">Todos</div><div class="chip" data-f="mine" onclick="setF(this)">Meus</div>
<div class="chip" data-f="pending" onclick="setF(this)">Pendentes</div><div class="chip" data-f="active" onclick="setF(this)">Ativos</div>
<div class="chip" data-f="revoked" onclick="setF(this)">Revogados</div><div class="chip" data-f="old" onclick="setF(this)">Desatualizados</div></div></div>
<div id="pending-sec"></div><div class="sec-title">Todos os veículos</div><div id="list" class="list"></div></div>
<div id="modal" class="modal-ov" style="display:none"><div class="modal"><div class="mtop">
<div class="warn"><svg viewBox="0 0 24 24" fill="none" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/><path d="M12 9v4M12 17h.01"/></svg></div>
<div><h3>Revogar acesso</h3><p class="sub" id="modal-sub">Veículo: —</p></div></div>
<div class="resp-warn" id="modal-warn-resp"></div>
<button class="opt" onclick="doRevoke(false)"><span class="opt-t">Apenas travar a loja</span><span class="opt-d">Bloqueia o catálogo neste carro. Os apps já instalados continuam funcionando.</span></button>
<button class="opt danger" onclick="doRevoke(true)"><span class="opt-t">Travar e apagar os apps</span><span class="opt-d">Bloqueia o catálogo e remove do carro os apps da sua loja já instalados.</span></button>
<button class="modal-cancel" onclick="closeModal()">Cancelar</button></div></div>
<div id="detail" class="modal-ov" style="display:none"><div class="modal"><div class="mtop" style="margin-bottom:10px">
<div style="flex:1"><h3 id="d-name">—</h3><p class="sub" id="d-id">ID —</p></div><button class="modal-x" onclick="closeDetail()">✕ Fechar</button></div>
<div id="d-head" style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-bottom:6px"></div>
<div id="d-actions" style="margin-top:14px"></div>
<div class="sec-title" style="margin:16px 0 2px">Histórico de alterações</div><div id="d-log"></div></div></div>
<div id="files" class="modal-ov" style="display:none"><div class="modal wide"><div class="mtop" style="margin-bottom:12px">
<div style="flex:1"><h3>Arquivos da loja (R2)</h3><p class="sub">APKs, discos, OBB, ícones e catálogo que os carros baixam.</p></div><button class="modal-x" onclick="closeFiles()">✕ Fechar</button></div>
<div class="ftabs" id="f-tabs"></div>
<div class="fnote" id="f-note" style="display:none"></div>
<div class="upbox" id="up-box"><div style="font-size:12.5px;font-weight:700;margin-bottom:8px" id="up-title">Enviar para Apps</div><div style="display:flex;gap:8px;flex-wrap:wrap;align-items:center">
<input type="text" id="up-sub" placeholder="pasta do jogo (ex.: re4)" style="flex:1;min-width:140px;display:none">
<input type="file" id="up-file" multiple style="font-size:12.5px;color:var(--muted)">
<button class="btn btn-reapprove" id="up-go" onclick="startUpload()">Enviar</button></div>
<div id="up-msg" style="font-size:12.5px;color:var(--muted);margin-top:8px">Arquivos com o mesmo nome são substituídos. Pode fechar outras abas, mas não esta, até terminar.</div>
<div class="bar" id="up-bar"><div></div></div></div>
<input type="text" id="f-q" placeholder="Filtrar arquivos..." oninput="renderFiles()" style="width:100%;background:var(--bg2);border:1px solid var(--border);color:var(--text);padding:9px 11px;border-radius:9px;font-size:13px;margin-bottom:6px">
<div id="f-list"><div class="empty">Carregando…</div></div></div></div>
<div id="toast"></div>
<script>
var TOKEN=null,currentUser=null,cars=[],filter="all",revokeTarget=null,latest=0,canFiles=false,files=[],uploading=false;
try{TOKEN=localStorage.getItem('ubyd_token');currentUser=localStorage.getItem('ubyd_user');}catch(e){}
function api(path,opts){opts=opts||{};opts.headers=opts.headers||{};if(TOKEN)opts.headers['Authorization']='Bearer '+TOKEN;
 if(opts.body){opts.headers['Content-Type']='application/json';opts.body=JSON.stringify(opts.body);}
 return fetch(path,opts).then(function(r){if(r.status===401){doLogout();throw new Error('unauth');}return r.json();});}
function userColor(n){n=(n||'').toLowerCase();if(n==='euler')return '#2bb0a6';if(n==='luis')return '#4f8cff';if(n==='richard')return '#b084f5';return '#8793a6';}
function respChip(n){if(!n)return '';var c=userColor(n);return '<span class="resp" style="color:'+c+';background:'+c+'22"><span class="dot" style="background:'+c+'"></span>'+n+'</span>';}
function carSvg(){return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M5 13l1.5-4.5A2 2 0 0 1 8.4 7h7.2a2 2 0 0 1 1.9 1.5L19 13"/><path d="M4 17h16v-2a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v2z"/><circle cx="7.5" cy="17.5" r="1.5"/><circle cx="16.5" cy="17.5" r="1.5"/></svg>';}
function badge(s){if(s==="pending")return '<span class="badge pending">Pendente</span>';if(s==="active")return '<span class="badge active">Ativo</span>';return '<span class="badge revoked">Revogado</span>';}
function actionsFor(c){if(c.status==="pending")return '<button class="btn btn-approve" onclick="event.stopPropagation();openDetail(\\''+c.id+'\\')">Aprovar</button>';
 if(c.status==="active")return '<button class="btn btn-revoke" onclick="event.stopPropagation();openRevoke(\\''+c.id+'\\')">Revogar</button>';
 return '<button class="btn btn-reapprove" onclick="event.stopPropagation();openDetail(\\''+c.id+'\\')">Reativar</button>';}
function planBadge(p){var m={basico:['Básico','#8793a6'],intermediario:['Intermediário','#4f8cff'],premium:['Premium','#f5a623']};var x=m[p]||m.basico;return '<span class="pbadge" style="color:'+x[1]+';background:'+x[1]+'22">'+x[0]+'</span>';}
function planName(p){return p==='premium'?'Premium':p==='intermediario'?'Intermediário':'Básico';}
function grupoName(g){return g==='byd_google'?'BYD · Google':g==='chery'?'Chery':g==='geely'?'Geely':'BYD · Simples';}
function grupoBadge(g){var m={byd_simples:['BYD Simples','#8793a6'],byd_google:['BYD Google','#34c759'],chery:['Chery','#ff8f4f'],geely:['Geely','#b57bff']};var x=m[g]||m.byd_simples;return '<span class="pbadge" style="color:'+x[1]+';background:'+x[1]+'22">'+x[0]+'</span>';}
/* versão do app / Android */
function isOld(c){return latest>0&&(c.vc||0)<latest;}
function verBadge(c){var col=!c.vc?'#f5a623':(isOld(c)?'#f5a623':'#27c08a');var t=c.vc?('v'+c.vc):'v12 ou anterior';
 return '<span class="pbadge" title="Versão do app Unlock BYD" style="color:'+col+';background:'+col+'22">'+t+'</span>';}
function androidName(s){var m={24:'7',25:'7.1',26:'8',27:'8.1',28:'9',29:'10',30:'11',31:'12',32:'12L',33:'13',34:'14',35:'15',36:'16'};return s?('Android '+(m[s]||('API '+s))):'';}
function androidBadge(c){if(!c.sdk)return '';return '<span class="pbadge" style="color:#8fb3ff;background:#8fb3ff22">'+androidName(c.sdk)+'</span>';}
function carHtml(c){var resp=c.owner?'<span class="sep">·</span> Responsável: '+respChip(c.owner):'<span class="sep">·</span> <span style="color:var(--faint)">sem responsável</span>';
 var extra=(c.status==="revoked"&&c.wipe)?' <span class="sep">·</span> <span class="wiped">apps apagados</span>':'';
 var pb=(c.status==="active")?' '+planBadge(c.plan)+' '+grupoBadge(c.grupo):'';
 return '<div class="car '+(c.status==="pending"?"pending":"")+'" onclick="openDetail(\\''+c.id+'\\')"><div class="ico">'+carSvg()+'</div>'
  +'<div class="info"><div class="name">'+c.nome+' '+badge(c.status)+pb+' '+verBadge(c)+' '+androidBadge(c)+'</div><div class="meta">ID <code>'+c.id+'</code> '+resp+' <span class="sep">·</span> visto '+c.seen+extra+'</div></div>'
  +'<div class="actions">'+actionsFor(c)+'</div></div>';}
function setF(el){var cs=document.querySelectorAll('.chip');for(var i=0;i<cs.length;i++)cs[i].classList.remove('on');el.classList.add('on');filter=el.dataset.f;render();}
function isMine(c){return c.owner&&currentUser&&c.owner.toLowerCase()===currentUser.toLowerCase();}
function render(){var q=(document.getElementById('q').value||"").toLowerCase();
 var pend=cars.filter(function(c){return c.status==="pending";});
 var ps=document.getElementById('pending-sec');
 if(pend.length&&(filter==="all"||filter==="pending")){ps.innerHTML='<div class="sec-title" style="color:var(--amber)">Aguardando aprovação ('+pend.length+')</div><div class="list">'+pend.map(carHtml).join('')+'</div>';}else ps.innerHTML='';
 var rows=cars.filter(function(c){if(filter==="mine"){if(!isMine(c))return false;}else if(filter==="old"){if(!isOld(c))return false;}else if(filter!=="all"&&c.status!==filter)return false;
  if(q){var hay=(c.nome+' '+c.id+' '+(c.owner||'')).toLowerCase();if(hay.indexOf(q)<0)return false;}
  if((filter==="all"||filter==="pending")&&c.status==="pending")return false;return true;});
 var l=document.getElementById('list');l.innerHTML=rows.length?rows.map(carHtml).join(''):'<div class="empty">Nenhum veículo nesta visão.</div>';
 document.getElementById('s-total').textContent=cars.length;
 document.getElementById('s-pend').textContent=cars.filter(function(c){return c.status==="pending";}).length;
 document.getElementById('s-act').textContent=cars.filter(function(c){return c.status==="active";}).length;
 document.getElementById('s-rev').textContent=cars.filter(function(c){return c.status==="revoked";}).length;
 var old=cars.filter(isOld).length;document.getElementById('s-old').textContent=latest?old:'—';
 document.getElementById('s-old-l').textContent=latest?('Abaixo da v'+latest):'Desatualizados';
 document.getElementById('s-old-box').className='stat old'+(latest&&!old?' ok':'');}
function loadCars(){api('/api/cars').then(function(res){cars=res.cars||[];latest=res.latest||0;canFiles=!!res.files;document.getElementById('files-btn').style.display=canFiles?'':'none';if(res.me){currentUser=res.me;setWho();}render();}).catch(function(){});}
function approve(id){api('/api/approve',{method:'POST',body:{id:id}}).then(function(){toast("✓ Ativado");loadCars();}).catch(function(){});}
function openRevoke(id){revokeTarget=id;var c=cars.filter(function(x){return x.id===id;})[0];
 document.getElementById('modal-sub').textContent='Veículo: '+c.nome+'  ('+c.id+')';
 var w=document.getElementById('modal-warn-resp');
 if(c.owner&&currentUser&&c.owner.toLowerCase()!==currentUser.toLowerCase()){w.style.display='block';w.innerHTML='⚠ Este veículo é responsabilidade de <b>'+c.owner+'</b>. Você (<b>'+currentUser+'</b>) está prestes a mexer no carro de outro responsável.';}else{w.style.display='none';}
 document.getElementById('modal').style.display='flex';}
function closeModal(){document.getElementById('modal').style.display='none';revokeTarget=null;}
function doRevoke(wipe){api('/api/revoke',{method:'POST',body:{id:revokeTarget,wipe:wipe}}).then(function(){toast(wipe?"✕ Revogado — apps serão removidos":"✕ Revogado");closeModal();loadCars();}).catch(function(){});}
function openDetail(id){var c=cars.filter(function(x){return x.id===id;})[0];if(!c)return;
 document.getElementById('d-name').textContent=c.nome;
 document.getElementById('d-id').textContent='ID '+c.id+'  ·  registrado '+c.reg+'  ·  visto '+c.seen;
 document.getElementById('d-head').innerHTML=badge(c.status)+(c.status==="active"?' '+planBadge(c.plan)+' '+grupoBadge(c.grupo):'')+' '+verBadge(c)+' '+androidBadge(c)+' '+(c.owner?('Responsável: '+respChip(c.owner)):'<span style="color:var(--faint);font-size:12px">sem responsável</span>');
 chosenPlan=c.plan||'basico'; chosenGrupo=c.grupo||'byd_simples';
 var h=planPicker(chosenPlan);
 if(c.ask) h+=groupPicker(chosenGrupo);
 else h+='<div class="sec-title" style="margin:2px 0 12px">Pacote: <span style="color:var(--text)">'+grupoName(c.grupo||'byd_simples')+'</span> <span style="color:var(--faint);font-weight:400">· definido pelo modelo</span></div>';
 if(c.status==='pending') h+='<button class="btn btn-approve" style="width:100%" onclick="activate(\\''+c.id+'\\')">Ativar com este plano</button>';
 else if(c.status==='active'){ h+='<button class="btn btn-reapprove" style="width:100%;margin-bottom:8px" onclick="activate(\\''+c.id+'\\')">Salvar plano</button>';
   h+='<button class="btn btn-revoke" style="width:100%" onclick="openRevoke(\\''+c.id+'\\')">Revogar acesso</button>'; }
 else h+='<button class="btn btn-reapprove" style="width:100%" onclick="activate(\\''+c.id+'\\')">Reativar com este plano</button>';
 if(c.allowRemove) h+='<div style="color:#f2c25b;font-size:12.5px;background:var(--amber-bg);border:1px solid #5a470f;border-radius:10px;padding:10px 12px;margin-top:10px">Remoção de Device Owner autorizada — aguardando o carro aplicar.</div>';
 else h+='<button class="btn" style="width:100%;margin-top:10px;background:var(--surface2);color:var(--muted);border:1px solid var(--border)" onclick="allowRemoval(\\''+c.id+'\\')">Autorizar remoção de Device Owner</button>';
 h+='<button class="btn" style="width:100%;margin-top:18px;background:transparent;border:1px solid var(--red);color:var(--red)" onclick="askDelete(\\''+c.id+'\\')">Excluir veículo</button>';
 document.getElementById('d-actions').innerHTML=h;
 var log=(c.log||[]).slice().reverse();var dl=document.getElementById('d-log');
 if(!log.length){dl.innerHTML='<div class="empty">Nenhuma ação ainda — registrado '+c.reg+', aguardando aprovação.</div>';}
 else{dl.innerHTML=log.map(function(e){var col=userColor(e.by);return '<div class="logrow"><span class="logact '+e.cls+'">'+e.acao+'</span><span class="logby">por <span style="color:'+col+';font-weight:650">'+e.by+'</span></span><span class="logwhen">'+e.when+'</span></div>';}).join('');}
 document.getElementById('detail').style.display='flex';}
function closeDetail(){document.getElementById('detail').style.display='none';}
function allowRemoval(id){api('/api/allow-removal',{method:'POST',body:{id:id}}).then(function(){toast('Remoção de Device Owner autorizada');loadCars();closeDetail();}).catch(function(){});}
var chosenPlan='basico'; var chosenGrupo='byd_simples';
function planPicker(sel){var o=[['basico','Básico'],['intermediario','Intermediário'],['premium','Premium']];
 var h='<div class="sec-title" style="margin:2px 0 8px">Plano da loja</div><div id="planpick" style="display:flex;gap:8px;margin-bottom:14px">';
 for(var i=0;i<o.length;i++){h+='<button class="planbtn'+(o[i][0]===sel?' on':'')+'" data-plan="'+o[i][0]+'" onclick="pickPlan(this)">'+o[i][1]+'</button>';}
 return h+'</div>';}
function pickPlan(el){chosenPlan=el.dataset.plan;var b=document.querySelectorAll('#planpick .planbtn');for(var i=0;i<b.length;i++)b[i].classList.remove('on');el.classList.add('on');}
function groupPicker(sel){var o=[['byd_simples','Simples'],['byd_google','Google']];
 var h='<div class="sec-title" style="margin:2px 0 8px">Pacote (Song/Yuan)</div><div id="grppick" style="display:flex;gap:8px;margin-bottom:14px">';
 for(var i=0;i<o.length;i++){h+='<button class="planbtn'+(o[i][0]===sel?' on':'')+'" data-grp="'+o[i][0]+'" onclick="pickGrupo(this)">'+o[i][1]+'</button>';}
 return h+'</div>';}
function pickGrupo(el){chosenGrupo=el.dataset.grp;var b=document.querySelectorAll('#grppick .planbtn');for(var i=0;i<b.length;i++)b[i].classList.remove('on');el.classList.add('on');}
function activate(id){var pl=chosenPlan,gr=chosenGrupo;api('/api/approve',{method:'POST',body:{id:id,plan:pl,grupo:gr}}).then(function(){toast('✓ Ativo · '+planName(pl)+' · '+grupoName(gr));for(var i=0;i<cars.length;i++){if(cars[i].id===id){cars[i].status='active';cars[i].plan=pl;cars[i].grupo=gr;cars[i].wipe=false;}}closeDetail();render();}).catch(function(){});}
function askDelete(id){var c=cars.filter(function(x){return x.id===id;})[0];
 if(!confirm('Excluir o veículo "'+(c?c.nome:id)+'"?\\n\\nEle sai do painel e, ao abrir o app, o cliente volta para a tela de escolher modelo e nome para solicitar acesso novamente.'))return;
 api('/api/delete',{method:'POST',body:{id:id}}).then(function(){toast('Veículo excluído');closeDetail();loadCars();}).catch(function(){});}
/* ---------- arquivos (R2) ---------- */
var CHUNK=50*1024*1024;
var fTab='apps';
var FTABS=[['apps','Apps','apps/','APKs e XAPKs que aparecem na loja. Depois de enviar, copie o link e cole no catálogo.'],
 ['extras','Extras','extras/','Discos, OBB e pastas data dos jogos. Use uma pasta por jogo (ex.: re4).'],
 ['icons','Ícones','icons/','Ícones dos apps (PNG).'],
 ['app','App Unlock BYD','app/',''],
 ['outros','Catálogo e outros','','']];
function tabOf(k){if(k.indexOf('apps/')===0)return 'apps';if(k.indexOf('extras/')===0)return 'extras';if(k.indexOf('icons/')===0)return 'icons';
 if(k.indexOf('app/')===0||k==='app_version.json')return 'app';return 'outros';}
function tabInfo(t){for(var i=0;i<FTABS.length;i++)if(FTABS[i][0]===t)return FTABS[i];return FTABS[0];}
function setFTab(t){fTab=t;document.getElementById('f-q').value='';renderFiles();}
function fmtSize(n){if(n>=1073741824)return (n/1073741824).toFixed(2)+' GB';if(n>=1048576)return (n/1048576).toFixed(1)+' MB';return Math.max(1,Math.round(n/1024))+' KB';}
function esc(s){return String(s).replace(/[&<>"']/g,function(ch){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch];});}
function fileUrl(k){return location.origin+'/f/'+k.split('/').map(encodeURIComponent).join('/');}
function openFiles(){document.getElementById('files').style.display='flex';loadFiles();}
function closeFiles(){if(uploading&&!confirm('Há um envio em andamento. Fechar mesmo assim? (o envio continua enquanto esta aba estiver aberta)'))return;document.getElementById('files').style.display='none';}
function loadFiles(){api('/api/files').then(function(r){files=r.files||[];renderFiles();}).catch(function(){document.getElementById('f-list').innerHTML='<div class="empty">Não consegui listar os arquivos.</div>';});}
function fileRow(f,shown){return '<div class="frow"><div class="fk">'+esc(shown)+'</div><div class="fs">'+fmtSize(f.size)+' · '+f.uploaded+'</div>'
  +'<button class="fbtn" data-k="'+esc(f.key)+'" onclick="copyLink(this.dataset.k)">Copiar link</button>'
  +(fTab==='app'||fTab==='outros'?'':'<button class="fbtn del" data-k="'+esc(f.key)+'" onclick="delFile(this.dataset.k)">Excluir</button>')+'</div>';}
function renderFiles(){var q=(document.getElementById('f-q').value||'').toLowerCase();var info=tabInfo(fTab);
 // abas com contagem
 var th='';for(var i=0;i<FTABS.length;i++){var t=FTABS[i];var n=files.filter(function(f){return tabOf(f.key)===t[0];}).length;
  th+='<div class="chip'+(t[0]===fTab?' on':'')+'" onclick="setFTab(\\''+t[0]+'\\')">'+t[1]+'<span class="ct">'+n+'</span></div>';}
 document.getElementById('f-tabs').innerHTML=th;
 // envio: só nas abas Apps, Extras e Ícones (App e Catálogo são publicados pelo GitHub)
 var managed=(fTab==='app'||fTab==='outros');
 document.getElementById('up-box').style.display=managed?'none':'';
 document.getElementById('up-sub').style.display=fTab==='extras'?'':'none';
 document.getElementById('up-title').textContent='Enviar para '+info[1]+(info[3]?' — '+info[3]:'');
 var note=document.getElementById('f-note');
 if(managed){note.style.display='block';note.innerHTML=fTab==='app'?'O APK do Unlock BYD e o <b>app_version.json</b> são publicados automaticamente pelo GitHub (pasta <b>dist/</b> e arquivo <b>app_version.json</b> do repositório). Não envie por aqui.'
  :'O <b>catalog.json</b> é publicado automaticamente pelo GitHub a partir do arquivo do repositório. Edite lá, não por aqui.';}else note.style.display='none';
 var all=files.filter(function(f){return tabOf(f.key)===fTab;});
 var rows=all.filter(function(f){return !q||f.key.toLowerCase().indexOf(q)>=0;});
 var total=all.reduce(function(a,f){return a+f.size;},0);
 var h='<div style="font-size:12px;color:var(--faint);margin:4px 0 6px">'+all.length+' arquivo'+(all.length===1?'':'s')+' · '+fmtSize(total)+'</div>';
 if(!rows.length){document.getElementById('f-list').innerHTML=h+'<div class="empty">Nenhum arquivo nesta seção.</div>';return;}
 var pre=info[2];
 if(fTab==='extras'){ // agrupa por pasta do jogo
  var groups={},order=[];rows.forEach(function(f){var rest=f.key.slice(pre.length);var g=rest.indexOf('/')>=0?rest.split('/')[0]:'(sem pasta)';if(!groups[g]){groups[g]=[];order.push(g);}groups[g].push(f);});
  order.sort();order.forEach(function(g){h+='<div class="fgrp">'+esc(g)+'</div>';groups[g].forEach(function(f){var rest=f.key.slice(pre.length);h+=fileRow(f,g==='(sem pasta)'?rest:rest.slice(g.length+1));});});
 } else h+=rows.map(function(f){return fileRow(f,pre&&f.key.indexOf(pre)===0?f.key.slice(pre.length):f.key);}).join('');
 document.getElementById('f-list').innerHTML=h;}
function copyLink(k){var u=fileUrl(k);try{navigator.clipboard.writeText(u).then(function(){toast('Link copiado');},function(){prompt('Copie o link:',u);});}catch(e){prompt('Copie o link:',u);}}
function delFile(k){if(!confirm('Excluir "'+k+'" do R2?\\n\\nCarros que ainda apontam para este arquivo não vão conseguir baixá-lo.'))return;
 api('/api/files/delete',{method:'POST',body:{key:k}}).then(function(){toast('Arquivo excluído');loadFiles();}).catch(function(){});}
function upMsg(t){document.getElementById('up-msg').textContent=t;}
function upBar(p){var b=document.getElementById('up-bar');b.style.display=p==null?'none':'block';b.firstChild.style.width=(p||0)+'%';}
function putPart(key,uploadId,n,blob){return fetch('/api/files/mpu/part?key='+encodeURIComponent(key)+'&uploadId='+encodeURIComponent(uploadId)+'&n='+n,{method:'PUT',headers:{'Authorization':'Bearer '+TOKEN},body:blob})
 .then(function(r){if(!r.ok)throw new Error('parte '+n+' falhou ('+r.status+')');return r.json();});}
function uploadOne(file,key,idx,count){var parts=[],uploadId=null,total=Math.max(1,Math.ceil(file.size/CHUNK)),n=0;
 return api('/api/files/mpu/start',{method:'POST',body:{key:key}}).then(function(r){if(!r.uploadId)throw new Error(r.error||'não iniciou');uploadId=r.uploadId;
  function next(){if(n>=total)return Promise.resolve();n++;var start=(n-1)*CHUNK;var blob=file.slice(start,Math.min(file.size,start+CHUNK));
   var tries=0;function attempt(){return putPart(key,uploadId,n,blob).catch(function(e){if(++tries<3)return attempt();throw e;});}
   return attempt().then(function(p){parts.push(p);upMsg('Enviando '+idx+'/'+count+': '+file.name+' — parte '+n+'/'+total);upBar(Math.round(n*100/total));return next();});}
  return next();}).then(function(){return api('/api/files/mpu/complete',{method:'POST',body:{key:key,uploadId:uploadId,parts:parts}});})
 .then(function(r){if(!r.ok)throw new Error(r.error||'falhou ao concluir');return r;})
 .catch(function(e){if(uploadId)api('/api/files/mpu/abort',{method:'POST',body:{key:key,uploadId:uploadId}}).catch(function(){});throw e;});}
function startUpload(){if(uploading)return;var inp=document.getElementById('up-file');var list=Array.prototype.slice.call(inp.files||[]);
 if(!list.length){toast('Escolha um ou mais arquivos');return;}
 var dir=tabInfo(fTab)[2];var sub=fTab==='extras'?(document.getElementById('up-sub').value||'').trim().replace(/^\\/+|\\/+$/g,''):'';
 if(fTab==='extras'&&!sub){toast('Digite a pasta do jogo (ex.: re4)');return;}
 var prefix=dir+(sub?sub+'/':'');uploading=true;document.getElementById('up-go').disabled=true;upBar(0);
 var i=0;function go(){if(i>=list.length)return Promise.resolve();var f=list[i++];return uploadOne(f,prefix+f.name,i,list.length).then(go);}
 go().then(function(){upMsg('✓ Envio concluído ('+list.length+' arquivo'+(list.length>1?'s':'')+').');toast('Envio concluído');inp.value='';})
 .catch(function(e){upMsg('✕ Falha no envio: '+(e&&e.message?e.message:e));})
 .then(function(){uploading=false;document.getElementById('up-go').disabled=false;upBar(null);loadFiles();});}
window.addEventListener('beforeunload',function(e){if(uploading){e.preventDefault();e.returnValue='';}});
var tTimer;function toast(m){var t=document.getElementById('toast');t.textContent=m;t.classList.add('show');clearTimeout(tTimer);tTimer=setTimeout(function(){t.classList.remove('show');},2600);}
function setWho(){var av=document.getElementById('whoav');av.textContent=(currentUser||'?').charAt(0);av.style.background=userColor(currentUser);document.getElementById('whoami').textContent=currentUser||'';}
function doLogin(e){e.preventDefault();var u=(document.getElementById('lu').value||'').trim();var p=document.getElementById('lp').value||'';var err=document.getElementById('login-err');err.textContent='';
 fetch('/api/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({user:u,pass:p})}).then(function(r){return r.json().then(function(j){return {ok:r.ok,j:j};});}).then(function(res){
  if(!res.ok||res.j.error){err.textContent='Usuário ou senha inválidos.';return;}
  TOKEN=res.j.token;currentUser=res.j.user;try{localStorage.setItem('ubyd_token',TOKEN);localStorage.setItem('ubyd_user',currentUser);}catch(e){}
  setWho();document.getElementById('login').style.display='none';document.getElementById('lp').value='';loadCars();
 }).catch(function(){err.textContent='Falha de conexão.';});}
function doLogout(){TOKEN=null;currentUser=null;try{localStorage.removeItem('ubyd_token');localStorage.removeItem('ubyd_user');}catch(e){}
 document.getElementById('lu').value='';document.getElementById('lp').value='';document.getElementById('login-err').textContent='';document.getElementById('login').style.display='flex';}
function logout(){doLogout();}
if(TOKEN){setWho();document.getElementById('login').style.display='none';loadCars();}
render();
</script></body></html>`;
