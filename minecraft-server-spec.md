# 🎮 Spec Technique — Plugin Minecraft Custom "SurvivalCore"

> Stack : **PaperMC 1.21.x** + **Java 21**  
> Joueurs cibles : 4 à 6  
> Plugin principal : `SurvivalCore` (plugin Paper monolithique avec modules séparés)  
> Plugins tiers recommandés : **SlimeFun4**, **Vault**, **PlaceholderAPI**, **BlueMap** (minimap web)

---

## 📁 Structure du plugin

```
SurvivalCore/
├── src/main/java/com/survivalcore/
│   ├── SurvivalCore.java          # Main class
│   ├── modules/
│   │   ├── MenuModule.java        # Menu GUI principal (touche)
│   │   ├── ClassModule.java       # Système de classes
│   │   ├── JobModule.java         # Système de jobs
│   │   ├── SkillModule.java       # Arbre de compétences
│   │   ├── QuestModule.java       # Quêtes journalières + arcs
│   │   ├── WeeklyModule.java      # Mission hebdo commune
│   │   ├── EconomyModule.java     # Monnaie custom
│   │   ├── ShopModule.java        # Shop admin + shop joueur
│   │   ├── DeathModule.java       # Système de mort custom
│   │   ├── MinimapModule.java     # Minimap custom
│   │   ├── LeaderboardModule.java # Classements live
│   │   └── AnnouncerModule.java   # Annonces dramatiques
│   └── data/
│       └── PlayerData.java        # Persistance données joueur (YAML/SQLite)
├── src/main/resources/
│   ├── plugin.yml
│   ├── config.yml
│   └── data/
│       ├── classes.yml
│       ├── jobs.yml
│       ├── quests.yml
│       ├── weekly.yml
│       └── shop.yml
```

---

## 🖥️ MODULE 1 — Menu Principal (touche personnalisable)

### Déclencheur
- Par défaut : touche `B` (configurable dans config.yml via keybind client → commande `/menu`)
- En pratique : le joueur appuie sur `B` → envoie la commande `/menu` automatiquement via un resource pack ou le joueur tape `/menu`

### Interface GUI
- Inventaire custom de **54 slots** (6 lignes) avec fond en verre coloré (noir)
- Titre : `§8✦ §bSurvival Menu §8✦`

### Onglets (icônes cliquables)
| Slot | Icône | Onglet |
|------|-------|--------|
| 10 | Épée en diamant | ⚔️ Ma Classe |
| 12 | Livre | 📜 Quêtes du jour |
| 14 | Étoile de nether | 🌟 Mission Hebdo |
| 16 | Lingot d'or | 💰 Shop |
| 28 | Niveau XP (tête custom) | 📊 Arbre de compétences |
| 30 | Pioche | 🔨 Mon Job |
| 32 | Tableau (map) | 🏆 Classements |
| 34 | Coffre | 📦 Coffre Commun |

### Comportement
- Chaque onglet ouvre un nouveau GUI (inventaire imbriqué)
- Bouton retour (flèche en os) toujours en slot 49

---

## ⚔️ MODULE 2 — Classes

### Classes disponibles (choix au premier login, changeable tous les 7 jours)

#### 🗡️ Guerrier
- **Capacité passive** : +20% de dégâts au corps à corps
- **Capacité active 1** (cooldown 30s) : `Charge` — dash vers l'ennemi ciblé (téléportation 5 blocs + knockback)
- **Capacité active 2** (cooldown 60s) : `Cri de guerre` — donne Strength II + Resistance I pendant 10s à tous les joueurs dans un rayon de 10 blocs
- **XP gagnée via** : tuer des mobs, PvP, utiliser des épées/haches

