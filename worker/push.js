// ============================================================================
//  Notificações no celular (Web Push) para o app do cliente.
//  Push "sem conteúdo": o servidor só cutuca o celular; o service worker busca a mensagem em
//  /c/api/push-pending (guardada no KV pela chave do endpoint). Assim não precisa criptografar o conteúdo.
//  As chaves VAPID são geradas na primeira vez e guardadas no KV ('vapid').
//
//  KV:  push:<user>   [ {endpoint, keys} ]      inscrições do celular
//       pend:<hash>   [ {title, body, tag, ts} ] mensagens esperando o celular buscar (2 dias)
// ============================================================================
const b64u = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const enc = new TextEncoder();

async function vapid(env){
  let v = await env.KV.get('vapid', {type: 'json'});
  if(v && v.pub && v.priv) return v;
  const kp = await crypto.subtle.generateKey({name: 'ECDSA', namedCurve: 'P-256'}, true, ['sign', 'verify']);
  const pub = b64u(await crypto.subtle.exportKey('raw', kp.publicKey));
  const priv = await crypto.subtle.exportKey('jwk', kp.privateKey);
  v = {pub, priv};
  await env.KV.put('vapid', JSON.stringify(v));
  return v;
}
export async function vapidPublic(env){ return (await vapid(env)).pub; }

async function hashEp(ep){ return b64u(await crypto.subtle.digest('SHA-256', enc.encode(ep))).slice(0, 32); }

async function poke(env, sub){
  const v = await vapid(env);
  const aud = new URL(sub.endpoint).origin;
  const head = b64u(enc.encode(JSON.stringify({typ: 'JWT', alg: 'ES256'})));
  const body = b64u(enc.encode(JSON.stringify({aud, exp: Math.floor(Date.now() / 1000) + 12 * 3600, sub: 'mailto:contato@unlockbyd.com.br'})));
  const key = await crypto.subtle.importKey('jwk', v.priv, {name: 'ECDSA', namedCurve: 'P-256'}, false, ['sign']);
  const sig = b64u(await crypto.subtle.sign({name: 'ECDSA', hash: 'SHA-256'}, key, enc.encode(head + '.' + body)));
  return fetch(sub.endpoint, {method: 'POST', headers: {TTL: '86400', Urgency: 'high', Authorization: 'vapid t=' + head + '.' + body + '.' + sig + ', k=' + v.pub, 'Content-Length': '0'}});
}

export async function addSub(env, user, sub){
  if(!sub || typeof sub.endpoint !== 'string' || sub.endpoint.indexOf('https://') !== 0) return false;
  const list = (await env.KV.get('push:' + user, {type: 'json'})) || [];
  const out = list.filter(s => s.endpoint !== sub.endpoint);
  out.push({endpoint: sub.endpoint, keys: sub.keys || null, at: Date.now()});
  await env.KV.put('push:' + user, JSON.stringify(out.slice(-6)));
  return true;
}

export async function takePending(env, endpoint){
  if(typeof endpoint !== 'string') return [];
  const k = 'pend:' + await hashEp(endpoint);
  const msgs = (await env.KV.get(k, {type: 'json'})) || [];
  if(msgs.length) await env.KV.delete(k);
  return msgs;
}

/** Manda a mensagem para todos os celulares vinculados ao carro. */
export async function notifyCar(env, carId, title, body, tag){
  const mon = (await env.KV.get('mon:' + carId, {type: 'json'})) || {};
  let sent = 0;
  for(const user of (mon.clients || [])) sent += await notifyUser(env, user, title, body, tag);
  return sent;
}

export async function notifyUser(env, user, title, body, tag){
  let sent = 0;
  {
    const subs = (await env.KV.get('push:' + user, {type: 'json'})) || [];
    const keep = [];
    for(const s of subs){
      try{
        const k = 'pend:' + await hashEp(s.endpoint);
        const msgs = (await env.KV.get(k, {type: 'json'})) || [];
        msgs.push({title, body, tag: tag || 'ubyd', ts: Date.now()});
        await env.KV.put(k, JSON.stringify(msgs.slice(-10)), {expirationTtl: 172800});
        const r = await poke(env, s);
        if(r.status === 404 || r.status === 410){ await env.KV.delete(k); continue; } // celular desinscreveu
        sent++;
      }catch(e){}
      keep.push(s);
    }
    if(keep.length !== subs.length) await env.KV.put('push:' + user, JSON.stringify(keep));
  }
  return sent;
}
