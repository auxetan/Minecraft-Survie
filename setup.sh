#!/usr/bin/env bash
# ================================================================
#  setup.sh — Configuration initiale COMPLETE du serveur SurvivalCraft
#  Compatible macOS (test) et Linux/Ubuntu (mini PC)
#  Lance UNE SEULE FOIS — installe tout automatiquement
# ================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SERVER_DIR="$SCRIPT_DIR/server"
PLUGIN_DIR="$SERVER_DIR/plugins"
PAPER_VERSION="1.21.4"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${BLUE}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# ── Detecter l'OS ──────────────────────────────────────────────
OS="$(uname -s)"
ARCH="$(uname -m)"
IS_MAC=false
IS_LINUX=false
[[ "$OS" == "Darwin" ]] && IS_MAC=true
[[ "$OS" == "Linux"  ]] && IS_LINUX=true

# Nom du binaire Playit selon la plateforme
if $IS_MAC; then
    [[ "$ARCH" == "arm64" ]] && PLAYIT_BIN_NAME="playit-darwin-aarch64" || PLAYIT_BIN_NAME="playit-darwin-x86_64"
else
    [[ "$ARCH" == "aarch64" ]] && PLAYIT_BIN_NAME="playit-linux-aarch64" || PLAYIT_BIN_NAME="playit-linux-amd64"
fi
PLAYIT_BIN="$SCRIPT_DIR/$PLAYIT_BIN_NAME"

echo ""
echo -e "${CYAN}╔══════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║   SurvivalCraft — Installation Complete              ║${NC}"
echo -e "${CYAN}║   Tout est automatique, laisse tourner !             ║${NC}"
echo -e "${CYAN}║   OS detecte : $OS ($ARCH)$(printf '%*s' $((22 - ${#OS} - ${#ARCH})) '')║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════════════════════╝${NC}"
echo ""

# ── Creer les dossiers si besoin ────────────────────────────────
mkdir -p "$SERVER_DIR" "$PLUGIN_DIR"

# ════════════════════════════════════════════════════════════════
#  ETAPE 1 — JAVA 21+
# ════════════════════════════════════════════════════════════════
info "1/8  Verification de Java 21+..."
JAVA_OK=false
if command -v java &>/dev/null; then
    JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/{print $2}' | cut -d'.' -f1)
    [[ "$JAVA_VER" -ge 21 ]] && JAVA_OK=true
fi

if [ "$JAVA_OK" = false ]; then
    if $IS_MAC; then
        info "Installation de Java via Homebrew..."
        if command -v brew &>/dev/null; then
            brew install --cask temurin@21 || brew install openjdk@21
            ok "Java installe via Homebrew."
        else
            error "Java 21+ requis. Installe Java depuis https://adoptium.net/temurin/releases/?version=21 puis relance setup.sh"
        fi
    else
        info "Installation de Java 21 (openjdk)..."
        sudo apt-get update -qq
        sudo apt-get install -y openjdk-21-jdk-headless
        ok "Java 21 installe."
    fi
else
    ok "Java $(java -version 2>&1 | awk -F '"' '/version/{print $2}') detecte."
fi

# JAVA_HOME : detection cross-platform
if $IS_MAC; then
    if /usr/libexec/java_home -v 21 &>/dev/null 2>&1; then
        export JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null)"
    elif /usr/libexec/java_home &>/dev/null 2>&1; then
        export JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null)"
    fi
else
    if [ -d "/usr/lib/jvm/java-21-openjdk-amd64" ]; then
        export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
    elif [ -d "/usr/lib/jvm/java-21-openjdk-arm64" ]; then
        export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-arm64"
    else
        export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(which java)")")")"
    fi
fi
export PATH="$JAVA_HOME/bin:$PATH"
info "JAVA_HOME = $JAVA_HOME"

# ════════════════════════════════════════════════════════════════
#  ETAPE 2 — OUTILS SYSTEME
# ════════════════════════════════════════════════════════════════
info "2/8  Outils systeme (screen, curl, python3)..."