#### 🏹 Archer
- **Capacité passive** : les flèches ont 15% de chance de ralentir la cible (Slowness II, 3s)
- **Capacité active 1** (cooldown 20s) : `Pluie de flèches` — tire 5 flèches en éventail
- **Capacité active 2** (cooldown 90s) : `Œil d'aigle` — active le Zoom (FOV réduit) + dégâts x2 pendant 8s
- **XP gagnée via** : tirer des flèches, chasser des animaux, tuer des mobs à distance

#### 🔮 Mage
- **Capacité passive** : les potions lancées ont un rayon d'effet +50%
- **Capacité active 1** (cooldown 25s) : `Boule de feu` — projectile custom en feu (dégâts 8 + fire 5s)
- **Capacité active 2** (cooldown 120s) : `Téléportation` — se téléporte là où le joueur regarde (max 30 blocs), effet de particules
- **XP gagnée via** : brasser des potions, lancer des sorts, utiliser des enchantements

### Activation des capacités
- **Clic droit** avec un item "Grimoire de classe" (custom item, tête de joueur custom) dans la main
- GUI s'ouvre avec les 2 capacités, clic pour activer si pas en cooldown
- Cooldown affiché en secondes avec barre de progression dans le GUI

### Stockage
- Classe stockée dans `playerdata/{uuid}.yml` → `class: WARRIOR`
- XP de classe séparée de l'XP vanilla, stockée dans `class_xp: 1200`

---

## 🔨 MODULE 3 — Jobs

### Jobs disponibles (choix indépendant de la classe, changeable tous les 3 jours)

| Job | Icône | Déclencheur d'XP + argent |
|-----|-------|---------------------------|
| ⛏️ Mineur | Pioche | Casser des blocs (minerais = plus d'XP) |
| 🌾 Fermier | Houe | Récolter des cultures, élever des animaux |
| 🏹 Chasseur | Arc | Tuer des mobs hostiles et animaux |
| 🧪 Alchimiste | Bouteille | Brasser des potions, récolter des herbes |
| 🌲 Bûcheron | Hache | Couper des arbres, récolter du bois |
| 🍖 Cuisinier | Bol de soupe | Cuisiner des aliments, utiliser un fumoir/fourneau |

### Système de gain
- **XP Job** : barre de progression spécifique au job (niveau 1 → 100), paliers tous les 10 niveaux
- **Argent** : chaque action rapporte une somme en monnaie custom (configurable dans `jobs.yml`)
- Exemple `jobs.yml` :
```yaml
MINER:
  DIAMOND_ORE:
    xp: 15
    money: 5.0
  IRON_ORE:
    xp: 5
    money: 1.5
FARMER:
  WHEAT:
    xp: 3
    money: 0.5
```

### Bonus de niveau de job
- Niveau 10 : +5% de gain d'argent dans ce job
- Niveau 25 : déblocage d'un bonus passif unique (ex. Mineur niv.25 = chance de doubler un minerai)
- Niveau 50 : titre spécial affiché dans le tab-list et le leaderboard
- Niveau 100 : accès à des recettes exclusives dans le shop

---

## 🌟 MODULE 4 — Arbre de Compétences

### Fonctionnement
- Chaque action en jeu donne de l'**XP générale** (séparée de l'XP vanilla et du job)
- Cette XP donne des **Points de Compétence** (1 point tous les 500 XP générale)
- GUI d'arbre avec des slots représentant les nœuds (inventaire 54 slots)

### Compétences disponibles (communes à tous)
| Nœud | Effet | Coût |
|------|-------|------|
| Résistance I | -10% dégâts reçus | 1 pt |
| Résistance II | -25% dégâts reçus | 3 pts (requiert Rés. I) |
| Vitalité | +2 cœurs max permanents | 2 pts |
| Chance | +10% loot sur les mobs | 2 pts |
| Vitesse | Speed I permanent | 1 pt |
| Nage | Respiration prolongée x2 | 1 pt |
| Forgeron | -20% coût de réparation | 2 pts |
| Marchand | -10% prix shop | 3 pts |
| Explorateur | +15% XP quêtes | 2 pts |
| Collecteur | +20% argent gagné via job | 3 pts |

