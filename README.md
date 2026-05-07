# DeepLoader – Universal Video & Media Downloader

A production-ready Android application for downloading videos, audio, and media from YouTube, Instagram, TikTok, Reddit, Vimeo, Twitter/X, Facebook, SoundCloud, Twitch, Pinterest, Dailymotion, and LinkedIn.

Note: the user-facing product name is now `DeepLoader`. The current Android package and some internal class names still use `com.mediadrop.app` to avoid an unnecessary package/db migration.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                       UI Layer (Jetpack Compose)            │
│  HomeScreen ─── DownloadsScreen ─── SettingsScreen         │
│  HomeViewModel   DownloadsViewModel   SettingsViewModel     │
│  FormatPickerBottomSheet  DownloadCard  RecentCarousel      │
└──────────────────────────┬──────────────────────────────────┘
                           │ StateFlow / UiState
┌──────────────────────────▼──────────────────────────────────┐
│                     Domain Layer                            │
│  FetchMediaInfoUseCase                                      │
│  StartDownloadUseCase                                       │
│  GetDownloadHistoryUseCase                                  │
│  MediaRepository (interface)  DownloadRepository (i-face)  │
└──────────┬───────────────────────────┬──────────────────────┘
           │                           │
┌──────────▼───────────┐   ┌──────────▼──────────────────────┐
│   Remote Data        │   │   Local Data (Room)             │
│  MediaApiService     │   │   DownloadDao                   │
│  (Retrofit / Flask)  │   │   AppDatabase                   │
│  MediaRepositoryImpl │   │   DownloadRepositoryImpl        │
└──────────────────────┘   └─────────────────────────────────┘
                                        │
┌───────────────────────────────────────▼─────────────────────┐
│                     Worker Layer                            │
│  DownloadWorker (WorkManager CoroutineWorker)               │
│  NotificationHelper (foreground + completion notifs)        │
└─────────────────────────────────────────────────────────────┘

         Hilt DI wires everything via:
         AppModule / DatabaseModule / NetworkModule / RepositoryModule
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 1.9.22 |
| UI | Jetpack Compose + Material 3 |
| DI | Hilt 2.50 |
| Async | Coroutines + Flow |
| Networking | Retrofit 2.9 + OkHttp 4.12 |
| Download Engine | WorkManager 2.9 |
| Database | Room 2.6.1 |
| Preferences | DataStore |
| Image Loading | Coil 2.5 |
| Navigation | Navigation Compose 2.7.6 |
| Min SDK | 24 (Android 7) |
| Target SDK | 34 (Android 14) |

---

## Project Structure

```
app/src/main/java/com/mediadrop/app/
├── MediaDropApplication.kt          ← Hilt + WorkManager init
├── data/
│   ├── local/
│   │   ├── db/AppDatabase.kt
│   │   ├── dao/DownloadDao.kt
│   │   └── entity/DownloadEntity.kt
│   ├── remote/
│   │   ├── api/MediaApiService.kt
│   │   └── dto/MediaInfoDto.kt
│   └── repository/
│       ├── MediaRepositoryImpl.kt
│       └── DownloadRepositoryImpl.kt
├── domain/
│   ├── model/
│   │   ├── MediaInfo.kt             ← VideoFormat, AudioFormat
│   │   ├── SupportedPlatform.kt     ← 12 platforms + detection
│   │   └── DownloadStatus.kt
│   ├── repository/                  ← Interfaces
│   └── usecase/
│       ├── FetchMediaInfoUseCase.kt
│       ├── StartDownloadUseCase.kt
│       └── GetDownloadHistoryUseCase.kt
├── ui/
│   ├── MainActivity.kt
│   ├── ShareReceiverActivity.kt
│   ├── MediaDropApp.kt              ← NavHost + bottom nav
│   ├── home/HomeScreen.kt + HomeViewModel.kt
│   ├── downloads/DownloadsScreen.kt + DownloadsViewModel.kt
│   ├── settings/SettingsScreen.kt + SettingsViewModel.kt
│   ├── components/
│   │   ├── FormatPickerBottomSheet.kt
│   │   ├── DownloadCard.kt
│   │   ├── PlatformBadge.kt
│   │   ├── AnimatedProgressBar.kt
│   │   ├── RecentDownloadsCarousel.kt
│   │   └── ErrorAlertDialog.kt
│   └── theme/ Color.kt + Type.kt + Theme.kt
├── worker/DownloadWorker.kt         ← HiltWorker with progress
├── di/                              ← AppModule, DB, Network, Repo
└── util/                            ← FileUtils, NetworkUtils, etc.

backend/                             ← Flask yt-dlp API
├── app.py
├── requirements.txt
├── Procfile
└── railway.json
```

