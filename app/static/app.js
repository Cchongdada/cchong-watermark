const $ = id => document.getElementById(id);
const fileInput = $('files'), dropzone = $('dropzone'), list = $('list');
const defaults = {overall: 1, histogram_width: 1, histogram_height: 1, metadata: 1, signature: 1, x: .5, y: .83, film_mode: false, film_stock: "", film_iso: "", film_camera: "Pentax Espio 125M"};
const fields = [
  ['overall', '整体大小', .5, 1.6, .05],
  ['histogram_width', '直方图宽度', .5, 1.5, .05],
  ['histogram_height', '直方图高度', .5, 1.8, .05],
  ['metadata', '参数文字大小', .6, 1.6, .05],
  ['signature', '署名大小', .6, 1.6, .05],
  ['x', '左右位置', 0, 1, .01],
  ['y', '上下位置', 0, 1, .01]
];
const allowed = /\.(jpe?g|png|webp|heic|heif|avif)$/i;
const preferenceKey = 'cchong-watermark-settings-v1';
let globalSettings = {...defaults}, globalColor = 'auto', selected = [], previewFile = null;
let scope = 'all', busy = false, previewTimer, previewController, previewUrl, displayedFile = null;
let originalFile = null, originalUrl = null, comparePressed = false, motionAvailable = false;
let motionFile = null, motionVideoUrl = null, motionOverlayUrl = null, motionLoading = false;
let motionGeneration = 0;
const rotations = new Map();
const overrides = new Map(), failedFiles = new Set();
const filmBrands = new Map([["Kodak","柯达"],["Fujifilm","富士"],["Ilford","伊尔福"]]);
let recentFilms = [];
try { recentFilms = JSON.parse(localStorage.getItem("cchong-recent-films") || "[]").filter(s=>typeof s==="string").slice(0,6); } catch (_) {}
const filmKinds = {color:"彩色负片",bw:"黑白负片",slide:"反转片",cine:"电影卷",special:"特殊效果"};
function filmSearchKey(value) { return value.toLowerCase().replace(/[\s!\u2019\u0027()\-]/g, ""); }

try {
  const saved = JSON.parse(localStorage.getItem(preferenceKey) || '{}');
  for (const [key, , min, max] of fields) {
    const value = saved.settings?.[key];
    if (typeof value === 'number' && Number.isFinite(value) && value >= min && value <= max) globalSettings[key] = value;
  }
  for (const key of ['film_stock', 'film_iso', 'film_camera']) if (typeof saved.settings?.[key] === 'string') globalSettings[key] = saved.settings[key].slice(0, 80);
  globalSettings.film_mode = saved.settings?.film_mode === true;
  if (['auto', 'white', 'black'].includes(saved.color)) globalColor = saved.color;
} catch (_) { /* Storage may be disabled in private browsing. */ }
function rememberGlobal() {
  try { localStorage.setItem(preferenceKey, JSON.stringify({settings: globalSettings, color: globalColor})); }
  catch (_) { /* Editing still works in this tab. */ }
}
function effective(file) { const base = overrides.get(file) || {settings: globalSettings, color: globalColor}; return {settings: {...base.settings, rotation: rotations.get(file) || 0}, color: base.color}; }
function editing() { return scope === 'one' && previewFile ? effective(previewFile) : {settings: globalSettings, color: globalColor}; }
function editSetting(key, value) {
  if (scope === 'one' && previewFile) {
    const previous = effective(previewFile);
    overrides.set(previewFile, {settings: {...previous.settings, [key]: value}, color: previous.color});
  } else { globalSettings[key] = value; rememberGlobal(); }
  render(); syncControls(); schedulePreview();
}
function editColor(value) {
  if (scope === 'one' && previewFile) {
    const previous = effective(previewFile);
    overrides.set(previewFile, {settings: {...previous.settings}, color: value});
  } else { globalColor = value; rememberGlobal(); }
  render(); syncControls(); schedulePreview();
}

