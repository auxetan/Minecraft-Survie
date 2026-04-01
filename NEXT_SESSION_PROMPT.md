# Prompt détaillé — Session Claude Code SurvivalCraft

## Contexte
Serveur Minecraft Survival RPG (Paper 1.21.4) avec un plugin monolithique `SurvivalCore` (Java 21, Gradle, TriumphGUI, Vault, Adventure API). Resource pack Faithless customisé avec système de GUI Paladium-style (NegativeSpaceFont bitmap glyphs). Le serveur utilise playit.gg pour le tunnel réseau.

**Branche principale** : `main` (tout le travail doit être fait dessus).

---

## TÂCHES PRIORITAIRES (dans l'ordre)

### 1. ✅ FAIT — Fixes portés depuis `claude/zen-elion` (2026-04-01)
- **Shop buy confirmation** : `openBuyConfirmation()` ajouté dans ShopModule ✅
- **Double-buy protection** : Le GUI de confirmation est la protection ✅
- **Arc tracking amélioré** : ENDER_PEARL pickup, CraftItemEvent (BLAZE_POWDER/MUSHROOM_STEW), InventoryMove depuis coffres ✅
- **Death bag immortality** : `setUnlimitedLifetime(true)` + `setPickupDelay(40)` dans DeathModule ✅
- **Commande `/mission`** : MissionCommand ajouté à WeeklyModule + plugin.yml ✅
- **Essentials userdata** : dossier créé ✅

**Non portés (nécessitent tests in-game ou complexité élevée) :**
- Sell quantity (`/vendre <qty>`)
- Claim border visualization (particules)
- DELIVER_ITEM coffre contributions

### 2. Vérifier que le GUI custom fonctionne
Le fichier `Faithless/assets/survivalcraft/font/gui.json` a été corrigé (suppression des champs `_info*` invalides qui empêchaient le chargement de la font).
- Relancer le serveur et vérifier que le background custom apparaît dans `/menu`
- Si ça ne marche toujours pas, débugger : vérifier que le zip Faithless contient bien `assets/survivalcraft/font/gui.json` et `assets/survivalcraft/textures/gui/*.png`
- Vérifier le format des PNG (352×168, RGBA) et que `height=256, ascent=22` sont corrects pour 1.21.4

### 3. Calibrer le positionnement des textures GUI
Le fichier `menu_bg.png` contient actuellement un PNG de calibration (grille de points colorés aux positions calculées des slots). **Après** vérification in-game :
- Si les points s'alignent sur les slots → les coordonnées calculées sont bonnes
- Si décalage → ajuster le `SHIFT` dans `GuiBackground.java` (actuellement `-40px` = `\uF0FC\uF0F8`)
- Puis régénérer tous les 7 PNG backgrounds avec des vrais designs (boutons, onglets, barres de recherche) aux bonnes positions

### 4. Redesign complet des GUIs style Paladium
Une fois la calibration validée, refaire les PNG backgrounds pour chaque GUI avec :
- **Vrais boutons** dessinés dans le PNG aux positions exactes des slots cliquables
- **Onglets/tabs** pour navigation (ex: catégories du shop)
- **Barre de recherche** visuelle (pour AuctionHouse)
- **Décorations** : branches, lianes, mousse, style aventure/jungle
- Les 7 GUIs : MENU, SHOP, MARKET/AH, QUEST, SKILLS, LEADERBOARD, AUCTION

### 5. Boss bar XP job
La boss bar a été implémentée dans `JobModule.java` mais pas encore testée. Vérifier in-game :
- Quand un joueur casse une bûche (Bûcheron), la boss bar jaune doit apparaître en haut
- Format : `✦ Bûcheron Niv.3 — 45/150 XP (+5)`
- S'auto-masque après 4 secondes d'inactivité
- Se met à jour à chaque action (pas de spam de nouvelles barres)

### 6. Terra world generator
Terra.jar a été téléchargé dans `server/plugins/`. La config `bukkit.yml` pointe vers `Terra:SurvivalCraft`.
- Vérifier que le pack Terra `SurvivalCraft` existe dans `server/plugins/Terra/packs/`
- Si le pack custom n'existe pas, soit créer une config Terra basique, soit utiliser le pack par défaut (`Terra:DEFAULT`)
- Supprimer le dossier `server/world/` si on veut un monde 100% Terra (les anciens chunks restent vanilla sinon)

