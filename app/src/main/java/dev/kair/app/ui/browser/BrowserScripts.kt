package dev.kair.app.ui.browser

import kotlinx.serialization.json.JsonPrimitive

/**
 * 注入页面执行的脚本（唯一来源）。
 *
 * 约定：
 * - 脚本只返回**一个 JSON 字符串**；Kotlin 侧再解一层（`evaluateJavascript` 会把返回串
 *   再包一层引号），解析统一在 `BrowserSnapshotParser.kt`。
 * - 不写 JS 模板字符串（不使用 `$`），避免与 Kotlin 原始串插值打架。
 * - 元素引用由快照写入 `window.__kairRefs`，动作只认最近一次快照的 ref。
 */
internal object BrowserScripts {

    /** 页面加载完成后注入：网络抓取、错误收集、Agent 高亮。可重复注入。 */
    val COLLECT = """
(function () {
  if (window.__kairCollectors) return 'already';
  window.__kairCollectors = true;

  function send(kind, data) {
    try {
      if (window.KairBrowser && window.KairBrowser.post) {
        window.KairBrowser.post(kind, JSON.stringify(data));
      }
    } catch (e) {}
  }

  window.__kairHighlight = function (el, label) {
    try {
      var r = el.getBoundingClientRect();
      var box = document.createElement('div');
      box.style.cssText = 'position:fixed;z-index:2147483647;pointer-events:none;' +
        'border:2px solid #7c6cff;border-radius:4px;box-shadow:0 0 0 3px rgba(124,108,255,.25);' +
        'left:' + r.left + 'px;top:' + r.top + 'px;width:' + r.width + 'px;height:' + r.height + 'px;' +
        'transition:opacity .3s;';
      var tag = document.createElement('div');
      tag.textContent = 'AGENT ' + label;
      tag.style.cssText = 'position:absolute;left:0;top:-20px;background:#7c6cff;color:#fff;' +
        'font:600 10px/16px monospace;padding:0 5px;border-radius:3px;white-space:nowrap;';
      box.appendChild(tag);
      document.documentElement.appendChild(box);
      setTimeout(function () {
        box.style.opacity = '0';
        setTimeout(function () { box.remove(); }, 320);
      }, 900);
    } catch (e) {}
  };

  var origFetch = window.fetch;
  if (origFetch) {
    window.fetch = function (input, init) {
      var started = Date.now();
      var method = (init && init.method) || (input && input.method) || 'GET';
      var url = typeof input === 'string' ? input : ((input && input.url) || String(input));
      try { url = new URL(url, location.href).href; } catch (e) {}
      return origFetch.apply(this, arguments).then(function (res) {
        send('network', {
          method: method,
          url: url,
          status: res.status,
          durationMs: Date.now() - started
        });
        return res;
      }, function (err) {
        send('network', {
          method: method,
          url: url,
          error: String((err && err.message) || err),
          durationMs: Date.now() - started
        });
        throw err;
      });
    };
  }

  var open = XMLHttpRequest.prototype.open;
  var sendXhr = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (method, url) {
    this.__kairMethod = method;
    this.__kairUrl = url;
    return open.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function () {
    var xhr = this;
    var started = Date.now();
    var xhrUrl = xhr.__kairUrl || '';
    try { xhrUrl = new URL(xhrUrl, location.href).href; } catch (e) {}
    xhr.addEventListener('loadend', function () {
      send('network', {
        method: xhr.__kairMethod || 'GET',
        url: xhrUrl,
        status: xhr.status || null,
        durationMs: Date.now() - started
      });
    });
    return sendXhr.apply(this, arguments);
  };

  window.addEventListener('error', function (e) {
    send('console', {
      level: 'error',
      message: 'Uncaught ' + (e.message || 'error') + ' @ ' + (e.filename || '') + ':' + (e.lineno || 0)
    });
  });
  window.addEventListener('unhandledrejection', function (e) {
    var r = e.reason;
    send('console', {
      level: 'error',
      message: 'Unhandled rejection: ' + String((r && (r.stack || r.message)) || r)
    });
  });

  return 'ok';
})()
"""

