const API = 'http://localhost:8080';
let lastLogId = null;

async function askRAG() {
    const question = document.getElementById('question').value;
    if (!question) return;
    const model = document.getElementById('model-select').value;
    const namespace = document.getElementById('ask-namespace')?.value || 'default';

    document.getElementById('rag-loading').classList.remove('hidden');
    document.getElementById('rag-answer').classList.add('hidden');
    document.getElementById('rag-sources').classList.add('hidden');
    document.getElementById('rag-feedback').classList.add('hidden');   // 새 질문 시 숨김

    try {
        const res = await fetch(`${API}/api/rag/ask`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({ question, model, namespace })
        });
        const data = await res.json();

        document.getElementById('rag-loading').classList.add('hidden');
        document.getElementById('rag-answer').classList.remove('hidden');
        document.getElementById('rag-answer').textContent = data.answer || '(No answer)';

        const sourcesList = document.getElementById('sources-list');
        sourcesList.innerHTML = (data.sources || []).map(s => `
            <div class="source-item">
                <a href="${s.url}" target="_blank">${s.title}</a>
                <div class="summary">${s.summary.substring(0, 150)}...</div>
            </div>
        `).join('');
        document.getElementById('rag-sources').classList.remove('hidden');

        // ← 여기 (data 받은 뒤)
        lastLogId = data.logId;
        document.getElementById('rag-feedback').classList.remove('hidden');
        document.getElementById('feedback-result').textContent = '';
    } catch (e) {
        document.getElementById('rag-loading').classList.add('hidden');
        alert('Error: ' + e.message);
    }
}
async function summarizeArticle(id) {
    const box = document.getElementById(`summary-${id}`);
    box.classList.remove('hidden');
    box.textContent = 'Summarizing...';
    try {
        const res = await fetch(`${API}/api/data/summarize/${id}`, { method: 'POST' });
        if (!res.ok) throw new Error(`${res.status}: ${(await res.text()).slice(0,200)}`);
        const data = await res.json();
        box.textContent = data.summary;
    } catch (e) {
        box.textContent = 'Error: ' + e.message;
    }
}
async function ingestArticle() {
    const title = document.getElementById('ingest-title').value;
    if (!title) return;

    document.getElementById('ingest-result').textContent = '⏳ Ingesting...';

    try {
        const res = await fetch(`${API}/api/data/ingest?title=${encodeURIComponent(title)}`, {
            method: 'POST'
        });
        const data = await res.json();
        document.getElementById('ingest-result').innerHTML =
            `Ingested: <b>${data.title}</b> (id: ${data.id})`;
        document.getElementById('ingest-title').value = '';
        loadDocuments();
    } catch (e) {
        document.getElementById('ingest-result').textContent = 'Error: ' + e.message;
    }
}
async function sendFeedback(value) {
    if (!lastLogId) return;
    await fetch(`${API}/api/governance/feedback`, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({ id: String(lastLogId), value })
    });
    document.getElementById('feedback-result').textContent = `Up ${counts.up || 0} Down ${counts.down || 0}`;
}

async function loadArticles() {
    const res = await fetch(`${API}/api/data/articles`);
    const articles = await res.json();

    const list = document.getElementById('articles-list');
    list.innerHTML = articles.map(a => `
        <div class="article-item">
            <a href="${a.url}" target="_blank">#${a.id} ${a.title}</a>
            <div class="summary">${a.summary.substring(0, 200)}...</div>
            <div class="meta">Ingested: ${a.ingestedAt}</div>
            <button onclick="summarizeArticle(${a.id})" class="btn-ghost">요약</button>
            <button onclick="deleteArticle(${a.id})" class="btn-ghost">삭제</button>
            <div id="summary-${a.id}" class="answer-box hidden"></div>
        </div>
    `).join('');
}

async function loadLogs() {
    const res = await fetch(`${API}/api/governance/logs`);
    const logs = await res.json();

    const table = document.getElementById('logs-table');
    table.innerHTML = logs.reverse().map(l => `
        <tr>
            <td>${l.id}</td>
            <td title="${l.question}">${l.question.substring(0, 60)}...</td>
            <td><span class="model-badge">${l.model}</span></td>
            <td class="text-right">${l.latencyMs}</td>
            <td>${l.piiCount > 0 ? '🔒 ' + l.piiCount : '–'}</td>
            <td class="text-sm text-muted">${l.sources || '–'}</td>     <!-- 추가 -->
            <td class="text-sm text-muted">${l.createdAt}</td>
        </tr>
    `).join('');
}
async function deleteArticle(id) {
    if (!confirm(`#${id} 삭제할까요?`)) return;
    await fetch(`${API}/api/data/articles/${id}`, { method: 'DELETE' });
    loadDocuments();
}

