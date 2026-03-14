package cz.xoam24.chromawars;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageManager {

    private final ChromaWars plugin;
    private File file;
    private YamlConfiguration messages;
    private final Pattern hexPattern = Pattern.compile("&#([a-fA-F0-9]{6})");

    public MessageManager(ChromaWars plugin) {
        this.plugin = plugin;
        loadMessages();
    }

    private void loadMessages() {
        file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            try {
                file.getParentFile().mkdirs();
                file.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        messages = YamlConfiguration.loadConfiguration(file);

        if (!messages.contains("prefix")) {
            messages.set("prefix", "&#00FF00[ChromaWars] &7");
            messages.set("error.no-permission", "&cNemáš práva!");
            messages.set("elo.add", "&aHráči {player} bylo přidáno {amount} ELO.");
            try {
                messages.save(file);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public String getMessage(String path) {
        String msg = messages.getString(path, "&cZpráva nenalezena: " + path);
        String prefix = messages.getString("prefix", "");
        return colorize(prefix + msg);
    }

    public String colorize(String text) {
        if (text == null) return "";
        Matcher matcher = hexPattern.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String color = "#" + matcher.group(1);
            matcher.appendReplacement(buffer, ChatColor.of(color).toString());
        }
        matcher.appendTail(buffer);
        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }
}