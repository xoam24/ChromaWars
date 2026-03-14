package cz.xoam24.chromawars.model;

import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;

/**
 * Neměnný (immutable) datový objekt reprezentující jeden tým.
 */
public record TeamConfig(
        String id,           // interní klíč (malými písmeny, bez mezer)
        String displayName,  // název zobrazovaný hráčům
        TextColor color,     // HEX barva Adventure API
        Material block,      // CONCRETE materiál tohoto týmu
        int maxPlayers       // max hráčů v týmu
) {}