async function uploadFile() {
    const fileInput = document.getElementById('upload-file');
    const file = fileInput.files[0];
    if (!file) { alert('파일을 선택하세요'); return; }

    const ns = document.getElementById('upload-namespace').value || 'default';
    const isImage = file.type.startsWith('image/');

    const form = new FormData();
    form.append('namespace', ns);
    let endpoint;
    if (isImage) {                                   // 이미지 → 멀티모달 ingest
        endpoint = `${API}/api/multimodal/ingest`;
        form.append('image', file);
    } else {                                         // 텍스트/문서 → 파일 ingest
        endpoint = `${API}/api/data/ingest-file`;
        form.append('file', file);
    }

    document.getElementById('upload-result').textContent = 'Uploading...';
    try {
        const res = await fetch(endpoint, { method: 'POST', body: form });  // 헤더 X (FormData)
        if (!res.ok) {
            const text = await res.text();
            throw new Error(`${res.status}: ${text.slice(0, 200)}`);
        }
        const data = await res.json();
        const msg = Array.isArray(data)
            ? `Ingested: <b>${data.length}</b> chunks (${data[0]?.title?.replace(/ #\d+$/, '') || ''})`
            : `Ingested: <b>${data.title}</b> (id ${data.id})`;   // 이미지(단일 Article)
        document.getElementById('upload-result').innerHTML = msg;
        fileInput.value = '';
        loadDocuments();                              // 목록 갱신
    } catch (e) {
        document.getElementById('upload-result').textContent = 'Error: ' + e.message;
    }
}
async function loadStats() {
    const res = await fetch(`${API}/api/governance/stats`);
    const s = await res.json();

    document.getElementById('stats-cards').innerHTML = `
        ${statCard('Total Calls', s.totalCalls)}
        ${statCard('Avg Latency', s.avgLatencyMs + ' ms')}
        ${statCard('PII Hits', s.totalPii)}
        ${statCard('Documents', s.totalDocs)}
    `;
    document.getElementById('stats-models').innerHTML =
        '<div class="meta-label">By Model</div>' +
        (s.byModel || []).map(m => `<div>${m.model}: ${m.calls} calls, ${m.avgMs} ms avg</div>`).join('');
    document.getElementById('stats-sources').innerHTML =
        '<div class="meta-label">By Source Type</div>' +
        (s.bySourceType || []).map(x => `<div>${x.sourceType}: ${x.docs} docs, ${x.chunks} chunks</div>`).join('');
}

function statCard(label, value) {
    return `<div class="status-card"><div class="status-content">
        <div class="status-label">${label}</div>
        <div class="status-value">${value}</div>
    </div></div>`;
}
async function loadModels() {
    try {
        const res = await fetch(`${API}/api/rag/models`);
        const data = await res.json();
        const sel = document.getElementById('model-select');
        sel.innerHTML = (data.available || []).map(m =>
            `<option value="${m}" ${m === data.default ? 'selected' : ''}>${m}</option>`
        ).join('');
        sel.addEventListener('change', updateAiStatus);   // 바꿀 때마다 갱신
        updateAiStatus();                                  // 초기 표시
    } catch (e) { /* 무시 */ }
}
async function loadModels() {
    try {
        const res = await fetch(`${API}/api/rag/models`);
        const data = await res.json();
        const sel = document.getElementById('model-select');
        sel.innerHTML = (data.available || []).map(m =>
            `<option value="${m}" ${m === data.default ? 'selected' : ''}>${m}</option>`
        ).join('');
        sel.addEventListener('change', updateAiStatus);   // ← 추가: 바꿀 때 카드 갱신
        updateAiStatus();                                  // ← 추가: 초기 표시
    } catch (e) {
        console.error('loadModels failed:', e);
    }
}
async function loadDocuments() {
    const res = await fetch(`${API}/api/data/documents`);
    const docs = await res.json();

    const list = document.getElementById('articles-list');
    list.innerHTML = docs.map(d => `
        <div class="article-item">
            <a href="${d.url}" target="_blank">${d.title}</a>
            <span class="badge">${d.chunks} chunks</span>
            <span class="badge">${d.namespace}</span>
            <button onclick="summarizeDocument('${d.ids[0]}')" class="btn-ghost">요약</button>
            <button onclick="deleteDocument('${encodeURIComponent(d.title)}','${encodeURIComponent(d.namespace)}')" class="btn-ghost">삭제</button>
            <div id="summary-${d.ids[0]}" class="answer-box hidden"></div>
        </div>
    `).join('');
}
async function deleteDocument(title, namespace) {
    if (!confirm(`"${decodeURIComponent(title)}" 문서를 삭제할까요? (모든 청크)`)) return;
    await fetch(`${API}/api/data/documents?title=${title}&namespace=${namespace}`, { method: 'DELETE' });
    loadDocuments();
}
async function summarizeDocument(id) {
    const box = document.getElementById(`summary-${id}`);
    box.classList.remove('hidden');
    box.textContent = 'Summarizing...';
    const res = await fetch(`${API}/api/data/summarize/${id}`, { method: 'POST' });
    const data = await res.json();
    box.textContent = data.summary;
}
function showTab(tab) {
    const articles = document.getElementById('articles-section');
    const logs = document.getElementById('logs-section');
    const tabArticles = document.getElementById('tab-articles');
    const tabLogs = document.getElementById('tab-logs');

    articles.classList.toggle('hidden', tab !== 'articles');
    logs.classList.toggle('hidden', tab !== 'logs');
    tabArticles.classList.toggle('active', tab === 'articles');
    tabLogs.classList.toggle('active', tab === 'logs');

    if (tab === 'logs') loadLogs();
    else loadDocuments();
}

function updateAiStatus() {
    const el = document.getElementById('ai-model');
    const sel = document.getElementById('model-select');
    if (el && sel && sel.value) el.textContent = 'Ollama · ' + sel.value;
}

loadDocuments();    // loadArticles() 대신
loadModels();

document.getElementById('question').addEventListener('keypress', e => {
    if (e.key === 'Enter') askRAG();
});
document.getElementById('ingest-title').addEventListener('keypress', e => {
    if (e.key === 'Enter') ingestArticle();
});