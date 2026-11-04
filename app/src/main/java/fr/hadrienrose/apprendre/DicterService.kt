package fr.hadrienrose.apprendre

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.webkit.CookieManager
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * « Dis-le, c'est une carte » (1.7, 04/11) : l'enregistreur d'une DICTÉE DE CARTE — un SERVICE DE PREMIER PLAN de type
 * microphone, comme celui de l'app Dictée (Android coupe le micro d'une page web cachée ; l'app 1.6 refusait même le micro
 * à la page). La page ouvre une PRISE chez Prisme et appelle `ApprendreNative.dicterStart(prise, url)` : le service lit le
 * micro en PCM 16 bits, mono, 16 kHz, et ENVOIE le son au fil de la voix (POST brut sur `url`, ~256 ms par morceau, dans
 * l'ordre, avec les cookies de la WebView : le login Cloudflare) ; Prisme le relaie à la transcription en temps réel et la
 * page lit le texte. Écran éteint ou app derrière, l'enregistrement CONTINUE (verrou de veille) ; rien ne s'arrête tout
 * seul : « Arrêter » dans la page (`dicterStop`), « Arrêter » dans la notification (qui dit aussi à Prisme de terminer),
 * ou l'app fermée (tâche retirée : le service s'arrête, Prisme termine le travail de lui-même après 20 s sans son).
 * Aucun fichier : le son part et n'est pas gardé.
 */
