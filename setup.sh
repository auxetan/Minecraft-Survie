#!/usr/bin/env bash
# ================================================================
#  setup.sh — Configuration initiale du serveur SurvivalCraft
#  À lancer UNE SEULE FOIS sur ton Ace Magician (Ubuntu)
# ================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SERVER_DIR="$SCRIPT_DIR/server"
PLUGIN_DIR="$SERVER_DIR/plugins"
PAPER_VERSION="1.21.4"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
info()  { echo -e "${BLUE}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

echo ""
echo -e "${BLUE}╔══════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║   SurvivalCraft — Setup Initial              ║${NC}"
echo -e "${BLUE}╚══════════════════════════════════════════════╝${NC}"
echo ""

# ── 1. Java 21 ──────────────────────────────────────────────────
info "Vérification de Java 21..."
JAVA_OK=false
if command -v java &>/dev/null; then
    JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/{print $2}' | cut -d'.' -f1)
    [[ "$JAVA_VER" -ge 21 ]] && JAVA_OK=true
fi

if [ "$JAVA_OK" = false ]; then
    info "Installation de Java 21 (openjdk)..."
    sudo apt-get update -qq
    sudo apt-get install -y openjdk-21-jdk-headless
    ok "Java 21 installé."
else
    ok "Java $(java -version 2>&1 | awk -F '"' '/version/{print $2}') déjà présent."
fi

# Forcer Java 21 si plusieurs versions
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
if [ -d "/usr/lib/jvm/java-21-openjdk-amd64" ]; then
    export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
fi

# ── 2. Outils système requis ─────────────────────────────────────
PKGS_TO_INSTALL=()
command -v screen  &>/dev/null || PKGS_TO_INSTALL+=(screen)
command -v unzip   &>/dev/null || PKGS_TO_INSTALL+=(unzip)
command -v curl    &>/dev/null || PKGS_TO_INSTALL+=(curl)
command -v python3 &>/dev/null || PKGS_TO_INSTALL+=(python3)

if [ ${#PKGS_TO_INSTALL[@]} -gt 0 ]; then
    info "Installation des outils requis : ${PKGS_TO_INSTALL[*]}..."
    sudo apt-get update -qq
    sudo apt-get install -y "${PKGS_TO_INSTALL[@]}"
    ok "Outils installés."
else
    ok "Outils système déjà présents (screen, unzip, curl, python3)."
fi

# ── 3. Gradle wrapper ────────────────────────────────────────────
WRAPPER_JAR="$SCRIPT_DIR/SurvivalCore/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ]; then
    info "Téléchargement du Gradle wrapper (8.10)..."
    GRADLE_VERSION="8.10"
    TMPDIR_GRADLE=$(mktemp -d)
    GRADLE_ZIP="$TMPDIR_GRADLE/gradle.zip"

    curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
        -o "$GRADLE_ZIP" \
        || error "Impossible de télécharger Gradle. Vérifie ta connexion internet."

    unzip -q "$GRADLE_ZIP" -d "$TMPDIR_GRADLE"
    GRADLE_BIN="$TMPDIR_GRADLE/gradle-${GRADLE_VERSION}/bin/gradle"

    cd "$SCRIPT_DIR/SurvivalCore"
    "$GRADLE_BIN" wrapper --gradle-version "$GRADLE_VERSION" --quiet
    chmod +x gradlew
    rm -rf "$TMPDIR_GRADLE"
    ok "Gradle wrapper généré."
else
    ok "Gradle wrapper déjà présent."
fi

# ── 4. Build du plugin SurvivalCore ─────────────────────────────
info "Build du plugin SurvivalCore..."
cd "$SCRIPT_DIR/SurvivalCore"
chmod +x gradlew
JAVA_HOME="$JAVA_HOME" ./gradlew shadowJar --quiet \
    || error "Build échoué. Vérifie les erreurs ci-dessus."

PLUGIN_JAR=$(ls "$SCRIPT_DIR/SurvivalCore/build/libs/"SurvivalCore-*.jar 2>/dev/null | head -1)
[ -z "$PLUGIN_JAR" ] && error "JAR introuvable après le build."

cp "$PLUGIN_JAR" "$PLUGIN_DIR/"
ok "SurvivalCore.jar → $PLUGIN_DIR/"

# ── 5. PaperMC 1.21.4 ───────────────────────────────────────────
PAPER_JAR="$SERVER_DIR/paper-${PAPER_VERSION}.jar"
if [ ! -f "$PAPER_JAR" ]; then
    info "Téléchargement de PaperMC ${PAPER_VERSION}..."
    LATEST_BUILD=$(curl -fsSL \
        "https://api.papermc.io/v2/projects/paper/versions/${PAPER_VERSION}/builds" \
        | python3 -c "import sys,json; print(json.load(sys.stdin)['builds'][-1]['build'])" \
        2>/dev/null) || error "Impossible de récupérer la version Paper. Vérifie ta connexion."

    PAPER_URL="https://api.papermc.io/v2/projects/paper/versions/${PAPER_VERSION}/builds/${LATEST_BUILD}/downloads/paper-${PAPER_VERSION}-${LATEST_BUILD}.jar"
    curl -fsSL -o "$PAPER_JAR" "$PAPER_URL" \
        || error "Téléchargement de PaperMC échoué."
    ok "PaperMC ${PAPER_VERSION} build ${LATEST_BUILD} téléchargé."
else
    ok "PaperMC déjà présent : $PAPER_JAR"
fi

# ── 6. Vault ────────────────────────────────────────────────────
VAULT_JAR="$PLUGIN_DIR/Vault.jar"
if [ ! -f "$VAULT_JAR" ]; then
    info "Téléchargement de Vault..."
    curl -fsSL -L \
        "https://github.com/milkbowl/Vault/releases/latest/download/Vault.jar" \
        -o "$VAULT_JAR" \
        || error "Téléchargement Vault échoué."
    ok "Vault.jar téléchargé."
else
    ok "Vault déjà présent."
fi

# ── 7. Plugins optionnels (conseil) ─────────────────────────────
echo ""
warn "Plugins optionnels NON installés automatiquement (à mettre dans server/plugins/) :"
warn "  - LuckPerms       → https://luckperms.net/download"
warn "  - EssentialsX     → https://essentialsx.net/downloads.html"
warn "  - BlueMap         → https://modrinth.com/plugin/bluemap"
warn "  - PlaceholderAPI  → https://hangar.papermc.io/HelpChat/PlaceholderAPI"
warn "  - SlimeFun4       → https://github.com/Slimefun/Slimefun4/releases"
warn "  - MythicMobs      → https://mythicmobs.net/index.php?resources/"

# ── 8. Droits d'exécution sur les scripts ───────────────────────
chmod +x "$SCRIPT_DIR/start.sh" "$SCRIPT_DIR/stop.sh" "$SCRIPT_DIR/build.sh" 2>/dev/null || true

# ── Résumé ───────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}╔══════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║   Setup terminé avec succès !                ║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════════╝${NC}"
echo ""
echo -e "  Lance le serveur avec :  ${YELLOW}./start.sh${NC}"
echo -e "  Arrête le serveur avec : ${YELLOW}./stop.sh${NC}"
echo -e "  Rebuild le plugin :      ${YELLOW}./build.sh${NC}"
echo ""
echo -e "  ${BLUE}IP locale :${NC} $(hostname -I | awk '{print $1}'):25565"
echo ""