for (const [key, label, min, max, step] of fields) {
  const row = document.createElement('div'); row.className = 'control';
  const title = document.createElement('div'); title.className = 'control-title';
  const name = document.createElement('label'); name.htmlFor = 'control-' + key; name.textContent = label;
  const value = document.createElement('output'); value.id = 'value-' + key;
  title.append(name, value);
  const slider = document.createElement('input'); slider.type = 'range'; slider.id = 'control-' + key;
  slider.min = min; slider.max = max; slider.step = step;
  slider.oninput = () => editSetting(key, Number(slider.value));
  row.append(title, slider); $('controls').append(row);
}
function syncControls() {
  const current = editing();
  document.querySelector(`input[name="photo-mode"][value="${current.settings.film_mode ? 'film' : 'digital'}"]`).checked = true;
  $('film-fields').hidden = !current.settings.film_mode;
  const parts = splitFilmStock(current.settings.film_stock || '');
  $('film-brand').value = parts.brand; $('film-stock').value = parts.model;
  $('film-brand-note').textContent = filmBrands.get(parts.brand) || '';
  $('film-iso').value = current.settings.film_iso || ''; $('film-camera').value = current.settings.film_camera ?? defaults.film_camera;
  for (const [key] of fields) {
    $('control-' + key).value = current.settings[key];
    $('value-' + key).textContent = Math.round(current.settings[key] * 100) + '%';
  }
  document.querySelector(`input[name=color][value=${current.color}]`).checked = true;
  $('scope-all').classList.toggle('active', scope === 'all');
  $('scope-one').classList.toggle('active', scope === 'one');
  $('scope-one').disabled = busy || !previewFile;
  $('inherit').hidden = !(scope === 'one' && overrides.has(previewFile));
  const index = selected.indexOf(previewFile);
  $('preview-index').textContent = index < 0 ? '尚未选择照片' : `${index + 1} / ${selected.length}`;
  $('preview-prev').disabled = busy || index <= 0;
  $('preview-next').disabled = busy || index < 0 || index >= selected.length - 1;
  for (const id of ['rotate-left','rotate-right','auto-upright','rotation-reset','batch-upright','export-upright']) $(id).disabled = busy || !previewFile;
  $('action-count').textContent = selected.length ? `已选 ${selected.length} 张` : '先添加照片';
  renderRecentFilms();
  $('scope-note').textContent = scope === 'one'
    ? `只调整 ${previewFile?.name || '当前照片'}；其他照片继续使用整批设置`
    : overrides.has(previewFile) ? '此照片已有单独设置，预览显示它的实际导出效果' : '调整后应用到没有单独设置的照片';
}
function setStatus(message, error = false) { $('status').textContent = message; $('status').classList.toggle('error', error); }
function formatSize(bytes) { return bytes >= 1048576 ? (bytes / 1048576).toFixed(1) + ' MB' : Math.ceil(bytes / 1024) + ' KB'; }
function render() {
  if (!selected.includes(previewFile)) previewFile = selected[0] || null;
  if (!previewFile) scope = 'all';
  list.replaceChildren(); $('count').textContent = selected.length;
  $('empty').hidden = selected.length > 0;
  $('clear').disabled = busy || !selected.length;
  $('process').disabled = busy || !selected.length;
  $('retry').hidden = !failedFiles.size;
  $('retry').disabled = busy;
  $('preview-name').textContent = previewFile ? previewFile.name : '选择照片后显示';
  for (const [index, file] of selected.entries()) {
    const row = document.createElement('div'); row.className = 'file' + (file === previewFile ? ' preview-selected' : '');
    row.tabIndex = 0; row.setAttribute('role', 'button'); row.setAttribute('aria-label', '预览 ' + file.name);
    row.onclick = () => { if (busy) return; previewFile = file; render(); syncControls(); schedulePreview(); };
    row.onkeydown = event => { if ((event.key === 'Enter' || event.key === ' ') && event.target === row) { event.preventDefault(); row.click(); } };
    const icon = document.createElement('div'); icon.className = 'file-icon'; icon.textContent = file.name.split('.').pop().toUpperCase();
    const meta = document.createElement('div'); meta.className = 'file-text';
    const name = document.createElement('strong'); name.textContent = file.name;
    const size = document.createElement('span'); size.textContent = formatSize(file.size);
    meta.append(name, size);
    if (overrides.has(file)) { const badge = document.createElement('small'); badge.textContent = '单独调整'; meta.append(badge); }
    if (failedFiles.has(file)) { const badge = document.createElement('small'); badge.className = 'failed-badge'; badge.textContent = '处理失败'; meta.append(badge); }
    const remove = document.createElement('button'); remove.type = 'button'; remove.textContent = '×'; remove.setAttribute('aria-label', '移除 ' + file.name);
    remove.disabled = busy; remove.onclick = event => {
      event.stopPropagation(); selected.splice(index, 1); overrides.delete(file); rotations.delete(file); failedFiles.delete(file);
      if (originalFile === file) clearOriginal(); if (motionFile === file) clearMotion();
      render(); syncControls(); schedulePreview();
    };
    row.append(icon, meta, remove); list.append(row);
  }
  syncControls();
  document.querySelectorAll('.settings input, .settings button').forEach(input=>input.disabled = busy || (input.id === 'scope-one' && !previewFile));
}
function addFiles(files) {
  const incoming = [...files].filter(file => allowed.test(file.name));
  if (!incoming.length) { setStatus('请选择 JPG、PNG、WebP、HEIC 或 AVIF 照片。', true); return; }
  const seen = new Set(selected.map(file => file.name + ':' + file.size + ':' + file.lastModified));
  for (const file of incoming) {
    const key = file.name + ':' + file.size + ':' + file.lastModified;
    if (!seen.has(key) && selected.length < 100) { selected.push(file); seen.add(key); }
  }
  setStatus(selected.length === 100 ? '每次最多处理 100 张。' : ''); render(); schedulePreview();
}
dropzone.onclick = () => { if (!busy) fileInput.click(); };
dropzone.onkeydown = event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); if (!busy) fileInput.click(); } };
fileInput.onchange = () => { addFiles(fileInput.files); fileInput.value = ''; };
for (const eventName of ['dragenter', 'dragover']) dropzone.addEventListener(eventName, event => { event.preventDefault(); dropzone.classList.add('drag'); });
for (const eventName of ['dragleave', 'drop']) dropzone.addEventListener(eventName, event => { event.preventDefault(); dropzone.classList.remove('drag'); });
dropzone.addEventListener('drop', event => { if (!busy) addFiles(event.dataTransfer.files); });
$('clear').onclick = () => {
  selected = []; rotations.clear(); overrides.clear(); failedFiles.clear(); clearOriginal(); clearMotion();
  render(); schedulePreview(); setStatus('');
};
$('scope-all').onclick = () => { scope = 'all'; syncControls(); };
$('scope-one').onclick = () => { if (previewFile) { scope = 'one'; syncControls(); } };
$('reset').onclick = () => {
  if (scope === 'one' && previewFile) overrides.set(previewFile, {settings: {...defaults}, color: 'auto'});
  else { globalSettings = {...defaults}; globalColor = 'auto'; rememberGlobal(); }
  render(); schedulePreview();
};
document.querySelectorAll('input[name=color]').forEach(input => input.onchange = () => editColor(input.value));

