package cz.xoam24.chromawars.model;

/**
 * Neměnný záznam v žebříčku.
 *
 * @param position  1-based pořadí (1 = nejlepší)
 * @param name      herní jméno hráče
 * @param elo       ELO skóre
 */
public record LeaderboardEntry(int position, String name, int elo) {}