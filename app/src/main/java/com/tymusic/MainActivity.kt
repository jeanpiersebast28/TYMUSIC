package com.tymusic

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.tymusic.ui.theme.TYMusicTheme
import java.io.ByteArrayInputStream

private const val TAG = "TYMusic"

private val AD_HOSTS = listOf(
    "doubleclick.net",
    "googlesyndication.com",
    "googleadservices.com",
    "adservice.google.com",
    "pagead2.google.com",
    "ad.youtube.com",
    "ads.youtube.com",
)

private val AD_URL_PATTERNS = listOf(
    "/api/stats/ads",
    "/get_midroll_info",
    "/ptracking",
    "/pagead/",
    "&adformat=",
    "ad_type=video",
    "/youtubei/v1/log_event",
)

private fun isAdUrl(uri: Uri): Boolean {
    val host = uri.host?.lowercase() ?: return false
    if (AD_HOSTS.any { host == it || host.endsWith(".$it") }) return true
    val url = uri.toString().lowercase()
    return AD_URL_PATTERNS.any { url.contains(it) }
}

private const val MUSIC_URL = "https://music.youtube.com"

private const val VISIBILITY_SPOOF_JS = """
    (function() {
        if (window.__tyVisibilitySpoofed) return;
        window.__tyVisibilitySpoofed = true;
        var spoof = function(obj, prop, value) {
            try {
                Object.defineProperty(obj, prop, {
                    get: function() { return value; },
                    configurable: true
                });
            } catch (e) {}
        };
        spoof(document, 'hidden', false);
        spoof(document, 'webkitHidden', false);
        spoof(document, 'visibilityState', 'visible');
        spoof(document, 'webkitVisibilityState', 'visible');
        var originalAddEventListener = EventTarget.prototype.addEventListener;
        EventTarget.prototype.addEventListener = function(type, listener, options) {
            if ((type === 'visibilitychange' || type === 'webkitvisibilitychange') &&
                (this === document || this === window)) {
                return originalAddEventListener.call(this, '__blocked_visibility__', listener, options);
            }
            return originalAddEventListener.call(this, type, listener, options);
        };
        try {
            var docProto = Object.getPrototypeOf(document);
            Object.defineProperty(docProto, 'onvisibilitychange', {
                set: function(fn) {},
                get: function() { return null; },
                configurable: true
            });
        } catch (e) {}
        console.log('TYTEST visibility spoofed');
    })();
"""

private const val AD_BLOCK_JS = """
    (function() {
        if (window.__tyAdBlock) return;
        window.__tyAdBlock = true;
        var AD_KEYS = ['adPlacements', 'adSlots', 'playerAds', 'adBreaks'];
        var stripCount = 0;
        var stripAdNodes = function(node) {
            if (!node || typeof node !== 'object') return;
            if (Array.isArray(node)) {
                for (var i = 0; i < node.length; i++) stripAdNodes(node[i]);
                return;
            }
            for (var key in node) {
                if (!Object.prototype.hasOwnProperty.call(node, key)) continue;
                if (AD_KEYS.indexOf(key) !== -1) {
                    delete node[key];
                    stripCount++;
                    continue;
                }
                stripAdNodes(node[key]);
            }
        };
        var hasAdKeys = function(text) {
            return typeof text === 'string' && (
                text.indexOf('"adPlacements"') !== -1 ||
                text.indexOf('"adSlots"') !== -1 ||
                text.indexOf('"playerAds"') !== -1 ||
                text.indexOf('"adBreaks"') !== -1
            );
        };
        var cleanPlayerResponse = function(pr) {
            if (!pr || typeof pr !== 'object') return pr;
            if (Array.isArray(pr)) { stripAdNodes(pr); return pr; }
            AD_KEYS.forEach(function(k) { delete pr[k]; });
            return pr;
        };
        try {
            var _ytipr;
            Object.defineProperty(window, 'ytInitialPlayerResponse', {
                get: function() { return _ytipr; },
                set: function(v) {
                    _ytipr = v;
                    stripAdNodes(_ytipr);
                    window.__tyLastPlayerResponse = _ytipr;
                    console.log('TYTEST trapped ytInitialPlayerResponse');
                },
                configurable: true
            });
        } catch (e) { console.log('TYTEST trap fail: ' + e); }
        try {
            var _ytid;
            Object.defineProperty(window, 'ytInitialData', {
                get: function() { return _ytid; },
                set: function(v) {
                    _ytid = v;
                    stripAdNodes(_ytid);
                    console.log('TYTEST trapped ytInitialData');
                },
                configurable: true
            });
        } catch (e) {}
        if (window.ytInitialPlayerResponse) stripAdNodes(window.ytInitialPlayerResponse);
        if (window.ytInitialData) stripAdNodes(window.ytInitialData);

        var originalJsonParse = JSON.parse;
        JSON.parse = function(text, reviver) {
            var data = originalJsonParse.apply(this, arguments);
            try {
                if (hasAdKeys(text)) {
                    var before = stripCount;
                    stripAdNodes(data);
                    if (stripCount > before) {
                        console.log('TYTEST JSON.parse stripped ' + (stripCount - before) + ' ad nodes');
                    }
                }
            } catch (e) {}
            return data;
        };

        setInterval(function() {
            try {
                var player = document.getElementById('movie_player');
                var adShowing = player && player.classList.contains('ad-showing');
                var video = document.querySelector('video');
                if (adShowing) {
                    console.log('TYTEST watchdog: ad showing!');
                    if (video && video.duration && isFinite(video.duration)) {
                        video.currentTime = video.duration;
                    }
                    var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-skip-ad-button, .ytp-ad-skip-button-slot');
                    if (skipBtn) { skipBtn.click(); }
                    if (video && !video.muted) {
                        video.muted = true;
                        video.__tyMutedByUs = true;
                    }
                    player.classList.remove('ad-showing');
                } else if (video && video.__tyMutedByUs) {
                    video.muted = false;
                    video.__tyMutedByUs = false;
                }
            } catch (e) {}
        }, 150);
        console.log('TYTEST adblock ready');
    })();
"""

