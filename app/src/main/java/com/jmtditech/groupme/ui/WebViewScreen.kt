package com.jmtditech.groupme.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.KeyEvent
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.jmtditech.groupme.data.AppState
import org.json.JSONArray
import org.json.JSONObject

/**
 * Spatial (D-pad) navigation for web pages, injected after every page load. The native side forwards the
 * arrow / OK keys to window.__dpadKey(), which moves a highlighted "cursor" between links, buttons and fields,
 * scrolls the page when nothing is left in that direction, and clicks on OK. It returns
 * 'moved' | 'scrolled' | 'caret' | 'ok' | 'edge' | 'error'.
 */
private const val DPAD_JS = """
(function () {
  if (window.__dpadKey) return;
  var SEL = 'a[href],button,input:not([type=hidden]),select,textarea,summary,[role=button],[role=link],[onclick],[tabindex]';
  var RX = /^(allow|deny|authorize|cancel|accept|approve|decline)${'$'}/i;
  var cur = null;
  var st = document.createElement('style');
  st.textContent = '.__dpad{outline:3px solid #ff9800 !important;outline-offset:2px !important;}';
  (document.head || document.documentElement).appendChild(st);

  function vis(el) {
    if (el.disabled) return false;
    var r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return false;
    var s = getComputedStyle(el);
    return s.visibility !== 'hidden' && s.display !== 'none';
  }
  function items() {
    return Array.prototype.filter.call(document.querySelectorAll(SEL), function (el) {
      return el.getAttribute('tabindex') !== '-1' && vis(el);
    });
  }
  function inView(r) {
    return r.bottom > 0 && r.top < window.innerHeight && r.right > 0 && r.left < window.innerWidth;
  }
  function byPos(a, b) {
    var ra = a.getBoundingClientRect(), rb = b.getBoundingClientRect();
    return (ra.top - rb.top) || (ra.left - rb.left);
  }
  function isText(el) {
    var t = (el.tagName || '').toLowerCase();
    if (t === 'textarea') return true;
    if (t === 'input') {
      var ty = (el.type || 'text').toLowerCase();
      return ['text', 'search', 'email', 'url', 'tel', 'password', 'number'].indexOf(ty) >= 0;
    }
    return !!el.isContentEditable;
  }
  function setCur(el) {
    if (cur) cur.classList.remove('__dpad');
    cur = el;
    el.classList.add('__dpad');
    try { el.focus({ preventScroll: true }); } catch (e) { try { el.focus(); } catch (e2) {} }
    var r = el.getBoundingClientRect();
    if (r.top < 0 || r.bottom > window.innerHeight || r.left < 0 || r.right > window.innerWidth) {
      el.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    }
  }
  function pickEdge(list, last) {
    var v = list.filter(function (e) { return inView(e.getBoundingClientRect()); });
    if (!v.length) return null;
    v.sort(byPos);
    return last ? v[v.length - 1] : v[0];
  }
  function scrollPage(dir) {
    var dy = dir === 'down' ? 0.6 : (dir === 'up' ? -0.6 : 0);
    var dx = dir === 'right' ? 0.6 : (dir === 'left' ? -0.6 : 0);
    var by = window.pageYOffset, bx = window.pageXOffset;
    window.scrollBy(dx * window.innerWidth, dy * window.innerHeight);
    return window.pageYOffset !== by || window.pageXOffset !== bx;
  }
  function gap(a1, a2, b1, b2) { return Math.max(0, Math.max(a1 - b2, b1 - a2)); }
  function move(dir) {
    var list = items();
    if (!cur || !document.contains(cur) || !vis(cur) || !inView(cur.getBoundingClientRect())) {
      var e = pickEdge(list, dir === 'up' || dir === 'left');
      if (e) { setCur(e); return 'moved'; }
      return scrollPage(dir) ? 'scrolled' : 'edge';
    }
    var cr = cur.getBoundingClientRect();
    var cx = cr.left + cr.width / 2, cy = cr.top + cr.height / 2;
    var best = null, bs = 1e18;
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (el === cur || cur.contains(el) || el.contains(cur)) continue;
      var r = el.getBoundingClientRect();
      if (!inView(r)) continue;
      var dx = (r.left + r.width / 2) - cx, dy = (r.top + r.height / 2) - cy;
      var ok, prim, sec;
      if (dir === 'down') { ok = dy > 2; prim = dy; sec = gap(cr.left, cr.right, r.left, r.right); }
      else if (dir === 'up') { ok = dy < -2; prim = -dy; sec = gap(cr.left, cr.right, r.left, r.right); }
      else if (dir === 'right') { ok = dx > 2; prim = dx; sec = gap(cr.top, cr.bottom, r.top, r.bottom); }
      else { ok = dx < -2; prim = -dx; sec = gap(cr.top, cr.bottom, r.top, r.bottom); }
      if (!ok) continue;
      var score = prim + 3 * sec;
      if (score < bs) { bs = score; best = el; }
    }
    if (best) { setCur(best); return 'moved'; }
    if (dir === 'up' || dir === 'down') return scrollPage(dir) ? 'scrolled' : 'edge';
    return 'edge';
  }
  function label(el) {
    var v = (el.tagName === 'INPUT' && el.value) ? el.value : (el.innerText || el.textContent || '');
    return String(v).trim();
  }

  window.__dpadKey = function (k) {
    try {
      var ae = document.activeElement;
      var text = !!(ae && isText(ae));
      if (k === 'left' || k === 'right') {
        if (text && typeof ae.selectionStart === 'number' && ae.setSelectionRange) {
          var s = ae.selectionStart, e = ae.selectionEnd, n = ae.value.length;
          if (k === 'left' && !(s === 0 && e === 0)) { var p = (s !== e) ? s : s - 1; ae.setSelectionRange(p, p); return 'caret'; }
          if (k === 'right' && !(s === n && e === n)) { var q = (s !== e) ? e : e + 1; ae.setSelectionRange(q, q); return 'caret'; }
        }
        var r = move(k);
        if (r === 'edge') r = move(k === 'right' ? 'down' : 'up');
        return r;
      }
      if (k === 'ok') {
        var target = (cur && document.contains(cur)) ? cur : (text ? ae : null);
        if (!target) {
          var f = pickEdge(items(), false);
          if (f) { setCur(f); return 'moved'; }
          return 'edge';
        }
        if (isText(target)) {
          var form = target.form;
          if (form) { if (form.requestSubmit) form.requestSubmit(); else form.submit(); }
          return 'ok';
        }
        target.click();
        return 'ok';
      }
      return move(k);
    } catch (err) { return 'error'; }
  };

  // Allow / Deny style buttons (only on pages without text fields), for the native fallback bar.
  window.__dpadActions = function () {
    var list = items(), out = [], i;
    for (i = 0; i < list.length; i++) { if (isText(list[i])) return []; }
    for (i = 0; i < list.length; i++) {
      var l = label(list[i]);
      if (RX.test(l) && out.indexOf(l) < 0) out.push(l);
    }
    return out;
  };
  window.__dpadClick = function (l) {
    var list = items();
    for (var i = 0; i < list.length; i++) {
      if (label(list[i]) === l) { setCur(list[i]); list[i].click(); return true; }
    }
    return false;
  };
})();
"""

