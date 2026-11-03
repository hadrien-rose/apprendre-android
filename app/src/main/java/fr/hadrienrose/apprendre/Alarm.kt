package fr.hadrienrose.apprendre

import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * L'alarme des cartes ratées (1.3, chantier 1 du 29/10, choix d'Hadrien sur la maquette apprendre-carte-ratee) : la page dit
 * l'heure (10 min après la fin de la séance), le nombre et les réglages de ⚙ par `ApprendreNative.alarm` ; l'app pose UNE
 * alarme exacte (setAlarmClock : traverse la veille, l'icône ⏰ dans la barre d'état), qui sonne même app fermée.
 * Par défaut une VRAIE alarme : notification plein écran (AlarmActivity sur l'écran verrouillé), le son d'alarme en boucle
 * (FLAG_INSISTENT) pendant la durée choisie (setTimeoutAfter) ; ou une simple notification. Son et vibreur selon ⚙ : un canal
 * par réglage (Android fige le son d'un canal). Toucher = les cartes ratées (#r=rat) ; « Dans 5 min » la repose.
 * Rien d'une carte ne sort : le titre ne dit que le nombre.
 */
object Alarm {
    private const val PREF = "apprendre"
    private const val K_AT = "rat_at"
    private const val K_N = "rat_n"
    private const val K_MODE = "rat_mode"
    private const val K_SON = "rat_son"
    private const val K_VIB = "rat_vib"
    private const val K_DUREE = "rat_duree"
    const val ACTION_FIRE = "fr.hadrienrose.apprendre.RATEES"
    const val ACTION_SNOOZE = "fr.hadrienrose.apprendre.RATEES_SNOOZE"
    const val ACTION_HUSH = "fr.hadrienrose.apprendre.HUSH"
    const val NOTIF_ID = 10

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** La dernière alarme demandée (at = 0 : déjà sonnée ou annulée ; les réglages restent pour « Dans 5 min »). */
    fun load(ctx: Context): Rules.AlarmSpec? {
        val p = prefs(ctx)
        if (!p.contains(K_MODE)) return null
        return Rules.AlarmSpec(p.getLong(K_AT, 0L), p.getInt(K_N, 1), p.getString(K_MODE, "alarme") ?: "alarme",
            p.getBoolean(K_SON, true), p.getBoolean(K_VIB, true), p.getInt(K_DUREE, 60))
    }

    private fun save(ctx: Context, s: Rules.AlarmSpec) {
        prefs(ctx).edit().putLong(K_AT, s.at).putInt(K_N, s.n).putString(K_MODE, s.mode).putBoolean(K_SON, s.son)
            .putBoolean(K_VIB, s.vibreur).putInt(K_DUREE, s.duree).apply()
    }

    /** Ce que la page demande : l'alarme à poser, ou null = l'annuler (rien à sonner). */
    fun set(ctx: Context, s: Rules.AlarmSpec?) {
        if (s == null) prefs(ctx).edit().putLong(K_AT, 0L).apply() else save(ctx, s)
        schedule(ctx)
    }

    private fun firePending(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(ctx, 10, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Ouvrir l'app sur les cartes ratées. */
    fun openPending(ctx: Context): PendingIntent =
        PendingIntent.getActivity(ctx, 11, Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_RATEES)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun exactAllowed(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    fun fullScreenAllowed(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true

    /** (Re)pose l'alarme : exacte si Android le permet (« Alarmes et rappels »), sinon à quelques minutes près. */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = firePending(ctx)
        am.cancel(pi)
        val s = load(ctx) ?: return
        if (s.at <= 0L) return
        if (s.at < System.currentTimeMillis() - 60_000L) { prefs(ctx).edit().putLong(K_AT, 0L).apply(); return }
        try {
            if (exactAllowed(ctx)) am.setAlarmClock(AlarmManager.AlarmClockInfo(s.at, openPending(ctx)), pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.at, pi)
        }
    }

    /** Le canal d'un réglage de sonnerie (le même pour l'alarme des ratées et celle du matin, 1.4 : un seul réglage). */
    fun channel(ctx: Context, mode: String, son: Boolean, vibreur: Boolean): String = ensureChannel(ctx, Rules.AlarmSpec(0L, 1, mode, son, vibreur, 60))

    private fun ensureChannel(ctx: Context, s: Rules.AlarmSpec): String {
        val id = Rules.alarmChannel(s.mode, s.son, s.vibreur)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return id
        if (nm.getNotificationChannel(id) != null) return id
        val alarmMode = s.mode != "notif"
        val name = ctx.getString(if (alarmMode) R.string.rat_channel_alarm else R.string.rat_channel_notif) +
            " · " + ctx.getString(if (s.son) R.string.rat_son_on else R.string.rat_son_off) +
            (if (alarmMode) "" else " · " + ctx.getString(if (s.vibreur) R.string.rat_vib_on else R.string.rat_vib_off))
        val ch = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH)
        ch.description = ctx.getString(R.string.rat_channel_desc)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        if (s.son) {
            val alarm = s.mode != "notif"
            val uri = RingtoneManager.getDefaultUri(if (alarm) RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ch.setSound(uri, AudioAttributes.Builder().setUsage(if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        } else ch.setSound(null, null)
        // 1.5 : une vraie alarme vibre par l'app (Buzz, toute la durée, usage ALARME) ; son canal, jamais (pas de double vibration)
        val chVib = s.vibreur && !alarmMode
        ch.enableVibration(chVib)
        if (chVib) ch.vibrationPattern = longArrayOf(0, 600, 400, 600, 400, 600)
        nm.createNotificationChannel(ch)
        return id
    }

    /** L'heure est venue : la notification (plein écran pour une vraie alarme), une fois. */
    fun fire(ctx: Context) {
        val s = load(ctx) ?: return
        if (s.at <= 0L) return
        prefs(ctx).edit().putLong(K_AT, 0L).apply()
        show(ctx, s)
    }

    fun show(ctx: Context, s: Rules.AlarmSpec) {
        val ch = ensureChannel(ctx, s)
        val alarm = s.mode != "notif"
        val snooze = PendingIntent.getBroadcast(ctx, 12, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_SNOOZE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(ctx, ch)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle((if (alarm) "⏰ " else "🧠 ") + Rules.alarmText(s.n))
            .setContentText(ctx.getString(R.string.rat_text))
            .setContentIntent(openPending(ctx))
            .setAutoCancel(true)
            .setCategory(if (alarm) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, ctx.getString(R.string.rat_snooze), snooze).build())
            .addAction(Notification.Action.Builder(null, ctx.getString(R.string.rat_open), openPending(ctx)).build())
        if (alarm) {
            val full = PendingIntent.getActivity(ctx, 13, Intent(ctx, AlarmActivity::class.java).putExtra("n", s.n)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.setFullScreenIntent(full, true).setTimeoutAfter(s.duree * 1000L)      // la sonnerie dure ce que dit ⚙
            b.setDeleteIntent(Buzz.hushPending(ctx))                                // l'écarter d'un geste fait taire le vibreur
        }
        val n = b.build()
        if (alarm) n.flags = n.flags or Notification.FLAG_INSISTENT                // le son en boucle jusqu'au geste ou à la fin
        try { ctx.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, n) } catch (_: SecurityException) { }
        if (alarm && s.vibreur) Buzz.start(ctx, s.duree)
    }

    fun stop(ctx: Context) {
        Buzz.stop(ctx)
        ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
    }

    /** « Dans 5 min » : la même alarme, 5 minutes plus tard. */
    fun snooze(ctx: Context) {
        stop(ctx)
        val s = load(ctx) ?: return
        set(ctx, s.copy(at = System.currentTimeMillis() + Rules.SNOOZE_MS))
    }
}

/**
 * Le vibreur d'une vraie alarme (1.5, demande d'Hadrien du 03/11 : « ça ne vibre pas alors que ça devrait »). Avant, seule la
 * notification vibrait : trois secousses de 0,6 s, une fois, et — son coupé — la notification n'était plus une alarme pour Android,
 * que « Ne pas déranger » ou le silencieux pouvaient faire taire. Ici l'app vibre ELLE-MÊME, en usage ALARME (comme le réveil),
 * 0,9 s / 0,5 s pendant toute la durée de ⚙ (Rules.buzzPattern), puis s'arrête d'elle-même ; un geste (Revoir, Jouer, Dans 5 min,
 * Retour, la notification écartée) l'arrête avant.
 */
object Buzz {
    // 1.6 (03/11, « quand je clique sur Revoir, le vibreur ne s'arrête pas ») : Android n'arrête une vibration que si l'arrêt vient
    // du MÊME vibreur (le jeton de son VibratorManager, un par Context) ; elle partait du récepteur (contexte de l'application) et
    // « Revoir » l'arrêtait depuis l'écran d'alarme ou l'app (leur propre contexte) : sans effet. Tout passe désormais par le
    // contexte de l'application ; et si l'app a été relancée entre-temps (nouveau jeton), une vibration d'1 ms, imperceptible, en
    // usage ALARME, remplace celle qui dure (Android coupe une vibration finie pour la nouvelle).
    private fun vibrator(ctx: Context): Vibrator? {
        val app = ctx.applicationContext ?: ctx
        return if (Build.VERSION.SDK_INT >= 31) app.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") app.getSystemService(Vibrator::class.java)
    }

    private fun vibrateAlarm(v: Vibrator, effect: VibrationEffect) {
        try {
            if (Build.VERSION.SDK_INT >= 33) v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            else @Suppress("DEPRECATION") v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        } catch (_: SecurityException) { }
    }

    fun start(ctx: Context, secs: Int) {
        val v = vibrator(ctx) ?: return
        if (!v.hasVibrator()) return
        vibrateAlarm(v, VibrationEffect.createWaveform(Rules.buzzPattern(secs), -1))
    }

    fun stop(ctx: Context) {
        val v = vibrator(ctx) ?: return
        try { v.cancel() } catch (_: SecurityException) { }
        if (v.hasVibrator()) {
            vibrateAlarm(v, VibrationEffect.createOneShot(1, 1))
            try { v.cancel() } catch (_: SecurityException) { }
        }
    }

    /** Une alarme sonne encore (sa notification est là). */
    fun ringing(ctx: Context): Boolean = try {
        Rules.ringing(ctx.getSystemService(NotificationManager::class.java)?.activeNotifications?.map { it.id }?.toIntArray() ?: IntArray(0))
    } catch (_: Exception) { false }

    /** Ouvrir l'app ou la toucher pendant qu'une alarme sonne = un geste : elle se tait (1.6). */
    fun hushIfRinging(ctx: Context) {
        if (!ringing(ctx)) return
        Alarm.stop(ctx); Matin.stop(ctx)
    }

    fun hushPending(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(ctx, 14, Intent(ctx, AlarmReceiver::class.java).setAction(Alarm.ACTION_HUSH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

/** L'alarme posée par l'app (non exportée) : sonner, « Dans 5 min » depuis la notification, ou se taire (notification écartée). */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Alarm.ACTION_FIRE -> Alarm.fire(ctx.applicationContext)
            Alarm.ACTION_SNOOZE -> Alarm.snooze(ctx.applicationContext)
            Alarm.ACTION_HUSH -> Buzz.stop(ctx.applicationContext)
        }
    }
}

/**
 * La vraie alarme, plein écran sur l'écran verrouillé (la maquette : « ⏰ 2 cartes à revoir », « Dans 5 min », « Revoir ») ;
 * le son vient de la notification (en boucle) et s'arrête au geste. 1.4 : la même pour l'alarme du matin (`kind` = matin :
 * « 3 nouvelles · 10 à revoir », « Dans 5 min », « Jouer » = l'accueil).
 */
class AlarmActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        val n = intent.getIntExtra("n", 1)
        val matin = intent.getStringExtra("kind") == "matin"
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Color.parseColor("#101010"))
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        fun text(t: String, sp: Float, color: String, serif: Boolean = false) = TextView(this).apply {
            text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(Color.parseColor(color)); gravity = Gravity.CENTER
            if (serif) typeface = Typeface.SERIF
            setPadding(0, dp(6), 0, dp(6))
        }
        box.addView(text("⏰", 64f, "#FDFBF0"))
        box.addView(text(if (matin) intent.getStringExtra("title") ?: Rules.MATIN_TEXT else Rules.alarmText(n), 30f, "#FDFBF0", serif = true))
        box.addView(text(getString(if (matin) R.string.matin_text else R.string.rat_text), 15f, "#A8A39A"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(28), 0, 0) }
        fun button(t: String, solid: Boolean, go: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.parseColor(if (solid) "#101010" else "#FDFBF0"))
            setBackgroundColor(Color.parseColor(if (solid) "#FDFBF0" else "#242424"))
            setOnClickListener { go() }
        }
        val lp = { LinearLayout.LayoutParams(0, dp(56), 1f).apply { setMargins(dp(6), 0, dp(6), 0) } }
        row.addView(button(getString(R.string.rat_snooze), false) { if (matin) Matin.snooze(this) else Alarm.snooze(this); finish() }, lp())
        row.addView(button(getString(if (matin) R.string.matin_open else R.string.rat_open), true) {
            if (matin) Matin.stop(this) else Alarm.stop(this)
            startActivity(Intent(this, MainActivity::class.java).setAction(if (matin) MainActivity.ACTION_MATIN else MainActivity.ACTION_RATEES)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }, lp())
        box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(box)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (intent.getStringExtra("kind") == "matin") Matin.stop(this) else Alarm.stop(this); finish() }
}
