#!/usr/bin/env bash
# ================================================================
#  start.sh — Demarre le serveur SurvivalCraft + Playit.gg
#  Usage : ./start.sh
#
#  Le serveur Minecraft tourne dans   : screen -r minecraft
#  Le tunnel Playit.gg tourne dans    : screen -r playit
#  Tout arreter proprement            : ./stop.sh
# ================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SERVER_DIR="$SCRIPT_DIR/server"
PLUGIN_DIR="$SERVER_DIR/plugins"
SCREEN_MC="minecraft"
SCREEN_PLAYIT="playit"
PLAYIT_BIN="$SCRIPT_DIR/playit-linux-amd64"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${BLUE}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# ── Verif prerequis ─────────────────────────────────────────────
command -v java &>/dev/null   || error "Java non trouve. Lance d'abord : ./setup.sh"
command -v screen &>/dev/null || error "screen non trouve. Lance d'abord : ./setup.sh"
[ -f "$SERVER_DIR/paper-1.21.4.jar" ] || error "PaperMC non trouve. Lance d'abord : ./setup.sh"

# ── Java 21 ─────────────────────────────────────────────────────
if [ -d "/usr/lib/jvm/java-21-openjdk-amd64" ]; then
    export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
    export PATH="$JAVA_HOME/bin:$PATH"
fi
JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/{print $2}' | cut -d'.' -f1)
[[ "$JAVA_VER" -ge 21 ]] || error "Java 21+ requis (detecte : $JAVA_VER)."

# ── Serveur deja en cours ? ─────────────────────────────────────
if screen -list 2>/dev/null | grep -q "$SCREEN_MC"; then
    warn "Le serveur tourne deja (screen '$SCREEN_MC')."
    echo -e "  Console MC      : ${YELLOW}screen -r $SCREEN_MC${NC}"
    echo -e "  Console Playit  : ${YELLOW}screen -r $SCREEN_PLAYIT${NC}"
    echo -e "  Tout arreter    : ${YELLOW}./stop.sh${NC}"
    exit 0
fi

# ── Build du plugin (auto) ──────────────────────────────────────
echo ""
info "Build du plugin SurvivalCore..."
cd "$SCRIPT_DIR/SurvivalCore"
JAVA_HOME="$JAVA_HOME" ./gradlew shadowJar --no-daemon 2>&1 \
    || { warn "Build echoue — demarrage avec l'ancien JAR."; }

PLUGIN_JAR=$(ls "$SCRIPT_DIR/SurvivalCore/build/libs/"SurvivalCore-*.jar 2>/dev/null | grep -v sources | head -1)
if [ -n "$PLUGIN_JAR" ]; then
    cp "$PLUGIN_JAR" "$PLUGIN_DIR/"
    ok "Plugin deploye : $(basename "$PLUGIN_JAR")"
fi

# ── Demarrage du serveur Minecraft ──────────────────────────────
echo ""
echo -e "${GREEN}╔══════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║   Demarrage de SurvivalCraft...                      ║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════════════════╝${NC}"
echo ""

# Flags JVM Aikar — optimises pour Intel N100 (4 coeurs, 8 Go RAM)
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
screen -dmS "$SCREEN_MC" \
    java "${JVM_FLAGS[@]}" \
    -jar "paper-1.21.4.jar" \
    --nogui

sleep 2
if screen -list 2>/dev/null | grep -q "$SCREEN_MC"; then
    ok "Serveur Minecraft demarre."
else
    error "Le serveur n'a pas demarre. Verifie server/logs/latest.log"
fi

# ── Demarrage de Playit.gg ──────────────────────────────────────
if [ -f "$PLAYIT_BIN" ]; then
    if screen -list 2>/dev/null | grep -q "$SCREEN_PLAYIT"; then
        ok "Playit.gg tourne deja."
    else
        info "Demarrage du tunnel Playit.gg..."
        screen -dmS "$SCREEN_PLAYIT" "$PLAYIT_BIN"
        sleep 2
        if screen -list 2>/dev/null | grep -q "$SCREEN_PLAYIT"; then
            ok "Playit.gg demarre."
        else
            warn "Playit.gg n'a pas demarre. Lance-le manuellement : $PLAYIT_BIN"
        fi
    fi
else
    warn "Playit.gg non installe. Lance ./setup.sh pour l'installer."
    warn "Sans Playit.gg, seuls les joueurs sur ton reseau local peuvent rejoindre."
fi

# ── Resume ──────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}╔══════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║   SERVEUR EN LIGNE !                                 ║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "  ${CYAN}Commandes utiles :${NC}"
echo -e "    Console Minecraft  : ${YELLOW}screen -r $SCREEN_MC${NC}"
echo -e "    Console Playit.gg  : ${YELLOW}screen -r $SCREEN_PLAYIT${NC}"
echo -e "    Quitter la console : ${YELLOW}Ctrl+A puis D${NC}"
echo -e "    Tout arreter       : ${YELLOW}./stop.sh${NC}"
echo -e "    Rebuild le plugin  : ${YELLOW}./build.sh${NC}"
echo ""
echo -e "  ${CYAN}Connexion :${NC}"
echo -e "    Reseau local : ${BLUE}$(hostname -I 2>/dev/null | awk '{print $1}'):25565${NC}"
echo -e "    Internet     : ${BLUE}Voir l'adresse dans : screen -r $SCREEN_PLAYIT${NC}"
echo ""
echo -e "  ${YELLOW}PREMIERE FOIS avec Playit.gg ?${NC}"
echo -e "  Tape ${YELLOW}screen -r playit${NC} et ouvre le lien affiche"
echo -e "  dans ton navigateur pour creer ton tunnel gratuit."
echo ""
