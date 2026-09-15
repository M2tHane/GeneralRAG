/* =========================================================
   通用知识问答 RAG — 共享原型 UI 脚本
   提供：主题（Light/Dark/System）、Toast、Modal/Drawer、
        分页渲染、侧栏移动端开合、右下角预览工具区。
   ========================================================= */
(function () {
  'use strict';

  /* ---------- 主题 ---------- */
  const THEME_KEY = 'rag-proto-theme';
  const Theme = {
    get() { return localStorage.getItem(THEME_KEY) || 'system'; },
    apply() {
      const pref = this.get();
      const dark = pref === 'dark' || (pref === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches);
      document.documentElement.classList.toggle('dark', dark);
      document.querySelectorAll('.theme-seg button').forEach((b) => {
        b.classList.toggle('on', b.dataset.theme === pref);
      });
    },
    init() {
      this.apply();
      window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => this.apply());
      document.querySelectorAll('.theme-seg button').forEach((b) => {
        b.addEventListener('click', () => { localStorage.setItem(THEME_KEY, b.dataset.theme); this.apply(); });
      });
    },
  };

  /* ---------- 侧栏（含移动端抽屉） ---------- */
  function renderSidebar(active, extra) {
    const icon = (d) => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${d}</svg>`;
    return `
      <div class="sidebar-brand"><span class="logo">知</span>通用知识问答 RAG</div>
      <a class="nav-item ${active === 'kb' ? 'active' : ''}" href="kb.html">${icon('<path d="M4 20h16a1 1 0 0 0 1-1V5a1 1 0 0 0-1-1H8L4 8v11a1 1 0 0 0 1 1Z"/><path d="M4 8h16"/>')}知识库</a>
      <a class="nav-item ${active === 'chat' ? 'active' : ''}" href="chat.html">${icon('<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>')}知识问答</a>
      <a class="nav-item ${active === 'debug' ? 'active' : ''}" href="debug.html">${icon('<circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>')}检索调试</a>
      <a class="nav-item ${active === 'eval' ? 'active' : ''}" href="eval.html">${icon('<path d="M8 3h8v4l4.5 9.2A3 3 0 0 1 17.8 21H6.2a3 3 0 0 1-2.7-4.8L8 7Z"/><path d="M7 3h10"/><path d="M6.2 15h11.6"/>')}效果评测</a>
      <div class="sidebar-footer">
        <div class="theme-seg" role="group" aria-label="主题">
          <button data-theme="light" type="button">浅色</button>
          <button data-theme="dark" type="button">深色</button>
          <button data-theme="system" type="button">系统</button>
        </div>
        <div class="proto-note">交互原型 · 模拟数据<br>未连接真实后端</div>
        ${extra || ''}
      </div>`;
  }

  function initShell(active) {
    const sidebar = document.getElementById('sidebar');
    const scrim = document.getElementById('sidebar-scrim');
    const menuBtn = document.getElementById('menu-btn');
    if (sidebar) sidebar.innerHTML = renderSidebar(active);
    Theme.init();
    if (menuBtn && sidebar) {
      menuBtn.addEventListener('click', () => { sidebar.classList.add('open'); scrim.classList.add('show'); });
      scrim.addEventListener('click', close);
      document.addEventListener('keydown', (e) => { if (e.key === 'Escape') close(); });
    }
    function close() { sidebar.classList.remove('open'); scrim.classList.remove('show'); }
  }

  /* ---------- Toast ---------- */
  function toast(msg, type = 'default') {
    let wrap = document.querySelector('.toast-wrap');
    if (!wrap) { wrap = document.createElement('div'); wrap.className = 'toast-wrap'; document.body.appendChild(wrap); }
    const icons = { success: '✓', error: '✕', default: '' };
    const el = document.createElement('div');
    el.className = `toast ${type}`;
    el.textContent = (icons[type] ? icons[type] + ' ' : '') + msg;
    wrap.appendChild(el);
    setTimeout(() => { el.style.opacity = '0'; el.style.transition = 'opacity .3s'; setTimeout(() => el.remove(), 320); }, 2600);
  }

  /* ---------- Modal / Drawer ---------- */
  function openModal({ title, bodyHTML, footerHTML, wide, onClose }) {
    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.innerHTML = `
      <div class="modal ${wide ? 'wide' : ''}" role="dialog" aria-modal="true" aria-label="${title}">
        <div class="modal-header"><h3>${title}</h3><button class="icon-btn" data-close aria-label="关闭">✕</button></div>
        <div class="modal-body">${bodyHTML}</div>
        ${footerHTML ? `<div class="modal-footer">${footerHTML}</div>` : ''}
      </div>`;
    document.body.appendChild(overlay);
    const close = () => { overlay.remove(); onClose && onClose(); document.removeEventListener('keydown', esc); };
    const esc = (e) => { if (e.key === 'Escape') close(); };
    document.addEventListener('keydown', esc);
    overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });
    overlay.querySelectorAll('[data-close]').forEach((b) => b.addEventListener('click', close));
    const firstInput = overlay.querySelector('input, textarea, select, button.btn-primary');
    firstInput && firstInput.focus();
    return { overlay, close };
  }

  function openDrawer({ title, bodyHTML, onClose }) {
    const overlay = document.createElement('div');
    overlay.className = 'drawer-overlay';
    overlay.innerHTML = `
      <div class="drawer" role="dialog" aria-modal="true" aria-label="${title}">
        <div class="drawer-header"><h3>${title}</h3><button class="icon-btn" data-close aria-label="关闭">✕</button></div>
        <div class="drawer-body">${bodyHTML}</div>
      </div>`;
    document.body.appendChild(overlay);
    const close = () => { overlay.remove(); onClose && onClose(); document.removeEventListener('keydown', esc); };
    const esc = (e) => { if (e.key === 'Escape') close(); };
    document.addEventListener('keydown', esc);
    overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });
    overlay.querySelectorAll('[data-close]').forEach((b) => b.addEventListener('click', close));
    return { overlay, close };
  }

  function confirmDanger({ title, html, confirmText = '删除', onConfirm }) {
    const m = openModal({
      title,
      bodyHTML: html,
      footerHTML: `<button class="btn btn-outline" data-cancel>取消</button>
                   <button class="btn btn-danger-solid" data-ok>${confirmText}</button>`,
    });
    m.overlay.querySelector('[data-cancel]').addEventListener('click', m.close);
    m.overlay.querySelector('[data-ok]').addEventListener('click', () => { m.close(); onConfirm && onConfirm(); });
    return m;
  }

  /* ---------- 分页 ---------- */
  /**
   * renderPagination(container, {
   *   total, page, pageSize, onPage(page), onPageSize(size),
   *   pageSizes=[10,20], forceJump=false
   * })
   * 结构：左=共 N 项 + 每页 10/20；右=上一页/下一页 + 当前页/总页数（>7 页含跳页输入）。
   */
  function renderPagination(container, opts) {
    const { total, page, pageSize } = opts;
    const pageSizes = opts.pageSizes || [10, 20];
    const totalPages = Math.max(1, Math.ceil(total / pageSize));
    const cur = Math.min(Math.max(1, page), totalPages);
    const showJump = opts.forceJump || totalPages > 7;
    container.innerHTML = `
      <div class="page-total">共 ${total} 项</div>
      <div class="page-size">每页
        <select class="select" aria-label="每页条数">
          ${pageSizes.map((s) => `<option ${s === pageSize ? 'selected' : ''}>${s}</option>`).join('')}
        </select> 条
      </div>
      <div class="page-nav">
        <button class="pager-btn" data-prev ${cur <= 1 ? 'disabled' : ''} aria-label="上一页">‹</button>
        <span class="pg-info">${cur} / ${totalPages}</span>
        <button class="pager-btn" data-next ${cur >= totalPages ? 'disabled' : ''} aria-label="下一页">›</button>
        ${showJump ? `
        <span class="page-jump">跳至
          <input class="input" type="text" inputmode="numeric" aria-label="跳转页码" value="">
          页 <button class="btn btn-outline btn-sm" data-jump>跳转</button>
          <span class="jump-err" style="display:none"></span>
        </span>` : ''}
      </div>`;
    const sel = container.querySelector('.page-size select');
    sel.addEventListener('change', () => opts.onPageSize(parseInt(sel.value, 10)));
    container.querySelector('[data-prev]').addEventListener('click', () => opts.onPage(cur - 1));
    container.querySelector('[data-next]').addEventListener('click', () => opts.onPage(cur + 1));
    const jump = container.querySelector('[data-jump]');
    if (jump) {
      const input = container.querySelector('.page-jump .input');
      const err = container.querySelector('.jump-err');
      const doJump = () => {
        const v = input.value.trim();
        err.style.display = 'none';
        if (!/^\d+$/.test(v) || parseInt(v, 10) < 1 || parseInt(v, 10) > totalPages) {
          err.textContent = '请输入 1–' + totalPages + ' 的整数';
          err.style.display = 'inline';
          return; // 非法输入不改变当前页
        }
        input.value = '';
        opts.onPage(parseInt(v, 10));
      };
      jump.addEventListener('click', doJump);
      input.addEventListener('keydown', (e) => { if (e.key === 'Enter') doJump(); });
    }
  }

  /* ---------- 演示控制区（位于侧栏底部，不遮挡业务控件） ---------- */
  /**
   * initProtoTools([{ label, control: html | {type:'button', text, onClick} | {type:'select', options, onChange, value} }])
   */
  function initProtoTools(controls) {
    const sidebarFooter = document.querySelector('#sidebar .sidebar-footer');
    const host = document.createElement('div');
    host.className = 'proto-tools';
    host.innerHTML = `
      <button class="proto-toggle" type="button" aria-expanded="false">⚙ 原型演示控制</button>
      <div class="panel" style="display:none">
        <div class="panel-body" data-body></div>
      </div>`;
    if (sidebarFooter) sidebarFooter.insertBefore(host, sidebarFooter.firstChild);
    else document.body.appendChild(host);
    const panel = host.querySelector('.panel');
    const toggle = host.querySelector('.proto-toggle');
    toggle.addEventListener('click', () => {
      const open = panel.style.display === 'none';
      panel.style.display = open ? 'block' : 'none';
      toggle.setAttribute('aria-expanded', String(open));
      toggle.textContent = open ? '✕ 收起演示控制' : '⚙ 原型演示控制';
    });
    const body = host.querySelector('[data-body]');
    body.innerHTML = '<span class="tag">以下开关仅影响演示，不改变产品功能</span>';
    (controls || []).forEach((c) => {
      const row = document.createElement('div');
      row.className = 'ctl';
      if (c.control && c.control.type === 'select') {
        row.innerHTML = `<label>${c.label}</label>
          <select class="select">${c.control.options.map((o) => `<option ${o === c.control.value ? 'selected' : ''}>${o}</option>`).join('')}</select>`;
        row.querySelector('select').addEventListener('change', (e) => c.control.onChange(e.target.value));
      } else if (c.control && c.control.type === 'button') {
        row.innerHTML = `<label>${c.label}</label>`;
        const b = document.createElement('button');
        b.className = 'btn btn-outline btn-sm';
        b.textContent = c.control.text;
        b.addEventListener('click', c.control.onClick);
        row.appendChild(b);
      } else {
        row.innerHTML = `<label>${c.label}</label>${c.control || ''}`;
      }
      body.appendChild(row);
    });
  }

  /* ---------- 文件图标 ---------- */
  function docIcon(format) {
    const label = { pdf: 'PDF', md: 'MD', txt: 'TXT' }[format] || 'DOC';
    return `<span class="doc-icon doc-${format}">${label}</span>`;
  }

  window.SupieUI = { Theme, initShell, toast, openModal, openDrawer, confirmDanger, renderPagination, initProtoTools, docIcon };
})();
