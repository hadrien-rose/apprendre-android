package fr.hadrienrose.apprendre

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * L'alarme du MATIN (1.4, chantier 4 du 30/10, maquette apprendre-cartes-du-jour, choix d'Hadrien) : chaque jour coché, à l'heure
 * dite (8 h 50 du lundi au vendredi par défaut), réglés dans ⚙ de la page, qui les redit par `ApprendreNative.matin`. Une alarme
 * exacte (setAlarmClock) ; à l'heure, l'app demande à Prisme (/api/apprendre/matin, un oui ou non et « 3 nouvelles · 10 à
 * revoir », rien d'une carte) : elle se TAIT s'il ne reste rien à faire ET qu'Hadrien a déjà joué depuis le début de la journée
 * (jouer la veille au soir ne la tait pas) ; sinon elle sonne. Prisme muet (hors ligne, login expiré) : elle sonne quand même.
 * La sonnerie est celle des cartes ratées (⚙, un seul réglage). Toucher = l'accueil, les nouvelles d'abord. Elle remplace le
 * rappel de 8 h (1.0 à 1.3), qui n'était qu'une notification sans trace.
 */
object Matin {
    private const val PREF = "apprendre"
    private const val K_SPEC = "matin_spec"
    const val ACTION_FIRE = "fr.hadrienrose.apprendre.MATIN"
    const val ACTION_SNOOZE = "fr.hadrienrose.apprendre.MATIN_SNOOZE"
    const val NOTIF_ID = 11

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** Les réglages redits par la page, ou ceux par défaut (8 h 50, du lundi au vendredi) tant qu'elle n'a rien dit. */
    fun load(ctx: Context): Rules.MatinSpec = Rules.parseMatin(prefs(ctx).getString(K_SPEC, null)) ?: Rules.MATIN_DEFAULT

    /** Ce que la page demande (déjà lu par Rules.parseMatin) : gardé tel quel, l'alarme reposée. */
    fun set(ctx: Context, json: String) {
        if (Rules.parseMatin(json) == null) return
        prefs(ctx).edit().putString(K_SPEC, json).apply()
        schedule(ctx)
    }

    private fun firePending(ctx: Context, code: Int = 20): PendingIntent =
        PendingIntent.getBroadcast(ctx, code, Intent(ctx, MatinReceiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Ouvrir l'app sur l'accueil (les nouvelles d'abord). */
    fun openPending(ctx: Context): PendingIntent =
        PendingIntent.getActivity(ctx, 21, Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_MATIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** L'instant posé pour le prochain matin coché, ou -1 (aucun jour). */
    fun next(ctx: Context, now: Long = System.currentTimeMillis()): Long = load(ctx).let { Rules.nextMatinAt(now, it.heure, it.jours) }

    /** (Re)pose l'alarme du prochain matin coché : exacte si Android le permet, sinon à quelques minutes près. */
    fun schedule(ctx: Context, from: Long = System.currentTimeMillis()) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = firePending(ctx)
        am.cancel(pi)
        val at = next(ctx, from)
        if (at <= 0L) return
        try {
            if (Alarm.exactAllowed(ctx)) am.setAlarmClock(AlarmManager.AlarmClockInfo(at, openPending(ctx)), pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Ce que dit Prisme à l'heure de l'alarme : (sonner, texte), ou null s'il ne répond pas. */
    fun ask(): Pair<Boolean, String>? = try {
        val url = BuildConfig.SITE + "/api/apprendre/matin"
        val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 10_000; instanceFollowRedirects = false
            android.webkit.CookieManager.getInstance().getCookie(url)?.let { setRequestProperty("Cookie", it) }
            setRequestProperty("Accept", "application/json")
        }
        try { if (c.responseCode == 200) Rules.parseMatinAnswer(c.inputStream.bufferedReader().use { it.readText() }) else null } finally { c.disconnect() }
    } catch (_: Throwable) { null }

    /** L'heure est venue (hors du fil principal) : sonner ou se taire, puis le matin suivant. */
    fun fire(ctx: Context) {
        val (sonner, texte) = ask() ?: Pair(true, Rules.MATIN_TEXT)
        if (sonner) show(ctx, texte)
        schedule(ctx, System.currentTimeMillis() + 60_000L)      // jamais deux fois le même matin
    }

    fun show(ctx: Context, texte: String) {
        val s = load(ctx)
        val ch = Alarm.channel(ctx, s.mode, s.son, s.vibreur)
        val alarm = s.mode != "notif"
        val snooze = PendingIntent.getBroadcast(ctx, 22, Intent(ctx, MatinReceiver::class.java).setAction(ACTION_SNOOZE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(ctx, ch)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle((if (alarm) "⏰ " else "🧠 ") + texte)
            .setContentText(ctx.getString(R.string.matin_text))
            .setContentIntent(openPending(ctx))
            .setAutoCancel(true)
            .setCategory(if (alarm) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)           // rien d'une carte : des nombres
            .addAction(Notification.Action.Builder(null, ctx.getString(R.string.rat_snooze), snooze).build())
            .addAction(Notification.Action.Builder(null, ctx.getString(R.string.matin_open), openPending(ctx)).build())
        if (alarm) {
            val full = PendingIntent.getActivity(ctx, 23, Intent(ctx, AlarmActivity::class.java).putExtra("kind", "matin").putExtra("title", texte)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.setFullScreenIntent(full, true).setTimeoutAfter(s.duree * 1000L)
        }
        val n = b.build()
        if (alarm) n.flags = n.flags or Notification.FLAG_INSISTENT
        try { ctx.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, n) } catch (_: SecurityException) { }
    }

    fun stop(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
    }

    /** « Dans 5 min » : une alarme de plus, 5 minutes plus tard (celle du matin suivant reste posée). */
    fun snooze(ctx: Context) {
        stop(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + Rules.SNOOZE_MS
        val pi = firePending(ctx, 24)
        try {
            if (Alarm.exactAllowed(ctx)) am.setAlarmClock(AlarmManager.AlarmClockInfo(at, openPending(ctx)), pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}

/** L'alarme du matin (la nôtre seulement : non exportée). Prisme se demande hors du fil principal. */
class MatinReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Matin.ACTION_SNOOZE -> Matin.snooze(ctx.applicationContext)
            Matin.ACTION_FIRE -> {
                val pending = goAsync()
                Thread { try { Matin.fire(ctx.applicationContext) } finally { pending.finish() } }.start()
            }
        }
    }
}
