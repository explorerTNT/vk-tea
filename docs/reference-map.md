# Карта референса (VK Coffee → VK Tea)

Живой документ: что и где смотреть в декомпиле оригинала при портировании.
Декомпил лежит ВНЕ репозитория: `C:\Users\danii\Documents\GitHub\vk-tea-reference\`
(`jadx-code\` — Java, `apktool-res\` — ресурсы, `*.txt` — инвентарные списки).

## Источник

| | |
| --- | --- |
| APK | `VKCoffee_ALPHA_8.09_20211229_23-07-13.apk` (30.2 MB) |
| SHA-256 | `286219832070C2CAE3116AAD37E3B11583668FD833255FCC8F27810797A65D3C` |
| package | `su.operator555.vkcoffee` |
| versionName / Code | `8.09` / `1113` (сборка 29.12.2021) |
| Автор | OPERATOR_555 (Reversing Solutions, Полтава) — из подписи APK |

Воспроизвести декомпил:

```
jadx --no-res -d vk-tea-reference\jadx-code <apk>
java -jar apktool.jar d -f -s -o vk-tea-reference\apktool-res <apk>
```

## Структура кода (jadx)

Собственный код клиента — `su.operator555.vkcoffee.*`:

| Пакет | Классов (файлов) | Что там |
| --- | --- | --- |
| `fragments` | 299 корневых (698 файлов с лямбдами) | все экраны (News, Audio, Messages…) |
| `ui` | 442 | кастомные view, ячейки списков |
| `api` | 340 | модели и запросы VK API |
| `audio` | 120 | плеер (см. ниже) |
| `caffeine` | 76 | загрузчик контента (CaffeineDownloader/CaffeineService) |
| `data` | 62 | БД/кеш |
| `attachments` | 66 | вложения постов/сообщений |
| `media`, `stickers`, `upload`, `auth`, `slidingmenu` | — | прочее |

`com.vk.*` [679] — ядро VK SDK (сеть, ORM). Остальное — библиотеки: google, facebook, flurry, okhttp3, exoplayer (внутри `audio.player`), jfeinstein SlidingMenu, astuetz PagerSlidingTabStrip, lottie и пр.

## Плеер (приоритет №1) — `su.operator555.vkcoffee.audio.player`

Архитектура «два движка + локальный прокси + кеш»:

- `PlayerService` — foreground-сервис, центр управления; `Player`, `PlayerState`, `PlayerTrack`, `Tracks`, `TrackInfo`, `LoopMode`, `Prefs`
- Движки: `MediaPlayerHelper(I)` (классика) и `ExoPlayerHelper` (основной) + `player.ads.*` (instream-реклама — сегодня мёртвая, не портируем)
- Сеть/кеш: `player.proxy.*` (`PlayerProxy`, `ClientThread`, `LockFile`) — локальный HTTP-прокси, через который идут аудио-URL (обход истечения ссылок + кеш); `player.exo.*` — свои DataSource для ExoPlayer (`AudioOkHttpDataSource`, `AudioFileDataSource`, `AudioCacheHelper`, HLS + AES128)
- Оффлайн: `SavedTracks`, `DownloadTaskTracks`, `DownloadTrackFileConverter`, `CacheUtils`
- Системная интеграция: `MediaSessionHelper(21)`, `MediaButtonEventReceiver`

План портирования: Media3 ExoPlayer + свои `DataSource` (паттерн 1:1) + `MediaSessionService` + локальный прокси. `AudioPlayerActivity` — полноэкранный плеер (UI переносить из apktool-res).

## Экраны и навигация

- `MainActivity` — хост, SlidingMenu (боковое меню) + PagerSlidingTabStrip (табы)
- `FragmentWrapperActivity` — generic-хост фрагментов
- Ключевые активити: `AuthActivity`, `WelcomeActivity`, `CaptchaActivity` (→ заменяем VK ID), `AudioPlayerActivity`, `PinCodeViewCoffee`, `VideoPlayerActivity`, `ValidationActivity`, `LinkRedirActivity` (сюда цеплять WebView-фоллбек), `QuickSearchActivity`, `RepostActivity`
- Полный список: `vk-tea-reference\activities.txt` (57 шт)
- Сервисы: `LongPollService` (realtime сообщений), `PlayerService`, `AudioMessagePlayerService`, `CaffeineDownloader/CaffeineService`, `ToyVpnService`, `UploaderIntentService`

## Ресурсы UI

- `apktool-res\res\layout*` — 622 layout, из них ~431 «своих» (не библиотечные abc_/design_/…); список: `vk-tea-reference\layouts_own.txt`
- Темы: базовая в `styles.xml`, но Coffee красит UI рантаймом (темы VK Coffee применяются к разметке кодом — учитывать при портировании 1:1, ищите по `ui` и `caffeine` пакетам)
- Иконки приложения — activity-aliases `.VKCoffee*` (несколько вариантов иконки)

## Замены относительно оригинала

| Модуль оригинала | Судьба в VK Tea |
| --- | --- |
| `AuthActivity`, `CaptchaActivity`, `ValidationActivity`, `SDKAuthActivity` | VK ID SDK |
| Мёртвые api-роуты (ads, старые методы) | WebView `m.vk.com` |
| `player.ads.*` | выкидываем |
| GMS-зависимости старые | актуальные или отказ |
