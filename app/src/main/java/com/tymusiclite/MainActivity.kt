package com.tymusiclite

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
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
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import kotlinx.coroutines.delay

private const val TAG = "TYMusicLite"
private val DARK_BACKGROUND = Color(0xFF0D0D0D)

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
        try {
            var _ytipr;
            Object.defineProperty(window, 'ytInitialPlayerResponse', {
                get: function() { return _ytipr; },
                set: function(v) {
                    _ytipr = v;
                    stripAdNodes(_ytipr);
                    window.__tyLastPlayerResponse = _ytipr;
                },
                configurable: true
            });
        } catch (e) {}
        try {
            var _ytid;
            Object.defineProperty(window, 'ytInitialData', {
                get: function() { return _ytid; },
                set: function(v) {
                    _ytid = v;
                    stripAdNodes(_ytid);
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
    })();
"""

private const val PLAYER_ARTIST_LINK_JS = """
    (function() {
        if (window.__tyArtistLink) return;
        window.__tyArtistLink = true;
        var splitArtists = function(text) {
            var parts = String(text).split(/\s*[,]\s*|\s*[;]\s*|\s*[•·]\s*|\s+y\s+|\s+&\s+/i);
            var out = [];
            for (var i = 0; i < parts.length; i++) {
                var p = parts[i].trim();
                if (p) out.push(p);
            }
            return out;
        };
        var navigate = function(url) {
            try {
                var app = document.querySelector('ytmusic-app');
                if (app && typeof app.navigateTo === 'function') {
                    app.navigateTo(url);
                    return;
                }
            } catch (err) {}
            window.location.href = url;
        };
        document.addEventListener('click', function(e) {
            var el = e.target;
            while (el && el !== document) {
                if (el.classList && el.classList.contains('ty-artist-link')) {
                    e.preventDefault();
                    e.stopPropagation();
                    navigate(el.href);
                    return;
                }
                el = el.parentElement;
            }
        }, true);
        var enhance = function() {
            try {
                var pp = document.querySelector('ytmusic-player-page');
                if (!pp) return;
                if (getComputedStyle(pp).visibility === 'hidden') return;
                var byline = pp.querySelector('.byline');
                if (!byline) return;
                var text = (byline.textContent || '').trim();
                if (!text) return;
                var names = splitArtists(text);
                if (names.length === 0) return;
                if (byline.__tyLastText === text) return;
                byline.__tyLastText = text;
                var frag = document.createDocumentFragment();
                for (var i = 0; i < names.length; i++) {
                    var a = document.createElement('a');
                    a.href = 'https://music.youtube.com/search?q=' + encodeURIComponent(names[i]);
                    a.textContent = names[i];
                    a.className = 'ty-artist-link';
                    a.style.textDecoration = 'none';
                    a.style.color = 'inherit';
                    a.style.pointerEvents = 'auto';
                    frag.appendChild(a);
                    if (i < names.length - 1) frag.appendChild(document.createTextNode(', '));
                }
                byline.replaceChildren(frag);
                window.__tyEnhancedArtists = names;
            } catch (err) {}
        };
        enhance();
        setInterval(enhance, 1000);
        new MutationObserver(function() {
            clearTimeout(window.__tyArtistTimer);
            window.__tyArtistTimer = setTimeout(enhance, 250);
        }).observe(document.documentElement, { childList: true, subtree: true });
    })();
"""

private const val TAP_HIGHLIGHT_JS = """
    (function() {
        var install = function() {
            var s = document.createElement('style');
            s.textContent = '*,*::before,*::after{-webkit-tap-highlight-color:rgba(0,0,0,0)!important;}';
            document.head.appendChild(s);
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
                }
            }, true);
        });
        setInterval(function() {
            try {
                var pr = window.ytInitialPlayerResponse;
                if (pr && pr.videoDetails && pr !== window.__tyLastPRseen) {
                    window.__tyLastPRseen = pr;
                    window.__tyLastPlayerResponse = pr;
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
                    }
                }
                if (!artSrc) {
                    var video = document.querySelector('video');
                    if (video && isValidArt(video.poster)) {
                        artSrc = video.poster;
                    }
                }
            } catch (e) {}
            if (artSrc !== window.__tyLastArtSent) {
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

private const val APP_PROMO_CSS = """
    (function() {
        if (window.__tyPromoCss) return;
        window.__tyPromoCss = true;
        try {
            var s = document.createElement('style');
            s.id = 'ty-hide-promo';
            s.textContent = '.app-install-link{display:none!important}';
            (document.head || document.documentElement).appendChild(s);
        } catch (e) {}
    })();
"""

private const val HIDE_OPEN_APP_PROMO_JS = """
    (function() {
        if (window.__tyHideOpenApp) return;
        window.__tyHideOpenApp = true;
        var reBtn = /(abrir|obtener|descargar|instalar|open|get|install)\s+(la\s+|el\s+)?(aplicaci[o\u00f3]n|app)/i;
        var reGuide = /^abrir\s*app$/i;
        var kill = function() {
            try {
                var els = document.querySelectorAll('.app-install-link');
                for (var i = 0; i < els.length; i++) {
                    if (els[i].style.display !== 'none') els[i].style.display = 'none';
                }
                var guide = document.querySelectorAll('ytmusic-guide-entry-renderer');
                for (var j = 0; j < guide.length; j++) {
                    var t = (guide[j].textContent || '').trim();
                    if (t.length < 30 && reGuide.test(t)) guide[j].style.display = 'none';
                }
                var btns = document.querySelectorAll('a,button,[role="button"]');
                for (var k = 0; k < btns.length; k++) {
                    var bt = (btns[k].textContent || '').trim();
                    if (bt && bt.length < 60 && reBtn.test(bt)) btns[k].style.display = 'none';
                }
            } catch (e) {}
        };
        try {
            var s = document.createElement('style');
            s.id = 'ty-hide-promo';
            s.textContent = '.app-install-link{display:none!important}';
            (document.head || document.documentElement).appendChild(s);
        } catch (e) {}
        kill();
        setInterval(kill, 1500);
        new MutationObserver(function() {
            clearTimeout(window.__tyKillTimer);
            window.__tyKillTimer = setTimeout(kill, 300);
        }).observe(document.documentElement, { childList: true, subtree: true });
    })();
"""

private const val PLAYER_VISIBILITY_JS = """
    (function() {
        if (window.__tyPvHook) return;
        window.__tyPvHook = true;
        var report = function() {
            try {
                var pp = document.querySelector('ytmusic-player-page');
                var open = !!pp && getComputedStyle(pp).visibility !== 'hidden';
                AndroidBridge.setPlayerOpen(open ? '1' : '0');
            } catch (e) {}
        };
        report();
        setInterval(report, 800);
    })();
"""

private const val CLOSE_PLAYER_JS = """
    (function() {
        var pp = document.querySelector('ytmusic-player-page');
        if (!pp) return 'no-player';
        if (getComputedStyle(pp).visibility === 'hidden') return 'already-closed';
        try {
            if (typeof pp.onCollapseButtonClick === 'function') {
                pp.onCollapseButtonClick();
                return 'method';
            }
        } catch (e) {}
        var btns = pp.querySelectorAll('button,[role="button"]');
        for (var i = 0; i < btns.length; i++) {
            var al = btns[i].getAttribute('aria-label') || '';
            if (/minimi|colaps|cerrar|close|dismiss/i.test(al)) {
                btns[i].click();
                return 'clicked';
            }
        }
        return 'no-op';
    })();
"""

private const val COMMAND_PAUSE_MEDIA_JS = """
    (function() {
        var v = document.querySelector('video,audio');
        if (v && !v.paused) { v.pause(); return 'paused'; }
        return 'already-paused';
    })();
"""

private const val AUTO_CONTINUE_JS = """
    (function() {
        if (window.__tyAutoContinue) return;
        window.__tyAutoContinue = true;
        var lastInteraction = Date.now();
        var activity = function() {
            lastInteraction = Date.now();
        };
        try {
            var events = ['mousemove','mousedown','mouseup','touchstart','touchend','touchmove','pointerdown','pointerup','keydown','click','scroll','wheel'];
            for (var i = 0; i < events.length; i++) {
                document.addEventListener(events[i], activity, true);
                window.addEventListener(events[i], activity, true);
            }
        } catch (e) {}
        var dismiss = function() {
            var done = false;
            var expr = /(todav|a[u\u00fa]n|still|continue|continuar|seguir|continuas|yes|s[i\u00ed]|keep|de acuerdo|ok|siguiente)/i;
            var selectors = [
                'yt-confirm-dialog-renderer button[aria-label]',
                'yt-confirm-dialog-renderer button',
                'tp-yt-paper-dialog button[aria-label]',
                'tp-yt-paper-dialog yt-button-renderer button',
                'ytmusic-prompt-banner-renderer button',
                'ytmusic-prompt-banner-renderer tp-yt-paper-button',
                'ytmusic-dialog-renderer button',
                '.ytp-ad-overlay button',
                'iron-overlay-backdrop',
            ];
            for (var s = 0; s < selectors.length; s++) {
                var nodes = document.querySelectorAll(selectors[s]);
                for (var n = 0; n < nodes.length; n++) {
                    var el = nodes[n];
                    var label = (el.getAttribute('aria-label') || '').trim();
                    var text = (el.textContent || '').trim();
                    if (el.tagName === 'IRON-OVERLAY-BACKDROP' || el.tagName === 'YT-CONFIRM-DIALOG-RENDERER') {
                        try { el.remove(); } catch (e) {}
                        continue;
                    }
                    if (label && expr.test(label)) { el.click(); done = true; continue; }
                    if (text && text.length < 30 && expr.test(text)) { el.click(); done = true; }
                }
            }
            var video = document.querySelector('video,audio');
            if (done && video && video.paused) {
                try { video.play(); } catch (e) {}
            }
            return done;
        };
        var keepAlive = function() {
            try {
                if (Date.now() - lastInteraction > 30000) {
                    activity();
                    var evt = new Event('mousemove', { bubbles: true });
                    document.dispatchEvent(evt);
                }
            } catch (e) {}
        };
        setInterval(function() {
            try {
                dismiss();
                keepAlive();
            } catch (e) {}
        }, 1200);
    })();
"""

class MainActivity : ComponentActivity() {

    companion object {
        @Volatile
        var currentActivity: MainActivity? = null
    }

    private var fullscreenContainer: FrameLayout? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    @Volatile
    private var isPlaying = false

    @Volatile
    private var trackTitle: String? = null

    private fun pushState() {
        PlaybackService.updateState(applicationContext, isPlaying, trackTitle)
    }

    private val jsBridge = object {
        @JavascriptInterface
        fun setPlaying(value: String) {
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

        @JavascriptInterface
        fun setPlayerOpen(value: String) {
            WebViewHolder.playerOverlayOpen = value == "1"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentActivity = this
        PlaybackService.clearTaskRemoved()
        requestNotificationPermissionIfNeeded()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(scrim = 0),
        )

        setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DARK_BACKGROUND),
            ) {
                MusicWebView(
                    bridge = jsBridge,
                    onWebViewReady = {},
                )
                SplashOverlay(
                    contentReady = WebViewHolder.pageLoaded.value,
                    backgroundColor = DARK_BACKGROUND,
                )
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        WebViewHolder.webView?.setBackgroundColor(DARK_BACKGROUND.toArgb())
    }

    override fun onPause() {
        super.onPause()
        if (isPlaying) {
            pushState()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isPlaying) {
            pushState()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (currentActivity === this) currentActivity = null
        if ((isFinishing || isDestroyed) && !isPlaying) {
            PlaybackService.stopNow(applicationContext)
        }
    }

    private class ScaleToFillFrameLayout(context: Context) : FrameLayout(context) {
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            if (childCount > 0) {
                val child = getChildAt(0)
                val containerW = (r - l).toFloat()
                val containerH = (b - t).toFloat()
                val childW = child.measuredWidth.toFloat()
                val childH = child.measuredHeight.toFloat()
                if (childW > 0 && childH > 0) {
                    val scale = maxOf(containerW / childW, containerH / childH)
                    child.scaleX = scale
                    child.scaleY = scale
                    child.translationX = (containerW - childW * scale) / 2f
                    child.translationY = (containerH - childH * scale) / 2f
                }
            }
        }
    }

    internal fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreenContainer != null) {
            callback.onCustomViewHidden()
            return
        }
        val container = ScaleToFillFrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            clipChildren = true
            clipToPadding = true
            addView(
                view,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        window.addContentView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        fullscreenContainer = container
        fullscreenCallback = callback
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
    }

    internal fun exitFullscreen() {
        val container = fullscreenContainer ?: return
        (container.parent as? ViewGroup)?.removeView(container)
        fullscreenContainer = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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

    @Volatile
    var playerOverlayOpen: Boolean = false

    var pageLoaded = mutableStateOf(false)

    fun detachAndDestroyWebView() {
        val webView = webView ?: return
        this.webView = null
        playerOverlayOpen = false
        pageLoaded.value = false
        webView.post { webView.destroy() }
    }
}

@Composable
private fun SplashOverlay(
    contentReady: Boolean,
    backgroundColor: Color,
) {
    var visible by remember { mutableStateOf(true) }

    LaunchedEffect(contentReady) {
        if (contentReady) {
            delay(300)
            visible = false
        } else {
            delay(6000)
            visible = false
        }
    }

    val pulse = rememberInfiniteTransition(label = "splashPulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    AnimatedVisibility(
        visible = visible,
        enter = EnterTransition.None,
        exit = fadeOut(animationSpec = tween(durationMillis = 500)) +
            scaleOut(
                targetScale = 1.15f,
                animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
                transformOrigin = TransformOrigin.Center,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                modifier = Modifier
                    .size(120.dp)
                    .graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                    },
                factory = { context ->
                    ImageView(context).apply { setImageResource(R.mipmap.ic_launcher) }
                },
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MusicWebView(
    bridge: Any,
    onWebViewReady: (WebView) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = LocalContext.current as? Activity

    BackHandler(enabled = true) {
        val webView = WebViewHolder.webView
        when {
            webView == null -> activity?.finish()
            WebViewHolder.playerOverlayOpen -> webView.evaluateJavascript(CLOSE_PLAYER_JS, null)
            webView.canGoBack() -> performSmartBack(webView)
            else -> activity?.finish()
        }
    }

    AndroidView(
        modifier = modifier
            .fillMaxSize()
            .padding(WindowInsets.safeDrawing.asPaddingValues()),
        factory = { context ->
            WebViewHolder.webView?.also { existing ->
                (existing.parent as? ViewGroup)?.removeView(existing)
                existing.setBackgroundColor(DARK_BACKGROUND.toArgb())
                onWebViewReady(existing)
            } ?: createMusicWebView(
                context.applicationContext,
                bridge,
            ).also { created ->
                WebViewHolder.webView = created
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

fun runWebViewCommand(command: String) {
    val webView = WebViewHolder.webView ?: return
    webView.post {
        if (command.startsWith(PlaybackService.COMMAND_SEEK_PREFIX)) {
            val targetMs = command.substringAfter(':').toLongOrNull() ?: return@post
            val seconds = targetMs / 1000.0
            webView.evaluateJavascript(
                "(function(){var v=document.querySelector('video,audio');if(v&&isFinite(v.duration)){v.currentTime=$seconds;}})()",
                null,
            )
            return@post
        }
        val script = when (command) {
            PlaybackService.COMMAND_PLAY_PAUSE -> COMMAND_PLAY_PAUSE_JS
            PlaybackService.COMMAND_NEXT -> COMMAND_NEXT_JS
            PlaybackService.COMMAND_PREVIOUS -> COMMAND_PREVIOUS_JS
            PlaybackService.COMMAND_PAUSE_MEDIA -> COMMAND_PAUSE_MEDIA_JS
            else -> return@post
        }
        webView.evaluateJavascript(script, null)
    }
}

private fun performSmartBack(webView: WebView) {
    val list = webView.copyBackForwardList()
    val curIdx = list.currentIndex
    if (curIdx <= 0) return
    val curUrl = webView.url ?: list.getItemAtIndex(curIdx).url ?: ""
    var target = -1
    for (i in curIdx - 1 downTo 0) {
        val entryUrl = list.getItemAtIndex(i).url ?: continue
        if (entryUrl.startsWith("https://accounts.google.com")) continue
        if (entryUrl.contains("/watch")) continue
        target = i
        break
    }
    when {
        target != -1 -> webView.goBackOrForward(target - curIdx)
        else -> webView.loadUrl(MUSIC_URL)
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createMusicWebView(
    appContext: Context,
    bridge: Any,
): WebView {
    val webView = BackgroundSafeWebView(appContext)
    webView.setBackgroundColor(DARK_BACKGROUND.toArgb())
    if (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
        WebView.setWebContentsDebuggingEnabled(true)
    }
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

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            MainActivity.currentActivity?.enterFullscreen(view, callback)
        }

        override fun onHideCustomView() {
            MainActivity.currentActivity?.exitFullscreen()
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
            VISIBILITY_SPOOF_JS + AD_BLOCK_JS + TAP_HIGHLIGHT_JS + APP_PROMO_CSS +
                HIDE_OPEN_APP_PROMO_JS + PLAYER_VISIBILITY_JS + AUTO_CONTINUE_JS +
                PLAYER_ARTIST_LINK_JS,
            setOf("https://music.youtube.com"),
        )
        WebViewCompat.addDocumentStartJavaScript(
            webView,
            HIDE_OPEN_APP_PROMO_JS,
            setOf(
                "https://accounts.google.com",
                "https://accounts.youtube.com",
                "https://myaccount.google.com",
            ),
        )
    } else {
        Log.w(TAG, "DOCUMENT_START_SCRIPT not supported")
    }

    webView.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase()
            if (scheme == "intent" || scheme == "market" ||
                (host == "play.google.com" && uri.path?.startsWith("/store/apps") == true)
            ) {
                view.loadUrl(MUSIC_URL)
                return true
            }
            return false
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            if (isAdUrl(request.url)) {
                return WebResourceResponse(
                    "text/plain",
                    "utf-8",
                    ByteArrayInputStream(ByteArray(0)),
                )
            }
            return null
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            super.onPageCommitVisible(view, url)
            WebViewHolder.pageLoaded.value = true
        }

        override fun onPageFinished(view: WebView, url: String?) {
            view.evaluateJavascript(HIDE_OPEN_APP_PROMO_JS, null)
            view.evaluateJavascript(MEDIA_HOOK_JS, null)
        }
    }
    webView.loadUrl(MUSIC_URL)
    return webView
}
