<div align="center">
  <img width="1080" height="462" alt="eh_main(1)(1)" src="https://github.com/user-attachments/assets/e11ddfe6-3c45-4eac-9d3b-c91ea8596cca" />
</div>
<div align="center">
  <img width="1080" height="2000" alt="eh_main(1)(1)" src="https://github.com/user-attachments/assets/e3a88d21-42f6-42b3-834b-e1ef0f968deb" />
</div>
<div align="center">
  <img width="1080" height="557" alt="eh_main(2)(2)" src="https://github.com/user-attachments/assets/29235835-66bc-431c-8480-eae557b4e04e" />
</div>

---

## Dependencies
- [PacketEvents](https://github.com/retrooper/packetevents)
- [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) *(optional)*

---

## Installation
1. Download **ExtendedHorizons.jar**
2. Download dependencies
3. Put all of them inside your `/plugins` folder
4. Start your server — done

---
## Messages
- All texts are in **messages.yml**, with MiniMessage support.

---

## Commands
Alias base: `/eh` (also: `extendedhorizons`, `horizons`, `viewdistance`, `vd`)

| Command | Description | Permission |
|----------|--------------|-------------|
| `/eh setme <distance>` | Sets your own preferred distance | `extendedhorizons.use` |
| `/eh set <player> <distance>` | Sets another player's preferred distance | `extendedhorizons.admin` |
| `/eh reload` | Reloads settings | `extendedhorizons.admin` |

---

## Permissions
- `extendedhorizons.use` — player commands
- `extendedhorizons.admin` — admin commands
- `extendedhorizons.max.<distance>` — max distance allowed for `/eh setme`

### Dynamic max distance (`/eh setme`)
- If a player has one or more `extendedhorizons.max.<number>` permissions, that value is used as their `/eh setme` max.
- If multiple values exist, the highest numeric value is used.
- If the player has no `extendedhorizons.max.<number>`, the plugin uses the default max from `fake-chunks.target-view-distance`.

---

## Placeholders (PlaceholderAPI)
- `%extendedhorizons_view_distance%` — current effective distance

---

---
# Support
- Report issues and suggestions in the repository’s Issues section.
- Join our **Discord**: [discord.gg/yA3vD2S8Zj](https://discord.gg/yA3vD2S8Zj)
---
<div align="center">
  <img width="1920" height="578" alt="photo-collage png(1)(1)" src="https://github.com/user-attachments/assets/db8c8477-4964-4466-8b01-9c4ed3a6d0a2" />
</div>