private const val TAP_HIGHLIGHT_JS = """
    (function() {
        var install = function() {
            var s = document.createElement('style');
            s.textContent = '*,*::before,*::after{-webkit-tap-highlight-color:rgba(0,0,0,0)!important;}';
            document.head.appendChild(s);
            console.log('TYTEST tap highlight disabled');
        };
        if (document.head) { install(); }
        else { document.addEventListener('DOMContentLoaded', install); }
    })();
"""

private const val MEDIA_HOOK_JS = """
    (function() {
        if (window.__tyHooked) return;
        window.__tyHooked = true;
        var bigThumbUrl = function(src) {
            try {
                if (src.indexOf('googleusercontent.com') !== -1) {
                    var eq = src.lastIndexOf('=');
                    if (eq > src.lastIndexOf('/')) {
                        return src.substring(0, eq) + '=w544-h544-l90-rj';
                    }
                }
                if (src.indexOf('/default.jpg') !== -1) {
                    return src.replace('/default.jpg', '/hqdefault.jpg');
                }
            } catch (e) {}
            return src;
        };
        var reportPlaying = function(playing) {
            try { AndroidBridge.setPlaying(playing ? '1' : '0'); } catch (e) {}
        };
        ['play', 'pause', 'ended'].forEach(function(evt) {
            document.addEventListener(evt, function(e) {
                var t = e.target;
                if (t && (t.tagName === 'VIDEO' || t.tagName === 'AUDIO')) {
                    reportPlaying(evt === 'play');
                    console.log('TYTEST media event: ' + evt);
                }
            }, true);
        });
        setInterval(function() {
            try {
                var pr = window.ytInitialPlayerResponse;
                if (pr && pr.videoDetails && pr !== window.__tyLastPRseen) {
                    window.__tyLastPRseen = pr;
                    window.__tyLastPlayerResponse = pr;
                    console.log('TYTEST captured playerResponse: ' + (pr.videoDetails.title || '?'));
                }
            } catch (e) {}
            var media = document.querySelector('video,audio');
            reportPlaying(!!(media && !media.paused && !media.ended));
            try {
                if (media) {
                    var curMs = Math.round((media.currentTime || 0) * 1000);
                    var durMs = isFinite(media.duration) ? Math.round(media.duration * 1000) : 0;
                    AndroidBridge.setProgress(curMs, durMs);
                }
            } catch (e) {}
            var meta = null;
            try {
                meta = window.__tyLastPlayerResponse ? window.__tyLastPlayerResponse.videoDetails : null;
            } catch (e) {}
            if (meta && meta.title) {
                AndroidBridge.setTrack(String(meta.title));
                var author = meta.author ? String(meta.author).split(',')[0].trim() : '';
                AndroidBridge.setArtist(author);
            } else {
                var titleEl = document.querySelector('ytmusic-player-bar .title');
                var title = titleEl ? titleEl.textContent.trim() : '';
                try { AndroidBridge.setTrack(title); } catch (e) {}
                var bylineEl = document.querySelector('ytmusic-player-bar .byline');
                var byline = bylineEl ? bylineEl.textContent.trim() : '';
                try { AndroidBridge.setArtist(byline); } catch (e) {}
            }
            var artSrc = '';
            var artSource = '';
            var isValidArt = function(src) {
                if (!src) return false;
                if (src.indexOf('data:') === 0) return src.length > 1000;
                return src.indexOf('http') === 0;
            };
            try {
                if (meta && meta.thumbnail && meta.thumbnail.thumbnails && meta.thumbnail.thumbnails.length > 0) {
                    var u = meta.thumbnail.thumbnails[meta.thumbnail.thumbnails.length - 1].url;
                    if (isValidArt(u)) {
                        artSrc = u;
                        artSource = 'videoDetails';
                    }
                }
                if (!artSrc) {
                    var bar = document.querySelector('ytmusic-player-bar');
                    var img = bar ? bar.querySelector('#thumbnail img, yt-img-shadow img, img.image') : null;
                    if (!img && bar) {
                        var probe = function(root) {
                            if (!root) return null;
                            var found = root.querySelector('img');
                            if (found) return found;
                            var children = root.querySelectorAll('*');
                            for (var i = 0; i < children.length; i++) {
                                if (children[i].shadowRoot) {
                                    found = probe(children[i].shadowRoot);
                                    if (found) return found;
                                }
                            }
                            return null;
                        };
                        img = probe(bar.shadowRoot);
                    }
                    if (img && isValidArt(img.src)) {
                        artSrc = img.src;
                        artSource = 'playerBar';
                    }
                }
                if (!artSrc) {
                    var video = document.querySelector('video');
                    if (video && isValidArt(video.poster)) {
                        artSrc = video.poster;
                        artSource = 'poster';
                    }
                }
            } catch (e) {}
            if (artSrc !== window.__tyLastArtSent) {
                console.log('TYTEST artwork source=' + artSource + ' len=' + artSrc.length);
                window.__tyLastArtSent = artSrc;
            }
            if (artSrc) {
                try { AndroidBridge.setArtwork(bigThumbUrl(artSrc)); } catch (e) {}
            }
        }, 3000);
    })();
"""

