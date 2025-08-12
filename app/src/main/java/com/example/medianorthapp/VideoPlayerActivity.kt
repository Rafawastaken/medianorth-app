package com.example.medianorthapp

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.medianorthapp.network.SupabaseClient
import kotlinx.coroutines.*

class VideoPlayerActivity : AppCompatActivity() {

    /* ────── JS → Kotlin bridge ────── */
    inner class JSBridge {
        @JavascriptInterface
        fun onEnded() = runOnUiThread { nextVideo() }
    }

    private lateinit var webView: WebView
    private lateinit var refreshJob: Job
    private var current = 0
    private var videos = emptyList<SupabaseClient.Video>()
    private var supaDeviceId = -1L

    /* ─────────────────────────────────────────────── */
    /* Ciclo de vida                                   */
    /* ─────────────────────────────────────────────── */
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(true)

        /* 1. WebView pronta p/ autoplay em Android TV */
        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                pluginState = WebSettings.PluginState.ON
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    Log.d("WEBVIEW", msg.message())
                    return true
                }
            }
            addJavascriptInterface(JSBridge(), "AndroidBridge")
        }
        setContentView(webView)

        /* 2. Device ID recebido do LoginActivity */
        supaDeviceId = intent.getLongExtra("device_id", -1L)
        if (supaDeviceId == -1L) return

        /* 3. Vai buscar vídeos e injeta HTML inicial */
        CoroutineScope(Dispatchers.IO).launch {
            videos = SupabaseClient.getValidVideos(supaDeviceId)
            Log.d("PLAYER", "Recebidos ${videos.size} vídeo(s)")
            if (videos.isEmpty()) return@launch

            withContext(Dispatchers.Main) {
                webView.loadDataWithBaseURL(
                    null,
                    buildPlayerHtml(videos.first().url),
                    "text/html",
                    "utf-8",
                    null
                )
                startRefreshingPlaylist()
            }
        }
    }

    /* ─────────────────────────────────────────────── */
    /* HTML e JavaScript gerados dinamicamente         */
    /* ─────────────────────────────────────────────── */
    private fun buildPlayerHtml(firstUrl: String): String {
        val id = extractId(firstUrl)

        return """
<!doctype html><html><head>
  <meta name="viewport" content="width=device-width,initial-scale=1"/>
  <style>
    html,body{margin:0;height:100%;background:#000}
    #mask{position:fixed;inset:0;background:#000;z-index:10}

    /* esconde logo, avatar, título, botão playlist  */
    .ytp-chrome-top,
    .ytp-chrome-top *,
    .ytp-playlist-menu-button{display:none!important}
  </style>

  <script src="https://www.youtube.com/iframe_api"></script>
  <script>
    /* 1. Função que remove de novo se voltar a aparecer */
    function hideBranding(){
      document.querySelectorAll('.ytp-chrome-top,* .ytp-playlist-menu-button')
              .forEach(e=>e.style.display='none');
    }

    /* 2. Instala observer depois de o player criar o DOM */
    function installObserver(){
      new MutationObserver(hideBranding)
        .observe(document.body,{childList:true,subtree:true});
      hideBranding();        // aplica logo na primeira vez
    }

    /* 3. Player YouTube */
    var player;
    function onYouTubeIframeAPIReady(){
      player = new YT.Player('player',{
        videoId:'$id',
        playerVars:{autoplay:1,mute:1,controls:0,rel:0,modestbranding:1,playsinline:1,fs:0,disablekb:1},
        events:{
          onReady:e=>{
            installObserver();      // body já existe
            e.target.playVideo();
          },
          onStateChange:e=>{
            if(e.data===YT.PlayerState.PLAYING)
              document.getElementById('mask').style.display='none';
            if(e.data===YT.PlayerState.ENDED)
              AndroidBridge.onEnded();
          }
        }
      });
    }

    /* 4. Chamado pelo Kotlin para trocar de vídeo */
    function loadNext(id){
      document.getElementById('mask').style.display='';
      player.loadVideoById(id);
    }
  </script>
</head>
<body>
  <div id="mask"></div>
  <div id="player" style="width:100%;height:100%"></div>
</body></html>
""".trimIndent()
    }



    /* ─────────────────────────────────────────────── */
    /* Navegação / reprodução                          */
    /* ─────────────────────────────────────────────── */
    private fun play(url: String) {
        val id = extractId(url)
        webView.evaluateJavascript("loadNext('$id');", null)
        heartbeat()
    }

    private fun nextVideo() = CoroutineScope(Dispatchers.IO).launch {
        videos = SupabaseClient.getValidVideos(supaDeviceId).ifEmpty { videos }
        withContext(Dispatchers.Main) {
            current = (current + 1) % videos.size
            play(videos[current].url)
        }
    }

    private fun startRefreshingPlaylist() {
        refreshJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(5 * 60 * 1000)
                val fresh = SupabaseClient.getValidVideos(supaDeviceId)
                if (fresh.isNotEmpty() && fresh != videos) {
                    videos = fresh
                    if (current >= videos.size) withContext(Dispatchers.Main) { play(videos.first().url) }
                }
            }
        }
    }

    private fun heartbeat() = CoroutineScope(Dispatchers.IO).launch {
        kotlin.runCatching { SupabaseClient.updateLastSeen(supaDeviceId) }
    }

    override fun onDestroy() {
        if (::refreshJob.isInitialized) refreshJob.cancel()
        super.onDestroy()
    }

    /* ─────────────────────────────────────────────── */
    /* Utilitário para extrair o ID                    */
    /* ─────────────────────────────────────────────── */
    private fun extractId(url: String): String =
        Regex("""(?:v=|\/|embed\/)([A-Za-z0-9_-]{11})""")
            .find(url)?.groupValues?.get(1) ?: ""
}