    /** 读取页面结构：给可交互元素分配 e1..eN，返回页面文本与元素清单。 */
    val SNAPSHOT = """
(function () {
  var refs = {};
  var old = document.querySelectorAll('[data-kair-ref]');
  for (var i = 0; i < old.length; i++) old[i].removeAttribute('data-kair-ref');

  var selector = 'a[href],button,input,textarea,select,summary,' +
    '[role="button"],[role="link"],[role="checkbox"],[role="tab"],[contenteditable="true"]';
  var all = document.querySelectorAll(selector);
  var out = [];
  var n = 0;

  function visible(el) {
    var r = el.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) return false;
    var s = window.getComputedStyle(el);
    return s.visibility !== 'hidden' && s.display !== 'none' && s.opacity !== '0';
  }
  function labelOf(el) {
    var t = el.getAttribute('aria-label');
    if (t) return t;
    if (el.labels && el.labels.length) return el.labels[0].innerText;
    if (el.placeholder) return el.placeholder;
    if (el.innerText) return el.innerText;
    if (el.value && el.type !== 'password') return el.value;
    return el.name || el.id || '';
  }
  function roleOf(el) {
    var r = el.getAttribute('role');
    if (r) return r;
    var tag = el.tagName.toLowerCase();
    if (tag === 'a') return 'link';
    if (tag === 'select') return 'combobox';
    if (tag === 'textarea') return 'textbox';
    if (tag === 'input') {
      var ty = (el.type || 'text').toLowerCase();
      if (ty === 'checkbox' || ty === 'radio') return ty;
      if (ty === 'submit' || ty === 'button') return 'button';
      return 'textbox';
    }
    return tag;
  }

  for (var i = 0; i < all.length && n < 80; i++) {
    var el = all[i];
    if (!visible(el)) continue;
    var ref = 'e' + (++n);
    el.setAttribute('data-kair-ref', ref);
    refs[ref] = el;
    var value = '';
    try { value = String(el.value !== undefined ? el.value : ''); } catch (e) {}
    out.push({
      ref: ref,
      role: roleOf(el),
      name: String(labelOf(el) || '').replace(/\s+/g, ' ').trim().slice(0, 80),
      value: el.type === 'password' ? (el.value ? '********' : '') : value.slice(0, 120),
      checked: (el.type === 'checkbox' || el.type === 'radio') ? !!el.checked : null,
      disabled: !!el.disabled
    });
  }
  window.__kairRefs = refs;
  return JSON.stringify({
    url: location.href,
    title: document.title,
    text: (document.body ? document.body.innerText : '').slice(0, 6000),
    elements: out
  });
})()
"""

    fun click(ref: String): String {
        val quoted = JsonPrimitive(ref).toString()
        return """
(function () {
  var el = (window.__kairRefs || {})[$quoted];
  if (!el || !el.isConnected) {
    return JSON.stringify({ ok: false, error: 'Unknown or stale ref. Call browser_snapshot again.' });
  }
  el.scrollIntoView({ block: 'center', inline: 'center' });
  try { el.focus({ preventScroll: true }); } catch (e) {}
  if (window.__kairHighlight) window.__kairHighlight(el, 'click');
  el.click();
  return JSON.stringify({ ok: true, message: 'Clicked ' + $quoted + '.' });
})()
"""
    }

    fun type(ref: String, text: String): String {
        val quotedRef = JsonPrimitive(ref).toString()
        val quotedText = JsonPrimitive(text).toString()
        return """
(function () {
  var el = (window.__kairRefs || {})[$quotedRef];
  if (!el || !el.isConnected) {
    return JSON.stringify({ ok: false, error: 'Unknown or stale ref. Call browser_snapshot again.' });
  }
  var text = $quotedText;
  el.scrollIntoView({ block: 'center', inline: 'center' });
  try { el.focus({ preventScroll: true }); } catch (e) {}
  if (el.isContentEditable) {
    el.textContent = text;
  } else {
    var proto = HTMLInputElement.prototype;
    if (el.tagName === 'TEXTAREA') proto = HTMLTextAreaElement.prototype;
    else if (el.tagName === 'SELECT') proto = HTMLSelectElement.prototype;
    var desc = Object.getOwnPropertyDescriptor(proto, 'value');
    if (desc && desc.set) desc.set.call(el, text);
    else el.value = text;
  }
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.dispatchEvent(new Event('change', { bubbles: true }));
  if (window.__kairHighlight) window.__kairHighlight(el, 'type');
  return JSON.stringify({ ok: true, message: 'Typed into ' + $quotedRef + '.' });
})()
"""
    }

    fun press(key: String): String {
        val quotedKey = JsonPrimitive(key).toString()
        return """
(function () {
  var el = document.activeElement || document.body;
  var key = $quotedKey;
  var allowed = true;
  ['keydown', 'keypress', 'keyup'].forEach(function (type) {
    var ev = new KeyboardEvent(type, { key: key, bubbles: true, cancelable: true });
    if (!el.dispatchEvent(ev)) allowed = false;
  });
  if (key === 'Enter' && allowed && el.form && el.tagName !== 'TEXTAREA') {
    try { el.form.requestSubmit(); } catch (e) {}
  }
  return JSON.stringify({ ok: true, message: 'Pressed ' + key + '.' });
})()
"""
    }

    fun scroll(direction: String, amount: Int): String = """
(function () {
  var dir = '$direction';
  var amount = $amount;
  if (dir === 'top') window.scrollTo(0, 0);
  else if (dir === 'bottom') window.scrollTo(0, document.body ? document.body.scrollHeight : 0);
  else window.scrollBy(0, dir === 'up' ? -amount : amount);
  return JSON.stringify({
    ok: true,
    message: 'Scrolled ' + dir + ' to ' + Math.round(window.scrollY) + 'px.'
  });
})()
"""

    /** 执行任意表达式，返回值的字符串形式；包装格式与动作结果一致。 */
    fun eval(expression: String): String {
        val quoted = JsonPrimitive(expression).toString()
        return """
(function () {
  var src = $quoted;
  try {
    var value = (0, eval)(src);
    var text;
    if (typeof value === 'string') {
      text = value;
    } else {
      try { text = JSON.stringify(value); } catch (e) { text = String(value); }
      if (text === undefined) text = String(value);
    }
    return JSON.stringify({ ok: true, message: String(text).slice(0, 8000) });
  } catch (e) {
    return JSON.stringify({ ok: false, error: String((e && e.message) || e) });
  }
})()
"""
    }
}
