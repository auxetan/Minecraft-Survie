#!/usr/bin/env bash
# ================================================================
#  setup.sh — Configuration initiale COMPLETE du serveur SurvivalCraft
#  A lancer UNE SEULE FOIS (macOS pour les tests, Linux/Ubuntu en prod)
#  Installe TOUT : Java, PaperMC, plugins, Playit.gg
# ================================================================
set -euo pipefail

# ── Detection OS / Architecture ─────────────────────────────────
OS="$(uname -s)"
ARCH="$(uname -m)"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SERVER_DIR="$SCRIPT_DIR/server"
PLUGIN_DIR="$SERVER_DIR/plugins"
PAPER_VERSION="1.21.4"

# Nom du binaire Playit selon la plateforme
if [ "$OS" = "Darwin" ]; then
    if [ "$ARCH" = "arm64" ]; then
        PLAYIT_BIN="$SCRIPT_DIR/playit-darwin-aarch64"
    else
        PLAYIT_BIN="$SCRIPT_DIR/playit-darwin-amd64"
    fi
else
    PLAYIT_BIN="$SCRIPT_DIR/playit-linux-amd64"
fi

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${BLUE}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# Remplacement portable de readlink -f (non dispo sur macOS sans GNU coreutils)
portable_realpath() {
    python3 -c "import os; print(os.path.realpath('$1'))" 2>/dev/null || echo "$1"
}

# IP locale portable
get_local_ip() {
    if [ "$OS" = "Darwin" ]; then
        ipconfig getifaddr en0 2>/dev/null || echo "localhost"
    else
        hostname -I 2>/dev/null | awk '{print $1}' || echo "localhost"
    fi
}

echo ""
echo -e "${CYAN}╔══════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║   SurvivalCraft — Installation Complete              ║${NC}"
echo -e "${CYAN}║   Tout est automatique, laisse tourner !             ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════════════════════╝${NC}"
echo ""
info "Plateforme detectee : $OS / $ARCH"
echo ""

# ── Creer les dossiers si besoin ────────────────────────────────
mkdir -p "$SERVER_DIR" "$PLUGIN_DIR"

# ════════════════════════════════════════════════════════════════
#  ETAPE 1 — JAVA 21
# ════════════════════════════════════════════════════════════════
info "1/8  Verification de Java 21..."
JAVA_OK=false
if command -v java &>/dev/null; then
    JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/{print $2}' | cut -d'.' -f1)
    [[ "$JAVA_VER" -ge 21 ]] && JAVA_OK=true
fi

if [ "$JAVA_OK" = false ]; then
    info "Installation de Java 21..."
    if [ "$OS" = "Darwin" ]; then
        command -v brew &>/dev/null || error "Homebrew requis sur macOS. Installe-le via https://brew.sh"
        brew install openjdk@21
        ok "Java 21 installe via Homebrew."
    else
        sudo apt-get update -qq
        sudo apt-get install -y openjdk-21-jdk-headless
        ok "Java 21 installe."
    fi
else
    ok "Java $(java -version 2>&1 | awk -F '"' '/version/{print $2}') deja present."
fi

# Configurer JAVA_HOME selon l'OS
if [ "$OS" = "Darwin" ]; then
    # Sur macOS, utiliser java_home si disponible
    if /usr/libexec/java_home -v 21 &>/dev/null 2>&1; then
        export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
    elif /usr/libexec/java_home &>/dev/null 2>&1; then
        export JAVA_HOME="$(/usr/libexec/java_home)"
    else
        # Fallback Homebrew
        for p in /opt/homebrew/opt/openjdk@21 /usr/local/opt/openjdk@21; do
            [ -d "$p" ] && export JAVA_HOME="$p" && break
        done
    fi
else
    # Linux : privilegier la version 21 explicite si presente
    if [ -d "/usr/lib/jvm/java-21-openjdk-amd64" ]; then
        export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
    else
        JAVA_BIN="$(command -v java)"
        JAVA_REAL="$(portable_realpath "$JAVA_BIN")"
        export JAVA_HOME="$(dirname "$(dirname "$JAVA_REAL")")"
    fi