function clearOriginal() {
  if (originalUrl) URL.revokeObjectURL(originalUrl);
  originalUrl = null; originalFile = null; comparePressed = false;
  $('compare').disabled = true;
}
function clearMotion() {
  const video = $('motion-video'); video.pause(); video.hidden = true; video.removeAttribute('src'); video.load();
  $('motion-overlay').hidden = true;
  if (motionVideoUrl) URL.revokeObjectURL(motionVideoUrl);
  if (motionOverlayUrl) URL.revokeObjectURL(motionOverlayUrl);
  motionVideoUrl = motionOverlayUrl = null; motionFile = null; motionLoading = false;
  $('motion-play').textContent = '播放动态照片';
}
function hideMotion() {
  $('motion-video').pause(); $('motion-video').hidden = true; $('motion-overlay').hidden = true;
  $('preview-image').hidden = !previewUrl;
  $('motion-play').textContent = '播放动态照片';
}
function schedulePreview() {
  motionGeneration++;
  clearTimeout(previewTimer);
  if (previewController) previewController.abort();
  previewController = null;
  hideMotion();
  if (!previewFile) {
    $('preview-stage').classList.remove('loading');
    $('preview-image').hidden = true; $('preview-placeholder').hidden = false; displayedFile = null;
    if (previewUrl) { URL.revokeObjectURL(previewUrl); previewUrl = null; }
    $('motion-play').hidden = true; $('compare').disabled = true;
    return;
  }
  if (previewFile !== displayedFile) {
    $('preview-image').hidden = true;
    if (previewUrl) { URL.revokeObjectURL(previewUrl); previewUrl = null; }
    displayedFile = null; motionAvailable = false; $('motion-play').hidden = true;
    if (originalFile !== previewFile) clearOriginal();
    if (motionFile !== previewFile) clearMotion();
  }
  $('compare').disabled = true;
  $('preview-stage').classList.add('loading');
  $('preview-placeholder').textContent = '正在生成预览…';
  if ($('preview-image').hidden) $('preview-placeholder').hidden = false;
  previewTimer = setTimeout(updatePreview, 250);
}
async function updatePreview() {
  const file = previewFile, controller = new AbortController(); previewController = controller;
  const chosen = effective(file);
  const form = new FormData(); form.append('file', file, file.name);
  form.append('color', chosen.color); form.append('settings', JSON.stringify(chosen.settings));
  try {
    const response = await fetch('/api/preview', {method: 'POST', body: form, signal: controller.signal});
    if (!response.ok) throw new Error('预览生成失败');
    const motion = response.headers.get('X-Motion-Photo') === '1';
    const url = URL.createObjectURL(await response.blob());
    if (controller.signal.aborted || file !== previewFile) { URL.revokeObjectURL(url); return; }
    const old = previewUrl; previewUrl = url; displayedFile = file; motionAvailable = motion;
    $('preview-image').src = url; $('preview-image').hidden = false; $('preview-placeholder').hidden = true;
    $('motion-play').hidden = !motion; $('compare').disabled = false;
    if (old) URL.revokeObjectURL(old);
  } catch (error) {
    if (error.name !== 'AbortError') {
      $('preview-placeholder').textContent = '这张照片暂时无法预览，仍可尝试处理。';
      $('preview-placeholder').hidden = false; $('preview-image').hidden = true;
      if (previewUrl) { URL.revokeObjectURL(previewUrl); previewUrl = null; }
      displayedFile = null; $('compare').disabled = true;
    }
  } finally { if (previewController === controller) $('preview-stage').classList.remove('loading'); }
}
async function showOriginal() {
  if (!previewFile || !previewUrl) return;
  comparePressed = true;
  const file = previewFile;
  if (originalFile !== file || !originalUrl) {
    const form = new FormData(); form.append('file', file, file.name); form.append('original', 'true'); form.append('settings', JSON.stringify(effective(file).settings));
    try {
      const response = await fetch('/api/preview', {method: 'POST', body: form});
      if (!response.ok) throw new Error('无法读取原图');
      const url = URL.createObjectURL(await response.blob());
      if (file !== previewFile || !comparePressed) { URL.revokeObjectURL(url); return; }
      clearOriginal(); originalUrl = url; originalFile = file; comparePressed = true;
    } catch (error) { setStatus(error.message, true); comparePressed = false; return; }
  }
  if (comparePressed && originalUrl) $('preview-image').src = originalUrl;
}
$('compare').onpointerdown = event => { event.preventDefault(); $('compare').setPointerCapture(event.pointerId); showOriginal(); };
function restorePreview() { comparePressed = false; if (previewUrl) $('preview-image').src = previewUrl; }
$('compare').onpointerup = restorePreview;
$('compare').onpointercancel = restorePreview;
$('compare').onlostpointercapture = restorePreview;

