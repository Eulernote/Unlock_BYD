// ============================================================================
//  Telemetria da bateria — widget de gráfico usado no painel ADM e no app do cliente.
//  (Código que roda no NAVEGADOR; vai embutido nas duas páginas.)
//  teleWidget(elemento, load) — load(horas) => Promise({last:{ts,soc,v12}, rows:[{ts,soc,v12}]})
// ============================================================================
export const TELE_UI_JS = String.raw`
(function(){
if(window.teleWidget) return;
var css='.tw{font-size:13px}.tw-now{display:flex;gap:18px;flex-wrap:wrap;align-items:flex-end;margin-bottom:10px}.tw-now b{display:block;font-size:22px;line-height:1.1}.tw-l{font-size:11.5px;opacity:.65}.tw-muted{opacity:.6;font-size:12px}'+
 '.tw-row{display:flex;gap:6px;flex-wrap:wrap;margin:6px 0}.tw-b{border:1px solid rgba(128,128,128,.35);background:transparent;color:inherit;border-radius:99px;padding:5px 11px;font-size:12.5px;cursor:pointer}.tw-b.on{background:#2bb0a6;border-color:#2bb0a6;color:#04201d;font-weight:700}'+
 '.tw svg{width:100%;height:auto;display:block;margin:6px 0}.tw-sum{margin:6px 0 4px;line-height:1.5}.tw-ch{max-height:220px;overflow:auto;border-top:1px solid rgba(128,128,128,.25);margin-top:6px}.tw-ch div{display:flex;justify-content:space-between;gap:8px;padding:5px 0;border-bottom:1px solid rgba(128,128,128,.15);font-size:12.5px}.tw-dn{color:#ef5350}.tw-up{color:#27c08a}';
var s=document.createElement('style');s.textContent=css;document.head.appendChild(s);
function n(v,d){return v==null||isNaN(v)?'—':Number(v).toLocaleString('pt-BR',{minimumFractionDigits:d,maximumFractionDigits:d});}
function hm(ts){var d=new Date(ts);return ('0'+d.getHours()).slice(-2)+':'+('0'+d.getMinutes()).slice(-2);}
function dm(ts){var d=new Date(ts);return ('0'+d.getDate()).slice(-2)+'/'+('0'+(d.getMonth()+1)).slice(-2);}
function dur(ms){var m=Math.round(ms/60000);if(m<60)return m+' min';var h=Math.floor(m/60),r=m%60;if(h<24)return h+' h'+(r?(' '+r+' min'):'');var dd=Math.floor(h/24);return dd+' d '+(h%24)+' h';}
function ago(ts){return 'há '+dur(Date.now()-ts);}
var K={soc:{name:'Bateria de tração',unit:'%',d:1,color:'#2bb0a6'},v12:{name:'Bateria 12V',unit:' V',d:2,color:'#f2c25b'}};
function chart(rows,k,h){
 var W=640,H=220,L=46,R=10,T=12,B=26,now=Date.now(),t0=now-h*3600000;
 var pts=rows.filter(function(r){return r[k]!=null&&r.ts>=t0;});
 if(pts.length<2) return '<div class="tw-muted" style="padding:18px 0">Ainda sem dados suficientes neste período (o carro manda a cada 2 min enquanto está acordado).</div>';
 var mn=Infinity,mx=-Infinity;pts.forEach(function(p){mn=Math.min(mn,p[k]);mx=Math.max(mx,p[k]);});
 var pad=(mx-mn)*0.15||(k==='soc'?0.5:0.05);mn-=pad;mx+=pad;
 var X=function(t){return L+(t-t0)/(now-t0)*(W-L-R);},Y=function(v){return T+(mx-v)/(mx-mn)*(H-T-B);};
 var g='';for(var i=0;i<=3;i++){var v=mn+(mx-mn)*i/3,y=Y(v);g+='<line x1="'+L+'" x2="'+(W-R)+'" y1="'+y+'" y2="'+y+'" stroke="rgba(128,128,128,.2)"/><text x="'+(L-6)+'" y="'+(y+4)+'" text-anchor="end" font-size="11" fill="currentColor" opacity=".6">'+n(v,K[k].d)+'</text>';}
 for(var j=0;j<=4;j++){var t=t0+(now-t0)*j/4,x=X(t);g+='<text x="'+x+'" y="'+(H-8)+'" text-anchor="'+(j===0?'start':j===4?'end':'middle')+'" font-size="11" fill="currentColor" opacity=".6">'+(h>24?dm(t)+' ':'')+hm(t)+'</text>';}
 // quebra a linha onde ficou mais de 15 min sem dado (multimídia dormindo)
 var path='',prev=null;pts.forEach(function(p){path+=(prev&&p.ts-prev.ts<15*60000?'L':'M')+X(p.ts).toFixed(1)+' '+Y(p[k]).toFixed(1)+' ';prev=p;});
 var lp=pts[pts.length-1];
 return '<svg viewBox="0 0 '+W+' '+H+'">'+g+'<path d="'+path+'" fill="none" stroke="'+K[k].color+'" stroke-width="2.2" stroke-linejoin="round"/><circle cx="'+X(lp.ts)+'" cy="'+Y(lp[k])+'" r="3.5" fill="'+K[k].color+'"/></svg>';
}
function summary(rows,k,h){
 var t0=Date.now()-h*3600000,pts=rows.filter(function(r){return r[k]!=null&&r.ts>=t0;});if(pts.length<2)return '';
 var a=pts[0],b=pts[pts.length-1],dv=b[k]-a[k],dt=b.ts-a.ts,cls=dv<0?'tw-dn':'tw-up';
 var rate=dt>0?dv/(dt/3600000):0;
 return '<div class="tw-sum">De <b>'+n(a[k],K[k].d)+K[k].unit+'</b> ('+dm(a.ts)+' '+hm(a.ts)+') para <b>'+n(b[k],K[k].d)+K[k].unit+'</b> ('+hm(b.ts)+'): <b class="'+cls+'">'+(dv>0?'+':'')+n(dv,K[k].d)+K[k].unit+'</b> em '+dur(dt)+
  ' <span class="tw-muted">(média '+(rate>0?'+':'')+n(rate,k==='soc'?2:3)+K[k].unit+'/h)</span></div>';
}
function changes(rows,k,h){
 var t0=Date.now()-h*3600000,pts=rows.filter(function(r){return r[k]!=null&&r.ts>=t0;}),out=[],ref=null,refTs=0,step=k==='soc'?0.1:0.05;
 pts.forEach(function(p){if(ref==null){ref=p[k];refTs=p.ts;return;}if(Math.abs(p[k]-ref)>=step-1e-9){out.push({a:ref,b:p[k],dt:p.ts-refTs,ts:p.ts});ref=p[k];refTs=p.ts;}});
 if(!out.length)return '';
 return '<div class="tw-ch">'+out.reverse().slice(0,40).map(function(c){var dn=c.b<c.a;return '<div><span class="'+(dn?'tw-dn':'tw-up')+'">'+n(c.a,K[k].d)+' → '+n(c.b,K[k].d)+K[k].unit+'</span><span>em '+dur(c.dt)+'</span><span class="tw-muted">'+dm(c.ts)+' '+hm(c.ts)+'</span></div>';}).join('')+'</div>';
}
window.teleWidget=function(el,load){
 var st={k:'soc',h:24,data:null,err:''};
 function draw(){var d=st.data,last=(d&&d.last)||{};
  var html='<div class="tw"><div class="tw-now"><div><span class="tw-l">Bateria de tração</span><b>'+n(last.soc,1)+'%</b></div><div><span class="tw-l">Bateria 12V</span><b>'+n(last.v12,2)+' V</b></div><div class="tw-muted">'+(last.ts?('atualizado '+ago(last.ts)):(st.err||'sem dados ainda'))+'</div></div>';
  html+='<div class="tw-row">'+[['soc','Tração (%)'],['v12','12V (V)']].map(function(t){return '<button class="tw-b'+(st.k===t[0]?' on':'')+'" data-k="'+t[0]+'">'+t[1]+'</button>';}).join('')+'<span style="flex:1"></span>'+
   [[6,'6 h'],[24,'24 h'],[168,'7 dias']].map(function(r){return '<button class="tw-b'+(st.h===r[0]?' on':'')+'" data-h="'+r[0]+'">'+r[1]+'</button>';}).join('')+'</div>';
  var rows=(d&&d.rows)||[];
  html+=d?chart(rows,st.k,st.h)+summary(rows,st.k,st.h)+changes(rows,st.k,st.h):'<div class="tw-muted" style="padding:14px 0">Carregando…</div>';
  el.innerHTML=html+'</div>';
  el.querySelectorAll('[data-k]').forEach(function(b){b.onclick=function(){st.k=b.getAttribute('data-k');draw();};});
  el.querySelectorAll('[data-h]').forEach(function(b){b.onclick=function(){st.h=Number(b.getAttribute('data-h'));fetchIt();};});}
 function fetchIt(){st.data=st.data||null;draw();load(st.h).then(function(d){st.data=d;st.err='';draw();}).catch(function(e){st.err='não consegui carregar ('+(e&&e.message||e)+')';st.data=st.data||{rows:[]};draw();});}
 fetchIt();
 return {reload:fetchIt};
};
})();
`;
