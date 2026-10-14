# WalkThor

A walkthrough reader for the **AYN Thor**'s bottom screen. Import a PDF or TXT guide and it
becomes an animated book you can flip through and search while you play on the top screen.

## Features

- **Lives on the bottom screen** — WalkThor moves itself to the Thor's bottom display on launch
  (toggle under ⋮ → *Always open on bottom screen*).
- **Import PDF or TXT guides** — from *Add guide*, a file manager's *Open with*, or *Share*.
  Guides are copied into the app, so the original file can be moved or deleted.
- **Animated book** — pages turn in 3D around the spine. Drag a page and it follows your finger;
  tap the outer edges to turn. Toggle single page / two-page spread from the top bar.
  - PDFs default to a two-page spread; TXT guides to a single wide page sized to 80 columns so
    GameFAQs-style ASCII tables stay aligned. Text size is adjustable.
- **Reading lens** — the magnifying-glass button (or tapping the middle of a page) shows the page
  fit to the full width, zoomable to 500% with − / +, pinch or double-tap, with page arrows.
- **Magnifier loupe** — press and hold on a page, then slide.
- **Search bar** — results as you type, a list of every matching page (snippet + match count),
  ▲/▼ to step through matches, and highlights on the page (PDFs included).
- **Cover art from Cocoon** — link your Cocoon data folder and covers are matched to guides by
  title; pick or search artwork manually, or use any image.
- **Controller support** — L1/R1 (or D-pad left/right) turn pages, in the book and in the lens.
- **Remembers your place** in every guide, with a page scrubber for big jumps.

## Getting the APK

Every push to `main` is built by GitHub Actions (`.github/workflows/build.yml`).
Open the latest run under **Actions → Build APK** and download the `WalkThor-apk` artifact.
Pushing a tag like `v0.1.0` also attaches the APK to a GitHub Release.

Install by sideloading (enable "Install unknown apps" for your file manager on the Thor).

## Building locally

Requires JDK 17 and the Android SDK (API 35).

```sh
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

## How it's built

| Area | Approach |
|---|---|
| UI | Kotlin + Jetpack Compose (Material 3) |
| PDF pages | Android's built-in `PdfRenderer`, LRU bitmap cache |
| PDF search | Text of every page is extracted once at import with PdfBox-Android and saved next to the guide; match rectangles are located on demand for highlighting |
| TXT pages | `StaticLayout` pagination in the background; the same layout settings draw each page, so pages never overflow. Reading position is stored as a character offset, so it survives font/layout changes |
| Page turn | `graphicsLayer` `rotationY` around the spine with camera perspective, front/back faces and shading |
| Bottom screen | The smaller of the device's displays; the activity relaunches there with `ActivityOptions.setLaunchDisplayId` |
| Cover art | Images under the linked folder are indexed (no assumed layout) and matched to guide titles by normalized word overlap; covers are copied into the app, loaded with Coil |

Source layout:

```
app/src/main/java/com/nerfherder/walkthor/
  MainActivity.kt        intents, controller keys, app navigation
  DisplayHelper.kt       moving between the Thor's screens
  LibraryViewModel.kt    import + shelf
  ReaderViewModel.kt     pagination, search, reading position
  data/                  Book, BookRepository, TxtPaginator, PdfSource, Search
  ui/                    BookView (flip animation), ReaderScreen, LibraryScreen, PageContent, Theme
```

## Notes / known limits

- Scanned PDFs without a text layer display fine but can't be searched (no OCR).
- Password-protected PDFs aren't supported.
- Matches that span a page break in TXT guides aren't found.