async function motionPart(file, part, chosen) {
  const form = new FormData(); form.append('file', file, file.name); form.append('part', part);
  form.append('color', chosen.color); form.append('settings', JSON.stringify(chosen.settings));
  const response = await fetch('/api/motion-preview', {method: 'POST', body: form});
  if (!response.ok) throw new Error('动态照片播放预览失败');
  return URL.createObjectURL(await response.blob());
}
function positionMotionOverlay() {
  const stage = $('preview-stage').getBoundingClientRect(), video = $('motion-video').getBoundingClientRect();
  const overlay = $('motion-overlay');
  overlay.style.left = (video.left - stage.left) + 'px'; overlay.style.top = (video.top - stage.top) + 'px';
  overlay.style.width = video.width + 'px'; overlay.style.height = video.height + 'px';
}
$('motion-play').onclick = async () => {
  if (!previewFile || !motionAvailable || motionLoading) return;
  if (!$('motion-video').hidden) { hideMotion(); return; }
  const file = previewFile, chosen = effective(file), generation = ++motionGeneration; motionLoading = true;
  try {
    if (motionFile !== file || !motionVideoUrl) {
      clearMotion(); motionLoading = true;
      const videoUrl = await motionPart(file, 'video', chosen);
      if (generation !== motionGeneration || file !== previewFile) { URL.revokeObjectURL(videoUrl); return; }
      motionVideoUrl = videoUrl; motionFile = file;
    }
    if (generation !== motionGeneration || file !== previewFile) return;
    const overlayUrl = await motionPart(file, 'overlay', chosen);
    if (generation !== motionGeneration || file !== previewFile) { URL.revokeObjectURL(overlayUrl); return; }
    if (motionOverlayUrl) URL.revokeObjectURL(motionOverlayUrl);
    motionOverlayUrl = overlayUrl;
    const video = $('motion-video'), overlay = $('motion-overlay');
    video.src = motionVideoUrl; overlay.src = motionOverlayUrl;
    video.hidden = false; overlay.hidden = false; $('preview-image').hidden = true;
    $('motion-play').textContent = '停止播放'; positionMotionOverlay();
    video.onloadedmetadata = positionMotionOverlay;
    video.play().catch(() => setStatus('点击视频上的播放按钮即可查看动态效果。'));
  } catch (error) { if (generation === motionGeneration) { hideMotion(); setStatus(error.message, true); } }
  finally { if (generation === motionGeneration) motionLoading = false; }
};
window.addEventListener('resize', positionMotionOverlay);

