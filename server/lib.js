'use strict';
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

function constantEqual(a, b) {
  const x = crypto.createHash('sha256').update(String(a)).digest();
  const y = crypto.createHash('sha256').update(String(b)).digest();
  return crypto.timingSafeEqual(x, y);
}
function validEvent(event) {
  return event && typeof event === 'object' &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(event.event_id || '') &&
    ['location', 'trip_start', 'trip_end'].includes(event.type) &&
    Number.isFinite(event.latitude) && Math.abs(event.latitude) <= 90 &&
    Number.isFinite(event.longitude) && Math.abs(event.longitude) <= 180 &&
    Number.isFinite(event.accuracy_m) && event.accuracy_m >= 0 && event.accuracy_m <= 10000 &&
    typeof event.captured_at === 'string' && Number.isFinite(Date.parse(event.captured_at)) &&
    Math.abs(Date.now() - Date.parse(event.captured_at)) < 1000*60*60*24*365 &&
    (event.distance_m == null || (Number.isFinite(event.distance_m) && event.distance_m >= 0)) &&
    (event.duration_s == null || (Number.isFinite(event.duration_s) && event.duration_s >= 0));
}
function formatTelegram(event) {
  const title = {location:'📍 Координаты LADA Vesta', trip_start:'🚗 Начало поездки', trip_end:'🏁 Завершение поездки'}[event.type];
  const loc = `${event.latitude.toFixed(6)},${event.longitude.toFixed(6)}`;
  const time = new Date(event.captured_at).toLocaleString('ru-RU', { timeZone:'Europe/Moscow', hour12:false });
  let msg = `${title}\nВремя GPS: ${time} (МСК)\nТочность: ±${Math.round(event.accuracy_m)} м\nКарта: https://www.google.com/maps?q=${loc}`;
  if (event.type === 'trip_end') {
    if (Number.isFinite(event.distance_m)) msg += `\nПробег: ${(event.distance_m/1000).toFixed(2)} км`;
    if (Number.isFinite(event.duration_s)) msg += `\nДлительность: ${Math.round(event.duration_s/60)} мин`;
  }
  if (Date.now() - Date.parse(event.captured_at) > 10*60*1000) msg += '\n⚠️ Координаты сохранены ранее (отложенная отправка)';
  return msg;
}
async function telegramSender(config, event) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 13000);
  try {
    const response = await fetch(`https://api.telegram.org/bot${config.telegramToken}/sendMessage`, {
      method:'POST', headers:{'Content-Type':'application/json'}, signal:controller.signal,
      body: JSON.stringify({chat_id:config.telegramChatId, text:formatTelegram(event), disable_web_page_preview:false})
    });
    const result = await response.json();
    if (!response.ok || result.ok !== true || !result.result?.message_id) throw new Error(`Telegram rejected (${response.status})`);
    return result.result.message_id;
  } finally { clearTimeout(timer); }
}
function createServer(config, sender = e => telegramSender(config, e)) {
  fs.mkdirSync(config.dataDir, {recursive:true, mode:0o700});
  const locks = new Map();
  const write = (filename, data) => {
    const tmp = `${filename}.${crypto.randomBytes(6).toString('hex')}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(data), {mode:0o600, flag:'wx'});
    fs.renameSync(tmp, filename);
  };
  function respond(res, status, payload) {
    res.writeHead(status, {'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});
    res.end(JSON.stringify(payload));
  }
  async function handle(req, res) {
    if (req.url === '/healthz' && req.method === 'GET') return respond(res,200,{ok:true});
    if (req.url !== '/v1/events' || req.method !== 'POST') return respond(res,404,{ok:false,error:'not_found'});
    const id = String(req.headers['x-device-id'] || '');
    const token = /^Bearer (.+)$/i.exec(String(req.headers.authorization || ''))?.[1];
    const expected = Object.prototype.hasOwnProperty.call(config.deviceKeys,id) ? config.deviceKeys[id] : null;
    if (!expected || !token || !constantEqual(token, expected)) return respond(res,401,{ok:false,error:'unauthorized'});
    let raw = '';
    try {
      for await (const part of req) {
        raw += part;
        if (Buffer.byteLength(raw) > 16384) return respond(res,413,{ok:false,error:'payload_too_large'});
      }
      const event = JSON.parse(raw);
      if (!validEvent(event)) return respond(res,400,{ok:false,error:'invalid_event'});
      const hash = crypto.createHash('sha256').update(`${id}:${event.event_id}`).digest('hex');
      const file = path.join(config.dataDir, `${hash}.json`);
      // Two concurrent POST requests with the same id share the same delivery Promise.
      if (!locks.has(hash)) {
        const job = (async () => {
          let record = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file,'utf8')) : null;
          if (record && (record.event.type !== event.type || record.event.latitude !== event.latitude || record.event.longitude !== event.longitude)) {
            return {status:409,payload:{ok:false,error:'event_id_conflict'}};
          }
          if (!record) {
            record = {device_id:id,event,status:'pending',created_at:new Date().toISOString(),attempts:0};
            write(file, record);
          }
          if (record.status === 'delivered') return {status:200,payload:{ok:true,event_id:event.event_id,delivery:'delivered'}};
          record.attempts++;
          write(file,record);
          try {
            const telegramId = await sender(record.event);
            record.status = 'delivered';
            record.telegram_message_id = telegramId;
            record.delivered_at = new Date().toISOString();
            write(file,record);
            console.log('Delivered event', {event_id:event.event_id,device_id:id});
            return {status:200,payload:{ok:true,event_id:event.event_id,delivery:'delivered'}};
          } catch (err) {
            record.last_error = String(err.message || err).slice(0,140);
            write(file,record);
            console.warn('Telegram delivery failed', {event_id:event.event_id});
            return {status:503,payload:{ok:false,event_id:event.event_id,error:'delivery_not_confirmed'}};
          }
        })();
        locks.set(hash,job);
        job.finally(() => {if (locks.get(hash) === job) locks.delete(hash);});
      }
      const result = await locks.get(hash);
      return respond(res,result.status,result.payload);
    } catch (err) {
      console.error('Request error:', String(err));
      if (!res.headersSent) return respond(res,400,{ok:false,error:'invalid_request'});
    }
  }
  return http.createServer((req,res)=>{handle(req,res).catch(err=>{console.error('Unexpected:',err);if(!res.headersSent)respond(res,500,{ok:false,error:'server_error'});});});
}
module.exports = {createServer, validEvent, formatTelegram};