### Affichage dans le menu
- GUI avec fond sombre, nœuds représentés par des têtes de joueur colorées ou des items
- Nœud disponible : item brillant (enchant glint)
- Nœud verrouillé : verre fumé avec lore "Requiert : X"
- Nœud acheté : item en or avec checkmark dans le nom

---

## 📜 MODULE 5 — Quêtes

### 5a. Quêtes Journalières

#### Génération
- Chaque joueur reçoit **3 quêtes différentes** à minuit (heure serveur)
- Générées aléatoirement depuis un pool configurable dans `quests.yml`
- Notification à la connexion : `§a✦ Tes quêtes du jour sont prêtes ! Tape /menu pour les voir.`

#### Types de quêtes journalières
- `KILL` : Tue X mobs d'un certain type
- `GATHER` : Collecte X unités d'une ressource
- `CRAFT` : Fabrique X items
- `TRAVEL` : Parcours X blocs à pied
- `BREW` : Brasse X potions

#### Récompenses
- Argent custom (50–200 selon difficulté)
- XP générale (100–500)
- Parfois : item rare ou clé de loot box

#### Affichage dans le menu
- GUI 27 slots avec les 3 quêtes
- Chaque quête = item avec :
  - Nom coloré selon difficulté (vert/jaune/rouge)
  - Lore : description + progression ex. `§7Progression : §e3/10`
  - Barre de progression (caractères custom dans le lore)

---

### 5b. Arcs d'Exploration (Quêtes Longues)

#### Structure
- Chaque arc = une série de **5 à 10 étapes** débloquées une par une
- Exemple d'arc : `🗺️ Les Ruines Oubliées`
  1. Trouve le biome "Deep Dark" (coordonnées données après découverte)
  2. Atteins les coordonnées X Z fournies (structure générée)
  3. Tue le Gardien de la Ruine (boss custom spawn)
  4. Ramène 1x Artefact Ancien (loot du boss)
  5. Dépose l'artefact dans le coffre communautaire

#### Récompenses d'arc
- Grosse somme d'argent (500–2000)
- Item légendaire exclusif
- Titre de prestige dans le tab-list

#### Déclenchement
- L'arc actif est affiché dans un onglet dédié du menu
- Un seul arc actif à la fois par joueur, indépendant des quêtes journalières

---

### 5c. Mission Hebdomadaire Commune

#### Fonctionnement
- Chaque lundi à minuit (heure serveur), **tous les joueurs connectés** reçoivent un **livre signé** dans leur inventaire
- Titre du livre : `§6§l📖 Mission de la Semaine`
- Contenu : objectif commun, description RP, coordonnées si applicable, récompense

#### Types de missions hebdo
- Tuer un boss spécifique (boss custom dans une structure rare)
- Trouver et ramener une ressource rare issue d'un biome spécifique
- Construire une structure commune (validation manuelle par admin ou via détection de blocs)
- Explorer une zone précise (rayon de 200 blocs autour de coordonnées données)

#### Participation & récompenses
- Chaque joueur contribue individuellement (kills, collecte comptés par joueur)
- Objectif commun : si **tous** les joueurs complètent leur part → bonus x2 pour tout le monde
- Récompense individuelle : argent + XP + item de collection (trophée de la semaine)
- Annonce dramatique quand la mission est complétée par un joueur (voir Module Annonceur)

#### Stockage
- Mission actuelle stockée dans `weekly.yml` avec date de reset et état par joueur UUID

---

## 💰 MODULE 6 — Économie & Shops