let retryWithoutWatermark = false;
async function processFiles(files, retry = false, noWatermark = false) {
  if (!files.length || busy) return;
  retryWithoutWatermark = noWatermark;
  const jobs = files.map(file => ({file, chosen: {settings: {...effective(file).settings, no_watermark: noWatermark}, color: effective(file).color}}));
  let succeeded = 0, failed = 0;
  for (const job of jobs) if(job.chosen.settings.film_mode) rememberFilm(job.chosen.settings.film_stock || "");
  busy = true; render(); $('process-label').textContent = '正在处理'; $('process-arrow').classList.add('spinner');
  $('progress').hidden = false; $('progress').classList.toggle('determinate', jobs.length > 1);
  $('progress-fill').style.width = jobs.length > 1 ? '0%' : '';
  if (retry) $('downloads').querySelectorAll('.download-error').forEach(row => row.remove());
  else $('downloads').replaceChildren();
  for (const [index, job] of jobs.entries()) {
    const {file, chosen} = job;
    failedFiles.delete(file); setStatus(`正在处理 ${index + 1}/${jobs.length}：${file.name}`);
    const form = new FormData(); form.append('files', file, file.name);
    form.append('color', chosen.color); form.append('settings', JSON.stringify(chosen.settings));
    try {
      const response = await fetch('/api/process', {method: 'POST', body: form});
      if (!response.ok) throw new Error('服务返回 ' + response.status);
      const result = await response.json();
      for (const item of result.items) {
        succeeded++;
        const link = document.createElement('a'); link.href = item.url; link.download = item.name; link.className = 'download-link';
        link.textContent = item.name + (item.motion ? ' · 动态照片' : ' · 静态照片') + ' ↓'; $('downloads').append(link);
        if (jobs.length === 1) { const direct = document.createElement('a'); direct.href = item.url; direct.download = item.name; document.body.append(direct); direct.click(); direct.remove(); }
      }
      if (!result.items.length && !result.errors.length) throw new Error('没有生成文件');
      for (const message of result.errors) { failed++; failedFiles.add(file); const row = document.createElement('p'); row.className = 'download-error'; row.textContent = message; $('downloads').append(row); }
    } catch (error) {
      failed++; failedFiles.add(file);
      const row = document.createElement('p'); row.className = 'download-error'; row.textContent = file.name + '：' + error.message; $('downloads').append(row);
    }
    $('progress-fill').style.width = Math.round((index + 1) / jobs.length * 100) + '%';
    render();
  }
  setStatus(`处理完成：${succeeded} 张成功，${failed} 张失败。`, failed > 0);
  busy = false; render(); $('process-label').textContent = '生成水印照片'; $('process-arrow').classList.remove('spinner');
  $('progress').hidden = true;
}
$('process').onclick = () => processFiles([...selected]);
$('retry').onclick = () => processFiles([...selected].filter(file => failedFiles.has(file)), true, retryWithoutWatermark);
render();

