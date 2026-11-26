// ============================================================================
//  Câmeras ao vivo — hub de sinalização (1 Durable Object por carro)
//
//  O vídeo NÃO passa por aqui: carro e celular trocam só as "propostas" WebRTC (SDP) por
//  este hub e depois o vídeo vai direto (ou pelo TURN da Cloudflare, quando a operadora bloqueia).
//
//  Conexões (WebSocket, com hibernação — parado não custa tempo de execução):
//    carro   → /ws/car?id=<carId>                 tag 'car'
//    celular → /c/ws?car=<carId>&t=<token>        tags 'viewer', 'v:<viewerId>'
//
//  Mensagens (JSON):
//    hub→celular  {t:'hello', online, ice}   {t:'online'}  {t:'offline'}
//    celular→hub  {t:'start'}  {t:'answer', sdp}  {t:'stop'}
//    hub→carro    {t:'start', v, ice}  {t:'answer', v, sdp}  {t:'stop', v}
//    carro→hub    {t:'offer', v, sdp}  {t:'info', v, ...}  {t:'error', v, msg}  → repassado ao celular v
//  "ping" (texto) é respondido com "pong" pelo próprio runtime, sem acordar o objeto.
// ============================================================================

const MAX_VIEWERS = 3;
let iceCache = null;  // {at, servers} — por isolate

/** STUN sempre; TURN da Cloudflare quando os segredos TURN_KEY_ID / TURN_KEY_TOKEN existem no Worker. */
export async function iceServers(env){
  const stun = [{urls: ['stun:stun.cloudflare.com:3478']}];
  if(!env.TURN_KEY_ID || !env.TURN_KEY_TOKEN) return stun;
  if(iceCache && Date.now() - iceCache.at < 30 * 60 * 1000) return iceCache.servers;
  try{
    const r = await fetch('https://rtc.live.cloudflare.com/v1/turn/keys/' + env.TURN_KEY_ID + '/credentials/generate-ice-servers', {
      method: 'POST',
      headers: {'Authorization': 'Bearer ' + env.TURN_KEY_TOKEN, 'Content-Type': 'application/json'},
      body: JSON.stringify({ttl: 7200})
    });
    if(!r.ok) return stun;
    const j = await r.json();
    let list = Array.isArray(j.iceServers) ? j.iceServers : (j.iceServers ? [j.iceServers] : []);
    // Sem trickle ICE, as URLs na porta 53 só atrasam (recomendação da própria Cloudflare).
    list = list.map(s => {
      const urls = (Array.isArray(s.urls) ? s.urls : [s.urls]).filter(u => u && !/:53(\?|$)/.test(u));
      return Object.assign({}, s, {urls});
    }).filter(s => s.urls.length);
    const servers = list.length ? list : stun;
    iceCache = {at: Date.now(), servers};
    return servers;
  }catch(e){ return stun; }
}

function send(ws, obj){ try{ ws.send(JSON.stringify(obj)); }catch(e){} }

export class CarHub {
  constructor(ctx, env){
    this.ctx = ctx; this.env = env;
    try{ ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair('ping', 'pong')); }catch(e){}
  }

  cars(except){ return this.ctx.getWebSockets('car').filter(w => w !== except); }
  car(){ return this.cars()[0] || null; }
  viewers(except){ return this.ctx.getWebSockets('viewer').filter(w => w !== except); }

  async fetch(request){
    const url = new URL(request.url);
    if(url.pathname === '/status') return Response.json({online: !!this.car(), viewers: this.viewers().length});
    if(request.headers.get('Upgrade') !== 'websocket') return new Response('esperava websocket', {status: 426});

    const role = request.headers.get('X-Role');
    const pair = new WebSocketPair();
    const client = pair[0], server = pair[1];

    if(role === 'car'){
      // Só uma conexão de carro por vez: a nova substitui a antiga (ex.: reconexão após queda de rede).
      for(const old of this.cars()){ try{ old.serializeAttachment({replaced: true}); old.close(4000, 'replaced'); }catch(e){} }
      this.ctx.acceptWebSocket(server, ['car']);
      server.serializeAttachment({role: 'car'});
      for(const v of this.viewers()) send(v, {t: 'online'});
    } else if(role === 'viewer'){
      if(this.viewers().length >= MAX_VIEWERS) return new Response('muitos celulares assistindo', {status: 429});
      const vid = (request.headers.get('X-Viewer') || 'anon').slice(0, 120);
      this.ctx.acceptWebSocket(server, ['viewer', 'v:' + vid]);
      server.serializeAttachment({role: 'viewer', vid});
      send(server, {t: 'hello', online: !!this.car(), ice: await iceServers(this.env)});
    } else {
      return new Response('role', {status: 400});
    }
    return new Response(null, {status: 101, webSocket: client});
  }

  async webSocketMessage(ws, msg){
    if(typeof msg !== 'string') return;
    let m; try{ m = JSON.parse(msg); }catch(e){ return; }
    const a = ws.deserializeAttachment() || {};

    if(a.role === 'car'){
      // carro → celular específico
      if(!m || !m.v) return;
      for(const v of this.ctx.getWebSockets('v:' + m.v)) send(v, m);
      return;
    }
    if(a.role === 'viewer'){
      const car = this.car();
      if(m.t === 'start'){
        if(!car) return send(ws, {t: 'offline'});
        send(car, {t: 'start', v: a.vid, cam: String(m.cam || 'auto').slice(0, 20), size: String(m.size || 'auto').slice(0, 12), codec: String(m.codec || 'auto').slice(0, 8), ice: await iceServers(this.env)});
      } else if(m.t === 'cam'){
        if(car) send(car, {t: 'cam', v: a.vid, cam: String(m.cam || 'auto').slice(0, 20), size: String(m.size || 'auto').slice(0, 12)});
      } else if(m.t === 'answer' && typeof m.sdp === 'string'){
        if(car) send(car, {t: 'answer', v: a.vid, sdp: m.sdp.slice(0, 20000)});
      } else if(m.t === 'stop'){
        if(car) send(car, {t: 'stop', v: a.vid});
      }
    }
  }

  async gone(ws){
    const a = ws.deserializeAttachment() || {};
    if(a.role === 'car'){
      if(!a.replaced && this.cars(ws).length === 0) for(const v of this.viewers()) send(v, {t: 'offline'});
    } else if(a.role === 'viewer'){
      // Celular saiu: avisa o carro pra encerrar aquela transmissão (e desligar a câmera se for o último).
      const car = this.car();
      if(car && this.ctx.getWebSockets('v:' + a.vid).filter(w => w !== ws).length === 0) send(car, {t: 'stop', v: a.vid});
    }
  }
  async webSocketClose(ws, code, reason){ await this.gone(ws); try{ ws.close(code, reason); }catch(e){} }
  async webSocketError(ws){ await this.gone(ws); }
}
