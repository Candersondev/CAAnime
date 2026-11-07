# CAAnime

Native Kotlin Android TV / Google TV / Fire TV anime app starter.

## Current status
- Native TV launcher and remote-focusable navigation
- Home, search, anime details and episode selection
- Media3 ExoPlayer with HLS support
- GitHub Actions debug APK build on pushes to main and manual dispatch
- **Not yet connected to aniwatchtv.ws**. The app expects an HTTPS JSON API implementing the endpoints below. Do not set the API URL to the website itself without a compatible adapter.

## API contract
- `GET /home` returns `{"items":[{"id":"sample","title":"Sample","description":"..."}]}`
- `GET /search?q=...` returns the same items structure
- `GET /anime/{id}` returns `{"episodes":[{"id":"episode-1","title":"Episode 1"}]}`
- `GET /episodes/{id}/stream` returns `{"url":"https://authorized-stream.example/playlist.m3u8"}`

## Building
Run `gradle :app:assembleDebug -PanimeApiUrl=https://your-api.example/` with JDK 17, Android SDK 35 and Gradle 8.11.1, or download the build artifact from the Actions workflow.

Set the repository Actions variable `ANIME_API_URL` to your deployed HTTPS adapter endpoint when available.

## Limitations
This is an early starter UI, not yet the final Netflix-style poster experience. Backend compatibility, playback, Fire TV device behavior and CI compilation have not been verified.

## HiAnime-compatible integration (in progress)
The TV client now requests these endpoints relative to the configured HTTPS API root:
- `/api/v2/hianime/home`
- `/api/v2/hianime/search?q=...`
- `/api/v2/hianime/anime/{id}/episodes`
- `/api/v2/hianime/episode/sources?animeEpisodeId=...&server=hd-1&category=sub`

These paths follow a commonly used HiAnime API contract; they are **not confirmed** endpoints on `aniwatchtv.ws`, nor are they necessarily compatible with `WBRK-dev/hianime-api`. A deployed, authorized API implementing these routes is required. Configure its root URL as repository Actions variable `ANIME_API_URL` and rebuild. Source URLs may require headers or subtitle handling not yet supported by the player. The client currently selects the first source and is intended for initial integration testing.