let filmCatalog = [];
fetch('/films.json').then(r => { if (!r.ok) throw new Error(); return r.json(); }).then(items => { filmCatalog = items; items.forEach(f=>filmBrands.set(f.brand,f.brand_zh));
  for (const brand of [...new Set(items.map(f=>f.brand))]) {
    const option=document.createElement('option'); option.value=brand; option.label=items.find(f=>f.brand===brand).brand_zh; $('film-brands').append(option);
  }
  $("film-brand-note").textContent=filmBrands.get(splitFilmStock(editing().settings.film_stock || "").brand) || "";
}).catch(() => {});
function splitFilmStock(stock) {
  const known = [...filmBrands.keys()].sort((a,b)=>b.length-a.length).find(b=>stock.toLowerCase()===b.toLowerCase() || stock.toLowerCase().startsWith(b.toLowerCase()+' '));
  if (known) return {brand:known,model:stock.slice(known.length).trimStart()};
  const at=stock.indexOf(' '); return at<0 ? {brand:stock,model:''} : {brand:stock.slice(0,at),model:stock.slice(at+1)};
}
function closeFilms() { $('film-results').hidden = true; $('film-stock').setAttribute('aria-expanded','false'); }
function searchFilms() {
  const query = filmSearchKey($('film-stock').value.trim());
  const brand = $('film-brand').value.trim().toLowerCase();
  const results = filmCatalog.filter(f => (!brand || f.brand.toLowerCase() === brand) && filmSearchKey(`${f.brand} ${f.brand_zh} ${f.model} ${f.model_zh} ${f.iso} ${f.aliases} ${filmKinds[f.kind] || ""} ${f.note || ""}`).includes(query));
  $('film-results').replaceChildren();
  for (const f of results) {
    const button = document.createElement('button'); button.type='button'; button.role='option'; button.textContent=`${brand ? "" : f.brand+" "}${f.model} · ${f.model_zh}${f.note?.includes("历史") ? " · 历史型号" : ""}`;
    button.onclick=()=>{ chooseFilm(f.name); closeFilms(); $('film-camera').focus(); };
    $('film-results').append(button);
  }
  $('film-results').hidden = !results.length; $('film-stock').setAttribute('aria-expanded',String(!!results.length));
}
$('film-stock').onfocus=searchFilms;
$('film-stock').oninput=()=>{ const value=[$('film-brand').value.trim(),$('film-stock').value.trimStart()].filter(Boolean).join(' '); editSetting('film_stock',value); searchFilms(); };
$('film-brand').onchange=()=>{ const value=$('film-brand').value.trim(); const brand=[...filmBrands.keys()].find(b=>filmSearchKey(b)===filmSearchKey(value)||filmBrands.get(b)===value)||value; editSetting('film_stock',brand+' '); $('film-stock').focus(); searchFilms(); };
$('film-stock').onkeydown=e=>{ if(e.key==='Escape') closeFilms(); if(e.key==='ArrowDown'){e.preventDefault(); $('film-results').querySelector('button')?.focus();} };
$('film-iso').oninput=()=>editSetting('film_iso',$('film-iso').value);
$('film-camera').oninput=()=>editSetting('film_camera',$('film-camera').value);
document.querySelectorAll('input[name="photo-mode"]').forEach(input=>input.onchange=()=>editSetting('film_mode',input.value==='film'));
document.addEventListener('pointerdown',e=>{if(!$('film-fields').contains(e.target))closeFilms();});

