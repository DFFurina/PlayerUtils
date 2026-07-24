# Player Utils

A NeoForge mod for Minecraft 1.21.1 that adds utility commands for server administration.

## Features

- `/choke` – Force players to suffocate unless inventory conditions are met.
- `/health` – Lock a player's health to a fixed value.
- `/pvp` – Disable a player's ability to attack other players.
- `/autosp` – Respawn at death location when clicking respawn button.

All states are persisted across server restarts and relogs.

## Requirements

- NeoForge 21.1.235 or later
- Minecraft 1.21.1
- Java 21 (or 17)

## Building

```bash
./gradlew build