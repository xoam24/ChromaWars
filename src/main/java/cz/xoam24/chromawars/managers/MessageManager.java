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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Načítá messages.yml a poskytuje metody pro odesílání Adventure komponent.
 *
 * Podporované formáty barev v messages.yml:
 *  - MiniMessage tagy:  <red>, <#FF0000>, <gradient:red:blue>, <rainbow>
 *  - Legacy &#RRGGBB:  &#FF4444text  (automaticky konvertováno na MiniMessage)
 */
public class MessageManager {

    private static final Pattern LEGACY_HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final ChromaWars plugin;
    private final Map<String, String> messages = new HashMap<>();
    private String prefix;

    public MessageManager(ChromaWars plugin) {
        this.plugin = plugin;
    }

    // ── Načtení messages.yml ──────────────────────────────────────────────────

    public void load() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        messages.clear();

        // Rekurzivně načteme všechny klíče (tečkový zápis)
        for (String key : cfg.getKeys(true)) {
            if (cfg.isString(key)) {
                messages.put(key, cfg.getString(key, ""));
            }
        }

        prefix = messages.getOrDefault("prefix", "<gray>[<gradient:#FF4444:#4477FF>ChromaWars</gradient>]</gray> ");
        plugin.getLogger().info("Zprávy načteny: " + messages.size() + " klíčů.");
    }

    // ── Překlad zprávy na Component ───────────────────────────────────────────

    /**
     * Přeloží klíč na Adventure Component.
     * Placeholdery {placeholder} jsou nahrazeny před parsováním.
     *
     * @param key     klíč v messages.yml (tečkový zápis, např. "game.start")
     * @param placeholders  páry: klíč, hodnota, klíč, hodnota, ...
     */
    public Component get(String key, String... placeholders) {
        String raw = messages.getOrDefault(key, "<red>Chybí zpráva: " + key + "</red>");
        raw = convertLegacyHex(raw);

        // Sestavíme TagResolver z páru klíč/hodnota
        if (placeholders.length >= 2) {
            TagResolver.Builder resolver = TagResolver.builder();
            for (int i = 0; i + 1 < placeholders.length; i += 2) {
                final String pKey = placeholders[i];
                final String pVal = placeholders[i + 1];
                resolver.resolver(Placeholder.parsed(pKey, pVal));
            }
            return MM.deserialize(raw, resolver.build());
        }
        return MM.deserialize(raw);
    }

    /**
     * Odešle zprávu s prefixem příjemci.
     */
    public void send(CommandSender sender, String key, String... placeholders) {
        Component prefixComp = MM.deserialize(convertLegacyHex(prefix));
        sender.sendMessage(prefixComp.append(get(key, placeholders)));
    }

    /**
     * Vrátí surový (neparsovaný) text pro zprávu – užitečné pro scoreboardy a tituly.
     */
    public String getRaw(String key) {
        return messages.getOrDefault(key, key);
    }

    /**
     * Konvertuje legacy formát &#RRGGBB na MiniMessage <#RRGGBB>.
     */
    private String convertLegacyHex(String input) {
        Matcher m = LEGACY_HEX.matcher(input);
        return m.replaceAll(mr -> "<#" + mr.group(1) + ">");
    }

    /**
     * Parsuje libovolný MiniMessage řetězec (pro dynamicky sestavené zprávy).
     */
    public Component parse(String miniMessage) {
        return MM.deserialize(convertLegacyHex(miniMessage));
    }

    /**
     * Serializuje Component zpět na MiniMessage řetězec.
     */
    public String serialize(Component component) {
        return MM.serialize(component);
    }

    public String getPrefix() { return prefix; }
}