#!/usr/bin/env bash
# ================================================================
#  stop.sh — Arrêt propre du serveur SurvivalCraft
# ================================================================
set -euo pipefail

SCREEN_NAME="minecraft"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
info()  { echo -e "\033[0;34m[INFO]\033[0m  $*"; }
ok()    { echo -e "${GREEN}[OK]\033[0m    $*"; }
warn()  { echo -e "${YELLOW}[WARN]\033[0m  $*"; }
error() { echo -e "${RED}[ERROR]\033[0m $*"; exit 1; }

if ! screen -list 2>/dev/null | grep -q "$SCREEN_NAME"; then
    warn "Aucun serveur '$SCREEN_NAME' en cours."
    exit 0
fi

info "Envoi de la commande 'stop' au serveur..."
screen -S "$SCREEN_NAME" -p 0 -X stuff "stop$(printf '\r')"

# Attendre que le screen se ferme (max 30s)
for i in $(seq 1 30); do
    sleep 1
    if ! screen -list 2>/dev/null | grep -q "$SCREEN_NAME"; then
        ok "Serveur arrêté proprement."
        exit 0
    fi
done

warn "Le serveur met du temps à s'arrêter (sauvegarde en cours ?)..."
screen -S "$SCREEN_NAME" -X quit 2>/dev/null || true
ok "Screen fermé."
