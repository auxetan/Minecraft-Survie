#!/usr/bin/env bash
# ================================================================
#  stop.sh — Arret propre du serveur Minecraft + Playit.gg
# ================================================================
set -euo pipefail

SCREEN_MC="minecraft"
SCREEN_PLAYIT="playit"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; BLUE='\033[0;34m'; NC='\033[0m'
info()  { echo -e "${BLUE}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }

MC_RUNNING=false
PLAYIT_RUNNING=false

screen -list 2>/dev/null | grep -q "$SCREEN_MC" && MC_RUNNING=true
screen -list 2>/dev/null | grep -q "$SCREEN_PLAYIT" && PLAYIT_RUNNING=true

if [ "$MC_RUNNING" = false ] && [ "$PLAYIT_RUNNING" = false ]; then
    warn "Aucun serveur en cours."
    exit 0
fi

# ── Arret Minecraft ─────────────────────────────────────────────
if [ "$MC_RUNNING" = true ]; then
    info "Arret du serveur Minecraft..."
    # Prevenir les joueurs connectes
    screen -S "$SCREEN_MC" -p 0 -X stuff "say Le serveur s'arrete dans 5 secondes...$(printf '\r')"
    sleep 5
    screen -S "$SCREEN_MC" -p 0 -X stuff "stop$(printf '\r')"

    # Attendre que le screen se ferme (max 30s)
    for i in $(seq 1 30); do
        sleep 1
        if ! screen -list 2>/dev/null | grep -q "$SCREEN_MC"; then
            ok "Serveur Minecraft arrete."
            break
        fi
        if [ "$i" -eq 30 ]; then
            warn "Le serveur met du temps... fermeture forcee."
            screen -S "$SCREEN_MC" -X quit 2>/dev/null || true
        fi
    done
fi

# ── Arret Playit.gg ────────────────────────────────────────────
if [ "$PLAYIT_RUNNING" = true ]; then
    info "Arret du tunnel Playit.gg..."
    screen -S "$SCREEN_PLAYIT" -X quit 2>/dev/null || true
    sleep 1
    ok "Playit.gg arrete."
fi

echo ""
ok "Tout est arrete proprement."
echo ""
