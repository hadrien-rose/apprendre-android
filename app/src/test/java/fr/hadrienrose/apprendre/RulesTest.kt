package fr.hadrienrose.apprendre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZonedDateTime

class RulesTest {
    private fun paris(s: String): Long = ZonedDateTime.parse(s + "[Europe/Paris]").toInstant().toEpochMilli()
    private fun utc(s: String): Long = ZonedDateTime.parse(s + "Z[UTC]").toInstant().toEpochMilli()

    @Test fun laFenetreNeVaQueSurLaPageApprendreEtLeLogin() {
        assertTrue(Rules.canNavigate("https", "chat.hadrien-rose.com", "/apprendre"))
        assertTrue(Rules.canNavigate("https", "CHAT.hadrien-rose.com", "/apprendre/"))
        assertTrue(Rules.canNavigate("https", "chat.hadrien-rose.com", "/cdn-cgi/access/authorized"))
        assertTrue(Rules.canNavigate("https", "hadrien-rose.cloudflareaccess.com", "/cdn-cgi/access/login/chat.hadrien-rose.com"))
        // le reste de Prisme s'ouvre dans le navigateur
        assertFalse(Rules.canNavigate("https", "chat.hadrien-rose.com", "/"))
        assertFalse(Rules.canNavigate("https", "chat.hadrien-rose.com", ""))
        assertFalse(Rules.canNavigate("https", "chat.hadrien-rose.com", "/mails"))
        assertFalse(Rules.canNavigate("https", "chat.hadrien-rose.com", "/apprendrex"))
        assertFalse(Rules.canNavigate("https", "chat.hadrien-rose.com", "/api/apprendre/file"))   // « Ouvrir le fichier » : le navigateur
        assertFalse(Rules.canNavigate("https", "chat.hadrien-rose.com", "/apprendre/../mails"))
        assertFalse(Rules.canNavigate("https", "chat.hadrien-rose.com", "/apprendre/%2e%2e/mails"))
        assertFalse(Rules.canNavigate("http", "chat.hadrien-rose.com", "/apprendre"))
        assertFalse(Rules.canNavigate("https", "voice.hadrien-rose.com", "/voice"))
        assertFalse(Rules.canNavigate("https", "chat.hadrien-rose.com.evil.net", "/apprendre"))
        assertFalse(Rules.canNavigate("https", "evilcloudflareaccess.com", "/"))
        assertFalse(Rules.canNavigate("https", "cloudflareaccess.com.evil.net", "/"))
        assertFalse(Rules.canNavigate("https", "accounts.google.com", "/"))
        assertFalse(Rules.canNavigate(null, null, null))
    }

    @Test fun laPageNeChargeQueSonApiSesPolicesEtCloudflare() {
        val site = "chat.hadrien-rose.com"
        assertTrue(Rules.canLoad(site, "https", site, "/apprendre"))
        assertTrue(Rules.canLoad(site, "https", site, "/api/apprendre"))
        assertTrue(Rules.canLoad(site, "https", site, "/api/apprendre/files"))
        assertTrue(Rules.canLoad(site, "https", site, "/api/apprendre/session/12/trou"))
        assertTrue(Rules.canLoad(site, "https", site, "/fonts/Inter-latin-260c81a4.woff2"))
        assertTrue(Rules.canLoad(site, "https", site, "/cdn-cgi/access/authorized"))
        assertFalse(Rules.canLoad(site, "https", site, "/api/conversations"))
        assertFalse(Rules.canLoad(site, "https", site, "/api/apprendrex"))
        assertFalse(Rules.canLoad(site, "https", site, "/api/voice/list"))
        assertFalse(Rules.canLoad(site, "https", site, "/api/apprendre/../mails/search"))
        assertFalse(Rules.canLoad(site, "https", site, "/api/apprendre/%2E%2E/mails"))
        assertFalse(Rules.canLoad(site, "https", site, "/api/apprendre/..%2fmails"))
        assertFalse(Rules.canLoad(site, "https", site, "/api/apprendre//x"))
        assertFalse(Rules.canLoad(site, "https", site, "/"))
        assertFalse(Rules.canLoad(site, "http", site, "/apprendre"))
        // notre page ne charge rien d'un autre site ; la page du login Cloudflare, si
        assertFalse(Rules.canLoad(site, "https", "static.cloudflareinsights.com", "/beacon.min.js"))
        assertFalse(Rules.canLoad(site, "https", "evil.net", "/x.js"))
        assertTrue(Rules.canLoad(site, "https", "hadrien-rose.cloudflareaccess.com", "/x.css"))
        assertTrue(Rules.canLoad("hadrien-rose.cloudflareaccess.com", "https", "challenges.cloudflare.com", "/turnstile.js"))
    }