private const val COMMAND_PLAY_PAUSE_JS = """
    (function() {
        var b = document.querySelector('ytmusic-player-bar #play-pause-button') ||
                document.querySelector('#play-pause-button');
        if (b) { b.click(); return; }
        var v = document.querySelector('video,audio');
        if (v) { if (v.paused) { v.play(); } else { v.pause(); } }
    })();
"""

private const val COMMAND_NEXT_JS = """
    (function() {
        var b = document.querySelector('ytmusic-player-bar .next-button') ||
                document.querySelector('.next-button');
        if (b) b.click();
    })();
"""

private const val COMMAND_PREVIOUS_JS = """
    (function() {
        var b = document.querySelector('ytmusic-player-bar .previous-button') ||
                document.querySelector('.previous-button');
        if (b) b.click();
    })();
"""

class MainActivity : ComponentActivity() {

    private var webView: WebView? = null

    @Volatile
    private var isPlaying = false

    @Volatile
    private var trackTitle: String? = null

    private fun pushState() {
        Log.d(TAG, "pushState playing=$isPlaying title=$trackTitle")
        PlaybackService.updateState(applicationContext, isPlaying, trackTitle)
    }

    private val jsBridge = object {
        @JavascriptInterface
        fun setPlaying(value: String) {
            Log.d(TAG, "bridge.setPlaying=$value")
            isPlaying = value == "1"
            pushState()
        }

        @JavascriptInterface
        fun setTrack(value: String) {
            trackTitle = value.ifBlank { null }
            pushState()
        }

        @JavascriptInterface
        fun setProgress(currentMs: Long, durationMs: Long) {
            PlaybackService.updateProgress(applicationContext, currentMs, durationMs)
        }

        @JavascriptInterface
        fun setArtwork(url: String) {
            PlaybackService.updateArtwork(url)
        }

        @JavascriptInterface
        fun setArtist(value: String) {
            PlaybackService.updateArtist(value)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "activity onCreate")
        requestNotificationPermissionIfNeeded()
        enableEdgeToEdge()

        PlayerBus.commandHandler = { command ->
            runOnUiThread { executeCommand(command) }
        }

        setContent {
            TYMusicTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MusicWebView(
                        bridge = jsBridge,
                        onWebViewReady = { webView = it },
                    )
                }
            }
        }
    }

    private fun executeCommand(command: String) {
        Log.d(TAG, "executeCommand=$command")
        if (command.startsWith(PlaybackService.COMMAND_SEEK_PREFIX)) {
            val targetMs = command.substringAfter(':').toLongOrNull() ?: return
            val seconds = targetMs / 1000.0
            webView?.evaluateJavascript(
                "(function(){var v=document.querySelector('video,audio');if(v&&isFinite(v.duration)){v.currentTime=$seconds;}})()",
                null,
            )
            return
        }
        val script = when (command) {
            PlaybackService.COMMAND_PLAY_PAUSE -> COMMAND_PLAY_PAUSE_JS
            PlaybackService.COMMAND_NEXT -> COMMAND_NEXT_JS
            PlaybackService.COMMAND_PREVIOUS -> COMMAND_PREVIOUS_JS
            else -> return
        }
        webView?.evaluateJavascript(script, null)
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "activity onPause")
        if (isPlaying) {
            pushState()
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "activity onStop")
        if (isPlaying) {
            pushState()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "activity onResume")
    }

    override fun onDestroy() {
        Log.d(TAG, "activity onDestroy finishing=$isFinishing")
        PlayerBus.commandHandler = null
        super.onDestroy()
        if (isFinishing || isDestroyed) {
            isPlaying = false
            PlaybackService.stopNow(applicationContext)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }
}