/**
 * In-app browser with full D-pad support. If [onToken] is given (GroupMe OAuth sign-in), it intercepts the
 * redirect to the HTTPS callback URL: as soon as a navigation carries `access_token`, the token is handed to
 * [onToken] and the callback page itself is never loaded. Without [onToken] it is a plain page viewer.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InAppWebView(title: String, url: String, onClose: () -> Unit, onToken: ((String) -> Unit)? = null) {
    val webRef = remember { arrayOfNulls<WebView>(1) }
    var loading by remember { mutableStateOf(true) }
    var actions by remember { mutableStateOf<List<String>>(emptyList()) }
    val actionsNow by rememberUpdatedState(actions)
    val barFocused = remember { booleanArrayOf(false) }
    val barFirst = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val currentOnToken by rememberUpdatedState(onToken)
    val handled = remember { booleanArrayOf(false) }

    fun tryCapture(target: Uri?): Boolean {
        val callback = currentOnToken ?: return false
        if (handled[0] || target == null || target.scheme != "https") return false
        val token = target.getQueryParameter("access_token")?.takeIf { it.isNotBlank() } ?: return false
        handled[0] = true
        callback(token)
        return true
    }

    BackHandler {
        val w = webRef[0]
        if (w != null && w.canGoBack()) w.goBack() else onClose()
    }

    // While this screen is up, the arrow / OK keys go to the page (via the injected script) instead of
    // Compose's focus system. Back and typing keys are untouched. When the native Allow/Deny bar has focus,
    // keys are left alone so Compose can move between its buttons.
    DisposableEffect(Unit) {
        val interceptor: (KeyEvent) -> Boolean = fun(ev: KeyEvent): Boolean {
            if (barFocused[0]) return false
            val action = when (ev.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> "up"
                KeyEvent.KEYCODE_DPAD_DOWN -> "down"
                KeyEvent.KEYCODE_DPAD_LEFT -> "left"
                KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "ok"
                else -> return false
            }
            val w = webRef[0] ?: return false
            if (ev.action == KeyEvent.ACTION_DOWN && !(action == "ok" && ev.repeatCount > 0)) {
                w.evaluateJavascript("window.__dpadKey && window.__dpadKey('$action')") { res ->
                    if (!w.hasFocus()) w.requestFocus()
                    // Pressing Down at the end of the page moves on to the Allow/Deny bar, if there is one.
                    if (action == "down" && res?.contains("edge") == true && actionsNow.isNotEmpty()) {
                        runCatching { barFirst.requestFocus() }
                    }
                }
            }
            return true
        }
        AppState.keyInterceptor = interceptor
        onDispose { if (AppState.keyInterceptor === interceptor) AppState.keyInterceptor = null }
    }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        CompactBar(title, onBack = onClose)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.setSupportZoom(false)
                    overScrollMode = android.view.View.OVER_SCROLL_NEVER
                    isFocusable = true
                    isFocusableInTouchMode = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val u = request.url
                            if (tryCapture(u)) return true
                            // GroupMe's OAuth flow redirects to an http:// login page; cleartext is blocked, so
                            // upgrade GroupMe hosts to https (browsers do this via the server's own redirect).
                            val host = u.host.orEmpty()
                            if (u.scheme == "http" && (host == "groupme.com" || host.endsWith(".groupme.com"))) {
                                view.loadUrl(u.buildUpon().scheme("https").build().toString())
                                return true
                            }
                            return false
                        }

                        override fun onPageStarted(view: WebView, pageUrl: String?, favicon: Bitmap?) {
                            loading = true
                            actions = emptyList()
                            barFocused[0] = false
                            // Fallback for redirects that don't go through shouldOverrideUrlLoading.
                            if (tryCapture(pageUrl?.let { Uri.parse(it) })) view.stopLoading()
                        }

                        override fun onPageFinished(view: WebView, pageUrl: String?) {
                            loading = false
                            view.evaluateJavascript(DPAD_JS, null)
                            if (!view.hasFocus()) view.requestFocus()
                            val detect = Runnable {
                                view.evaluateJavascript("window.__dpadActions && window.__dpadActions()") { res ->
                                    actions = runCatching {
                                        val a = JSONArray(res)
                                        List(a.length()) { a.getString(it) }
                                    }.getOrDefault(emptyList())
                                }
                            }
                            detect.run()
                            view.postDelayed(detect, 800)
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) loading = false
                        }
                    }
                    loadUrl(url)
                    requestFocus()
                    webRef[0] = this
                }
            },
            onRelease = {
                webRef[0] = null
                it.destroy()
            },
        )

        // Fallback for consent pages: real buttons that press the page's Allow / Deny, reachable with
        // Down from the end of the page.
        if (actions.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(4.dp)
                    .onFocusChanged { barFocused[0] = it.hasFocus }
                    .onPreviewKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp) {
                            focusManager.clearFocus()
                            barFocused[0] = false
                            webRef[0]?.requestFocus()
                            true
                        } else false
                    },
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                actions.forEachIndexed { i, label ->
                    PadButton(
                        label,
                        onClick = {
                            webRef[0]?.evaluateJavascript(
                                "window.__dpadClick && window.__dpadClick(" + JSONObject.quote(label) + ")",
                                null,
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .then(if (i == 0) Modifier.focusRequester(barFirst) else Modifier),
                        primary = i == 0,
                    )
                }
            }
        }
    }
}