---

## Setup Instructions

### 1. Clone & Open

```bash
git clone <your-repo>
# Open in Android Studio Hedgehog (2023.1.1) or newer
```

### 2. Configure local.properties

Copy the template and fill in your values:
```bash
cp local.properties.template local.properties
```

```properties
sdk.dir=C:\Users\YourName\AppData\Local\Android\Sdk
API_BASE_URL=https://your-ytdlp-api.railway.app/
```

### 3. Deploy the Backend

#### Option A – Railway (recommended, free tier)

```bash
cd backend
# Push to a new GitHub repo, then connect to Railway
# Railway auto-detects Python via Nixpacks
```

Set environment variables in Railway:
- `MAX_DURATION_SECONDS=7200` (optional)
- `YTDLP_COOKIES_FILE=/app/cookies.txt` (optional, helps with YouTube anti-bot/login checks)
- `YTDLP_COOKIES_BROWSER=chrome` (optional alternative to a cookie file)
- `YTDLP_COOKIES_PROFILE=Default` (optional browser profile for `YTDLP_COOKIES_BROWSER`)
- `YTDLP_YOUTUBE_PO_TOKEN=mweb.gvs+...` (optional, for YouTube PO-token-protected requests)
- `YTDLP_YOUTUBE_VISITOR_DATA=...` (optional, pairs with the same session/PO token when needed)

After deploy, copy the URL to `local.properties` as `API_BASE_URL`.

The `/health` endpoint now also reports whether cookie/PO-token based YouTube auth is configured, without exposing the secret values.

#### Option B – Local development (with ngrok)

```bash
cd backend
pip install -r requirements.txt
python app.py
# In another terminal:
ngrok http 8080
# Copy the ngrok HTTPS URL → local.properties API_BASE_URL
```

### 4. Build & Run

```bash
./gradlew assembleDebug
# Or Run from Android Studio
```

### 5. Cloud APK Build with GitHub Actions

This repo includes a GitHub Actions workflow at `.github/workflows/build.yml`.

- Push to `main`, `master`, or `develop`, or run the workflow manually from the Actions tab.
- Download the installable debug APK artifact named `DeepLoader-debug-<run_number>`.
- To generate a signed release APK on tags like `v1.0.0`, add these GitHub secrets:
- `API_BASE_URL`
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

---

## Supported Platforms

| Platform | Domains |
|---|---|
| YouTube | youtube.com, youtu.be |
| Instagram | instagram.com, instagr.am |
| Facebook | facebook.com, fb.watch, fb.com |
| Twitter / X | twitter.com, x.com, t.co |
| TikTok | tiktok.com, vm.tiktok.com |
| Reddit | reddit.com, v.redd.it |
| Vimeo | vimeo.com |
| Dailymotion | dailymotion.com, dai.ly |
| Pinterest | pinterest.com, pin.it |
| SoundCloud | soundcloud.com |
| Twitch | twitch.tv, clips.twitch.tv |
| LinkedIn | linkedin.com |

---

## Download Flow

```
User pastes URL
      │
HomeViewModel.fetchMedia()
      │
FetchMediaInfoUseCase → MediaRepository.fetchMediaInfo()
      │                       │
      │               Retrofit → Flask API → yt-dlp
      │
FormatPickerBottomSheet shown (video / audio selection)
      │
StartDownloadUseCase
      │
WorkManager.enqueueUniqueWork(DownloadWorker)
      │
DownloadWorker.doWork()
  ├── GET /download → direct CDN URL proxy
  ├── OkHttp stream download with progress
  ├── setProgressAsync() → UI progress bar
  ├── NotificationHelper.updateProgress()
  └── On complete:
       ├── Room DB updated (COMPLETED)
       └── NotificationHelper.showCompletionNotification()
```

---

## Permissions

| Permission | Why |
|---|---|
| INTERNET | Network requests |
| FOREGROUND_SERVICE | Download notifications |
| POST_NOTIFICATIONS | Download progress / completion |
| WRITE_EXTERNAL_STORAGE | Downloads on Android ≤ 9 |
| READ_MEDIA_VIDEO/AUDIO | Media access on Android 13+ |

---

## Legal Notice

This app is intended for personal, non-commercial use only. Always respect the Terms of Service of the platforms you download from. Only download content you have permission to download.

---

## Contributing

PRs welcome. Follow MVVM + Clean Architecture patterns established in the project.
