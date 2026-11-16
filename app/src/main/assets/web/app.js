(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const elements = Object.fromEntries(['pair-screen','workspace','pair-form','pair-code','pair-submit','pair-error','host-label','connection-dot','connection-label','device-name','live-badge','screen-stage','screen-image','stage-message','stage-title','stage-description','stage-caption','retry-button','resolution-label','fps-label','pause-button','pause-icon','snapshot-button','fullscreen-button','logout-button','file-input','drop-zone','upload-list','upload-limit','file-list','file-count','refresh-files','files-error','toast'].map(id => [id,$(id)]));
  let paired = false, paused = false, online = false, capturing = false, streamActive = false;
  let polling = false, filesLoading = false, timer = null, toastTimer = null, streamRetry = null, fileTick = 0;
  let uploadLimit = 1024 * 1024 * 1024, queue = [], activeUpload = null, currentFiles = '';
  let generation = 0;
  elements['host-label'].textContent = location.host;

  class ApiError extends Error { constructor(message, status) { super(message); this.status = status; } }
  async function request(path, options = {}) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 10000);
    try {
      const response = await fetch(path, {cache:'no-store',credentials:'same-origin',...options,signal:controller.signal});
      if (!response.ok) {
        let message = '';
        try { message = (await response.json()).error || ''; } catch (_) { /* Server may be restarting. */ }
        if (response.status === 401) throw new ApiError('配对已失效，请输入手机上的配对码。',401);
        if (response.status === 429) throw new ApiError('尝试次数过多，请稍候再连接。',429);
        throw new ApiError(message || `请求失败（${response.status}），请重试。`,response.status);
      }
      return response;
    } catch (error) {
      if (error instanceof ApiError) throw error;
      throw new ApiError('无法连接手机，请检查 Wi-Fi 和手机上的投屏状态。',0);
    } finally { clearTimeout(timeout); }
  }
  function toast(message) {
    clearTimeout(toastTimer); elements.toast.textContent = message; elements.toast.hidden = false;
    toastTimer = setTimeout(() => { elements.toast.hidden = true; },4500);
  }
  function icon(name) {
    const svg = document.createElementNS('http://www.w3.org/2000/svg','svg');
    svg.classList.add('icon'); svg.setAttribute('aria-hidden','true');
    const use = document.createElementNS('http://www.w3.org/2000/svg','use');
    use.setAttribute('href',`#i-${name}`); svg.append(use); return svg;
  }
  function sizeLabel(bytes) {
    if (!Number.isFinite(Number(bytes)) || Number(bytes) < 0) return '未知大小';
    const size = Number(bytes);
    if (size < 1024) return `${size} B`;
    const units = ['KB','MB','GB']; let value = size / 1024, unit = 0;
    while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
    return `${value >= 100 ? value.toFixed(0) : value.toFixed(1).replace(/\.0$/,'')} ${units[unit]}`;
  }
  function stopStream() {
    streamActive = false; clearTimeout(streamRetry); streamRetry = null;
    elements['screen-image'].removeAttribute('src'); elements['screen-image'].hidden = true;
    elements['stage-caption'].hidden = true;
  }
  function stageMessage(title, description, retry = false) {
    elements['stage-title'].textContent = title; elements['stage-description'].textContent = description;
    elements['stage-message'].hidden = false; elements['retry-button'].hidden = !retry;
  }
  function updateStage() {
    elements['pause-button'].disabled = !online || !capturing;
    elements['snapshot-button'].disabled = !online || !capturing;
    elements['pause-icon'].setAttribute('href', paused ? '#i-play' : '#i-pause');
    elements['pause-button'].title = paused ? '继续观看' : '暂停观看';
    elements['pause-button'].setAttribute('aria-label',elements['pause-button'].title);
    elements['live-badge'].classList.toggle('active', online && capturing && !paused);
    if (!online) {
      stopStream(); elements['live-badge'].textContent = '连接中断';
      stageMessage('暂时与手机失联','保持手机与电脑在同一网络，重新开启手机 App 后将自动重连。',true);
    } else if (!capturing) {
      stopStream(); elements['live-badge'].textContent = '等待投屏';
      stageMessage('等待手机画面','在手机上点击“开始投屏”，并允许共享屏幕。');
    } else if (paused) {
      stopStream(); elements['live-badge'].textContent = '观看已暂停';
      stageMessage('休息一下，随时继续','点击下方播放按钮继续观看。手机端投屏和文件传输仍在运行。');
    } else if (document.hidden) {
      stopStream();
    } else if (!streamActive && !streamRetry) {
      streamActive = true; elements['live-badge'].textContent = '正在投屏';
      stageMessage('正在接收画面','第一帧很快就到。');
      elements['screen-image'].hidden = false;
      elements['screen-image'].src = `/stream.mjpeg?t=${Date.now()}`;
    }
  }
  elements['screen-image'].addEventListener('load',() => {
    if (!streamActive) return;
    elements['stage-message'].hidden = true; elements['stage-caption'].hidden = false;
  });
  elements['screen-image'].addEventListener('error',() => {
    if (!streamActive) return;
    stopStream(); stageMessage('画面连接正在恢复','手机仍可传输文件；投屏画面将自动重试。',true);
    elements['live-badge'].textContent = '画面重连中';
    streamRetry = setTimeout(() => { streamRetry = null; updateStage(); },3000);
  });
  function setPaired(value, message = '') {
    if (paired !== value) generation++;
    paired = value;
    elements['pair-screen'].hidden = value;
    elements.workspace.inert = !value; elements.workspace.setAttribute('aria-hidden',String(!value));
    if (!value) {
      online = false; capturing = false; paused = false; stopStream();
      queue.forEach(item => { if (item.state === 'queued' || item.state === 'sending') cancelUpload(item); });
      elements['pair-error'].textContent = message;
      elements['pair-code'].value = ''; currentFiles = ''; fileTick = 0;
      elements['device-name'].textContent = 'Android 设备';
      elements['file-list'].replaceChildren(); elements['file-count'].textContent = '0';
      queue = []; elements['upload-list'].replaceChildren();
    }
  }
  function applyStatus(status) {
    online = true; capturing = status.capturing === true;
    elements['connection-dot'].className = 'status-dot online'; elements['connection-label'].textContent = '局域网已连接';
    elements['device-name'].textContent = status.deviceName || 'Android 设备';
    elements['resolution-label'].textContent = status.width && status.height ? `${status.width} × ${status.height}` : '— × —';
    elements['fps-label'].textContent = capturing ? `目标 ${Number(status.fps) || 10} FPS` : '等待投屏';
    const limit = Number(status.maxUploadBytes); if (limit > 0) uploadLimit = limit;
    elements['upload-limit'].textContent = `单个文件最大 ${sizeLabel(uploadLimit)}`;
    updateStage();
  }
  async function poll() {
    if (polling || document.hidden) return;
    polling = true;
    const requestedGeneration = generation;
    try {
      const response = await request('/api/status'); const status = await response.json();
      if (requestedGeneration !== generation) return;
      if (!paired) setPaired(true);
      applyStatus(status);
      if (fileTick++ % 3 === 0) await refreshFiles();
    } catch (error) {
      if (requestedGeneration !== generation) return;
      if (error.status === 401) { if (paired) setPaired(false,'配对已失效，请重新连接。'); }
      else if (paired) {
        online = false; elements['connection-dot'].className = 'status-dot offline';
        elements['connection-label'].textContent = '连接中断'; updateStage();
      } else { elements['pair-error'].textContent = '请在手机上开启投屏，再确认当前地址和网络连接。'; }
    } finally { polling = false; schedulePoll(); }
  }
  function schedulePoll(delay = 2500) { clearTimeout(timer); if (!document.hidden && paired) timer = setTimeout(poll,delay); }
  elements['pair-code'].addEventListener('input',() => { elements['pair-code'].value = elements['pair-code'].value.replace(/[^0-9]/g,'').slice(0,6); elements['pair-error'].textContent = ''; });
  elements['pair-form'].addEventListener('submit',async event => {
    event.preventDefault();
    const code = elements['pair-code'].value;
    if (!/^\d{6}$/.test(code)) { elements['pair-error'].textContent = '请输入完整的 6 位数字配对码。'; return; }
    elements['pair-submit'].disabled = true; elements['pair-submit'].querySelector('span').textContent = '正在连接…'; elements['pair-error'].textContent = '';
    try {
      await request('/api/pair',{method:'POST',headers:{'Content-Type':'text/plain','X-AirBridge':'1'},body:code});
      setPaired(true); await poll(); elements['pause-button'].focus({preventScroll:true});
    } catch (error) { elements['pair-error'].textContent = error.status === 401 ? '配对码不正确，请查看手机上显示的数字。' : error.message; }
    finally { elements['pair-submit'].disabled = false; elements['pair-submit'].querySelector('span').textContent = '连接我的手机'; }
  });
  elements['logout-button'].addEventListener('click',async () => {
    elements['logout-button'].disabled = true;
    try { await request('/api/logout',{method:'POST',headers:{'X-AirBridge':'1'}}); clearTimeout(timer); setPaired(false); elements['pair-code'].focus(); }
    catch(error) { if(error.status === 401) setPaired(false); else toast(error.message); }
    finally { elements['logout-button'].disabled = false; }
  });
  elements['pause-button'].addEventListener('click',() => { paused = !paused; updateStage(); });
  elements['retry-button'].addEventListener('click',() => { stopStream(); updateStage(); poll(); });
  elements['fullscreen-button'].addEventListener('click',async () => {
    try {
      if (document.fullscreenElement) await document.exitFullscreen();
      else if (elements['screen-stage'].requestFullscreen) await elements['screen-stage'].requestFullscreen();
      else toast('当前浏览器不支持全屏，可尝试浏览器菜单中的全屏模式。');
    } catch (_) { toast('无法进入全屏，请使用浏览器的全屏功能。'); }
  });
  elements['snapshot-button'].addEventListener('click',async () => {
    elements['snapshot-button'].disabled = true;
    try {
      const response = await request('/api/snapshot.jpg'); const blob = await response.blob();
      const url = URL.createObjectURL(blob); const a = document.createElement('a');
      a.href = url; a.download = `AirBridge-${new Date().toISOString().replace(/[:.]/g,'-')}.jpg`; document.body.append(a); a.click(); a.remove();
      setTimeout(() => URL.revokeObjectURL(url),30000); toast('截图已发送到浏览器下载。');
    } catch (error) { if(error.status === 401) setPaired(false); else toast(error.message); }
    finally { elements['snapshot-button'].disabled = !online || !capturing; }
  });
  function renderFiles(files) {
    const signature = JSON.stringify(files); if(signature === currentFiles) return; currentFiles = signature;
    elements['file-count'].textContent = String(files.length); const fragment = document.createDocumentFragment();
    if (!files.length) {
      const empty = document.createElement('div'); empty.className = 'files-empty'; empty.append(icon('file'));
      const p = document.createElement('p'); p.textContent = '还没有共享文件'; const small = document.createElement('small'); small.textContent = '在手机上选择文件，即可在这里下载'; empty.append(p,small); fragment.append(empty);
    }
    for (const file of files) {
      const row = document.createElement('div'); row.className = 'file-row';
      const emblem = document.createElement('span'); emblem.className = 'file-type-icon'; emblem.append(icon('file'));
      const info = document.createElement('div'); info.className = 'file-info';
      const name = document.createElement('span'); name.className = 'file-name'; name.textContent = file.name; name.title = file.name;
      const meta = document.createElement('span'); meta.className = 'file-meta'; meta.textContent = `${sizeLabel(file.size)} · ${file.source === 'phone' ? '来自手机' : '来自浏览器'}`; info.append(name,meta);
      const link = document.createElement('a'); link.className = 'icon-button'; link.href = `/api/files/${encodeURIComponent(file.id)}`; link.download = file.name; link.title = `下载 ${file.name}`; link.setAttribute('aria-label',link.title); link.append(icon('download'));
      row.append(emblem,info,link); fragment.append(row);
    }
    elements['file-list'].replaceChildren(fragment);
  }
  async function refreshFiles() {
    if(filesLoading || !paired) return; filesLoading = true; const requestedGeneration = generation;
    elements['refresh-files'].disabled = true;
    try {
      const response = await request('/api/files'); const data = await response.json();
      if(requestedGeneration !== generation) return;
      renderFiles(Array.isArray(data.files) ? data.files : []); elements['files-error'].textContent = '';
    } catch(error) { if(requestedGeneration !== generation) return; if(error.status === 401) setPaired(false); else elements['files-error'].textContent = '文件列表暂时无法更新，请稍后刷新。'; }
    finally { filesLoading = false; elements['refresh-files'].disabled = false; }
  }
  elements['refresh-files'].addEventListener('click',refreshFiles);
  function cancelUpload(item) {
    if(item.state !== 'queued' && item.state !== 'sending') return;
    item.state = 'cancelled'; item.status.textContent = '已取消'; item.cancel.disabled = true;
    if(item.xhr) item.xhr.abort();
    item.file = null;
  }
  function addFiles(files) {
    if(!paired) return;
    for(const file of Array.from(files)) {
      if(file.size > uploadLimit) { toast(`“${file.name}”超过 ${sizeLabel(uploadLimit)}，请换一个较小的文件。`); continue; }
      if(queue.filter(item => item.state === 'queued' || item.state === 'sending').length >= 50) { toast('一次最多排队 50 个文件，请等待当前传输完成。'); break; }
      const row = document.createElement('div'); row.className = 'upload-item';
      const top = document.createElement('div'); top.className = 'upload-item-top';
      const name = document.createElement('span'); name.className = 'upload-item-name'; name.textContent = file.name; name.title = file.name;
      const cancel = document.createElement('button'); cancel.className = 'icon-button'; cancel.type = 'button'; cancel.title = `取消发送 ${file.name}`; cancel.setAttribute('aria-label',cancel.title); cancel.append(icon('close')); top.append(name,cancel);
      const progress = document.createElement('progress'); progress.className = 'upload-progress'; progress.max = 100; progress.value = 0; progress.setAttribute('aria-label',`${file.name} 传输进度`);
      const status = document.createElement('span'); status.className = 'upload-item-status'; status.textContent = `${sizeLabel(file.size)} · 等待发送`;
      row.append(top,progress,status); elements['upload-list'].append(row);
      const item = {file,row,cancel,progress,status,state:'queued',xhr:null}; cancel.addEventListener('click',() => cancelUpload(item)); queue.push(item);
    }
    trimQueue(); sendNext();
  }
  function trimQueue() {
    const completed = queue.filter(item => !['queued','sending'].includes(item.state));
    for(const item of completed.slice(0,Math.max(0,completed.length-5))) { item.row.remove(); queue = queue.filter(candidate => candidate !== item); }
  }
  function sendNext() {
    if(activeUpload || !paired) return;
    const item = queue.find(candidate => candidate.state === 'queued'); if(!item) return;
    activeUpload = item; item.state = 'sending'; item.status.textContent = '正在发送…';
    const xhr = new XMLHttpRequest(); item.xhr = xhr;
    xhr.open('POST',`/api/upload?name=${encodeURIComponent(item.file.name)}`); xhr.setRequestHeader('X-AirBridge','1'); xhr.setRequestHeader('Content-Type','application/octet-stream'); xhr.timeout = 30 * 60 * 1000;
    xhr.upload.addEventListener('progress',event => {
      if(item.state !== 'sending') return;
      if(event.lengthComputable) {
        const percent = Math.round(event.loaded/event.total*100); item.progress.value = percent;
        item.status.textContent = percent === 100 ? '手机正在保存文件…' : `${sizeLabel(event.loaded)} / ${sizeLabel(event.total)} · ${percent}%`;
      }
    });
    const fail = message => { if(item.state === 'cancelled') return; item.state = 'error'; item.row.classList.add('error'); item.status.textContent = message; };
    xhr.addEventListener('load',() => {
      if(item.state === 'cancelled') return;
      if(xhr.status >= 200 && xhr.status < 300) {
        item.state = 'complete'; item.row.classList.add('complete'); item.progress.value = 100; item.status.textContent = '已送达手机'; refreshFiles();
      } else if(xhr.status === 401) { fail('配对已失效，请重新连接后发送。'); setPaired(false,'配对已失效，请重新连接。'); }
      else { let message = '传输失败，请重新选择文件重试。'; try { message = JSON.parse(xhr.responseText).error || message; } catch(_) {} fail(message); }
    });
    xhr.addEventListener('error',() => fail('连接中断，请检查网络后重新发送。'));
    xhr.addEventListener('timeout',() => fail('传输超时，请重新发送。'));
    xhr.addEventListener('abort',() => { if(item.state !== 'cancelled') fail('传输已中断。'); });
    xhr.addEventListener('loadend',() => { item.cancel.disabled = true; item.file = null; item.xhr = null; activeUpload = null; trimQueue(); sendNext(); });
    xhr.send(item.file);
  }
  elements['file-input'].addEventListener('change',event => { addFiles(event.target.files); event.target.value = ''; });
  elements['drop-zone'].addEventListener('keydown',event => { if(event.key === 'Enter' || event.key === ' ') { event.preventDefault(); elements['file-input'].click(); } });
  let dragDepth = 0;
  for(const type of ['dragenter','dragover','dragleave','drop']) elements['drop-zone'].addEventListener(type,event => {
    event.preventDefault(); event.stopPropagation();
    if(type === 'dragenter') { dragDepth++; elements['drop-zone'].classList.add('dragging'); }
    if(type === 'dragover') event.dataTransfer.dropEffect = 'copy';
    if(type === 'dragleave') { dragDepth--; if(dragDepth <= 0) elements['drop-zone'].classList.remove('dragging'); }
    if(type === 'drop') { dragDepth = 0; elements['drop-zone'].classList.remove('dragging'); addFiles(event.dataTransfer.files); }
  });
  window.addEventListener('dragover',event => event.preventDefault());
  window.addEventListener('drop',event => event.preventDefault());
  document.addEventListener('visibilitychange',() => {
    if(document.hidden) { clearTimeout(timer); stopStream(); }
    else { updateStage(); poll(); }
  });
  window.addEventListener('pagehide',() => { clearTimeout(timer); stopStream(); queue.forEach(item => cancelUpload(item)); });
  window.addEventListener('pageshow',event => { if(event.persisted) poll(); });
  window.addEventListener('online',() => poll());
  poll();
})();
