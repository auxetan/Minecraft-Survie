package com.survivalcore.modules;

import com.survivalcore.SurvivalCore;

/**
 * Interface commune pour tous les modules du plugin.
 * Chaque module gère son propre cycle de vie (enable/disable).
 */
public interface CoreModule {

    /**
     * Appelé au démarrage du plugin.
     * Enregistrer ici les listeners, commandes, et tâches planifiées.
     */
    void onEnable(SurvivalCore plugin);

    /**
     * Appelé à l'arrêt du plugin.
     * Libérer ici les ressources, sauvegarder les données en attente.
     */
    void onDisable();

    /**
     * Nom du module pour le logging.
     */
    String getName();
}
