# BlackBox

`/gearcheck` checks the currently selected main of every active player profile.
It is also available through **Profiles → Enchants & Gems** and uses the bot's
existing Telegram access policy and Blizzard API credentials.

The report lists missing permanent enchants and empty existing gem sockets for
each character, with enchant/gem counts and Blizzard's source timestamp when
available. Midnight slots are head, shoulders, chest, legs (spellthread/armor kit),
boots, both rings, and equipped weapons. Shields and held off-hands do not need a
weapon enchant. Temporary effects and cosmetic illusions do not count.

This checks presence, not enchant/gem rank, best stats, or sockets that could still
be added. It uses Blizzard's last saved equipment, which may lag behind the game,
and caches successful checks for five minutes per character. Switching mains is
reflected on the next command. Unavailable or incomplete equipment is reported
per character; profiles outside the configured Blizzard API region are unavailable.

Rules reference: [Midnight enchants and gems](https://www.method.gg/guides/list-of-all-midnight-consumables-enchants-and-gems).
Data source: Blizzard's Character Equipment Summary (`/profile/wow/character/{realm}/{name}/equipment`).
