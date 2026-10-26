const $ = id => document.getElementById(id);
const fileInput = $('files'), dropzone = $('dropzone'), list = $('list');
const defaults = {overall: 1, histogram_width: 1, histogram_height: 1, metadata: 1, signature: 1, x: .5, y: .83};
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
const overrides = new Map(), failedFiles = new Set();

try {
  const saved = JSON.parse(localStorage.getItem(preferenceKey) || '{}');
  for (const [key, , min, max] of fields) {
    const value = saved.settings?.[key];
    if (typeof value === 'number' && Number.isFinite(value) && value >= min && value <= max) globalSettings[key] = value;
  }
  if (['auto', 'white', 'black'].includes(saved.color)) globalColor = saved.color;
} catch (_) { /* Storage may be disabled in private browsing. */ }
function rememberGlobal() {
  try { localStorage.setItem(preferenceKey, JSON.stringify({settings: globalSettings, color: globalColor})); }
  catch (_) { /* Editing still works in this tab. */ }
}
function effective(file) { return overrides.get(file) || {settings: globalSettings, color: globalColor}; }
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
  for (const [key] of fields) {
    $('control-' + key).value = current.settings[key];
    $('value-' + key).textContent = Math.round(current.settings[key] * 100) + '%';
  }
  document.querySelector(`input[name=color][value=${current.color}]`).checked = true;
  $('scope-all').classList.toggle('active', scope === 'all');
  $('scope-one').classList.toggle('active', scope === 'one');
  $('scope-one').disabled = !previewFile;
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
      event.stopPropagation(); selected.splice(index, 1); overrides.delete(file); failedFiles.delete(file);
      if (originalFile === file) clearOriginal(); if (motionFile === file) clearMotion();
      render(); syncControls(); schedulePreview();
    };
    row.append(icon, meta, remove); list.append(row);
  }
  syncControls();
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
  selected = []; overrides.clear(); failedFiles.clear(); clearOriginal(); clearMotion();
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
function moveWatermark(event) {
  if (!$('preview-image').src || $('preview-image').hidden) return;
  const bounds = $('preview-image').getBoundingClientRect();
  if (scope === 'all') { scope = 'one'; syncControls(); }
  editSetting('x', Math.max(0, Math.min(1, (event.clientX - bounds.left) / bounds.width)));
  editSetting('y', Math.max(0, Math.min(1, (event.clientY - bounds.top) / bounds.height)));
}
$('preview-image').onpointerdown = event => { $('preview-image').setPointerCapture(event.pointerId); moveWatermark(event); };
$('preview-image').onpointermove = event => { if (event.buttons) moveWatermark(event); };
async function showOriginal() {
  if (!previewFile || !previewUrl) return;
  comparePressed = true;
  const file = previewFile;
  if (originalFile !== file || !originalUrl) {
    const form = new FormData(); form.append('file', file, file.name); form.append('original', 'true');
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

async function processFiles(files, retry = false) {
  if (!files.length || busy) return;
  const jobs = files.map(file => ({file, chosen: {settings: {...effective(file).settings}, color: effective(file).color}}));
  let succeeded = 0, failed = 0;
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
  busy = false; render(); $('process-label').textContent = '开始处理'; $('process-arrow').classList.remove('spinner');
  $('progress').hidden = true;
}
$('process').onclick = () => processFiles([...selected]);
$('retry').onclick = () => processFiles([...selected].filter(file => failedFiles.has(file)), true);
render();