---

## BUGS CORRIGÉS (session précédente)
- `getCommand("lobby")` doublon supprimé
- `/competences` et `/arcs` ajoutés (SkillModule + ArcModule)
- shop.yml : 3 erreurs YAML (`:{ `), 3 doublons, WOOL→WHITE_WOOL
- arcs.yml : UTF-8 minus `−` → `-`
- SkillModule : cache playerGeneralXp jamais mis à jour
- DeathModule : NPE safety sur getKiller()/getItemMeta()
- Font gui.json : champs `_info*` supprimés (empêchaient le chargement)
- Terra.jar téléchargé, bukkit.yml nettoyé

## BUGS RESTANTS
*(les bugs de la session précédente ont été corrigés)*

---

## ARCHITECTURE DU PROJET

```
Minecraft-Server/
├── SurvivalCore/                    # Plugin Java (Gradle)
│   └── src/main/java/com/survivalcore/
│       ├── SurvivalCore.java        # Main plugin, module registration
│       ├── data/DatabaseManager.java # SQLite
│       ├── modules/                 # Un fichier par module
│       │   ├── EconomyModule.java   # Solde, /balance, /pay
│       │   ├── MenuModule.java      # /menu GUI principal
│       │   ├── ShopModule.java      # /shop, /vendre, /marche, /coffre-commun
│       │   ├── AuctionHouseModule.java # /ah (enchères joueur)
│       │   ├── JobModule.java       # 6 jobs, XP, milestones, boss bar
│       │   ├── QuestModule.java     # Quêtes journalières
│       │   ├── WeeklyModule.java    # Mission hebdo
│       │   ├── SkillModule.java     # Arbre de compétences (PAS DE COMMANDE!)
│       │   ├── ClassModule.java     # 4 classes RPG
│       │   ├── ArcModule.java       # Arcs narratifs
│       │   ├── ClaimModule.java     # Protection de chunks
│       │   ├── DeathModule.java     # /deaths, sac de mort
│       │   ├── LeaderboardModule.java # /classement
│       │   ├── MinimapModule.java   # /waypoint
│       │   ├── ModPageModule.java   # /mods, serveur HTTP page de mods
│       │   ├── AnnouncerModule.java # Messages auto broadcast
│       │   ├── EventsModule.java    # Listeners globaux (first join, etc)
│       │   └── ProgressModule.java  # BossBar progression
│       └── ui/GuiBackground.java    # Enum backgrounds GUI (font glyphs)
├── Faithless/                       # Resource pack
│   ├── pack.mcmeta                  # format 75-76
│   ├── assets/minecraft/font/default.json
│   ├── assets/survivalcraft/font/gui.json      # Font custom GUI
│   └── assets/survivalcraft/textures/gui/*.png  # 7 backgrounds
├── server/                          # Dossier serveur MC
│   ├── plugins/                     # JARs des plugins
│   ├── bukkit.yml                   # Config Terra
│   └── server.properties
├── start.sh                         # Build + zip Faithless + start
├── setup.sh                         # Install dépendances
└── stop.sh
```

## COMMANDES ENREGISTRÉES (plugin.yml)
`/spawn` (alias `/lobby`), `/menu`, `/waypoint`, `/deaths`, `/commandes` (alias `/aide`, `/cmds`), `/balance` (alias `/bal`, `/money`), `/pay`, `/shop`, `/ah` (alias `/hdv`, `/auction`), `/vendre`, `/marche`, `/classe`, `/job`, `/quetes`, `/classement`, `/competences` (alias `/skills`), `/arcs`, `/claim`, `/coffre-commun`, `/mods`, `/admin`

**MANQUANTE** : `/mission` (WeeklyModule — pas encore de commande dédiée)

## TECH STACK
- Paper 1.21.4 (pack format 75-76)
- Java 21 / Gradle avec shadow plugin
- TriumphGUI pour les inventaires custom
- Vault + Essentials pour l'économie
- Adventure API (Kyori) pour les components texte
- SQLite (DatabaseManager.java)
- LuckPerms pour les permissions
- Faithless resource pack (custom)

## BRANCHE zen-elion
La branche `claude/zen-elion` a 8 commits d'avance sur son point de divergence (commit `2da600a`). Elle contient des gameplay fixes importants listés en tâche 1. Ne PAS merger directement (14k+ fichiers en conflit), porter manuellement les améliorations pertinentes.
