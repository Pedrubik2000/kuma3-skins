# kuma3 Skins

Different review screens for [kuma3](https://github.com/Pedrubik2000/kuma3-anki-app) (my AnkiDroid build with
FSRS-7 + RWKV) on Android. The skins show and answer kuma3's own cards through its content provider. Scheduling,
sync and the collection stay in kuma3.

Skins (each one page in `src/main/assets/<skin>/index.html`):

- **Anki**: kuma3's own look (deck tree, drawer, four answer buttons)
- **Onigiri**: a calm dashboard: level ring, stat tiles, review heatmap, palettes, one Study button
- **Flashcolor**: one colour per moment and huge type: green home, yellow question, black answer
- **Anki XP**: Windows XP with XP, combos, daily quests and achievements; can be your home screen
- **Anki Hunger**: Fear & Hunger style fights (sprites are not included: put your own in `Pictures/anki-hunger`)
- **Anki Joker**: Balatro-style runs where your reviews are the cards

All skins share one progress note in the collection, so level, XP and streak are the same in every skin and on
every device. They work with an 8BitDo Micro gamepad too.

Build: `./gradlew assembleDebug` (JDK 17). Open a page in a browser to try it with example data.
Unofficial personal project, not affiliated with Anki, AnkiDroid, Onigiri, Fear & Hunger or Balatro.
