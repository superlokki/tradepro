# Trade Pro — add-on Bookmap (API L1, testé sur 7.9)

Couche d'affichage et de drag par-dessus le trading de Bookmap : une ligne sur la position, des poignées TP / SL à tirer, et le déplacement des ordres à la souris. L'add-on ne remplace rien : les ordres d'entrée, leur taille (TCP) et la position restent ceux de Bookmap.

## Installer
1. Bookmap > Settings > Configure add-ons > **Add** > `tradepro.jar`
2. Choisir **Trade Pro**, cocher la case pour l'activer sur le chart
3. Le panneau de réglages de l'add-on (en anglais) a trois sections, enregistrées avec le workspace : **Colors** (Long, Short, Take profit, Stop loss), **Lines** (style Solid ou Dashed et largeur de 1 à 6 px, pour la position, le TP et le SL) et **Labels** (position horizontale des étiquettes dans la zone à droite de la timeline : 0 = contre la timeline, 100 = bord droit, 50 par défaut)
4. Le trading doit être activé dans le TCP de Bookmap

Les add-ons non signés sont bloqués sur le temps réel de Bookmap Data / dxFeed :
utiliser les données différées, le replay, le crypto ou une connexion courtier.

## Utilisation
- **Position ouverte** : ligne au prix moyen + étiquette « LONG 3 @ prix » avec le PnL latent. Le sens, la taille et le prix moyen viennent du statut de Bookmap quand le fournisseur l'envoie, sinon ils sont reconstruits à partir des exécutions
- **Poignées TP / SL** à droite de la timeline, au-dessus et en dessous de la position : tirer puis relâcher pose un ordre limite (TP) ou stop (SL). Une poignée ne couvre que la partie de la position qui n'a pas encore d'ordre de sortie de ce type (3 contrats si rien n'est posé, 2 s'il existe déjà un TP de 1) et disparaît quand toute la position est couverte. Pendant le geste, l'étiquette affiche la taille, le prix, l'écart en ticks et le montant par rapport au prix moyen
- **Ordres de sortie** : tout ordre qui sort de la position s'affiche comme TP (limite) ou SL (stop), avec son écart en ticks et son montant, qu'il vienne d'une poignée, du TCP ou d'un bracket Bookmap
- **Drag & drop** : attrape n'importe quelle ligne d'ordre actif et relâche au nouveau prix. **Clic droit pendant le drag** → abandon, rien n'est envoyé
- **Crosshair** (lignes H + V) avec prix sur le ladder, tant que la souris est sur le chart

Le montant est en dollars quand Bookmap donne la valeur du point de l'instrument, sinon seuls les ticks sont affichés.

### Scale in / scale out
Quand la position grossit ou diminue sans changer de sens, les TP / SL posés par les poignées prennent sa nouvelle taille (3 → 5 contrats : le TP et le SL passent à 5). Les ordres posés depuis le TCP et les brackets Bookmap ne sont pas touchés.

### Ce que l'add-on ne fait pas
- **Il n'annule pas les TP / SL quand la position se ferme ou se retourne.** Ils restent au marché : utilise l'add-on Execution Pro de Bookmap (« Cancel All Orders on Flat ») ou annule-les à la main, sinon ils peuvent ouvrir une nouvelle position
- Il ne place pas d'ordre d'entrée et n'a pas de réglage de taille : tout passe par le trading natif de Bookmap

### Garde-fous
- Un simple clic n'envoie rien : il faut tirer d'au moins 4 pixels, et un ordre relâché sur son propre prix n'est pas modifié
- Une poignée TP / SL relâchée du mauvais côté du marché (l'ordre partirait tout de suite) pose à la place une limite au meilleur prix. TP : passif, SELL ASK pour un long et BUY BID pour un short. SL : sortie immédiate, SELL BID pour un long et BUY ASK pour un short
- Un ordre existant déplacé du mauvais côté du marché est refusé, de même que tout ordre tant que le carnet n'est pas connu. Pendant le drag, l'aperçu passe en rouge sombre avec la raison, en anglais (« rejected: wrong side of the market », « rejected: no market data ») ; au relâchement, le message reste 4 secondes sur le chart et dans le log Bookmap
- Si la position se ferme ou se retourne pendant que tu tires une poignée, rien n'est envoyé

## Recompiler
JDK 17 ou plus requis ; le jar est compilé en Java 17, comme les exemples officiels. Deux façons, qui donnent le même résultat (`tradepro.jar` à la racine, classes dans `build/classes`) :

    .\gradlew.bat jar

(Gradle 9.8.0, téléchargé automatiquement au premier lancement ; fonctionne avec Java 17 à 27), ou sans Gradle :

    powershell -ExecutionPolicy Bypass -File build.ps1

Les deux compilent contre l'API de l'installation locale (`C:\Program Files\Bookmap\lib`) et préparent le rechargement rapide. Si Bookmap est installé ailleurs : `.\gradlew.bat jar -PbookmapLib="D:/Bookmap/lib"` ou `build.ps1 -BookmapLib 'D:\Bookmap\lib'`.

### Tests
L'add-on se vérifie à la main dans Bookmap, en replay ou en simulation : poser, déplacer, annuler, recharger.

### Rechargement rapide (pendant le développement)
Pour ne pas changer le nom du jar à chaque essai :

1. Dans Bookmap > Configure add-ons > **Add**, choisir `build\classes\bm-strategy-package-fs-root.jar` (fichier vide : Bookmap lit alors les classes directement dans `build\classes`)
2. Après chaque modification : recompiler, puis recharger l'add-on (ou redémarrer Bookmap)

Si Bookmap a `tradepro.jar` chargé (fichier verrouillé), sortir le jar sous un autre nom :

    powershell -ExecutionPolicy Bypass -File build.ps1 -Name tradepro-v2.jar