    @Test fun cheminPropre() {
        assertTrue(Rules.isCleanPath("/apprendre"))
        assertFalse(Rules.isCleanPath("apprendre"))
        assertFalse(Rules.isCleanPath("/a/./b"))
        assertFalse(Rules.isCleanPath("/a\\b"))
        assertFalse(Rules.isCleanPath(null))
    }

    @Test fun champsDeFichiers() {
        val photo = Rules.acceptList(arrayOf("image/*"))
        val tout = Rules.acceptList(arrayOf(".pdf,.docx,.doc,.xlsx,.xls,.csv,.md,.txt,.html,.htm,.png,.jpg,.jpeg"))
        assertEquals(13, tout.size)
        assertEquals(listOf(".pdf", ".png"), Rules.acceptList(arrayOf(" .PDF ", "", ".png")))
        // 📷 Photographier une page : capture + images = l'appareil tout de suite
        assertTrue(Rules.cameraDirect(photo, true))
        assertFalse(Rules.cameraDirect(photo, false))
        assertFalse(Rules.cameraDirect(tout, true))
        assertEquals("image/*", Rules.pickerMime(photo))
        assertEquals("image/*", Rules.pickerMime(Rules.acceptList(arrayOf(".png,.JPG"))))
        // Choisir des fichiers : tout (le serveur refuse un format), l'appareil photo proposé à côté
        assertEquals("*/*", Rules.pickerMime(tout))
        assertEquals("*/*", Rules.pickerMime(emptyList()))
        assertTrue(Rules.acceptsImages(tout))
        assertTrue(Rules.acceptsImages(emptyList()))
        assertFalse(Rules.acceptsImages(Rules.acceptList(arrayOf(".pdf,.docx"))))
        assertFalse(Rules.imagesOnly(emptyList()))
    }

    @Test fun ceQueLaPageRecoit() {
        assertEquals(listOf("content://a", "content://b"), Rules.pickResult(listOf("content://a", "content://b"), "content://p", 900))
        assertEquals(listOf("content://p"), Rules.pickResult(emptyList(), "content://p", 900))
        assertEquals(emptyList<String>(), Rules.pickResult(emptyList(), "content://p", 0))    // appareil fermé sans photo
        assertEquals(emptyList<String>(), Rules.pickResult(emptyList(), null, 900))
    }

    @Test fun photoConfineeAuDossierDesPhotos() {
        val root = Files.createTempDirectory("apprendre-photos").toFile()
        val now = paris("2026-10-27T08:05:09+01:00")
        assertEquals("photo-20261027-080509.jpg", Rules.photoName(now))
        assertEquals("photo-20261027-080509-2.jpg", Rules.photoName(now, 2))
        val f = Rules.photoFile(root, Rules.photoName(now))
        assertNotNull(f)
        assertEquals(File(root, "photo-20261027-080509.jpg").canonicalPath, f!!.canonicalPath)
        assertNull(Rules.photoFile(root, "../shared_prefs/apprendre.xml"))
        assertNull(Rules.photoFile(root, "photo-20261027-080509.jpg/../../x"))
        assertNull(Rules.photoFile(root, "photo-2026.jpg"))
        assertNull(Rules.photoFile(root, "photo-20261027-080509.png"))
        assertNull(Rules.photoFile(root, null))
        // un lien glissé dans le dossier vers ailleurs : refusé (chemin canonique hors du dossier)
        val dehors = Files.createTempFile("apprendre-dehors", ".jpg")
        Files.createSymbolicLink(File(root, "photo-20261027-080510.jpg").toPath(), dehors)
        assertNull(Rules.photoFile(root, "photo-20261027-080510.jpg"))
        Files.delete(dehors)
        root.deleteRecursively()
    }

