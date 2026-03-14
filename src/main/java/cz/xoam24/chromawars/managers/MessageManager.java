package cz.xoam24.chromawars.managers;

import cz.xoam24.chromawars.ChromaWars;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Načítá messages.yml a poskytuje metody pro odesílání zpráv.
 *
 * Klíčová oprava: &#RRGGBB se konvertuje na <#RRGGBB> PŘED parsováním
 * přes MiniMessage, takže gradienty a HEX barvy fungují správně.
 */
public class MessageManager {

    private static final Pattern LEGACY_HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final ChromaWars plugin;
    private final Map<String, String> messages = new HashMap<>();
    private String prefix = "";

    public MessageManager(ChromaWars plugin) {
        this.plugin = plugin;
    }

    // ── Načtení messages.yml ──────────────────────────────────────────────

    public void load() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }

        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        messages.clear();

        // Rekurzivně načteme všechny string klíče (tečkový zápis)
        flattenConfig(cfg, "", messages);

        prefix = messages.getOrDefault("prefix",
                "<gray>[<gradient:#FF4444:#4477FF>ChromaWars</gradient>]</gray> ");

        plugin.getLogger().info("Zprávy načteny: " + messages.size() + " klíčů.");
    }

    /** Rekurzivně flattenuje YAML sekce do Map s tečkovými klíči. */
    private void flattenConfig(org.bukkit.configuration.ConfigurationSection section,
                               String prefix, Map<String, String> result) {
        for (String key : section.getKeys(false)) {
            String fullKey = prefix.isEmpty() ? key : prefix + "." + key;
            if (section.isConfigurationSection(key)) {
                flattenConfig(section.getConfigurationSection(key), fullKey, result);
            } else if (section.isString(key) || section.isInt(key) || section.isDouble(key)) {
                result.put(fullKey, section.getString(key, ""));
            }
        }
    }

    // ── Parsování ─────────────────────────────────────────────────────────

    /**
     * Vrátí Component pro daný klíč s nahrazenými placeholdery.
     * Placeholdery: střídají se klíč, hodnota, klíč, hodnota...
     * Hodnoty jsou parsovány jako MiniMessage (mohou obsahovat barvy).
     */
    public Component get(String key, String... placeholders) {
        String raw = messages.getOrDefault(key, "<red>Chybí zpráva: " + key + "</red>");
        raw = convertHex(raw);

        if (placeholders.length >= 2) {
            TagResolver.Builder resolver = TagResolver.builder();
            for (int i = 0; i + 1 < placeholders.length; i += 2) {
                String pKey = placeholders[i];
                String pVal = convertHex(placeholders[i + 1]);
                resolver.resolver(Placeholder.parsed(pKey, pVal));
            }
            return MM.deserialize(raw, resolver.build());
        }
        return MM.deserialize(raw);
    }

    /**
     * Odešle zprávu s prefixem.
     */
    public void send(CommandSender sender, String key, String... placeholders) {
        Component prefixComp = MM.deserialize(convertHex(prefix));
        sender.sendMessage(prefixComp.append(get(key, placeholders)));
    }

    /**
     * Vrátí surový (neparsovaný) string pro klíč.
     * Použít jen pro účely kde se string zpracovává jindy (např. scoreboard).
     */
    public String getRaw(String key) {
        return messages.getOrDefault(key, key);
    }

    /**
     * Parsuje libovolný MiniMessage string (pro dynamicky sestavené texty).
     */
    public Component parse(String miniMessage) {
        return MM.deserialize(convertHex(miniMessage));
    }

    /**
     * Konvertuje &#RRGGBB → <#RRGGBB> pro správné zpracování přes MiniMessage.
     * Bez tohoto kroku se tagy zobrazují jako plain text v Minecraftu.
     */
    public static String convertHex(String input) {
        if (input == null) return "";
        return LEGACY_HEX.matcher(input).replaceAll(mr -> "<#" + mr.group(1) + ">");
    }

    public String getPrefix() { return prefix; }
}