class DicterService : Service() {
    companion object {
        const val CHANNEL = "dicter_rec"
        const val NOTIF_ID = 9
        const val ACTION_START = "fr.hadrienrose.apprendre.DICTER_START"
        const val ACTION_STOP = "fr.hadrienrose.apprendre.DICTER_STOP"
        const val EXTRA_PRISE = "prise"
        const val EXTRA_URL = "url"
        const val RATE = 16_000
        const val CHUNK_SAMPLES = 4096                 // ~256 ms à 16 kHz
        const val NOTIF_MS = 1_000L
        @Volatile var running: Boolean = false
            private set
        @Volatile var prise: String = ""
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var rec: AudioRecord? = null
    private var reader: Thread? = null
    private var sender: Thread? = null
    @Volatile private var stopping = false
    private var wake: PowerManager.WakeLock? = null
    private var url = ""
    private var t0 = 0L
    private val tick = object : Runnable { override fun run() { if (running) { updateNotif(); main.postDelayed(this, NOTIF_MS) } } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent.getStringExtra(EXTRA_PRISE).orEmpty(), intent.getStringExtra(EXTRA_URL).orEmpty())
            ACTION_STOP -> stop(tellServer = true)
            else -> if (!running) stopSelf()
        }
        return START_NOT_STICKY
    }

    /** L'app fermée (sa tâche retirée) : on s'arrête ; Prisme termine seul quand le son ne vient plus. */
    override fun onTaskRemoved(rootIntent: Intent?) { stop(tellServer = false); super.onTaskRemoved(rootIntent) }

    override fun onDestroy() { if (running) stop(tellServer = false); super.onDestroy() }

    private fun start(p: String, u: String) {
        if (!Rules.dicterUrlOk(u) || !Rules.isDigits(p)) { stopSelf(); return }
        if (running) stop(tellServer = false)        // une autre prise commence : la précédente est lâchée (Prisme la finit)
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { stopSelf(); return }
        ensureChannel()
        prise = p; url = u; t0 = System.currentTimeMillis(); stopping = false; queue.clear()
        try {
            val n = buildNotif()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(NOTIF_ID, n)
        } catch (_: Exception) { stopSelf(); return }
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, CHUNK_SAMPLES * 2 * 4))
        } catch (_: Exception) { null }
        if (r == null || r.state != AudioRecord.STATE_INITIALIZED) { r?.release(); stopForegroundCompat(); stopSelf(); return }
        rec = r
        acquireWake()
        running = true
        r.startRecording()
        reader = Thread({ readLoop(r) }, "dicter-lit").also { it.start() }
        sender = Thread({ sendLoop() }, "dicter-envoie").also { it.start() }
        main.removeCallbacks(tick); main.postDelayed(tick, NOTIF_MS)
    }

    /** Le micro → des morceaux PCM 16 bits petit-boutiste, dans l'ordre. */
    private fun readLoop(r: AudioRecord) {
        val buf = ShortArray(CHUNK_SAMPLES)
        while (!stopping) {
            val n = try { r.read(buf, 0, buf.size) } catch (_: Exception) { -1 }
            if (n <= 0) { if (n < 0) break; continue }
            queue.offer(Rules.pcmBytes(buf, n))
        }
    }

    /** Un envoi à la fois, dans l'ordre ; un raté réseau est repris une fois, puis le morceau est lâché (le suivant repart). */
    private fun sendLoop() {
        while (true) {
            val chunk = queue.poll(300, TimeUnit.MILLISECONDS)
            if (chunk == null) { if (stopping) break else continue }
            val more = ArrayList<ByteArray>().apply { add(chunk); queue.drainTo(this, 8) }     // le retard se rattrape en un envoi
            val body = if (more.size == 1) chunk else Rules.concat(more)
            if (!post(url, body, "application/octet-stream") && !stopping) { Thread.sleep(500); post(url, body, "application/octet-stream") }
        }
    }

    private fun post(u: String, body: ByteArray, type: String): Boolean = try {
        val c = URL(u).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 8_000; c.readTimeout = 15_000; c.doOutput = true
        c.setRequestProperty("Content-Type", type)
        c.setRequestProperty("User-Agent", "ApprendreApp/" + BuildConfig.VERSION_NAME)
        CookieManager.getInstance().getCookie(u)?.let { c.setRequestProperty("Cookie", it) }
        c.setFixedLengthStreamingMode(body.size)
        c.outputStream.use { it.write(body) }
        val code = c.responseCode
        try { (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } } catch (_: Exception) { }
        c.disconnect()
        if (code == 404 || code == 410) main.post { stop(tellServer = false) }        // la prise n'existe plus : rien à envoyer
        code in 200..299
    } catch (_: IOException) { false } catch (_: Exception) { false }

    /** « Arrêter » : le micro se tait, ce qui reste à envoyer part (5 s au plus), puis, depuis la notification, Prisme est prié de finir. */
    fun stop(tellServer: Boolean) {
        if (!running) return
        running = false; stopping = true
        main.removeCallbacks(tick)
        try { rec?.stop() } catch (_: Exception) { }
        try { rec?.release() } catch (_: Exception) { }
        rec = null
        val p = prise; val u = url
        Thread {
            try { reader?.join(2_000) } catch (_: Exception) { }
            try { sender?.join(5_000) } catch (_: Exception) { }
            if (tellServer) post(Rules.dicterStopUrl(u), "{\"prise\":$p}".toByteArray(), "application/json")
        }.start()
        stopForegroundCompat(); releaseWake()
        stopSelf()
    }

    private fun secs(): Long = if (t0 > 0) (System.currentTimeMillis() - t0) / 1000 else 0

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, getString(R.string.dict_channel), NotificationManager.IMPORTANCE_LOW)
            ch.description = getString(R.string.dict_channel_desc)
            ch.setShowBadge(false); ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotif(): Notification {
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 3, Intent(this, DicterService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif).setContentTitle(getString(R.string.dict_notif) + " · " + Rules.fmtSecs(secs()))
            .setContentText(getString(R.string.dict_notif_text)).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, getString(R.string.dict_stop), stop).build())
            .build()
    }

    private fun updateNotif() {
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotif()) } catch (_: Exception) { }
    }

    private fun stopForegroundCompat() {
        try { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true) } catch (_: Exception) { }
    }

    private fun acquireWake() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "apprendre:dicter").also { it.acquire(2 * 3600 * 1000L) }
        } catch (_: Exception) { }
    }

    private fun releaseWake() { try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }; wake = null }
}
