// Browser integration smoke test. Uses installed Chrome and Node.js; no npm packages.
// Run: node tests/web/smoke.mjs
import {createServer} from 'node:http';
import {readFile, mkdtemp, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import {spawn} from 'node:child_process';
import assert from 'node:assert/strict';

const root = resolve(import.meta.dirname, '../../app/src/main/assets/web');
const profile = await mkdtemp(join(tmpdir(),'airbridge-web-test-'));
const chromeBinary = process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const errors = [];
let chrome, socket, port, upload = Buffer.alloc(0), paired = false, capture = false, statusAvailable = true, jpeg;
const files = [{id:'test-id',name:'<img src=x onerror=alert(1)>中文.txt',size:17,source:'phone',createdAt:Date.now()}];
const server = createServer(async (req,res) => {
  const url = new URL(req.url,'http://localhost');
  const send = (status,object) => {res.writeHead(status,{'Content-Type':'application/json'});res.end(JSON.stringify(object));};
  if (url.pathname === '/api/pair') {
    let body = ''; for await(const chunk of req) body += chunk;
    if(body !== '123456') return send(401,{error:'Invalid code'});
    paired = true; res.setHeader('Set-Cookie','session=test; HttpOnly; SameSite=Strict; Path=/'); return send(200,{ok:true});
  }
  if(url.pathname.startsWith('/api/') || url.pathname === '/stream.mjpeg') {
    if(!paired || !req.headers.cookie?.includes('session=test')) return send(401,{error:'Unauthorized'});
    if(url.pathname === '/api/status') return statusAvailable ? send(200,{deviceName:'Android 测试设备',capturing:capture,width:1080,height:2400,fps:12,maxUploadBytes:1073741824}) : send(503,{error:'Fixture network interruption'});
    if(url.pathname === '/api/files') return send(200,{files});
    if(url.pathname === '/api/upload') {
      assert.equal(req.headers['x-airbridge'],'1');
      const chunks=[]; for await(const chunk of req) chunks.push(chunk); upload=Buffer.concat(chunks);
      files.push({id:'uploaded',name:url.searchParams.get('name'),size:upload.length,source:'browser'});
      return send(201,{ok:true});
    }
    if(url.pathname === '/api/logout') {paired=false;return send(200,{ok:true});}
    if(url.pathname === '/stream.mjpeg') {
      // Continuous multipart JPEG fixture exercises the real MJPEG browser event lifecycle.
      res.writeHead(200,{'Content-Type':'multipart/x-mixed-replace; boundary=frame','Cache-Control':'no-store'});
      const writeFrame=()=>{res.write(`--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.length}\r\n\r\n`);res.write(jpeg);res.write('\r\n');};
      writeFrame();const interval=setInterval(writeFrame,250);res.on('close',()=>clearInterval(interval));return;
    }
    return send(404,{error:'Not found'});
  }
  const name=url.pathname==='/'?'index.html':url.pathname.slice(1);
  if(!['index.html','styles.css','app.js'].includes(name)){res.writeHead(404);return res.end();}
  res.setHeader('Content-Type',name.endsWith('.css')?'text/css':name.endsWith('.js')?'text/javascript':'text/html');
  res.setHeader('Content-Security-Policy',"default-src 'self'; img-src 'self' blob: data:; style-src 'self'; script-src 'self'; object-src 'none'");
  res.end(await readFile(join(root,name)));
});
await new Promise((resolve,reject) => {server.once('error',reject);server.listen(0,'127.0.0.1',resolve);});
port = server.address().port;
const pending = new Map(); let counter = 0;
function command(method,params={}) {
  const id=++counter;return new Promise((resolve,reject)=>{pending.set(id,{resolve,reject});socket.send(JSON.stringify({id,method,params}));});
}
async function evaluate(expression) {
  const result=await command('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});
  if(result.exceptionDetails)throw new Error(JSON.stringify(result.exceptionDetails));
  return result.result.value;
}
async function waitFor(expression,timeout=8000) {
  const start=Date.now();while(Date.now()-start<timeout){if(await evaluate(expression))return;await new Promise(resolve=>setTimeout(resolve,100));}throw new Error(`Timed out: ${expression}`);
}
try {
  chrome=spawn(chromeBinary,['--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check','--remote-debugging-port=0',`--user-data-dir=${profile}`,'about:blank'],{stdio:['ignore','ignore','pipe']});
  const endpoint=await new Promise((resolve,reject)=>{
    let output=''; const timeout=setTimeout(()=>reject(new Error(`Chrome startup timed out: ${output}`)),15000);
    chrome.once('error',reject);chrome.stderr.on('data',chunk=>{output+=chunk;const match=output.match(/DevTools listening on (ws:\/\/[^\s]+)/);if(match){clearTimeout(timeout);resolve(match[1]);}});
    chrome.once('exit',code=>{clearTimeout(timeout);reject(new Error(`Chrome exited ${code}: ${output}`));});
  });
  const endpointUrl=new URL(endpoint);
  const targets=await (await fetch(`http://${endpointUrl.host}/json/list`)).json();
  socket=new WebSocket(targets.find(target=>target.type==='page').webSocketDebuggerUrl);
  await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',reject,{once:true});});
  socket.addEventListener('message',event=>{
    const message=JSON.parse(event.data);
    if(message.id){const handler=pending.get(message.id);pending.delete(message.id);if(message.error)handler.reject(new Error(JSON.stringify(message.error)));else handler.resolve(message.result);}
    if(message.method==='Runtime.exceptionThrown')errors.push(message.params.exceptionDetails);
  });
  await command('Runtime.enable');await command('Page.enable');
  await command('Emulation.setDeviceMetricsOverride',{width:1440,height:1100,deviceScaleFactor:1,mobile:false});
  await command('Page.navigate',{url:`http://127.0.0.1:${port}/`});
  await waitFor(`document.getElementById('pair-form') && document.readyState === 'complete'`);
  assert.equal(await evaluate(`document.getElementById('workspace').inert`),true);
  const pairing=await command('Page.captureScreenshot',{format:'png'});await writeFile(join(tmpdir(),'airbridge-pairing.png'),Buffer.from(pairing.data,'base64'));
  await evaluate(`document.getElementById('pair-code').value='000000';document.getElementById('pair-form').requestSubmit()`);
  await waitFor(`document.getElementById('pair-error').textContent.includes('不正确')`);
  await evaluate(`document.getElementById('pair-code').value='123456';document.getElementById('pair-form').requestSubmit()`);
  await waitFor(`document.getElementById('pair-screen').hidden && document.querySelector('.file-name')`);
  assert.equal(await evaluate(`document.querySelector('.file-name').textContent`),files[0].name);
  assert.equal(await evaluate(`document.querySelector('.file-name img') === null`),true);
  assert.equal(await evaluate(`document.getElementById('device-name').textContent`),'Android 测试设备');
  assert.equal(await evaluate(`document.documentElement.scrollWidth <= innerWidth`),true);
  await evaluate(`{ const transfer=new DataTransfer();transfer.items.add(new File(['hello 中文 😀'],'demo-中文.txt'));const input=document.getElementById('file-input');input.files=transfer.files;input.dispatchEvent(new Event('change')); }`);
  await waitFor(`document.querySelector('.upload-item.complete') !== null`);
  assert.equal(upload.toString(),'hello 中文 😀');
  jpeg=Buffer.from(await evaluate(`(()=>{const canvas=document.createElement('canvas');canvas.width=180;canvas.height=400;const context=canvas.getContext('2d');context.fillStyle='#183d38';context.fillRect(0,0,180,400);context.fillStyle='#c9ee8a';context.font='16px sans-serif';context.textAlign='center';context.fillText('Android test frame',90,200);return canvas.toDataURL('image/jpeg').split(',')[1];})()`),'base64');
  capture=true;
  await evaluate(`document.getElementById('retry-button').click()`);
  await waitFor(`!document.getElementById('screen-image').hidden && document.getElementById('stage-message').hidden`);
  await evaluate(`document.getElementById('pause-button').click()`);
  assert.equal(await evaluate(`document.getElementById('stage-title').textContent`),'休息一下，随时继续');
  await evaluate(`document.getElementById('pause-button').click()`);
  await waitFor(`document.getElementById('stage-message').hidden`);
  statusAvailable=false;
  await evaluate(`document.getElementById('retry-button').click()`);
  await waitFor(`document.getElementById('connection-label').textContent === '连接中断'`);
  statusAvailable=true;
  await evaluate(`document.getElementById('retry-button').click()`);
  await waitFor(`document.getElementById('connection-label').textContent === '局域网已连接' && document.getElementById('stage-message').hidden`);
  const desktop=await command('Page.captureScreenshot',{format:'png'});await writeFile(join(tmpdir(),'airbridge-desktop.png'),Buffer.from(desktop.data,'base64'));
  await command('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});
  assert.equal(await evaluate(`document.documentElement.scrollWidth <= innerWidth`),true);
  const mobile=await command('Page.captureScreenshot',{format:'png',captureBeyondViewport:true});await writeFile(join(tmpdir(),'airbridge-mobile.png'),Buffer.from(mobile.data,'base64'));
  await evaluate(`document.getElementById('logout-button').click()`);
  await waitFor(`!document.getElementById('pair-screen').hidden`);
  assert.equal(await evaluate(`document.getElementById('workspace').inert`),true);
  assert.equal(errors.length,0,JSON.stringify(errors));
  console.log('PASS: pairing, auth error, XSS-safe filenames, binary upload, continuous MJPEG, pause/resume, disconnect/reconnect, desktop/mobile sizing, logout; zero JS exceptions.');
  console.log(`Screenshots: ${join(tmpdir(),'airbridge-desktop.png')}, ${join(tmpdir(),'airbridge-mobile.png')}`);
} finally {
  socket?.close();chrome?.kill('SIGTERM');server.closeAllConnections();await new Promise(resolve=>server.close(resolve));
  await new Promise(resolve=>setTimeout(resolve,500));await rm(profile,{recursive:true,force:true});
}
