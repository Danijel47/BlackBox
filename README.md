# BlackBox

`/adresa` returns the fixed Google Maps location with an **Otvori Google Maps**
button. It also supports `/adresa@BlackBoxBot` in groups and follows the bot's
existing Telegram access policy.

The admin can change the minimum key level for M+ combat and awards while the bot
is running: **`/wow_admin` → M+ Key Level → +12 through +18**, or
`/mplus_keylevel 14`. Use `/mplus_keylevel` without a number to open the picker and
see the current selection. The selected value means **timed +N and above**, for
all profiles; it is not an exact-level filter and does not change the Vault target.

The setting takes effect on the next combat/awards report and is saved in the
database across restarts. No restart or wait for the :00/:30 collection schedule
is needed to change the filter. Existing Telegram messages are unchanged; request
a new report. The configured `warcraft-logs.combat-minimum-keystone-level` is only
the initial default until an admin saves a selection. A saved selection takes
precedence over subsequent environment/default changes.

Warcraft Logs collection and metric backfills retain eligible runs from **+12
upward**, including levels above +18, regardless of the selected report minimum.
Switching down therefore reuses already collected runs. Previously uncollected
runs still need a scheduled sync and must be available within the collector's
normal report discovery limits. Existing history is not deleted. The database
migration `V30` adds one global setting row on first save; applying this new
feature still requires the normal one-time application deployment.

`/gearupg` opens **Character → Gear Upg → All Profiles / individual profile**.
Both selections use each active profile's currently selected main. All Profiles
sends a compact summary of the three highest-priority verified next upgrades per
main, with a **Details** button. Selecting one profile shows the full ordered
report, split into Telegram-sized pages.

The report groups recommendations into **High**, **Medium**, and **Low**, with
item names, current and next item levels/ranks, standard crest-cost estimates,
and reasons. Its guide-based starting order is weapon → trinkets → head/chest/legs
→ shoulders/gloves/belt/boots → cloak/bracers/jewelry. Fury and Frost off-hands
receive weapon priority; other off-hands use medium priority. A medium/low-priority
item at least 13 item levels below the median equipped item moves up one priority
group. Within equivalent slots, larger next-step gains come first, then lower
item level. This catch-up threshold is a bot heuristic, not a simulation result.
Trinket effects, secondary stats, tank/healer needs, and planned replacements can
change the best order; no DPS gains or best-in-slot claims are calculated.

Seasonal rules currently support **Midnight Season 2** and are isolated in
`GearUpgradeRules`. Track/rank identification requires a known seasonal item bonus
ID **and** its matching item level. Overlapping item levels alone never determine
the track. Maxed items, recognizable crafted items (`crafted_by`), and unknown or
conflicting tracks receive separate statuses and no ordinary upgrade recommendation.
Unknown items may include crafted items when Blizzard omits their crafter, special
items, and gear from other seasons. Missing equipment and unavailable specialization
data are identified explicitly.

**Crest balances cannot currently be read by this integration.** It does not infer
balances from activity, assume zero, or claim that an upgrade is affordable. Costs
are the standard 20 matching Mistcrests per rank, before same-slot and Warband
discounts, plus gold. Neither historical owned-item levels nor Warband discount
eligibility is established by equipped gear, so the player must confirm the final
cost at the vendor. Manual balances and addon imports are not implemented.

Gear Upg shares the five-minute equipment cache with Enchants & Gems and caches
successful character-specialization lookups for five minutes. It uses existing
Telegram access checks and configured Blizzard credentials/region. Equipment
timestamps reflect Blizzard's last saved data, which may lag behind the game.
No credentials or upstream error bodies are included in reports.

Upgrade references (checked 2026-09-07):

- [Season 2 upgrade costs and discounts](https://www.wowhead.com/guide/midnight/item-level-gear-upgrades-dawncrests)
- [Season 2 item-level tables](https://www.icy-veins.com/wow/world-of-warcraft-gear-upgrading-guide)
- [Slot-order guidance and simulation limitations](https://www.icy-veins.com/wow/enhancement-shaman-pve-dps-gear-best-in-slot)
- [Published Season 2 item bonus-ID mapping](https://github.com/consecrated-hammer/wow-site/blob/main/site/season-data.js)

`/gearcheck` checks the currently selected main of every active player profile.
It is also available through **Character → Enchants & Gems** and uses the bot's
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
