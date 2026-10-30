# Apprendre (Android)

L'app Apprendre d'Hadrien Rose : **la page Apprendre de Prisme** (`chat.hadrien-rose.com/apprendre?app=1`, protégée par
Cloudflare Access) dans une WebView, et seulement ce qu'il faut autour : les fichiers et l'appareil photo, la mise à
jour, deux alarmes (le matin, et les cartes ratées). Les séances, les jeux et les cartes vivent dans la page : ils se mettent à jour avec
Prisme, sans nouvelle APK.

Ce dépôt est public et ne contient **aucun secret** : pas de clé d'API, pas de jeton, pas de clé de signature (elle vit
dans les secrets GitHub). L'app n'ouvre rien sans le login de son propriétaire.

## Ce que fait l'app

- `MainActivity` : la fenêtre ne va que sur la page Apprendre (`/apprendre…`) et le login Cloudflare ; la page ne charge
  que son API (`/api/apprendre/…`), ses polices (`/fonts/`) et les scripts de Cloudflare (`/cdn-cgi/`), toute autre
  requête reçoit un 403 ; tout autre lien (le reste de Prisme, « Ouvrir le fichier », un site) s'ouvre dans le
  navigateur, sans rien de l'app. Un seul pont JavaScript, `ApprendreNative` (`alarm` et `matin`) : la page y dit quand
  sonner, rien ne revient à la page. Les règles sont dans `Rules` et testées en JVM (`RulesTest`).
- **Les fichiers** (`onShowFileChooser`) : « 📷 Photographier une page » ouvre l'appareil photo ; « Choisir des fichiers »
  ouvre le sélecteur d'Android (fichiers, galerie) avec l'appareil photo à côté. La photo s'écrit dans le cache de l'app
  par `PhotoProvider` (un dossier, un motif de nom, non exporté) ; l'app ne déclare pas la permission CAMERA :
  l'appareil photo du téléphone prend la photo pour elle.
- **L'alarme du matin** (`Matin`, 1.4) : chaque jour coché (8 h 50, du lundi au vendredi par défaut), réglée dans ⚙ de
  la page ; une alarme exacte (`setAlarmClock`). À l'heure, l'app demande à Prisme `/api/apprendre/matin` : elle se tait
  s'il ne reste rien à faire et que tu as déjà joué depuis le début de la journée (6 h par défaut), sinon elle sonne
  (« 3 nouvelles · 10 à revoir », rien d'une carte) ; Prisme muet = elle sonne quand même. Elle remplace le rappel de
  8 h des versions 1.0 à 1.3 (`Reminder` ne fait plus que l'annuler).
- **L'alarme des cartes ratées** (`Alarm`, 1.3) : 10 minutes après la fin de la séance. Les deux alarmes partagent la
  même sonnerie (⚙ : alarme plein écran ou notification, son, vibreur, durée) ; reposées au redémarrage, au changement
  d'heure et après une mise à jour. Android 13 et plus : l'autorisation des notifications est demandée une fois.
- **La mise à jour** (`Updater`) : à l'ouverture, au plus une fois par jour, l'app lit `version.json` de la dernière
  release ; une version plus récente → « Nouvelle version x.y — Mettre à jour » ; l'APK est téléchargée, son SHA-256
  comparé à celui publié et sa signature à celle de l'app installée, puis l'installeur d'Android demande confirmation.
- **Le menu** : appui long sur l'icône Apprendre → « Réglages » (la prochaine alarme du matin, l'état des autorisations, version) et
  « Vérifier les mises à jour ».

## Installer

1. Télécharger l'APK de la [dernière release](https://github.com/hadrien-rose/apprendre-android/releases/latest) sur le
   téléphone et l'ouvrir ; Android demande d'autoriser l'installation depuis le navigateur (« sources inconnues »).
2. Ouvrir Apprendre : le login Cloudflare Access s'affiche. **Se connecter par code reçu par e-mail** : Google refuse
   les connexions depuis une WebView (« navigateur non sécurisé »), le bouton Google ne marchera pas dans l'app.
3. Autoriser les notifications et « Alarmes et rappels » (les alarmes du matin et des cartes ratées).

Les mises à jour suivantes se font depuis l'app (la première demande l'autorisation « sources inconnues » pour
Apprendre).

## Construire

```
export ANDROID_HOME=~/android-sdk
./gradlew test assembleRelease      # clé de débogage sans APPRENDRE_KEYSTORE*, clé de release avec
```

La CI (`.github/workflows/release.yml`) fait la même chose à chaque push sur `main`, après un scan de secrets (gitleaks
sur tout l'historique, aucun fichier de clé), et publie la release que l'app installée propose d'installer.
