# SurvivalCraft Resource Pack

Pack de ressources custom pour le serveur SurvivalCraft.
Version Minecraft cible : **1.21.4** (`pack_format: 46`)

---

## Structure

```
resourcepack/
├── pack.mcmeta                              # Métadonnées du pack
├── assets/
│   ├── minecraft/
│   │   ├── font/
│   │   │   └── default.json                # Surcharge de police (icône ✦)
│   │   └── sounds.json                     # Sons personnalisés
│   └── survivalcraft/
│       └── textures/
│           └── font/
│               ├── README.txt              # Instructions pour les assets
│               └── eclat.png              # À créer — icône de la monnaie ✦
```

---

## Construire le resource pack (zip)

Le serveur attend un fichier `.zip` dont la **racine contient directement** `pack.mcmeta`.

### macOS / Linux

```bash
cd resourcepack/
zip -r ../SurvivalCraft-RP.zip .
```

### Windows (PowerShell)

```powershell
Compress-Archive -Path resourcepack\* -DestinationPath SurvivalCraft-RP.zip
```

> Ne pas zipper le dossier `resourcepack/` lui-même, mais son **contenu**.

---

## Héberger le resource pack

1. Uploader `SurvivalCraft-RP.zip` sur un hébergeur accessible publiquement :
   - GitHub Releases (recommandé, gratuit)
   - Un VPS/serveur web quelconque
   - Services comme MCPackets, Dropbox (lien direct), etc.

2. Dans `server/server.properties`, décommenter et remplir :

```properties
resource-pack=https://your-url.com/SurvivalCraft-RP.zip
resource-pack-required=true
resource-pack-prompt=§6SurvivalCraft §7requiert le resource pack pour la meilleure expérience !
```

3. Générer le SHA-1 du zip pour éviter les problèmes de cache :

```bash
shasum -a 1 SurvivalCraft-RP.zip
# Copier le hash dans server.properties :
# resource-pack-sha1=<le_hash>
```

---

## Assets à créer

### `eclat.png` — Icône monnaie ✦

- **Emplacement** : `assets/survivalcraft/textures/font/eclat.png`
- **Taille** : 8×8 px (ou 16×16, 32×32 pour plus de détail)
- **Format** : PNG avec transparence (RGBA)
- **Contenu** : pixel art d'un cristal/éclat doré représentant la monnaie
- Une fois en place, le caractère `✦` (U+2726) s'affichera avec cette image dans le chat

### Sons d'annonce

- **Emplacement** : `assets/survivalcraft/sounds/announcement.ogg`
- **Format** : OGG Vorbis (obligatoire pour Minecraft)
- **Usage** : joué lors des annonces importantes du serveur
- Déclaré dans `assets/minecraft/sounds.json`

---

## Notes

- Le `pack_format 46` correspond à **Minecraft 1.21.4**.
  Voir la liste complète : https://minecraft.wiki/w/Pack_format
- La surcharge de `assets/minecraft/font/default.json` **remplace** la police par défaut ;
  ajouter d'autres providers dans le même fichier si nécessaire.
