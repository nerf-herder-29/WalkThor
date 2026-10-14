# WalkThor

A walkthrough reader for the **AYN Thor**'s bottom screen. Import a PDF or TXT guide and it
becomes an animated book you can flip through and search while you play on the top screen.

## Features

- **Import PDF or TXT guides** — from the in-app picker, a file manager's *Open with*, or *Share*.
  Guides are copied into the app, so the original file can be moved or deleted.
- **Animated book** — pages turn in 3D around the spine. Drag a page and it follows your finger;
  tap the outer edges to turn; tap the middle of a page to open it full screen with pinch-zoom.
  - PDFs default to a **two-page spread** (a portrait PDF page fits each half of the 1240×1080 panel almost exactly).
  - TXT guides default to a **single wide page** sized to fit 80 columns, so GameFAQs-style ASCII
    tables and maps stay aligned. Text size is adjustable.
  - Switch layouts any time from the ⋮ menu.
- **Search bar** across the top — results as you type, with a list of every page that matches
  (snippet + match count), ▲/▼ to step through matching pages, and matches highlighted on the page
  (including inside PDFs).
- **Controller support** — L1/R1 (or D-pad left/right) turn pages.
- **Remembers your place** in every guide, and a page scrubber at the bottom for big jumps.
- **Move to other screen** (⋮ menu / library header) relaunches WalkThor on the Thor's other
  display and remembers that choice for next launch.

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