object WebViewHolder {
    @Volatile
    var webView: WebView? = null
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MusicWebView(
    bridge: Any,
    onWebViewReady: (WebView) -> Unit,
    modifier: Modifier = Modifier,
) {
    var canGoBack by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        PlayerBus.historyListener = { canGoBack = it }
        onDispose { PlayerBus.historyListener = null }
    }

    BackHandler(enabled = canGoBack) {
        WebViewHolder.webView?.goBack()
    }

    AndroidView(
        modifier = modifier
            .fillMaxSize()
            .padding(WindowInsets.safeDrawing.asPaddingValues()),
        factory = { context ->
            WebViewHolder.webView?.also { existing ->
                Log.d(TAG, "reattaching persistent WebView")
                (existing.parent as? ViewGroup)?.removeView(existing)
                canGoBack = existing.canGoBack()
                onWebViewReady(existing)
            } ?: createMusicWebView(
                context.applicationContext,
                bridge,
            ).also { created ->
                WebViewHolder.webView = created
                canGoBack = false
                onWebViewReady(created)
            }
        },
    )
}

private class BackgroundSafeWebView(context: Context) : WebView(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(View.VISIBLE)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(true)
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createMusicWebView(
    appContext: Context,
    bridge: Any,
): WebView {
    val webView = BackgroundSafeWebView(appContext)
    webView.layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )
    webView.settings.javaScriptEnabled = true
    webView.settings.domStorageEnabled = true
    webView.settings.mediaPlaybackRequiresUserGesture = false
    webView.settings.javaScriptCanOpenWindowsAutomatically = true
    webView.settings.setSupportMultipleWindows(true)
    webView.settings.userAgentString = webView.settings.userAgentString.replace("; wv", "")

    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(webView, true)
    }

    webView.webChromeClient = object : WebChromeClient() {
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean {
            val target = WebViewHolder.webView ?: view
            val trap = WebView(view.context)
            trap.webViewClient = object : WebViewClient() {
                override fun onPageStarted(popup: WebView, url: String, favicon: Bitmap?) {
                    super.onPageStarted(popup, url, favicon)
                    if (url == "about:blank") return
                    target.loadUrl(url)
                    popup.post { popup.destroy() }
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = trap
            resultMsg.sendToTarget()
            return true
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        webView.settings.setOffscreenPreRaster(true)
    }

    webView.addJavascriptInterface(bridge, "AndroidBridge")

    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
        WebViewCompat.addDocumentStartJavaScript(
            webView,
            VISIBILITY_SPOOF_JS + AD_BLOCK_JS + TAP_HIGHLIGHT_JS,
            setOf("https://music.youtube.com"),
        )
    } else {
        Log.w(TAG, "DOCUMENT_START_SCRIPT not supported")
    }

    webView.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            if (isAdUrl(request.url)) {
                Log.d(TAG, "blocked ad url: ${request.url.host}")
                return WebResourceResponse(
                    "text/plain",
                    "utf-8",
                    ByteArrayInputStream(ByteArray(0)),
                )
            }
            return null
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            PlayerBus.historyListener?.invoke(view.canGoBack())
        }

        override fun onPageFinished(view: WebView, url: String?) {
            Log.d(TAG, "onPageFinished $url")
            view.evaluateJavascript(MEDIA_HOOK_JS, null)
        }
    }
    webView.loadUrl(MUSIC_URL)
    return webView
}