    @Test fun rappelAHuitHeuresDeParis() {
        // avant 8 h : aujourd'hui ; à 8 h pile ou après : demain
        assertEquals(paris("2026-10-27T08:00+01:00"), Rules.nextReminderAt(paris("2026-10-27T07:59:59+01:00")))
        assertEquals(paris("2026-10-28T08:00+01:00"), Rules.nextReminderAt(paris("2026-10-27T08:00+01:00")))
        assertEquals(paris("2026-10-28T08:00+01:00"), Rules.nextReminderAt(paris("2026-10-27T21:30+01:00")))
        // heure d'hiver (CET, UTC+1) et d'été (CEST, UTC+2), en UTC
        assertEquals(utc("2026-10-27T07:00"), Rules.nextReminderAt(utc("2026-10-26T23:30")))
        assertEquals(utc("2026-07-14T06:00"), Rules.nextReminderAt(utc("2026-07-13T12:00")))
        // les nuits du changement d'heure : 8 h reste 8 h
        assertEquals(utc("2026-10-25T07:00"), Rules.nextReminderAt(utc("2026-10-24T20:00")))   // passage à l'heure d'hiver
        assertEquals(utc("2026-03-29T06:00"), Rules.nextReminderAt(utc("2026-03-28T20:00")))   // passage à l'heure d'été
        // minuit UTC = déjà le lendemain à Paris
        assertEquals("2026-10-27", Rules.parisDay(utc("2026-10-26T23:30")))
    }

    @Test fun uneNotificationParJourJamaisAvantHuitHeures() {
        val t = paris("2026-10-27T08:00:30+01:00")
        assertTrue(Rules.shouldNotify(null, t))
        assertTrue(Rules.shouldNotify("2026-10-26", t))
        assertFalse(Rules.shouldNotify("2026-10-27", t))                                   // déjà fait aujourd'hui
        assertFalse(Rules.shouldNotify(null, paris("2026-10-27T07:59+01:00")))              // heure changée à la main
        assertTrue(Rules.shouldNotify("2026-10-26", paris("2026-10-27T11:40+01:00")))      // alarme retardée par la veille
    }

    @Test fun versionPlusRecente() {
        assertTrue(Rules.isNewer(102, 101))
        assertFalse(Rules.isNewer(101, 101))
        assertFalse(Rules.isNewer(100, 101))
    }

    @Test fun laFenetreNouvelleVersionNeHarcelePas() {
        val now = 1_800_000_000_000L
        assertTrue(Rules.shouldPrompt(102, 101, 0, 0, now))                                 // jamais proposée
        assertFalse(Rules.shouldPrompt(101, 101, 0, 0, now))                                // à jour
        assertFalse(Rules.shouldPrompt(102, 101, 102, now - 3_600_000, now))               // « Plus tard » il y a une heure
        assertTrue(Rules.shouldPrompt(102, 101, 102, now - Rules.DAY_MS, now))             // le lendemain
        assertTrue(Rules.shouldPrompt(103, 101, 102, now - 60_000, now))                   // une autre version
    }

    @Test fun seulesLesApkDeCeDepot() {
        assertTrue(Rules.isReleaseApk("https://github.com/hadrien-rose/apprendre-android/releases/download/v1.0.3/apprendre-1.0.3.apk"))
        assertFalse(Rules.isReleaseApk("https://github.com/hadrien-rose/dictee-android/releases/download/v1.0.3/dictee-1.0.3.apk"))
        assertFalse(Rules.isReleaseApk("https://github.com/hadrien-rose/apprendre-android/releases/download/../../evil/x.apk"))
        assertFalse(Rules.isReleaseApk("http://github.com/hadrien-rose/apprendre-android/releases/download/v1/a.apk"))
        assertFalse(Rules.isReleaseApk("https://github.com/hadrien-rose/apprendre-android/releases/download/v1/a.zip"))
        assertFalse(Rules.isReleaseApk(null))
    }
}