fi
export PATH="$JAVA_HOME/bin:$PATH"
ok "JAVA_HOME = $JAVA_HOME"

# ════════════════════════════════════════════════════════════════
#  ETAPE 2 — OUTILS SYSTEME
# ════════════════════════════════════════════════════════════════
info "2/8  Outils systeme (screen, curl, unzip, zip, python3)..."

if [ "$OS" = "Darwin" ]; then
    command -v brew &>/dev/null || error "Homebrew requis sur macOS. Installe-le via https://brew.sh"
    BREW_TO_INSTALL=()
    command -v screen  &>/dev/null || BREW_TO_INSTALL+=(screen)
    command -v unzip   &>/dev/null || BREW_TO_INSTALL+=(unzip)
    command -v zip     &>/dev/null || BREW_TO_INSTALL+=(zip)
    command -v python3 &>/dev/null || BREW_TO_INSTALL+=(python3)
    if [ ${#BREW_TO_INSTALL[@]} -gt 0 ]; then
        brew install "${BREW_TO_INSTALL[@]}"
        ok "Outils installes via Homebrew : ${BREW_TO_INSTALL[*]}"
    else
        ok "Tous les outils sont deja presents."
    fi
else
    PKGS_TO_INSTALL=()
    command -v screen  &>/dev/null || PKGS_TO_INSTALL+=(screen)
    command -v unzip   &>/dev/null || PKGS_TO_INSTALL+=(unzip)
    command -v zip     &>/dev/null || PKGS_TO_INSTALL+=(zip)
    command -v curl    &>/dev/null || PKGS_TO_INSTALL+=(curl)
    command -v python3 &>/dev/null || PKGS_TO_INSTALL+=(python3)
    if [ ${#PKGS_TO_INSTALL[@]} -gt 0 ]; then
        sudo apt-get update -qq
        sudo apt-get install -y "${PKGS_TO_INSTALL[@]}"
        ok "Outils installes : ${PKGS_TO_INSTALL[*]}"
    else
        ok "Tous les outils sont deja presents."
    fi
fi

# ════════════════════════════════════════════════════════════════
#  ETAPE 3 — GRADLE WRAPPER (fix du NoSuchFileException)
# ════════════════════════════════════════════════════════════════
info "3/8  Gradle wrapper..."
WRAPPER_DIR="$SCRIPT_DIR/SurvivalCore/gradle/wrapper"
WRAPPER_JAR="$WRAPPER_DIR/gradle-wrapper.jar"
mkdir -p "$WRAPPER_DIR"

if [ ! -f "$WRAPPER_JAR" ]; then
    info "Telechargement direct du gradle-wrapper.jar..."
    # Telecharger le JAR directement depuis le repo Gradle officiel
    curl -fsSL \
        "https://raw.githubusercontent.com/gradle/gradle/v8.10.0/gradle/wrapper/gradle-wrapper.jar" \
        -o "$WRAPPER_JAR" \
        || {
            # Fallback : utiliser le service Gradle distributions
            info "Fallback : telechargement via services.gradle.org..."
            TMPDIR_GRADLE=$(mktemp -d)
            curl -fsSL "https://services.gradle.org/distributions/gradle-8.10-bin.zip" \
                -o "$TMPDIR_GRADLE/gradle.zip"
            unzip -q "$TMPDIR_GRADLE/gradle.zip" -d "$TMPDIR_GRADLE"
            cp "$TMPDIR_GRADLE/gradle-8.10/lib/gradle-wrapper-8.10.jar" "$WRAPPER_JAR" 2>/dev/null \
                || cp "$TMPDIR_GRADLE/gradle-8.10/lib/plugins/gradle-tooling-api-builders-8.10.jar" "$WRAPPER_JAR" 2>/dev/null \
                || {
                    # Dernier recours : generer le wrapper avec gradle lui-meme
                    cd "$SCRIPT_DIR/SurvivalCore"
                    "$TMPDIR_GRADLE/gradle-8.10/bin/gradle" wrapper --gradle-version 8.10 2>/dev/null
                }
            rm -rf "$TMPDIR_GRADLE"
        }
    ok "gradle-wrapper.jar pret."
else
    ok "Gradle wrapper deja present."
fi

# S'assurer que le properties est correct
cat > "$WRAPPER_DIR/gradle-wrapper.properties" << 'PROPS'
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.10-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
PROPS

chmod +x "$SCRIPT_DIR/SurvivalCore/gradlew"

# ════════════════════════════════════════════════════════════════
#  ETAPE 4 — BUILD SURVIVALCORE
# ════════════════════════════════════════════════════════════════
info "4/8  Build du plugin SurvivalCore..."
cd "$SCRIPT_DIR/SurvivalCore"
JAVA_HOME="$JAVA_HOME" ./gradlew shadowJar --no-daemon \
    || error "Build echoue ! Verifie les erreurs ci-dessus."

PLUGIN_JAR=$(ls "$SCRIPT_DIR/SurvivalCore/build/libs/"SurvivalCore-*.jar 2>/dev/null | grep -v sources | head -1)
[ -z "$PLUGIN_JAR" ] && error "JAR introuvable apres le build."

cp "$PLUGIN_JAR" "$PLUGIN_DIR/"
ok "SurvivalCore.jar deploye."

# ════════════════════════════════════════════════════════════════
#  ETAPE 5 — PAPERMC 1.21.4
# ════════════════════════════════════════════════════════════════
info "5/8  PaperMC ${PAPER_VERSION}..."
PAPER_JAR="$SERVER_DIR/paper-${PAPER_VERSION}.jar"
if [ ! -f "$PAPER_JAR" ]; then
    LATEST_BUILD=$(curl -fsSL \
        "https://api.papermc.io/v2/projects/paper/versions/${PAPER_VERSION}/builds" \
        | python3 -c "import sys,json; builds=json.load(sys.stdin)['builds']; print(builds[-1]['build'])" \
        2>/dev/null) || error "Impossible de recuperer la version Paper."

    curl -fsSL -o "$PAPER_JAR" \
        "https://api.papermc.io/v2/projects/paper/versions/${PAPER_VERSION}/builds/${LATEST_BUILD}/downloads/paper-${PAPER_VERSION}-${LATEST_BUILD}.jar" \
        || error "Telechargement PaperMC echoue."
    ok "PaperMC ${PAPER_VERSION} build ${LATEST_BUILD} telecharge."
else
    ok "PaperMC deja present."
fi

# ════════════════════════════════════════════════════════════════
#  ETAPE 6 — PLUGINS (Vault + LuckPerms + EssentialsX)
# ════════════════════════════════════════════════════════════════
info "6/8  Plugins obligatoires..."
cd "$SCRIPT_DIR"

# --- Vault ---
if [ ! -f "$PLUGIN_DIR/Vault.jar" ]; then
    info "  Telechargement de Vault..."
    curl -fsSL -L "https://github.com/milkbowl/Vault/releases/latest/download/Vault.jar" \
        -o "$PLUGIN_DIR/Vault.jar" || warn "Vault: echec telechargement (non critique si deja present)"
    ok "  Vault.jar"
else
    ok "  Vault deja present."
fi

# --- LuckPerms ---
if [ ! -f "$PLUGIN_DIR/LuckPerms-Bukkit.jar" ]; then
    info "  Telechargement de LuckPerms..."
    # Recuperer la derniere version depuis l'API Modrinth
    LP_URL=$(curl -fsSL "https://api.modrinth.com/v2/project/luckperms/version" \
        | python3 -c "
import sys, json
versions = json.load(sys.stdin)
for v in versions:
    for f in v.get('files', []):
        if 'bukkit' in f['filename'].lower():
            print(f['url'])
            sys.exit(0)
print('')
" 2>/dev/null)

    if [ -n "$LP_URL" ]; then
        curl -fsSL -L -o "$PLUGIN_DIR/LuckPerms-Bukkit.jar" "$LP_URL"
        ok "  LuckPerms-Bukkit.jar"
    else
        # Fallback URL connue
        info "  Fallback LuckPerms..."
        curl -fsSL -L -o "$PLUGIN_DIR/LuckPerms-Bukkit.jar" \
            "https://download.luckperms.net/1556/bukkit/loader/LuckPerms-Bukkit-5.4.145.jar" \
            2>/dev/null || warn "  LuckPerms: telechargement echoue. Telecharge manuellement depuis https://luckperms.net/download"
    fi
else
    ok "  LuckPerms deja present."
fi

# --- EssentialsX ---
if [ ! -f "$PLUGIN_DIR/EssentialsX.jar" ] && ! ls "$PLUGIN_DIR"/EssentialsX*.jar &>/dev/null; then
    info "  Telechargement de EssentialsX..."
    ESSX_URL=$(curl -fsSL "https://api.modrinth.com/v2/project/essentialsx/version" \
        | python3 -c "
import sys, json
versions = json.load(sys.stdin)
for v in versions:
    for f in v.get('files', []):
        fname = f['filename'].lower()
        if 'essentialsx-' in fname and 'chat' not in fname and 'spawn' not in fname and 'antibuild' not in fname and 'discord' not in fname and 'geo' not in fname and 'protect' not in fname and 'xmpp' not in fname:
            print(f['url'])
            sys.exit(0)
print('')
" 2>/dev/null)

    if [ -n "$ESSX_URL" ]; then
        curl -fsSL -L -o "$PLUGIN_DIR/EssentialsX.jar" "$ESSX_URL"
        ok "  EssentialsX.jar"
    else
        # Fallback : GitHub releases
        info "  Fallback EssentialsX via GitHub..."
        ESSX_TAG=$(curl -fsSL "https://api.github.com/repos/EssentialsX/Essentials/releases/latest" \
            | python3 -c "import sys,json; print(json.load(sys.stdin)['tag_name'])" 2>/dev/null)
        if [ -n "$ESSX_TAG" ]; then
            curl -fsSL -L -o "$PLUGIN_DIR/EssentialsX.jar" \
                "https://github.com/EssentialsX/Essentials/releases/download/${ESSX_TAG}/EssentialsX-${ESSX_TAG}.jar" \
                2>/dev/null || warn "  EssentialsX: telechargement echoue."
        else
            warn "  EssentialsX: impossible de trouver la derniere version. Telecharge depuis https://essentialsx.net/downloads.html"
        fi
    fi

    # EssentialsX Spawn (pour /spawn, /setspawn)
    if [ -n "$ESSX_URL" ]; then
        ESSX_SPAWN_URL=$(echo "$ESSX_URL" | sed 's/EssentialsX-/EssentialsXSpawn-/')
        curl -fsSL -L -o "$PLUGIN_DIR/EssentialsXSpawn.jar" "$ESSX_SPAWN_URL" 2>/dev/null \
            && ok "  EssentialsXSpawn.jar" \
            || warn "  EssentialsXSpawn: optionnel, echec silencieux."
    fi
else
    ok "  EssentialsX deja present."
fi

# --- PlaceholderAPI ---
if [ ! -f "$PLUGIN_DIR/PlaceholderAPI.jar" ]; then
    info "  Telechargement de PlaceholderAPI..."
    PAPI_URL=$(curl -fsSL "https://api.modrinth.com/v2/project/placeholderapi/version" \
        | python3 -c "
import sys, json
versions = json.load(sys.stdin)
for v in versions:
    for f in v.get('files', []):
        if 'placeholderapi' in f['filename'].lower():
            print(f['url'])
            sys.exit(0)
print('')
" 2>/dev/null)
    if [ -n "$PAPI_URL" ]; then
        curl -fsSL -L -o "$PLUGIN_DIR/PlaceholderAPI.jar" "$PAPI_URL"
        ok "  PlaceholderAPI.jar"
    else
        warn "  PlaceholderAPI: telecharge depuis https://hangar.papermc.io/HelpChat/PlaceholderAPI"
    fi
else
    ok "  PlaceholderAPI deja present."
fi

# --- Simple Voice Chat ---
if [ ! -f "$PLUGIN_DIR/VoiceChat.jar" ] && ! ls "$PLUGIN_DIR"/voicechat*.jar &>/dev/null 2>&1; then
    info "  Telechargement de Simple Voice Chat..."
    VOICECHAT_URL=$(curl -fsSL "https://api.modrinth.com/v2/project/simple-voice-chat/version?loaders=[\"paper\"]" \
        | python3 -c "
import sys, json
versions = json.load(sys.stdin)
for v in versions:
    for f in v.get('files', []):
        if 'bukkit' in f['filename'].lower() or 'paper' in f['filename'].lower() or 'voicechat-bukkit' in f['filename'].lower():
            print(f['url'])
            sys.exit(0)
# fallback: first file
if versions and versions[0].get('files'):
    print(versions[0]['files'][0]['url'])
" 2>/dev/null)
    if [ -n "$VOICECHAT_URL" ]; then
        curl -fsSL -L -o "$PLUGIN_DIR/VoiceChat.jar" "$VOICECHAT_URL"
        ok "  Simple Voice Chat.jar"
    else
        warn "  Simple Voice Chat: telecharge depuis https://modrinth.com/plugin/simple-voice-chat"
    fi
else
    ok "  Simple Voice Chat deja present."
fi

# --- MythicMobs (premium — avertissement) ---
if ! ls "$PLUGIN_DIR"/MythicMobs*.jar &>/dev/null 2>&1; then
    warn "  MythicMobs : plugin PREMIUM. Telecharge manuellement sur"
    warn "    https://mythiccraft.io/index.php?pages/official-mythicmobs-download/"
    warn "    Puis place le jar dans $PLUGIN_DIR/"
else
    ok "  MythicMobs deja present."
fi

# --- BlueMap (carte 3D web du monde) ---
if ! ls "$PLUGIN_DIR"/BlueMap*.jar &>/dev/null 2>&1; then
    info "  Telechargement de BlueMap..."
    BLUEMAP_URL=$(curl -fsSL "https://api.modrinth.com/v2/project/bluemap/version?loaders=[\"paper\"]" \
        | python3 -c "
import sys, json
versions = json.load(sys.stdin)
for v in versions:
    for f in v.get('files', []):
        fname = f['filename'].lower()
        if 'bluemap' in fname and '.jar' in fname:
            print(f['url'])
            sys.exit(0)
" 2>/dev/null)
    if [ -n "$BLUEMAP_URL" ]; then
        curl -fsSL -L -o "$PLUGIN_DIR/BlueMap.jar" "$BLUEMAP_URL"
        ok "  BlueMap.jar"
    else
        warn "  BlueMap: telecharge depuis https://modrinth.com/plugin/bluemap"
    fi
else
    ok "  BlueMap deja present."
fi

# --- Terra (biomes custom) ---
if ! ls "$PLUGIN_DIR"/Terra*.jar &>/dev/null 2>&1; then
    info "  Telechargement de Terra..."
    TERRA_URL=$(curl -fsSL "https://api.modrinth.com/v2/project/terra/version?loaders=[\"paper\"]" \
        | python3 -c "
import sys, json
versions = json.load(sys.stdin)
for v in versions:
    for f in v.get('files', []):
        fname = f['filename'].lower()
        if 'bukkit' in fname or 'paper' in fname:
            print(f['url'])
            sys.exit(0)
if versions and versions[0].get('files'):
    print(versions[0]['files'][0]['url'])
" 2>/dev/null)
    if [ -n "$TERRA_URL" ]; then
        curl -fsSL -L -o "$PLUGIN_DIR/Terra.jar" "$TERRA_URL"
        ok "  Terra.jar"
    else
        warn "  Terra: telecharge depuis https://modrinth.com/plugin/terra"
    fi
else
    ok "  Terra deja present."
fi

# ════════════════════════════════════════════════════════════════
#  ETAPE 7 — PLAYIT.GG (tunnel pour acces internet)
# ════════════════════════════════════════════════════════════════
info "7/8  Playit.gg (tunnel pour tes potes)..."
if [ ! -f "$PLAYIT_BIN" ]; then
    # Choisir le bon binaire selon l'OS et l'architecture
    if [ "$OS" = "Darwin" ]; then
        if [ "$ARCH" = "arm64" ]; then
            PLAYIT_RELEASE_NAME="playit-darwin-aarch64"
        else
            PLAYIT_RELEASE_NAME="playit-darwin-amd64"
        fi
    else
        PLAYIT_RELEASE_NAME="playit-linux-amd64"
    fi

    curl -fsSL -L \
        "https://github.com/playit-cloud/playit-agent/releases/latest/download/${PLAYIT_RELEASE_NAME}" \
        -o "$PLAYIT_BIN" \
        || {
            # Fallback version connue
            curl -fsSL -L \
                "https://github.com/playit-cloud/playit-agent/releases/download/v0.15.0/${PLAYIT_RELEASE_NAME}" \
                -o "$PLAYIT_BIN" \
                || error "Impossible de telecharger Playit.gg"
        }
    chmod +x "$PLAYIT_BIN"
    ok "Playit.gg telecharge ($PLAYIT_RELEASE_NAME)."
else
    ok "Playit.gg deja present."
fi

# ════════════════════════════════════════════════════════════════
#  ETAPE 8 — CONFIGURATION FINALE
# ════════════════════════════════════════════════════════════════
info "8/8  Configuration finale..."

# EULA
echo "eula=true" > "$SERVER_DIR/eula.txt"

# Droits d'execution
chmod +x "$SCRIPT_DIR/start.sh" "$SCRIPT_DIR/stop.sh" "$SCRIPT_DIR/build.sh" 2>/dev/null || true

ok "Configuration terminee."

# ════════════════════════════════════════════════════════════════
#  RESUME
# ════════════════════════════════════════════════════════════════
echo ""
echo -e "${GREEN}╔══════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║                                                      ║${NC}"
echo -e "${GREEN}║   SETUP TERMINE AVEC SUCCES !                        ║${NC}"
echo -e "${GREEN}║                                                      ║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "  ${YELLOW}Plugins installes :${NC}"
ls -1 "$PLUGIN_DIR"/*.jar 2>/dev/null | while read f; do echo "    - $(basename "$f")"; done
echo ""
echo -e "  ${CYAN}PROCHAINE ETAPE :${NC}"
echo -e "  ${YELLOW}./start.sh${NC}  →  Lance le serveur + Playit.gg"
echo ""
echo -e "  ${CYAN}Pack de textures pour internet (optionnel, 1 fois) :${NC}"
echo -e "  1. Va sur ${BLUE}playit.gg/account/tunnels${NC} → 'Add Tunnel'"
echo -e "  2. Type : HTTP • Local port : ${YELLOW}8080${NC} • Copie l'URL publique"
echo -e "  3. ${YELLOW}echo 'https://ton-url.gl.joinmc.link' > server/rp_url.txt${NC}"
echo -e "  → start.sh le lira auto à chaque lancement."
echo ""
echo -e "  ${CYAN}Carte BlueMap (navigateur) :${NC}"
echo -e "  Accessible sur ${BLUE}http://localhost:8123${NC} après le premier lancement."
echo -e "  Pour accès internet : même tunnel HTTP port ${YELLOW}8123${NC}."
echo ""
echo -e "  ${CYAN}Au premier lancement de Playit.gg :${NC}"
echo -e "  Un lien s'affichera dans la console pour lier ton compte."
echo -e "  Ouvre-le dans un navigateur, cree un compte gratuit,"
echo -e "  puis ajoute un tunnel Minecraft Java sur le port 25565."
echo -e "  Tes potes pourront alors rejoindre avec l'adresse Playit.gg !"
echo ""
echo -e "  ${BLUE}IP locale (meme reseau) :${NC} $(get_local_ip):25565"
echo ""