if $IS_MAC; then
    MISSING_TOOLS=()
    command -v screen  &>/dev/null || MISSING_TOOLS+=(screen)
    command -v python3 &>/dev/null || MISSING_TOOLS+=(python3)
    # curl est inclus dans macOS par defaut
    if [ ${#MISSING_TOOLS[@]} -gt 0 ] && command -v brew &>/dev/null; then
        brew install "${MISSING_TOOLS[@]}" 2>/dev/null || warn "Certains outils n'ont pas pu etre installes (non critique)."
    fi
else
    PKGS_TO_INSTALL=()
    command -v screen  &>/dev/null || PKGS_TO_INSTALL+=(screen)
    command -v unzip   &>/dev/null || PKGS_TO_INSTALL+=(unzip)
    command -v curl    &>/dev/null || PKGS_TO_INSTALL+=(curl)
    command -v python3 &>/dev/null || PKGS_TO_INSTALL+=(python3)
    if [ ${#PKGS_TO_INSTALL[@]} -gt 0 ]; then
        sudo apt-get update -qq
        sudo apt-get install -y "${PKGS_TO_INSTALL[@]}"
        ok "Outils installes : ${PKGS_TO_INSTALL[*]}"
    fi
fi
ok "Outils systeme prets."

# ════════════════════════════════════════════════════════════════
#  ETAPE 3 — GRADLE WRAPPER
# ════════════════════════════════════════════════════════════════
info "3/8  Gradle wrapper..."
WRAPPER_DIR="$SCRIPT_DIR/SurvivalCore/gradle/wrapper"
WRAPPER_JAR="$WRAPPER_DIR/gradle-wrapper.jar"
mkdir -p "$WRAPPER_DIR"

if [ ! -f "$WRAPPER_JAR" ]; then
    info "Telechargement du gradle-wrapper.jar..."
    curl -fsSL \
        "https://raw.githubusercontent.com/gradle/gradle/v8.10.0/gradle/wrapper/gradle-wrapper.jar" \
        -o "$WRAPPER_JAR" \
        || {
            TMPDIR_GRADLE=$(mktemp -d)
            curl -fsSL "https://services.gradle.org/distributions/gradle-8.10-bin.zip" \
                -o "$TMPDIR_GRADLE/gradle.zip"
            unzip -q "$TMPDIR_GRADLE/gradle.zip" -d "$TMPDIR_GRADLE"
            cd "$SCRIPT_DIR/SurvivalCore"
            "$TMPDIR_GRADLE/gradle-8.10/bin/gradle" wrapper --gradle-version 8.10 2>/dev/null || true
            rm -rf "$TMPDIR_GRADLE"
        }
    ok "gradle-wrapper.jar pret."
else
    ok "Gradle wrapper deja present."
fi

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
        -o "$PLUGIN_DIR/Vault.jar" || warn "Vault: echec telechargement"
    ok "  Vault.jar"
else
    ok "  Vault deja present."
fi

# --- LuckPerms ---
if [ ! -f "$PLUGIN_DIR/LuckPerms-Bukkit.jar" ]; then
    info "  Telechargement de LuckPerms..."
    LP_URL=$(curl -fsSL "https://api.modrinth.com/v2/project/luckperms/version" \
        | python3 -c "
import sys, json
versions = json.load(sys.stdin)
for v in versions:
    for f in v.get('files', []):
        if 'bukkit' in f['filename'].lower():
            print(f['url'])
            import sys; sys.exit(0)
print('')
" 2>/dev/null)
    if [ -n "$LP_URL" ]; then
        curl -fsSL -L -o "$PLUGIN_DIR/LuckPerms-Bukkit.jar" "$LP_URL"
        ok "  LuckPerms-Bukkit.jar"
    else
        curl -fsSL -L -o "$PLUGIN_DIR/LuckPerms-Bukkit.jar" \
            "https://download.luckperms.net/1556/bukkit/loader/LuckPerms-Bukkit-5.4.145.jar" \
            2>/dev/null || warn "  LuckPerms: echec. Telecharge manuellement depuis https://luckperms.net"
    fi
else
    ok "  LuckPerms deja present."
fi

# --- EssentialsX ---
if ! ls "$PLUGIN_DIR"/EssentialsX*.jar &>/dev/null; then
    info "  Telechargement de EssentialsX..."
    ESSX_TAG=$(curl -fsSL "https://api.github.com/repos/EssentialsX/Essentials/releases/latest" \
        | python3 -c "import sys,json; print(json.load(sys.stdin)['tag_name'])" 2>/dev/null)
    if [ -n "$ESSX_TAG" ]; then
        curl -fsSL -L -o "$PLUGIN_DIR/EssentialsX.jar" \
            "https://github.com/EssentialsX/Essentials/releases/download/${ESSX_TAG}/EssentialsX-${ESSX_TAG}.jar" \
            2>/dev/null && ok "  EssentialsX.jar" || warn "  EssentialsX: echec."
    else
        warn "  EssentialsX: impossible de trouver la derniere version."
    fi
else
    ok "  EssentialsX deja present."
fi

# ════════════════════════════════════════════════════════════════
#  ETAPE 7 — PLAYIT.GG (tunnel pour acces internet)
# ════════════════════════════════════════════════════════════════
info "7/8  Playit.gg (tunnel pour tes potes) [$PLAYIT_BIN_NAME]..."
if [ ! -f "$PLAYIT_BIN" ]; then
    PLAYIT_RELEASE_URL=$(curl -fsSL "https://api.github.com/repos/playit-cloud/playit-agent/releases/latest" \
        | python3 -c "
import sys, json
release = json.load(sys.stdin)
name = '$PLAYIT_BIN_NAME'
for asset in release.get('assets', []):
    if asset['name'] == name:
        print(asset['browser_download_url'])
        import sys; sys.exit(0)
print('')
" 2>/dev/null)
    if [ -n "$PLAYIT_RELEASE_URL" ]; then
        curl -fsSL -L "$PLAYIT_RELEASE_URL" -o "$PLAYIT_BIN" && chmod +x "$PLAYIT_BIN"
        ok "Playit.gg telecharge."
    else
        warn "Playit.gg : binaire '$PLAYIT_BIN_NAME' introuvable dans la derniere release."
        warn "Telecharge manuellement depuis https://github.com/playit-cloud/playit-agent/releases"
    fi
else
    ok "Playit.gg deja present."
fi

# ════════════════════════════════════════════════════════════════
#  ETAPE 8 — CONFIGURATION FINALE
# ════════════════════════════════════════════════════════════════
info "8/8  Configuration finale..."
echo "eula=true" > "$SERVER_DIR/eula.txt"
chmod +x "$SCRIPT_DIR/start.sh" "$SCRIPT_DIR/stop.sh" "$SCRIPT_DIR/build.sh" 2>/dev/null || true
ok "Configuration terminee."

# ── IP locale ────────────────────────────────────────────────
LOCAL_IP="?"
if $IS_MAC; then
    LOCAL_IP=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo "127.0.0.1")
else
    LOCAL_IP=$(hostname -I 2>/dev/null | awk '{print $1}' || echo "127.0.0.1")
fi

echo ""
echo -e "${GREEN}╔══════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║   SETUP TERMINE AVEC SUCCES !                        ║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "  ${YELLOW}Plugins installes :${NC}"
ls -1 "$PLUGIN_DIR"/*.jar 2>/dev/null | while read f; do echo "    - $(basename "$f")"; done
echo ""
echo -e "  ${CYAN}PROCHAINE ETAPE :${NC}"
echo -e "  ${YELLOW}./start.sh${NC}  →  Lance le serveur + Playit.gg"
echo ""
echo -e "  ${BLUE}IP locale (meme reseau) :${NC} ${LOCAL_IP}:25565"
echo ""
