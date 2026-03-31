#!/usr/bin/env bash
# ================================================================
#  build.sh — Rebuild et deploie SurvivalCore
#  Si le serveur tourne, propose un restart propre
# ================================================================
set -euo pipefail

# ── Detection OS ─────────────────────────────────────────────────
OS="$(uname -s)"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SERVER_DIR="$SCRIPT_DIR/server"
SCREEN_MC="minecraft"

BLUE='\033[0;34m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
info()  { echo -e "${BLUE}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# Java 21 — portable macOS + Linux
if [ "$OS" = "Darwin" ]; then
    JAVA_HOME_DETECTED="$(/usr/libexec/java_home 2>/dev/null || true)"
    if [ -z "$JAVA_HOME_DETECTED" ] && command -v brew &>/dev/null; then
        for candidate in \
            "$(brew --prefix openjdk@21 2>/dev/null)/libexec/openjdk.jdk/Contents/Home" \
            "$(brew --prefix)/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"; do
            if [ -d "$candidate" ]; then
                JAVA_HOME_DETECTED="$candidate"
                break
            fi
        done
    fi
    if [ -n "$JAVA_HOME_DETECTED" ]; then
        export JAVA_HOME="$JAVA_HOME_DETECTED"
        export PATH="$JAVA_HOME/bin:$PATH"
    fi
else
    if [ -d "/usr/lib/jvm/java-21-openjdk-amd64" ]; then
        export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
        export PATH="$JAVA_HOME/bin:$PATH"
    fi
fi

info "Build de SurvivalCore..."
cd "$SCRIPT_DIR/SurvivalCore"
JAVA_HOME="${JAVA_HOME:-}" ./gradlew shadowJar --no-daemon \
    || error "Build echoue."

PLUGIN_JAR=$(ls "$SCRIPT_DIR/SurvivalCore/build/libs/"SurvivalCore-*.jar 2>/dev/null | grep -v sources | head -1)
[ -z "$PLUGIN_JAR" ] && error "JAR introuvable apres le build."

cp "$PLUGIN_JAR" "$SERVER_DIR/plugins/"
ok "$(basename "$PLUGIN_JAR") deploye."

# Restart si le serveur tourne
if screen -list 2>/dev/null | grep -q "$SCREEN_MC"; then
    echo ""
    warn "Le serveur tourne. Pour appliquer les changements :"
    echo -e "  ${YELLOW}./stop.sh && ./start.sh${NC}  (recommande)"
    echo -e "  Ou reload risque (instable) :"
    echo -e "  ${YELLOW}screen -S minecraft -p 0 -X stuff 'reload confirm\r'${NC}"
else
    info "Serveur arrete — le plugin sera charge au prochain ./start.sh"
fi