function rememberFilm(name) {
  if (!name.trim()) return;
  recentFilms = [name,...recentFilms.filter(s=>s!==name)].slice(0,6);
  try { localStorage.setItem('cchong-recent-films',JSON.stringify(recentFilms)); } catch (_) {}
}
function chooseFilm(name) {
  const stock=filmCatalog.find(f=>f.name===name);
  rememberFilm(name); editSetting('film_stock',name);
  if(stock) editSetting('film_iso',stock.iso);
}
function renderRecentFilms() {
  const container=$('film-recent');container.replaceChildren();container.hidden=!recentFilms.length;
  for(const name of recentFilms){const button=document.createElement('button');button.type='button';button.textContent=name;button.disabled=busy;button.onclick=()=>chooseFilm(name);container.append(button);}
}
$('inherit').onclick=()=>{ if(busy)return;overrides.delete(previewFile);render();schedulePreview(); };
function adjacentPreview(delta){if(busy)return;const i=selected.indexOf(previewFile)+delta;if(i>=0&&i<selected.length){previewFile=selected[i];render();schedulePreview();}}
$('preview-prev').onclick=()=>adjacentPreview(-1);
$('preview-next').onclick=()=>adjacentPreview(1);

function setRotation(file, angle) {
  rotations.set(file, (angle + 360) % 360);
  if (file === previewFile) { clearOriginal(); clearMotion(); render(); schedulePreview(); }
}
$('rotate-left').onclick = () => { if (!busy && previewFile) setRotation(previewFile,(rotations.get(previewFile)||0)-90); };
$('rotate-right').onclick = () => { if (!busy && previewFile) setRotation(previewFile,(rotations.get(previewFile)||0)+90); };
$('rotation-reset').onclick = () => { if (!busy && previewFile) setRotation(previewFile,0); };
$('auto-upright').onclick = async () => {
  if (busy || !previewFile) return;
  const file = previewFile, starting = rotations.get(file)||0;
  busy = true; render(); setStatus('正在识别方向…');
  try {
    const form = new FormData(); form.append('file',file,file.name); form.append('settings',JSON.stringify(effective(file).settings));
    const response = await fetch('/api/orientation',{method:'POST',body:form}); const result = await response.json();
    if (!response.ok) throw new Error(result.detail || '方向识别失败');
    if (!selected.includes(file)) return;
    if (result.confident) { setRotation(file,starting+result.rotation); setStatus(result.rotation ? '已自动转正，请检查预览。' : '识别为正向，请检查预览。'); }
    else setStatus('方向不确定，已保留当前方向，请使用左转或右转。');
  } catch(error) { setStatus(error.message,true); }
  finally { busy=false; render(); }
};

$('export-upright').onclick = () => processFiles([...selected], false, true);
$('batch-upright').onclick = async () => {
  if (busy || !selected.length) return;
  const files=[...selected]; busy=true;render();
  const progress = $('progress'), fill = $('progress-fill');
  progress.hidden = false;
  progress.classList.add('determinate');
  progress.setAttribute('aria-label','整批转正进度');
  progress.setAttribute('aria-valuemax',files.length);
  progress.setAttribute('aria-valuenow',0);
  fill.style.width = '0%';
  let applied=0,uncertain=0,failed=0;
  const review=[];
  try {
    for(const [index,file] of files.entries()) {
      setStatus(`正在识别方向 ${index+1}/${files.length}：${file.name}`);
      try {
        const chosen=effective(file), starting=chosen.settings.rotation;
        const form=new FormData();form.append('file',file,file.name);form.append('settings',JSON.stringify(chosen.settings));
        const response=await fetch('/api/orientation',{method:'POST',body:form});
        if(!response.ok)throw Error('识别失败');
        const result=await response.json();
        if(result.confident){setRotation(file,starting+result.rotation);applied++;}
        else {uncertain++;review.push(file.name);}
      } catch(error){failed++;review.push(file.name);}
      progress.setAttribute('aria-valuenow', index + 1);
      fill.style.width = ((index + 1) / files.length * 100) + '%';
    }
    setStatus(`识别完成：${applied} 张已确认方向，${uncertain} 张不确定，${failed} 张失败。`+(review.length?` 请检查：${review.join('、')}`:' 请检查预览后导出。'));
  } finally {
    progress.hidden = true;
    progress.removeAttribute('aria-valuenow');
    progress.removeAttribute('aria-valuemax');
    progress.setAttribute('aria-label','照片处理进度');
    busy=false;render();schedulePreview();
  }
};
