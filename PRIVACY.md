# WalkThor privacy policy

_Last updated: November 16, 2026_

**WalkThor does not collect or share any data.** It has no analytics and no ads. It goes online
for one thing only: downloading a speech model for Captions, once, if you ask it to (see below).
Everything else stays on your device.

## What WalkThor reads, and why

- **Guides you import** (PDF/TXT) are copied into WalkThor's private storage so you can read and
  search them. The original file is left untouched.
- **Accessibility access ("WalkThor", an Android accessibility service), if you turn it on,** draws WalkThor's
  drawer and responds to the button shortcuts you set (see below).
  While a game is running, it also taps the very top edge of
  the game's screen once, a couple of seconds after WalkThor opens or after you finish typing a
  search, because Android gives top
  performance priority to the screen last touched (turning off accessibility access stops it too).
  To make sure that tap only ever lands on a game, never on your frontend or another app, it notes which
  app is in front on each screen (the app's name only, in memory). It does **not** read the content
  of your screen or of other apps, and does not record what you type.

- **Mirror (screen capture), only when you use it.** The Mirror tab shows part of the top
  screen live on the bottom screen, using Android's screen capture. Android asks for your
  permission when capture starts, and shows its recording indicator while it runs. Each frame is
  held in memory just long enough to draw it and is then discarded: nothing is recorded, saved,
  or sent anywhere. Capture pauses while you're on another of the game's tabs and stops when you
  leave the game's screens or put WalkThor away. The only thing stored is which parts of the
  screen you chose for each game.

- **Shots (screenshots), only when you press the button.** The Shots tab uses the same screen
  access as Mirror to show the top screen, and saves a screenshot (the whole screen or the part
  you framed) only when you press its button, as a PNG in Pictures/WalkThor on your device. You can
  view, share or delete them from the tab. Nothing is sent anywhere.

- **Clips, only when you switch them on.** In the Shots tab's Clip mode, WalkThor can record the
  top screen with the game's own sound (never the microphone), through the same screen access as
  Mirror. *Keep the last* holds the last few seconds (10 to 60, your choice) in memory only, each
  second dropped as a new one arrives; nothing is written anywhere unless you press *Save*.
  *Record* writes from that moment until you press Stop (at most 30 minutes). A red "Clips" or
  "REC" mark shows next to the clock whenever either is on, and both stop when you leave the game's
  screens or put WalkThor away; WalkThor never records in the background. Clips are saved as MP4s in
  Movies/WalkThor on your device, and listed in the tab to play, share or delete. Nothing is sent anywhere.

- **Captions, only while they're on.** The Captions tab captures your game's own sound (never
  the microphone) through the same screen access as Mirror, and passes it to Android's speech
  recognizer on your device to show what's said as text. Android requires its "record audio"
  permission for capturing another app's sound, so it asks for that. The sound isn't recorded or
  saved by WalkThor, and it stops when you leave the tab. If the device's speech recognizer works
  online rather than on the device, it's that recognizer's privacy terms that apply to the sound
  it receives; the readout shows which it is.
  WalkThor also has its own speech recognizer for devices without one (Vosk): only if you tap
  Download on the Captions tab, it downloads a speech model (about 40 MB) once from
  alphacephei.com, the Vosk project's site, and from then on works entirely on the device.

- **Stats.** The Stats tab counts how often the top screen changes (its frame rate) using the
  same screen capture as Mirror, with the same permission and the same rules: nothing is recorded,
  saved or sent. It also reads the battery's power draw, the device's heat status, the CPU and GPU
  temperatures, the fan's speed, free memory and CPU speed from Android. While a game is played with WalkThor open, it notes the power draw every
  half minute to learn how long that game usually lasts on a charge; only that average is kept,
  on the device.
  The drawer's Home shows the temperature, free storage and memory the same way, and adds up
  how long one of your emulators has been in front each day ("Played today"); only that day's
  total is kept, on the device.

- **Brightness and volume, only when you move the sliders.** Home's and Light & Sound's controls change
  Android's brightness setting (with the *Modify system settings* access you grant), the media
  volume, and, where WalkThor can't change the bottom screen's brightness, dim it with a dark layer
  drawn by the accessibility service. On the Thor, the bottom screen's real brightness is changed
  through the system helper AYN builds into the Thor's firmware (the one behind its "Run Script as
  Root" feature and its drawer), which runs commands with system rights. WalkThor uses it only to
  read and set each screen's brightness, with commands made from numbers it works out itself; it
  doesn't use it for anything else. While the brightness controls are on screen, a small brightness
  program runs through the helper and takes levels from a pipe in WalkThor's private storage (which
  no other app can reach); it stops when you leave them. If the helper isn't available (for example with the Thor's
  *Force SELinux* setting on), WalkThor dims the bottom screen instead.
  The Light & Sound tab also sets the stick lights' colour and brightness, through the system files
  that drive them (no special access needed); once you've set them, WalkThor also sets AYN's own stick-light settings (on/off, colour and brightness) to match, through the same system helper, so AYN shows your lights after the screen wakes. A stick lights log (in About, Diagnostics) notes those changes, changes to AYN's stick-light settings and when the screen turns off and on; it's kept in memory only and shared only if you press Share log. To find AYN's brightness setting, WalkThor looks at the names of
  Android's system settings; it reads the top screen's frame rate from the display driver. Nothing is
  stored except the levels you chose, and nothing is sent.
  The Controls tab (and Home, if you add it) sets the performance mode, fan mode and
  controller layout through the same system helper, only when you pick one.

