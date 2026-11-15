'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {createServer, validEvent, formatTelegram} = require('../lib');
function event(id='c0a80101-1234-4abc-8abc-123456789abc') {return {event_id:id,type:'location',latitude:55.7558,longitude:37.6173,accuracy_m:12,captured_at:new Date().toISOString()};}
async function harness(sender) {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(),'lada-trip-'));
  const app = createServer({dataDir,deviceKeys:{car:'topsecret'}},sender);
  await new Promise(resolve => app.listen(0,'127.0.0.1',resolve));
  return {url:`http://127.0.0.1:${app.address().port}`,dataDir, close:()=>new Promise(resolve=>app.close(resolve))};
}
async function post(url, e, token='topsecret') {
  const r = await fetch(`${url}/v1/events`,{method:'POST',headers:{'Content-Type':'application/json','Authorization':`Bearer ${token}`,'X-Device-ID':'car'},body:JSON.stringify(e)});
  return {status:r.status,body:await r.json()};
}
test('authenticated ACK, duplicate idempotency and persisted audit',async()=>{
  let sent=0;
  const h=await harness(async()=>{sent++;return 42;});
  try {
    const e=event();
    assert.equal((await post(h.url,e)).status,200);
    assert.equal((await post(h.url,e)).body.delivery,'delivered');
    assert.equal(sent,1);
    const record=JSON.parse(fs.readFileSync(path.join(h.dataDir,fs.readdirSync(h.dataDir)[0])));
    assert.equal(record.status,'delivered');
    assert.equal(record.telegram_message_id,42);
    assert.equal((await post(h.url,e,'wrong')).status,401);
  } finally {await h.close();fs.rmSync(h.dataDir,{recursive:true,force:true});}
});
test('503 is not ACK: retry later succeeds with the same id',async()=>{
  let n=0;
  const h=await harness(async()=>{if(++n===1)throw Error('offline');return 99;});
  try {assert.equal((await post(h.url,event())).status,503);assert.equal((await post(h.url,event())).status,200);assert.equal(n,2);}
  finally{await h.close();fs.rmSync(h.dataDir,{recursive:true,force:true});}
});
test('concurrent duplicated events are delivered once',async()=>{
  let n=0;
  const h=await harness(async()=>{n++;await new Promise(r=>setTimeout(r,20));return 99;});
  try {const [a,b]=await Promise.all([post(h.url,event()),post(h.url,event())]);assert.equal(a.status,200);assert.equal(b.status,200);assert.equal(n,1);}
  finally{await h.close();fs.rmSync(h.dataDir,{recursive:true,force:true});}
});
test('validation, map and timestamps',()=>{
  assert.equal(validEvent({...event(),latitude:200}),false);
  assert.equal(validEvent(event()),true);
  assert.match(formatTelegram(event()),/google.com\/maps\?q=/);
  assert.match(formatTelegram(event()),/Точность/);
});
test('server restart retains delivered idempotency',async()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'lada-reboot-'));
  let total=0;
  const start=async()=>{const app=createServer({dataDir:dir,deviceKeys:{car:'topsecret'}},async()=>{total++;return 700;});await new Promise(r=>app.listen(0,'127.0.0.1',r));return app;};
  let app=await start();
  try {
    assert.equal((await post(`http://127.0.0.1:${app.address().port}`,event())).status,200);
    await new Promise(r=>app.close(r));
    app=await start();
    assert.equal((await post(`http://127.0.0.1:${app.address().port}`,event())).status,200);
    assert.equal(total,1);
  } finally {await new Promise(r=>app.close(r));fs.rmSync(dir,{recursive:true,force:true});}
});
test('same event id with changed GPS is rejected',async()=>{
  const h=await harness(async()=>100);
  try {
    assert.equal((await post(h.url,event())).status,200);
    assert.equal((await post(h.url,{...event(),longitude:30})).status,409);
  } finally{await h.close();fs.rmSync(h.dataDir,{recursive:true,force:true});}
});
