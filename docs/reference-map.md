# Карта референса (VK Coffee → VK Tea)

Живой документ: что и где смотреть в декомпиле оригинала при портировании.
Локальная копия из архива Даниила лежит в `vk-tea-reference/` в корне рабочего каталога. Папка и `vk-tea-reference.rar` исключены через `.gitignore`: исходники референса остаются на машине и не попадают в коммиты. Раньше у Даниила использовался путь `C:\Users\danii\Documents\GitHub\vk-tea-reference\`.

- `jadx-code/sources/su/operator555/vkcoffee/` — декомпил Coffee.
- `apktool-res/res/` — ресурсы Coffee; `apktool-res/` также содержит манифест и DEX для уточнения неполного Java-декомпила.
- `official-jadx/sources/` — отдельный частичный декомпил официального VK, включая `com/vk/music/`, `com/vk/dto/music/` и обфусцированные пакеты. Это другой источник, его нельзя автоматически приравнивать к версии Coffee.
- `activities.txt`, `audio_classes.txt`, `layouts_own.txt` — инвентарные списки.

## Как использовать при разработке

При добавлении функции сначала проследить её в референсе: точка входа UI → обработчик → API-запрос → параметры → разбор ответа → изменение состояния. В документации модуля или описании изменения указывать конкретные классы/методы референса и имена API-методов; при неоднозначности добавлять номера строк. Java-код реализации пишется заново, как установлено в README.

Старый вызов показывает устройство клиента, но не подтверждает доступность метода сегодня. Проверку по исходникам, локальные тесты и фактическую работу VK API отмечать отдельно. Вход не использовать как диагностический цикл: подготовить поток по референсу и проверкам до попытки авторизации, не повторять попытки автоматически при ошибке.

Декомпил может быть неполным: Даниил сообщил о восьми ошибках jadx для Coffee, официальный клиент дополнительно обфусцирован и восстановлен частично. Участки `Method not decompiled`/`UnsupportedOperationException` нельзя считать исходной логикой клиента: уточнять их по соседним классам, DEX/smali или явно фиксировать неизвестное. Не переносить из исходников секреты и не включать токены, подписи запросов, пароли или ответы авторизации в документацию и логи.

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

### Проверенные точки входа аудио (локальная копия, 22.10.2026)

Пути Coffee в таблице указаны относительно `jadx-code/sources/su/operator555/vkcoffee/`. Номера строк относятся к переданной копии.

| Сценарий | Референс | Вызов и параметры |
| --- | --- | --- |
| Список треков | `api/audio/AudioGet.java:10–13` | `audio.get`: `owner_id`, `count` |
| Поиск | `api/audio/AudioSearch.java:9–12` | `audio.search`: `q`, `count` |
| Список плейлистов | `api/audio/AudioGetAlbums.java:9–12` | `audio.getPlaylists`: `owner_id`, `count` |
| Содержимое плейлиста | `api/audio/AudioGetPlaylist.java:22–30,34–65` | `execute.getPlaylist`: `owner_id`, `id`, необязательный `access_key`; разбор `owner`, `playlist`, `audios` |
| Треки по ID | `api/audio/AudioGetById.java:15–29` | `audio.getById`: список `audios` |
| Актуализация URL | `api/audio/AudioGetUrlForPlay.java:11–20` | `audio.getById`: `audios=mid`; первый элемент ответа преобразуется в `MusicTrack` |

Цепочка актуализации: `audio/MusicTrack.java:204–215` (`getActualLink(forceLoad)`) → `audio/MusicApp.java:70–94` → `AudioGetUrlForPlay`. Старый прокси `audio/player/proxy/ClientThread.java:477–511` получает отсутствующий URL и повторяет запрос после HTTP 404; отправляет `Range`, а в `351–354` ожидает HTTP 206. Этот участок не подтверждает обновление ссылки при HTTP 403: такую обработку нужно проектировать и проверять отдельно.

Для сопоставления с частичным официальным клиентом, относительно `official-jadx/sources/`:

- `xsna/b35.java:190–214`: `audio.get` с `owner_id`, `playlist_id`, `shuffle_seed`, `offset`, `count`, `extended`, `access_key`, `ref`.
- `xsna/b35.java:115–121`: `audio.getById`, `audios` и необязательный `ref`.
- `xsna/b35.java:70–80`: `audio.getPlaylists`, пагинация через `offset`/`start_from`.
- `xsna/x25.java:16–28`: `audio.search`, включая `search_own`, `performer_only`, пагинацию и необязательные `ref`/`owner_id`.
- Модели — `com/vk/dto/music/MusicTrack.java`, `Playlist.java`; сервис — `com/vk/music/playerservice/impl/PlayerService.java`.

Конкретный пробел Coffee: `api/APIController.java:174–194` содержит предупреждения JADX и заглушку `runRequest(VKAPIRequest,int)` с `Method not decompiled`. Также есть предупреждение о дублированном блоке в `audio/MusicApp.java:122`. Восстановление сетевого и авторизационного слоя требует дополнительного анализа, эти Java-заглушки не являются реализацией. `official-jadx/resources/` в переданной копии пуст: версию APK официального клиента по нему не подтверждаем.

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
| `AuthActivity`, `CaptchaActivity`, `ValidationActivity`, `SDKAuthActivity` | Свой вход и обработка капчи/2FA; текущие поддерживаемые способы входа описаны в README. Старый SDK не переносим |
| Мёртвые api-роуты (ads, старые методы) | WebView `m.vk.com` |
| `player.ads.*` | выкидываем |
| GMS-зависимости старые | актуальные или отказ |