### Monnaie Custom
- Nom : **Éclats** (symbole `✦` ou `E`)
- Intégré avec **Vault** pour compatibilité avec d'autres plugins
- Commandes : `/balance`, `/pay <joueur> <montant>`
- Pas de monnaie vanilla (les émeraudes restent mais n'ont pas de valeur spéciale)

---

### Shop Admin (NPC ou GUI via /shop)
- Ouvert via le menu principal ou commande `/shop`
- GUI 54 slots organisé en catégories (onglets) :

| Catégorie | Exemples d'items |
|-----------|-----------------|
| ⛏️ Ressources | Minerais, bois, pierre |
| 🌾 Nourriture | Tous les aliments craftables |
| 🔮 Potions | Potions de base et avancées |
| ⚙️ SlimeFun | Items SlimeFun exclusifs |
| 🎒 Équipements | Armures, outils enchantés |
| 🌟 Légendaires | Items rares (prix élevé, stock limité) |

- Prix configurables dans `shop.yml`
- Items légendaires : stock global limité (ex. 3 disponibles par semaine), remis à zéro chaque lundi

---

### Shop Joueur à Joueur
- Commande `/vendre <item_en_main> <prix>` → place l'item sur le marché
- GUI `/marche` → liste tous les items en vente par les joueurs
- Chaque vente prend une **taxe de 5%** reversée au Coffre Commun

---

### Coffre Communautaire
- Coffre physique placé dans un lieu fixe du spawn (coordonnées dans config.yml)
- Ou accessible via GUI `/coffre-commun`
- Règles :
  - Tous les joueurs peuvent déposer librement
  - Retrait : uniquement si le joueur a contribué dans les dernières 24h (job ou quête)
  - **Taxe automatique** : 5% de chaque gain d'argent va automatiquement dans un fonds commun (affiché dans le leaderboard)

---

## ☠️ MODULE 7 — Système de Mort Custom

### Au moment de la mort

1. **Drop du Backpack** : tout l'inventaire est placé dans un item "Sac de Mort" (custom item, tête de joueur rouge) droppé à l'emplacement de la mort
   - Le sac ne disparaît jamais (keepAlive = -1)
   - Ne peut être ouvert que par le joueur concerné (vérification UUID dans les métadonnées)

2. **Balise dans le ciel** : particules qui montent verticalement pendant 5 minutes depuis l'endroit de la mort
   - Particules : `SOUL_FIRE_FLAME` ou `REDSTONE` rouge + noir
   - Tête de mort flottante (ArmorStand avec tête custom) pendant 5 min

3. **Marqueur Minimap** : voir Module Minimap

4. **Annonce pour tous** : voir Module Annonceur

5. **Affichage au joueur mort** :
   - Message dans le chat avec coordonnées X Y Z de la mort
   - Action bar pendant 10s après respawn : `§c☠ Ton sac est en X: 234 Y: 64 Z: -891`

### Stockage de la position de mort
- Enregistré dans `playerdata/{uuid}.yml` → `last_death: {x, y, z, world}`
- Effacé quand le joueur récupère son sac

---

## 🗺️ MODULE 8 — Minimap

### Choix technique
- **Pas de minimap in-game** (impossible sans client mod)
- **Solution** : carte web via **BlueMap** (rendu 3D du monde en temps réel) + **overlay custom de markers** via l'API BlueMap
- Accessible à `http://[IP_SERVEUR]:8100`
- Optionnel : les joueurs peuvent aussi utiliser le mod client **Xaero's Minimap** (compatible Paper)

### Markers affichés sur BlueMap
- 💀 Position de mort de chaque joueur (icône tête de mort rouge) → visible par tous
- 🏠 Home de chaque joueur (commande `/sethome`, visible par le joueur seulement)
- 📌 Structures importantes découvertes (ajoutées manuellement ou auto-détectées)
- 🌟 Boss actifs (marqueur pulsant si un boss est en vie)

### API BlueMap
```java
// Exemple d'ajout de marker de mort
BlueMapAPI.onEnable(api -> {
    BlueMapMap map = api.getWorld(world).getMaps().get(0);
    MarkerSet set = MarkerSet.builder().label("Morts").build();
    POIMarker marker = POIMarker.builder()
        .label("Mort de " + playerName)
        .position(x, y, z)
        .icon("assets/skull.png", 16, 16)
        .build();
    set.getMarkers().put("death-" + uuid, marker);
    map.getMarkerSets().put("deaths", set);
});
```

### Commandes joueur
- `/waypoint add <nom>` → ajoute un waypoint perso sur la carte
- `/waypoint list` → liste ses waypoints
- `/deaths` → affiche dans le chat les coordonnées de sa dernière mort

---

## 🏆 MODULE 9 — Leaderboards

### Stats trackées
| Stat | Clé |
|------|-----|
| Mobs tués | `kills_mobs` |
| Joueurs tués | `kills_players` |
| Morts totales | `deaths` |
| Blocs minés | `blocks_mined` |
| Argent gagné (total cumulé) | `money_earned` |
| Argent actuel | via Vault |
| Quêtes complétées | `quests_done` |
| Missions hebdo complétées | `weekly_done` |
| Niveau de job (par job) | `job_level_{jobname}` |

### Affichage
- GUI dans le menu : classement top 6 par stat (une stat par page)
- Scoreboard latéral (sidebar) visible en jeu :
  ```
  §6§l✦ SurvivalCraft
  §7Argent: §e450 E
  §7Job: §bMineur Niv.12
  §7Quêtes: §a2/3
  §7Semaine: §d1/1 ✓
  ```
- Mis à jour toutes les 30 secondes

---

## 📢 MODULE 10 — Annonceur Dramatique

### Déclencheurs et messages

#### Mort d'un joueur
```
§4§l☠ §c[PLAYER] vient de mordre la poussière ! §4§l☠
§7Cause : §e[CAUSE_DE_MORT]
§7Position : §bX:[X] Y:[Y] Z:[Z]
§7Morts totales : §c[DEATH_COUNT]
```

#### Kill d'un mob rare / boss
```
§6§l⚔ VICTOIRE ! §e[PLAYER] a terrassé [MOB_NAME] !
§7Butin légendaire obtenu !
```

#### Quête journalière complétée
```
§a§l✦ QUÊTE ACCOMPLIE ! §7[PLAYER] a complété : §e[QUEST_NAME]
§7Récompense : §6+[MONEY] Éclats §7| §b+[XP] XP
```

#### Mission hebdo complétée par un joueur
```
§d§l★ MISSION HEBDO §7[PLAYER] a rempli sa part de la mission !
§7[N]/[TOTAL] joueurs ont contribué cette semaine.
```

#### Mission hebdo complétée par tous
```
§6§l✦✦✦ VICTOIRE D'ÉQUIPE ! ✦✦✦
§eLa mission de la semaine est accomplie par toute l'équipe !
§6Bonus x2 activé pour tous ! 🎉
```

#### Premier joueur à atteindre un palier de job
```
§b§l🔨 RECORD ! §7[PLAYER] est le premier à atteindre le niveau §b50 §7en tant que §e[JOB] !
```

#### Exploit (premier kill d'un boss, première découverte d'un biome rare…)
```
§5§l🌟 EXPLOIT LÉGENDAIRE ! §7[PLAYER] a découvert [EXPLOIT] en premier !
```

### Format
- Tous les messages sont préfixés d'une ligne de séparation : `§8§m                                        `
- Son joué à tous les joueurs (`playSound` → `ENTITY_ENDER_DRAGON_GROWL` pour les big events, `ENTITY_EXPERIENCE_ORB_PICKUP` pour les petits)

---

## 🌍 MODULE 11 — World & Mods Recommandés

> Ces mods/plugins enrichissent le monde sans nécessiter de code custom.

### Plugins à installer sur Paper
| Plugin | Rôle |
|--------|------|
| **SlimeFun4** | Machines, automatisation, alchimie avancée, véhicules |
| **SlimeFun-ExoticGarden** | Addon SlimeFun : nouvelles plantes et ressources |
| **InfernalMobs** | Mobs "élites" aléatoires avec capacités spéciales et meilleur loot |
| **MythicMobs** | Créer des boss custom scriptés (animations, phases, compétences) |
| **Terra** | Générateur de monde custom : +100 biomes, structures, donjons |
| **EssentialsX** | /home, /tpa, /spawn, commandes de base |
| **Vault** | API économie (requis pour notre plugin) |
| **LuckPerms** | Gestion des permissions et rôles |
| **PlaceholderAPI** | Permet d'afficher les stats du plugin dans d'autres plugins |
| **BlueMap** | Carte web 3D temps réel du monde |
| **DiscordSRV** | (Optionnel) Lier le chat Minecraft à un salon Discord |

### Générateur de monde (Terra)
- Biomes additionnels : forêts volcaniques, déserts de cristal, marais hanté, toundra glaciale, jungle abyssale, etc.
- Nouvelles structures auto-générées : ruines, temples, donjons souterrains, tours, camps de bandit
- Config recommandée : pack `OVERWORLD` de Terra avec `BETAC` preset

---

## 💾 MODULE 12 — Stockage des Données

### Format
- **SQLite** (fichier unique `plugins/SurvivalCore/database.db`) via JDBC
- Tables :

```sql
CREATE TABLE players (
  uuid TEXT PRIMARY KEY,
  name TEXT,
  money REAL DEFAULT 0,
  class TEXT DEFAULT 'NONE',
  job TEXT DEFAULT 'NONE',
  job_xp INTEGER DEFAULT 0,
  job_level INTEGER DEFAULT 1,
  skill_points INTEGER DEFAULT 0,
  general_xp INTEGER DEFAULT 0,
  kills_mobs INTEGER DEFAULT 0,
  kills_players INTEGER DEFAULT 0,
  deaths INTEGER DEFAULT 0,
  blocks_mined INTEGER DEFAULT 0,
  money_earned REAL DEFAULT 0,
  quests_done INTEGER DEFAULT 0,
  weekly_done INTEGER DEFAULT 0,
  last_death_x REAL,
  last_death_y REAL,
  last_death_z REAL,
  last_death_world TEXT,
  last_class_change BIGINT,
  last_job_change BIGINT
);

CREATE TABLE skills (
  uuid TEXT,
  skill_id TEXT,
  PRIMARY KEY (uuid, skill_id)
);

CREATE TABLE quests (
  uuid TEXT,
  quest_id TEXT,
  progress INTEGER DEFAULT 0,
  completed INTEGER DEFAULT 0,
  date TEXT,
  PRIMARY KEY (uuid, quest_id, date)
);

CREATE TABLE weekly_mission (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  week TEXT,
  mission_type TEXT,
  mission_data TEXT,
  completed INTEGER DEFAULT 0
);

CREATE TABLE weekly_participation (
  uuid TEXT,
  week TEXT,
  progress INTEGER DEFAULT 0,
  completed INTEGER DEFAULT 0,
  PRIMARY KEY (uuid, week)
);

CREATE TABLE market (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  seller_uuid TEXT,
  item_serialized TEXT,
  price REAL,
  listed_at BIGINT
);
```

---

## ⚙️ config.yml

```yaml
server-name: "SurvivalCraft"
currency-name: "Éclat"
currency-symbol: "✦"

menu-command: "menu"      # Commande pour ouvrir le menu
scoreboard: true          # Activer le scoreboard latéral
scoreboard-update-ticks: 600  # Toutes les 30s

death-sack-timeout: -1    # -1 = jamais expire
death-beacon-duration: 300  # Secondes (5 min)

weekly-reset-day: MONDAY
weekly-reset-hour: 0

common-chest-location:
  world: world
  x: 0
  y: 64
  z: 0

tax-rate: 0.05            # 5% sur ventes joueur-à-joueur
common-fund-rate: 0.05    # 5% de chaque gain → fonds commun

bluemap:
  enabled: true
  port: 8100
```

---

## 🔗 Projets GitHub Open Source à Étudier & Réutiliser

> L'IA qui code ce plugin est **fortement encouragée** à analyser le code de ces projets open source existants pour s'en inspirer, réutiliser des patterns, des utilitaires, ou des implémentations complètes plutôt que de tout réécrire de zéro.

---

### ☠️ Système de mort — DeadChest
**→ https://github.com/Mischief-Managers/DeadChest**
- Backpack/coffre spawné à l'endroit de la mort avec hologramme
- Réutiliser : logique de drop d'inventaire dans un bloc custom, persistance de la position de mort, restriction d'accès par UUID propriétaire
- Notre ajout par rapport à ce plugin : beacon de particules + marker BlueMap

---

### 💰 Jobs & gains d'argent — Jobs Reborn
**→ https://github.com/Zrips/Jobs**
- Système complet de jobs avec XP, niveaux, gains d'argent configurables par action
- Réutiliser : le système de listeners d'événements par type d'action (minage, craft, kill…), le format de config `jobs.yml`, la logique de calcul XP/argent
- Notre ajout : intégration dans notre menu GUI unifié + bonus de niveau custom

---

### 🌟 Arbre de compétences — AuraSkills
**→ https://github.com/Archy-X/AuraSkills**
- Compétences par activité (farming, mining, combat…) avec XP, niveaux et abilities passives
- Réutiliser : pattern de tracking XP par événement Bukkit, système de capacités passives appliquées via PotionEffect/EventModifier, GUI d'arbre de compétences
- Notre ajout : points de compétences dépensables manuellement + nœuds custom dans notre GUI

---

### 📜 Quêtes — BetonQuest
**→ https://github.com/BetonQuest/BetonQuest**
- Système de quêtes scripté très avancé : conditions, objectifs, événements, conversations NPC
- Réutiliser : architecture des objectifs (ObjectiveEvent), système de tracking de progression, reset journalier, format de définition de quêtes en YAML
- Notre ajout : génération aléatoire des quêtes journalières + intégration dans notre menu GUI

---

### 🗺️ Carte web & markers — BlueMap
**→ https://github.com/BlueMap-Minecraft/BlueMap**
- Carte 3D interactive du monde en temps réel, accessible via navigateur
- Réutiliser : API Java pour ajouter des markers (POIMarker, LineMarker), écouter les events de chargement de chunks
- Documentation API : https://bluemap.bluecolored.de/wiki/customization/Markers.html
- Notre usage : markers de mort (tête de mort), waypoints joueur, boss actifs

---

### ⚔️ Classes avec capacités actives — MMOCore
**→ https://github.com/phoenix-dvpmt/mmocore**
- Système de classes RPG complet : stats, capacités actives/passives, arbre de talents
- Réutiliser : architecture des capacités actives (cooldown, activation, effets), système de stats par classe, GUI de sélection de classe
- Leur implémentation de cooldown est particulièrement propre à réutiliser

---

### 🏆 Leaderboards & Scoreboard — AnimatedScoreboard / ChestCommands
**→ https://github.com/nicuch/AnimatedScoreboard**
- Scoreboard latéral animé avec PlaceholderAPI
- Réutiliser : pattern de mise à jour du scoreboard toutes les X ticks, intégration PlaceholderAPI
- Alternative plus simple : **https://github.com/clip/Holographic-Scoreboard**

---

### 🖥️ GUI Framework — TriumphGUI
**→ https://github.com/TriumphTeam/triumph-gui**
- Librairie légère pour créer des GUI Minecraft proprement (pagination, boutons, callbacks)
- **Utiliser directement comme dépendance Maven/Gradle** plutôt que de gérer les InventoryClickEvent manuellement
- Réduit massivement le boilerplate de création de menus

---

### 📢 Annonces & effets — TitleManager
**→ https://github.com/Puharesource/TitleManager**
- Gestion des titres, action bars, et annonces stylisées
- Réutiliser : pattern d'envoi de titres avec fadeIn/stay/fadeOut, action bar persistante

---

### 💡 Conseils pour l'IA qui code
- **Commence par lire les README et les classes principales** de chaque repo avant de coder le module correspondant
- **Pour Jobs et AuraSkills** : au lieu de recoder ces systèmes, envisage de les utiliser comme dépendances via leur API plutôt que de dupliquer la logique
- **Pour les GUI** : utilise TriumphGUI comme dépendance Gradle, ça économise ~500 lignes de boilerplate
- **Pattern à copier de MMOCore** : leur système `Skill.java` + `SkillHandler.java` pour les capacités actives avec cooldown est la référence

---

## 🛠️ Commandes

| Commande | Description | Permission |
|----------|-------------|------------|
| `/menu` | Ouvre le menu principal | `survival.menu` |
| `/classe` | Choisir/voir sa classe | `survival.class` |
| `/job` | Choisir/voir son job | `survival.job` |
| `/balance` | Voir son argent | `survival.economy` |
| `/pay <joueur> <montant>` | Payer un joueur | `survival.economy` |
| `/shop` | Ouvrir le shop admin | `survival.shop` |
| `/vendre <prix>` | Vendre l'item en main | `survival.market` |
| `/marche` | Voir le marché joueur | `survival.market` |
| `/coffre-commun` | Ouvrir le coffre commun | `survival.chest` |
| `/quetes` | Voir ses quêtes | `survival.quests` |
| `/classement` | Voir les leaderboards | `survival.leaderboard` |
| `/deaths` | Voir position dernière mort | `survival.death` |
| `/waypoint add <nom>` | Ajouter un waypoint | `survival.waypoint` |
| `/admin reload` | Recharger la config | `survival.admin` |
| `/admin givemoney <joueur> <montant>` | Donner de l'argent | `survival.admin` |
| `/admin setweekly <type>` | Forcer la mission hebdo | `survival.admin` |

---

## 🗓️ Priorité de développement suggérée

1. **Phase 1 – Base** : Économie + Vault + Menu principal vide + Scoreboard
2. **Phase 2 – Jobs** : Système de jobs avec tracking des actions + gains
3. **Phase 3 – Mort** : Backpack de mort + beacon de particules + BlueMap markers
4. **Phase 4 – Classes** : 3 classes + capacités actives/passives
5. **Phase 5 – Quêtes** : Quêtes journalières + Mission hebdo + Book
6. **Phase 6 – Shop** : Shop admin GUI + Marché joueur + Coffre commun
7. **Phase 7 – Arbre compétences** : GUI + achat de nœuds
8. **Phase 8 – Arcs** : Quêtes longues multi-étapes
9. **Phase 9 – Finitions** : Annonceur dramatique + Sons + Polish GUI

---

## 📝 Notes importantes pour l'IA qui code

- Utilise **Paper API 1.21.x** (pas Bukkit pur, utilise les méthodes Paper pour les performances)
- Tous les GUI sont faits avec `Bukkit.createInventory()` + listeners sur `InventoryClickEvent`
- Empêche toujours le vol d'items dans les GUI avec `event.setCancelled(true)` sur les slots UI
- Les items custom utilisent `ItemMeta` + `PersistentDataContainer` pour stocker des données (type, UUID owner, etc.)
- Les capacités de classe utilisent `BukkitRunnable` pour les cooldowns + `Particle` API pour les effets visuels
- Le sac de mort utilise un `PersistentDataContainer` avec la clé `owner_uuid` pour restreindre l'ouverture
- Utilise **async** pour toutes les opérations SQLite (ne jamais bloquer le thread principal)
- Toutes les chaînes de caractères colorées utilisent `ChatColor` ou les codes `§`
- Enregistre tous les listeners dans `onEnable()` avec `getServer().getPluginManager().registerEvents()`
