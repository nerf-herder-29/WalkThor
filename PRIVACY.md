# WalkThor privacy policy

_Last updated: October 25, 2026_

**WalkThor does not collect or share any data.** It has no analytics and no ads. It goes online
for one thing only: fetching artwork for the games in your ROMs folder, and only if you add a
scraper account and start a scrape (see below). Everything else stays on your device.

## What WalkThor reads, and why

- **Guides you import** (PDF/TXT) are copied into WalkThor's private storage so you can read and
  search them. The original file is left untouched.
- **An artwork folder you link** (for example Cocoon's `downloaded_media`) is read to find cover
  images for your guides. Chosen covers are copied into WalkThor's private storage.
- **The Cocoon folder you link** is read only for Cocoon's `launch_debug.log`, to learn which game
  you launched most recently (its name, file name and emulator) so WalkThor can open that game's
  guide. Nothing in the folder is changed.
- **Accessibility access ("WalkThor", an Android accessibility service), if you turn it on,** places a thin
  invisible strip along the top edge of the bottom screen and responds only to swipes that start
  there, to open or close WalkThor. While a game is running, it also taps the very top edge of
  the game's screen once, a couple of seconds after WalkThor opens or after you finish typing a
  search, because Android gives top
  performance priority to the screen last touched (turning off accessibility access stops it too). It does **not** read the content of your screen or of other apps, and does not
  record what you type.

- **Mirror (screen capture), only when you use it.** The Mirror tab shows part of the top
  screen live on the bottom screen, using Android's screen capture. Android asks for your
  permission when capture starts, and shows its recording indicator while it runs. Each frame is
  held in memory just long enough to draw it and is then discarded: nothing is recorded, saved,
  or sent anywhere. Capture pauses while you're on another of the game's tabs and stops when you
  leave the game's screens or put WalkThor away. The only thing stored is which parts of the
  screen you chose for each game.

- **Stats.** The Stats tab counts how often the top screen changes (its frame rate) using the
  same screen capture as Mirror, with the same permission and the same rules: nothing is recorded,
  saved or sent. It also reads the battery's power draw, the device's heat status, free memory and
  CPU speed from Android. While a game is played with WalkThor open, it notes the power draw every
  half minute to learn how long that game usually lasts on a charge; only that average is kept,
  on the device.

- **Face Buttons: button presses, only after you agree.** The Face Buttons screen lights up the
  buttons you press. After you agree to the explanation shown the first time you open it,
  the accessibility service sees each controller button press just before your game does, and
  passes it straight on. This happens only while the Face Buttons screen is open; nothing is recorded,
  stored or sent, and keyboard text elsewhere is never read. The button layout you pick for each
  emulator is stored on the device.

- **Open when an emulator starts, only if you turn it on.** While it's on, WalkThor's
  accessibility service notes which app comes to the front, so it knows when an emulator starts a
  game and can open on the bottom screen. With Cocoon linked, Cocoon's log names the game as usual.
  Without Cocoon, for the emulator that's running, and only that one, it also looks at its window
  title and its own notifications, to spot a game name that matches one of your games or guides.
  Other apps' notifications are ignored and nothing on the game's screen is read. What it sees is
  kept in memory only until the game ends; only the games and guides you pick for each emulator
  are remembered, on the device.

- **Recognise the game you pick, only if you turn it on** (it needs *Open when an emulator
  starts*). WalkThor's accessibility service reads the text on your frontend's screen (Cocoon,
  Daijisho, ES-DE and similar apps, or your home-screen app; never any other app), as a screen
  reader would, so the game name on screen just before an emulator starts tells WalkThor which
  game you launched. Only names that match a game in your ROMs folder or one of your guides are
  used. The text is kept in memory only, and nothing is stored or sent.

- **A ROMs folder you link.** WalkThor reads the names of the folders and files in it (one folder
  per system, like `gba` or `switch`), never the games themselves, so you can pick which game
  you're playing and scrape its artwork. The list of names is kept on the device.

- **Scraping artwork (the only time WalkThor goes online), only if you add an account.** When you
  start a scrape (or pick a game that has no artwork yet), WalkThor sends each game's ROM file name
  and system to [ScreenScraper](https://www.screenscraper.fr) with your ScreenScraper login and
  developer credentials, and/or its tidied-up title to [SteamGridDB](https://www.steamgriddb.com)
  with your API key, and downloads the box art, wide art and logo they return. Nothing else is
  sent. Those services' own privacy policies apply to what you send them. Your account details
  and the downloaded artwork are stored only on the device.

- **Apps tab: your installed apps.** The Apps tab lists the apps that have a home-screen icon
  (their names and icons), read from Android when you open the tab, so you can open them on
  either screen. The list isn't stored or sent anywhere; only the apps you pin are remembered, on
  the device. Opening an app on WalkThor's screen tells WalkThor not to reopen itself over it.

- **Reopening after Android closes WalkThor.** While WalkThor is on screen during a game, it notes
  the time every few seconds. If Android closes it (for example to free memory for a heavy game),
  WalkThor asks Android why it last closed, so it can reopen on the bottom screen when that
  happened during a game, and never when you closed it yourself. Only the time and the reason are
  kept, on the device.

## What WalkThor stores (on your device only)

Your library of guides, cover images, reading positions, settings, which guide you linked to
which game, the apps you pinned, the games and guides you picked for each emulator, your ROMs
folder's list of game names, artwork you scraped and your scraper account details, the last time Android closed WalkThor and why, and — only if WalkThor crashes — a crash report (the error and device model) that you
can choose to share from About & licences. Uninstalling WalkThor deletes all of it. You can unlink folders and turn off
accessibility access at any time from the library's Settings (⋮) or Android's Accessibility settings.

## Contact

Questions or concerns: open an issue at https://github.com/nerf-herder-29/WalkThor/issues
