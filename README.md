# BlackBox

BlackBox is a Telegram bot for World of Warcraft player and group reports. It tracks
Mythic+ and raid activity, Great Vault progress, character gear, auction prices,
and WoW Token trends. It also provides notifications and TomTom travel-time reports
for Zadar ↔ Zagreb.

## Getting started

Send `/start` or `/wow` to open the main menu. Use the buttons to choose a report;
`/help` opens the same menu. Access follows the bot’s configured user and chat
permissions. Ask the bot administrator to register your profile and characters.

## Player and group commands

| Command | What it does |
| --- | --- |
| `/profile` | Show your registered characters. |
| `/profile_main <realm> <character>` | Select a registered character as your main. |
| `/mains` | List the group’s current mains. |
| `/profiles` | Open the player profile menu. |
| `/ilvl` | Open the item-level report menu. |
| `/gearcheck` | Check all active mains for missing enchants and empty gem sockets. |
| `/gearupg` | Open gear upgrade recommendations for one profile or all active mains. |
| `/rio <region> <realm> <name>` | Look up a character on Raider.IO. |

Gear reports use Blizzard’s last saved equipment, which may lag behind the game.
Upgrade recommendations are guidance; crest balances and affordability are not checked.

## Mythic+ and raids

| Command | What it does |
| --- | --- |
| `/mplus` | Open the Mythic+ report menu. |
| `/mplus_progress [profile]` | Show Mythic+ progress. |
| `/mplus_vault [profile]` | Show current-week Mythic+ Vault progress. |
| `/mplus_combat` | Show combat statistics for the selected mains. |
| `/mplus_awards` | Show Mythic+ awards. |
| `/vault` | Show the group’s weekly Vault watch. |
| `/vault <realm> <name>` | Check a character’s Mythic+ Vault progress (EU by default). |
| `/raid_progress` | Show tracked players’ raid progress. |
| `/raid_vault` | Show tracked players’ raid Vault progress. |
| `/raid_combat` | Show raid combat statistics. |
| `/guild` | Show the configured guild’s raid report. |
| `/guildlist` | List available raid keys for `/guild <raidKey>`. |
| `/rwf` | Show Race to World First standings. |

Use `/mplus` and the main menu for additional reports, including dungeon coverage,
team reports, season recaps, and title tracking. `[profile]` is optional;
replace values in `<angle brackets>` with your own input.

## Prices and travel

| Command | What it does |
| --- | --- |
| `/price <itemId or item name>` | Look up an item’s price. |
| `/ores` / `/herbs` | Show lowest and typical material prices for each quality. |
| `/token` | Show the current WoW Token price. |
| `/token_lowest_week` / `/token_highest_week` | Show the week’s lowest or highest Token price. |
| `/token_lowest_month` / `/token_highest_month` | Show the month’s lowest or highest Token price. |
| `/token_best` | Show historical Token trading-hour analysis. |
| `/road zadar zagreb` | Show current travel time; reverse the cities for the return route. |
| `/roadbest zadar zagreb` | Show the best historical travel slots; also supports the reverse route. |
| `/adresa` | Open the configured location in Google Maps. |

Material reports use all available listings in the cached auction snapshot. The typical
price is the quantity-weighted median, so a few unusually cheap or expensive units
have less influence. These are asking prices, not confirmed sales.

## Administrator commands

These commands require the configured bot administrator.

| Command | What it does |
| --- | --- |
| `/wow_admin` | Open the administration menu. |
| `/help_admin` | Show administration commands and usage. |
| `/mplus_keylevel` | Choose the minimum timed key level for Mythic+ combat and awards. |
| `/mplus_keylevel 14` | Include timed +14 runs and above; saved across restarts. |
| `/prospect` | Open ore selection and recorded-batch prospecting analysis. |

Profile registration, character management, and Telegram user access are managed
through the administration commands. The Mythic+ key-level setting does not change
the Vault target.

## Development

The application uses Java 25, Spring Boot, and PostgreSQL with Flyway migrations.
Build and run verification with the Maven Wrapper:

```sh
./mvnw clean verify
```
