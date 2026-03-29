#!/usr/bin/env bash
# ================================================================
#  start.sh — Démarre le serveur SurvivalCraft
#  Usage : ./start.sh
#  Le serveur tourne dans un screen nommé "minecraft"
#  Pour y accéder : screen -r minecraft
#  Pour le quitter sans l'arrêter : Ctrl+A puis D
# ================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SERVER_DIR="$SCRIPT_DIR/server"
SCREEN_NAME="minecraft"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
info()  { echo -e "${BLUE}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# ── Vérif prérequis ──────────────────────────────────────────────
command -v java &>/dev/null  || error "Java non trouvé. Lance d'abord : ./setup.sh"
command -v screen &>/dev/null || error "screen non trouvé. Lance d'abord : ./setup.sh"
[ -f "$SERVER_DIR/paper-1.21.4.jar" ] || error "PaperMC non trouvé. Lance d'abord : ./setup.sh"

# ── Java 21 ─────────────────────────────────────────────────────
if [ -d "/usr/lib/jvm/java-21-openjdk-amd64" ]; then
    export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
    export PATH="$JAVA_HOME/bin:$PATH"
fi
JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/{print $2}' | cut -d'.' -f1)
[[ "$JAVA_VER" -ge 21 ]] || error "Java 21+ requis (version détectée : $JAVA_VER). Lance ./setup.sh"

# ── Serveur déjà en cours ? ──────────────────────────────────────
if screen -list 2>/dev/null | grep -q "$SCREEN_NAME"; then
    warn "Le serveur tourne déjà (screen '$SCREEN_NAME')."
    echo -e "  Pour y accéder : ${YELLOW}screen -r $SCREEN_NAME${NC}"
    echo -e "  Pour l'arrêter : ${YELLOW}./stop.sh${NC}"
    exit 0
fi

# ── Build du plugin ──────────────────────────────────────────────
echo ""
info "Build du plugin SurvivalCore..."
cd "$SCRIPT_DIR/SurvivalCore"
JAVA_HOME="$JAVA_HOME" ./gradlew shadowJar --quiet 2>&1 \
    || { warn "Build échoué, démarrage avec l'ancien JAR."; }

PLUGIN_JAR=$(ls "$SCRIPT_DIR/SurvivalCore/build/libs/"SurvivalCore-*.jar 2>/dev/null | head -1)
if [ -n "$PLUGIN_JAR" ]; then
    cp "$PLUGIN_JAR" "$SERVER_DIR/plugins/"
    ok "Plugin déployé → $(basename "$PLUGIN_JAR")"
fi

# ── Démarrage du serveur ─────────────────────────────────────────
echo ""
echo -e "${GREEN}╔══════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║   Démarrage de SurvivalCraft...              ║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════════╝${NC}"
echo ""

# Flags JVM Aikar's pour PaperMC — optimisés pour N100 (4 cœurs, 8 Go RAM)
# On alloue 4 Go au serveur (laisse 4 Go pour l'OS)
JVM_FLAGS=(
    "-Xms4G"
    "-Xmx4G"
    "-XX:+UseG1GC"
    "-XX:+ParallelRefProcEnabled"
    "-XX:MaxGCPauseMillis=200"
    "-XX:+UnlockExperimentalVMOptions"
    "-XX:+DisableExplicitGC"
    "-XX:+AlwaysPreTouch"
    "-XX:G1NewSizePercent=30"
    "-XX:G1MaxNewSizePercent=40"
    "-XX:G1HeapRegionSize=8M"
    "-XX:G1ReservePercent=20"
    "-XX:G1HeapWastePercent=5"
    "-XX:G1MixedGCCountTarget=4"
    "-XX:InitiatingHeapOccupancyPercent=15"
    "-XX:G1MixedGCLiveThresholdPercent=90"
    "-XX:G1RSetUpdatingPauseTimePercent=5"
    "-XX:SurvivorRatio=32"
    "-XX:+PerfDisableSharedMem"
    "-XX:MaxTenuringThreshold=1"
    "-Dusing.aikars.flags=https://mcflags.emc.gs"
    "-Daikars.new.flags=true"
)

cd "$SERVER_DIR"
screen -dmS "$SCREEN_NAME" \
    java "${JVM_FLAGS[@]}" \
    -jar "paper-1.21.4.jar" \
    --nogui

sleep 2
if screen -list 2>/dev/null | grep -q "$SCREEN_NAME"; then
    ok "Serveur démarré dans le screen '$SCREEN_NAME'."
    echo ""
    echo -e "  Accéder à la console : ${YELLOW}screen -r $SCREEN_NAME${NC}"
    echo -e "  Quitter la console   : ${YELLOW}Ctrl+A puis D${NC} (le serveur continue)"
    echo -e "  Arrêter le serveur   : ${YELLOW}./stop.sh${NC}"
    echo -e "  IP locale            : ${BLUE}$(hostname -I | awk '{print $1}'):25565${NC}"
    echo ""
else
    error "Le serveur ne semble pas avoir démarré. Vérifie les logs dans server/logs/latest.log"
fi