- **A memory log during games, kept on the device.** While one of your emulators is in front,
  WalkThor notes the free memory every few seconds and, through the same system helper, when the
  bottom screen's apps (your home app, the app in front there, and WalkThor) are closed by Android
  and start again, with Android's reason. It's kept in a small file in WalkThor's own storage
  (the last few hundred lines), shown in About, Diagnostics, and shared only if you press Share log.
  While a game is in front, WalkThor also ranks your home app and itself just below the game for
  Android's memory killer, through the system helper, so they're closed only after everything
  else. WalkThor's small second process (Keeper) keeps doing this once a second while a game is in
  front, even while WalkThor itself has been closed, and notes what it changed in that same log. Each time WalkThor starts, it reads Android's records of why the home app and WalkThor
  were last closed (and the memory killer's own log lines about them) into that same log.

- **That a screen was touched, only while the controller is locked to a screen.** With *Lock
  controller to top/bottom screen* on (a shortcut), a one-pixel invisible WalkThor window on the
  other screen is told by Android each time that screen is touched (not where, and the touch still
  goes where it was meant to). WalkThor then taps the very top edge of the locked screen, which
  gives it the controller back. Nothing is recorded, stored or sent. Unlocking takes the window
  away.

- **Button shortcuts, only the ones you set.** With a shortcut set in Settings → Shortcuts, the
  accessibility service is handed each hardware button press just before your game gets it, so it
  can spot the presses you chose (the AYN button, which is Home from "gpio-keys" with scan code 194;
  Back pressed twice, or Back with a D-pad direction). The AYN button's presses go to WalkThor
  (or on to AYN's own drawer, if you leave them to it); every other press, including the
  controller's Home button, passes straight on to your game. With a shortcut set on Back and another button,
  Back is passed on when you let go of it, and while it's held a tiny invisible WalkThor window
  takes the controller, so the game gets nothing until you let go; WalkThor only looks at which
  D-pad direction, shoulder button or trigger is pressed in that time. Nothing is recorded, stored or sent.
  A shortcut makes the device or controller rumble briefly, if that's on.

- **Notifications on the bottom screen, only if you switch it on.** With Settings → General →
  Notifications on the bottom screen on, and notification access given to WalkThor in Android's
  settings, Android hands WalkThor each notification as it arrives. WalkThor shows the ones that
  would have popped up (app name, title and text) as a card on the bottom screen for a few
  seconds, then forgets them: nothing is recorded, stored or sent. While it's on, WalkThor
  switches Android's own pop-up banners off through AYN's system helper, and switches them back on
  when you turn the setting off, or when WalkThor loses accessibility or notification access.
  Android doesn't let an app do anything as it's uninstalled, so switch the setting off first.
  WalkThor only sends a notification of its own when you tap Send a test notification in Diagnostics.

- **Face Buttons: button presses, only after you agree.** The Face Buttons screen lights up the
  buttons you press. After you agree to the explanation shown the first time you open it,
  the accessibility service sees each controller button press just before your game does, and
  passes it straight on. This happens only while the Face Buttons screen is open; nothing is recorded,
  stored or sent, and keyboard text elsewhere is never read. The button layout you pick for each
  emulator is stored on the device.

- **WalkThor keyboard: what you type, only if you choose it.** WalkThor includes its own keyboard,
  which is used only if you switch it on (Settings → General) or pick it in Android's
  keyboard settings. Like any keyboard, it passes each key you press to the app you're typing in.
  WalkThor doesn't record, store or send what you type, and the keyboard has no suggestions,
  dictionary or learning.

- **Which app is in front.** WalkThor's accessibility service notes which app is in front on each
  screen (its package name only, in memory), so the drawer knows when one of your emulators is
  running and shows its tabs, and, with *Open when an emulator starts* on, comes up by itself. It
  never reads notifications, window titles or anything on the game's screen.

- **A ROMs folder you link.** WalkThor reads the names of the folders and files in it (one folder
  per system, like `gba` or `switch`), never the games themselves, to list your games by system.
  The list of names is kept on the device.

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

Your library of guides, reading positions, settings, which guide goes with which game, the apps
you pinned, your ROMs folder's list of game names, a list of the recent times WalkThor's app closed and why (with how much memory it was using and what it was doing, such as a game running or clips on), and — only if WalkThor crashes — a crash report (the error and device model) that you
can choose to share from About & licences. Uninstalling WalkThor deletes all of it. You can unlink folders and turn off
accessibility access at any time from the library's Settings (⋮) or Android's Accessibility settings.

## Contact

Questions or concerns: open an issue at https://github.com/nerf-herder-29/WalkThor/issues
