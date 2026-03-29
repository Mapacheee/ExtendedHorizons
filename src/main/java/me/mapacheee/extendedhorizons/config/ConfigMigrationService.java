package me.mapacheee.extendedhorizons.config;

import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

@Service
public class ConfigMigrationService {

  @OnEnable
  public void onEnable() {
    syncAll();
  }

  public void syncAll() {
    syncFile("config.yml");
    syncFile("messages.yml");
  }

  private void syncFile(String fileName) {
    ExtendedHorizonsPlugin plugin = ExtendedHorizonsPlugin.getInstance();
    if (plugin == null) return;
    File dataFolder = plugin.getDataFolder();
    if (!dataFolder.exists()) {
      dataFolder.mkdirs();
    }
    File file = new File(dataFolder, fileName);
    if (!file.exists()) {
      plugin.saveResource(fileName, false);
      return;
    }
    try (InputStream input = plugin.getResource(fileName)) {
      if (input == null) return;
      YamlConfiguration defaults =
          YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
      YamlConfiguration current = YamlConfiguration.loadConfiguration(file);
      boolean changed = mergeSection(current, defaults);
      if (changed) {
        current.save(file);
      }
    } catch (Exception ignored) {
    }
  }

  private boolean mergeSection(ConfigurationSection target, ConfigurationSection defaults) {
    boolean changed = false;
    for (String key : defaults.getKeys(false)) {
      Object defaultValue = defaults.get(key);
      if (defaultValue instanceof ConfigurationSection defaultSection) {
        ConfigurationSection targetSection = target.getConfigurationSection(key);
        if (targetSection == null) {
          targetSection = target.createSection(key);
          changed = true;
        }
        changed = mergeSection(targetSection, defaultSection) || changed;
      } else if (!target.contains(key)) {
        target.set(key, defaultValue);
        changed = true;
      }
    }
    return changed;
  }
}
