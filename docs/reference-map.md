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

В VK Tea уже используются Media3 ExoPlayer + `MediaSessionService`, список, общий мини-плеер и полноэкранный `AudioPlayerActivity`. Старые движки, прокси и управляемый кеш Coffee пока остаются референсом для следующих этапов. Интерфейс написан заново по разметке и четырём предоставленным скриншотам; отдельные PNG перенесены под новыми именами. Актуальные возможности и границы описаны в [audio-playback.md](audio-playback.md).

### Интерфейс аудио по четырём скриншотам (23.10.2026)

Переданные файлы используются как визуальные референсы и не включаются в репозиторий. Показанные в них названия треков не переносятся в демонстрационные или production-данные.

| Скриншот | Что сопоставляется |
| --- | --- |
| `photo_2026-10-23_12-19-12.jpg` | Белый список, синий toolbar «Мои аудиозаписи», строка shuffle, длительности, закреплённый мини-плеер |
| `photo_2026-10-23_12-19-13.jpg` | Текущая строка с синим индикатором слева, локальные значки, тёмное название, мини-плеер |
| `photo_2026-10-23_12-19-13 (2).jpg` | Тёмная обложка-заглушка, сворачивание, seek/time, название/исполнитель/качество, два ряда управления |
| `photo_2026-10-23_12-19-14.jpg` | Порядок пунктов контекстного меню и отметка «Сохранить» |

Пути ресурсов ниже указаны относительно `vk-tea-reference/apktool-res/res/`, Java — относительно `vk-tea-reference/jadx-code/sources/su/operator555/vkcoffee/`. Файлы и обработчики проверены в локальной копии; доступность старых VK API из этого не следует.

| Часть | Точный Coffee-источник | Новая реализация VK Tea |
| --- | --- | --- |
| Строка трека | `layout/audio_list_item.xml`, `layout/audio_list_item_common.xml`; `ui/holder/audio/AudioViewHolderBase.java:36–63`, `ui/holder/audio/AudioViewHolderStandard.java:14–43` | `layout/item_audio_track.xml`, `ui/AudioQueueAdapter.java`: название/исполнитель, длительность, отдельный текущий индикатор, локальный значок |
| Текущий индикатор | `ui/drawable/AudioVisualizerDrawable.java`; установка в `ui/holder/audio/AudioViewHolderStandard.java:17` | Свой вектор `drawable/audio_list_equalizer.xml`; Java-анимация Coffee не копируется |
| Shuffle над списком | `layout/audio_shuffle_all_item.xml`; `fragments/AudioListFragment.java:542–552` | Строка 60 dp в `layout/fragment_audio.xml`, команда общей очереди в `ui/AudioFragment.java` |
| Мини-плеер под списком | `layout/audio_bottom_panel.xml`; `fragments/AudioListFragment.java:940–979` | `ui/AudioMiniPlayerView.java`, `layout/view_audio_mini_player.xml`, один общий экземпляр в `MainActivity` |
| Плеер бокового меню | `layout/menu_audio_player.xml`; `ui/widget/MenuListView.java:1414–1461` и обновление состояния в `906–918` | Компактная композиция обложка/название/исполнитель/кнопка использована общим мини-плеером; старый SlidingMenu не копируется |
| Полноэкранная композиция | `layout/audio_player.xml`, `layout-v21/audio_player.xml`, `layout-land/audio_player.xml`, `layout-land-v21/audio_player.xml`, `layout/audio_player_cover_scroll.xml`; `AudioPlayerActivity.java:338–419` | `ui/AudioPlayerActivity.java`, `layout/activity_audio_player.xml`, `layout/audio_player_body.xml`: адаптивная обложка, прокручиваемое управление, отступы системных панелей |
| Транспорт/очередь/shuffle/repeat | `AudioPlayerActivity.java:893–967` | Media3-команды через общий `audio/AudioUi.java`; отдельный ExoPlayer в Activity не создаётся |
| Полноэкранное меню | `menu/audio.xml`; `AudioPlayerActivity.java:468–588` | `AudioUi.showActions`: порядок соответствует скриншоту, доступность ограничена текущими локальными действиями |
| Долгое нажатие строки | `fragments/AudioListFragment.java:1041–1105` | То же меню `AudioUi.showActions` для конкретного индекса общей очереди |

Это адаптация интерфейса: исходный `audio_list_item.xml` имеет высоту 72 dp, текущая строка Tea — минимум 68 dp с увеличением под крупный шрифт; размеры текста задаются в sp. Проверка точного визуального совпадения на физическом Pixel ещё не заявляется. Текущий источник списка — документы SAF; настоящий каталог, сетевой поиск и действия над библиотекой VK не имитируются.

#### Перенесённые PNG

Исходные изображения лежат в `drawable-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}-v4/`; назначения — в соответствующих `app/src/main/res/drawable-{density}/`. Сопоставление всех 99 перенесённых вариантов из таблицы проверено по SHA-256: байты совпадают с локальным референсом. У `ic_aplayer_cache_normal.png` нет варианта xxxhdpi в переданной копии, поэтому для него перенесены четыре доступные плотности.

| Назначение Tea (`.png`) | Coffee (`.png`) |
| --- | --- |
| `audio_list_shuffle`, `audio_player_shuffle` | `ic_shuffle_24dp` |
| `audio_list_local` | `ic_music_cached_12dp` |
| `audio_mini_placeholder` | `placeholder_albumart_56dp` |
| `audio_mini_play` | `ic_play_24dp` |
| `audio_mini_pause` | `ic_pause_24dp` |
| `audio_player_placeholder` | `ic_music_placeholder_120dp` |
| `audio_player_collapse` | `ic_music_minimize_32dp` |
| `audio_player_previous` | `ic_previous_48dp` |
| `audio_player_play` | `ic_play_48dp` |
| `audio_player_pause` | `ic_pause_48dp` |
| `audio_player_next` | `ic_next_48dp` |
| `audio_player_queue` | `ic_playlist_24dp` |
| `audio_player_add` | `ic_add_24dp` |
| `audio_player_saved` | `ic_check_24dp` |
| `audio_player_volume` | `ic_mute_shadow_48dp` |
| `audio_player_muted` | `ic_unmute_shadow_48dp` |
| `audio_player_repeat` | `ic_repeat_24dp` |
| `audio_player_download` | `ic_aplayer_cache_normal` |
| `audio_player_menu` | `ic_actions_small_24dp` |

В исходных ресурсах встречаются идентичные алиасы: `ic_play_24dp`/`ic_menu_play`, `ic_pause_24dp`/`ic_menu_pause`, `ic_add_24dp`/`ic_add`, `ic_check_24dp`/`ic_ab_done`. В таблице выбран вариант, соответствующий назначению элемента; тождество PNG само по себе не устанавливает, какое имя использовалось при первоначальном копировании.

Важные различия поведения: Coffee `audio_cache` управляет его собственным кешем, а «Сохранить» в Tea сейчас создаёт только локальную отметку. «Скачать аудиозапись» в Tea копирует доступный `content`-документ в выбранное пользователем SAF-назначение; `CaffeineDownloader` и скачивание удалённых ссылок не перенесены. Lyrics/similar/artist-пункты отключены до реализации соответствующих API. Уведомление MediaSession открывает новый полноэкранный плеер. Эти отличия не скрываются под визуальным сходством.

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
