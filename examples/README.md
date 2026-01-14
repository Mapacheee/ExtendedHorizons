# ExtendedHorizons API Example Plugin

This is a complete, working example plugin that demonstrates how to use the ExtendedHorizons API.


## Building This Example

1. **Copy the ExtendedHorizons JAR** to `libs/` folder:
   ```bash
   mkdir libs
   cp ../build/libs/ExtendedHorizons-2.3.3.jar libs/
   ```

2. **Build the plugin**:
   ```bash
   ./gradlew jar
   ```

3. **Find the JAR** in `build/libs/ExamplePlugin-1.0.0.jar`

## Testing This Example

1. Install both ExtendedHorizons and ExamplePlugin on your server
2. Join the server
3. Use the commands:
   - `/fakechunkinfo` - Shows your current fake chunk status
   - `/clearfakechunks` - Clears all your fake chunks
   - `/refreshfakechunks` - Refreshes your fake chunks

## Files Explained

### build.gradle
Shows how to configure your Gradle build to depend on ExtendedHorizons.

**Key points:**
- Uses `compileOnly` for ExtendedHorizons (it will be on the server at runtime)
- Includes the JAR from a local `libs/` folder
- Sets up Paper API dependency

### plugin.yml
Shows the required configuration.

**Key points:**
- `depend: [ExtendedHorizons]` ensures ExtendedHorizons loads first
- Defines commands for testing

### ExamplePlugin.java
The main plugin class showing all API features.

**Key sections:**
- `setupAPI()` - How to safely access the API
- `displayAPIStats()` - Using cache statistics methods
- `showFakeChunkInfo()` - Using player-specific methods
- Event handlers - How to listen to chunk events

## Using This as a Template

1. Copy this `examples/` folder as your project base
2. Rename the package from `com.example.exampleplugin` to your own
3. Update `plugin.yml` with your plugin information
4. Modify `ExamplePlugin.java` to add your own logic
5. Build and test!

## Common Modifications

### Make ExtendedHorizons Optional

Change `plugin.yml`:
```yaml
softdepend: [ExtendedHorizons]  # Instead of depend
```

Then check if it's available:
```java
@Override
public void onEnable() {
    if (!setupAPI()) {
        getLogger().warning("ExtendedHorizons not found, some features disabled");
        // Continue loading with limited features
    }
}
```

### Track Chunk Statistics

```java
private int totalChunksLoaded = 0;
private int totalChunksUnloaded = 0;

@EventHandler
public void onFakeChunkLoad(FakeChunkLoadEvent event) {
    totalChunksLoaded++;
}

@EventHandler
public void onFakeChunkUnload(FakeChunkUnloadEvent event) {
    totalChunksUnloaded++;
}
```

### Restrict Fake Chunks to Specific Regions

```java
@EventHandler
public void onFakeChunkLoad(FakeChunkLoadEvent event) {
    int chunkX = event.getChunkX();
    int chunkZ = event.getChunkZ();
    
    // Only allow fake chunks in spawn area (-100 to +100)
    if (Math.abs(chunkX) > 100 || Math.abs(chunkZ) > 100) {
        event.setCancelled(true);
    }
}
```

## Troubleshooting

### "Cannot find symbol" errors when building

Make sure:
1. ExtendedHorizons JAR is in the `libs/` folder
2. The JAR filename matches what's in `build.gradle`
3. You've run `./gradlew build` at least once

### "ExtendedHorizons" plugin not found error

Make sure:
1. ExtendedHorizons is installed on your server
2. It's in the `plugins/` folder
3. It loads before your plugin (check `depend:` in plugin.yml)

### API returns null

This usually means:
1. ExtendedHorizons isn't properly loaded
2. You're calling `getService()` too early (before plugin is enabled)
3. Version mismatch between your plugin and ExtendedHorizons

## More Information

See [API-USAGE.md](../API-USAGE.md) for complete API documentation.
