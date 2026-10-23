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

В VK Tea используются Media3 ExoPlayer + `MediaSessionService`, каталог собственной библиотеки VK, общий мини-плеер и полноэкранный `AudioPlayerActivity`. `audio.get` наполняет отдельное состояние списка; `audio.getById` разрешает свежий URL конкретного трека при подготовке воспроизведения. Старые движки, прокси и управляемый кеш Coffee пока остаются референсом для следующих этапов. Интерфейс написан заново по разметке и четырём предоставленным скриншотам; отдельные PNG перенесены под новыми именами. Актуальные возможности и границы описаны в [audio-playback.md](audio-playback.md).

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

Это адаптация интерфейса: исходный `audio_list_item.xml` имеет высоту 72 dp, текущая строка Tea — минимум 68 dp с увеличением под крупный шрифт; размеры текста задаются в sp. Источник списка Tea — настоящий `audio.get` текущего аккаунта, с пагинацией и обновлением; системный picker для открытия раздела не используется. Сетевой поиск и изменение библиотеки VK ещё не подключены. Визуальное сходство и автономные тесты сами по себе не подтверждают доступность музыкальных методов или прослушивание на физическом устройстве.

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

Важные различия поведения: Coffee `audio_cache` управляет его собственным кешем, а «Сохранить» в Tea сейчас создаёт только локальную отметку. Скачивание удалённых VK-ссылок не реализовано; копирование доступного `content`-документа через SAF остаётся внутренней локальной возможностью. `CaffeineDownloader`, прокси и управляемый кеш не перенесены. Lyrics/similar/artist-пункты отключены до реализации соответствующих API. Уведомление MediaSession открывает новый полноэкранный плеер. Эти отличия не скрываются под визуальным сходством.

### Проверенные точки входа аудио (локальная копия, 22.10.2026)

Пути Coffee в таблице указаны относительно `jadx-code/sources/su/operator555/vkcoffee/`. Номера строк относятся к переданной копии.

| Сценарий | Референс | Вызов и параметры |
| --- | --- | --- |
| Список треков | `api/audio/AudioGet.java:10–13`; `api/ListAPIRequest.java:35–52`; `data/VKList.java:23–36` | `audio.get`: `owner_id`, `count`; ответ `response.count`, `response.items`. Coffee задаёт `count=5000`, это не доказанный максимум сервера |
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

### Каталог и ленивое разрешение в Tea

| Контракт | Точный референс | Реализация Tea |
| --- | --- | --- |
| Своя библиотека | Coffee `api/audio/AudioGet.java:10–13`; официальный `xsna/b35.java:190–215` подтверждает `offset`/`count` | `audio/catalog/DefaultVkAudioRepository.getMyAudio`: `owner_id` из текущей Session, страницы по 200, offset по исходной длине `items` |
| Поля трека | Coffee `audio/MusicTrack.java:117–128`: `id` с fallback `aid`, `owner_id`, `artist`, `title`, `duration` в секундах, `url`, `content_restricted` | Неизменяемый `VkAudioTrack`; пустой URL не скрывает строку и не означает ограничение |
| Дополнительные ограничения/ключ | Официальный `com/vk/dto/music/MusicTrack.java:716–729`; `com/vk/api/generated/audio/dto/AudioRestrictionDto.java:33–51` | Сохраняется optional `access_key`; любой ненулевой `content_restricted` считается ограничением, включая значения новее Coffee. `access_key` не попадает в публичный ID и metadata |
| Стабильная идентичность | Coffee `audio/MusicTrack.java:175–177` (`getNormalMid`); официальный `MusicTrack.java:298–311` различает базовый ID и request ID с ключом | `mediaId=vk-audio:owner_id_id`; для собственной библиотеки `getById` получает базовый `owner_id_id` |
| Обновление ссылки | Coffee `api/audio/AudioGetUrlForPlay.java:11–20`; `AudioGetById.java:15–29` | `VkAudioMediaSourceFactory` запрашивает только подготавливаемый трек; ответ `response` — массив, а не объект страницы. HTTPS URL проверяется заново, HLS/MP3 передаются Media3 |
| Подпись API | Coffee `api/VKAPIRequest.java:72,86,136–143,166–175`: LinkedHashMap, версия 5.82, MD5 от пути/параметров/секрета | Отдельный аудиотранспорт берёт token/secret из существующей Tea Session. Порядок параметров сохраняется, подпись строится из исходных значений перед URL-кодированием; общий логирующий `VKApi.get` не вызывается |
| Разделение состояний | Coffee UI/старый Player остаются поведенческим референсом, архитектура Tea новая | `VkAudioViewModel` удерживает каталог и один запрос. Paging/refresh/rotation не меняют сервисную очередь; очередь формируется явным нажатием трека или shuffle |

У аудиотранспорта нет собственного OAuth или повторного входа. Redirects и автоматические повторы отключены, есть отмена и проверка неизменности сессии перед доставкой результата. API/HTTP-ошибка превращается в конечный безопасный тип и числовой код; URL, тела ответов, токены, подписи и исходные exception не логируются и не передаются UI. Подписанный URL остаётся внутренней деталью MediaSource, а не публичными метаданными очереди.

Проверенные локальные исходники описывают форму запроса, но не гарантируют доступность метода для текущей сессии. Сетевой поиск, плейлисты, чужие закрытые аудио с ключами, загрузка файлов и изменения библиотеки аккаунта требуют отдельных этапов. Результаты текущего финального build/test/lint и приёмка реального воспроизведения фиксируются после выполнения, без переноса результатов прежнего локального SAF-потока.

Конкретный пробел Coffee: `api/APIController.java:174–194` содержит предупреждения JADX и заглушку `runRequest(VKAPIRequest,int)` с `Method not decompiled`. Также есть предупреждение о дублированном блоке в `audio/MusicApp.java:122`. Восстановление сетевого и авторизационного слоя требует дополнительного анализа, эти Java-заглушки не являются реализацией. `official-jadx/resources/` в переданной копии пуст: версию APK официального клиента по нему не подтверждаем.

## Отображение чата (23.10.2026)

Пути Coffee ниже — относительно `jadx-code/sources/su/operator555/vkcoffee/`, официального клиента — относительно `official-jadx/sources/`.

| Контракт | Проверенный локальный референс | Реализация Tea |
| --- | --- | --- |
| История | Coffee `api/messages/MessagesGetHistory.java:32–45,55–86`: `peer_id`, `offset`, `count`, `photo_sizes=1`, `fields`, `extended=1`, обратный порядок | `ChatActivity.requestHistory`: последние 50 сообщений, `rev=0`, `extended=1`, `photo_sizes=1`; `ChatHistoryParser` возвращает хронологический список. Дополнительные поля `online,last_seen` для шапки берутся из `profiles` этого ответа |
| Сообщение и вложения | Coffee `Message.java:241–314,636–666`; `attachments/Attachment.java:78–93,138–156,198–208` | Отдельные неизменяемые `ChatMessage` / `ChatAttachment`; обработаны все элементы массива, недоступные типы получают подпись. Ответы и пересылки показывают автора и краткий текст |
| Фото и пропорции | Coffee `Photo.java:123–134`; `attachments/PhotoAttachment.java:130–145` | Выбирается наибольший размер с исходными пропорциями, с поддержкой `url` / `src` и legacy-полей. Подписанный URL сохраняется целиком. `ChatMediaPreview` показывает полное изображение через `fitCenter` |
| Видео | Coffee `api/VideoFile.java:145–156,222–226`; официальный `xsna/b74.java:1507–1516,1538–1548` | `image` / `first_frame` и legacy-превью, кнопка воспроизведения, длительность; существующий `VideoPlayerActivity` получает `owner_id`, `id`, optional `access_key` |
| Документы, стикеры | Coffee `api/Document.java:82–136`; `attachments/Attachment.java:198–208` | Превью фото / граффити / стикера; при отсутствии изображения — название и доступные сведения о вложении |
| Пузыри и группы | Coffee `apktool-res/res/layout/message_in.xml:2–10`, `message_out.xml:4–8`; `ui/holder/messages/ChatMessageHolder.java:71,539–560` | Существующие `bg_msg_*` используются для одиночных / верхних / средних / нижних строк; измерение пузыря и положение времени реализованы заново |
| Индикатор видео | Coffee `apktool-res/res/layout/attach_video.xml:2–8` | Пять `drawable-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}-v4/ic_attach_video_play.png` выборочно перенесены как `chat_attachment_video_play.png`; остальные новые векторные заглушки и shape-фоны написаны в Tea |
| Шапка и поле ввода | Coffee `fragments/messages/ChatFragment.java:2179–2213,4046–4082,4731–4774,5564–5573`; `apktool-res/res/layout/subtitle_toolbar_fragment.xml`, `write_bar.xml`; `ui/WriteBar.java:923` | Аватар, имя и доступный статус; белое многострочное поле без подчёркивания. Системные панели и клавиатура учитываются через WindowInsets |

`ChatMessagesAdapter` ограничивает ширину пузыря фактической шириной списка, переносит длинные строки, группирует соседние входящие и исходящие сообщения одного отправителя в пределах пяти минут и одного дня. Время располагается внутри пузыря; изображения и слушатели очищаются при переиспользовании строки. Даты разделяют дни переписки.

Это исправление представления существующих последних 50 сообщений. Пагинация истории, LongPoll, запись голосовых и отправка вложений остаются отдельными задачами. Референсный `MessagesGetLastActivity` не добавляется: шапка использует данные уже загруженной истории. Проверка UI выполняется на синтетических ответах; личная переписка не используется как тестовый материал.

Проверка этой версии: `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` — 138 тестов, 0 ошибок / пропусков; lint — 0 ошибок, 218 предупреждений проекта. На API 37 пройдены 30 проверок UI: портрет / альбом, фото / видео / несколько вложений, полный длинный текст, клавиатура, 320dp при fontScale=1.5, empty / error / retry. Временный QA APK не имеет INTERNET; `markRead` и отправка подменены локальными счётчиками. Обычный APK проверен на отсутствие QA entrypoint / фикстур и установлен через `adb install -r` на Pixel 10 (23.10.2026, 14:19), без открытия личного диалога и попыток входа.

## Фото в стене, ленте и открытом посте (23.10.2026)

Исправлены две независимые причины одинаковых обрезанных картинок: `ProfileFragment` задавал высоту каждой строки как треть ширины дисплея с `CENTER_CROP`; селекторы стены и ленты проходили список типов `w…s` в обратном приоритете и выбирали маленькую миниатюру.

| Контракт | Референс Coffee относительно `jadx-code/sources/su/operator555/vkcoffee/` | Реализация Tea |
| --- | --- | --- |
| Размеры фото из API | `api/wall/WallGet.java:67–75`; `api/newsfeed/NewsfeedGet.java:148` — `photo_sizes=1`; `Photo.java:120–130` — `sizes`, `width`, `height`, `type`, `src` / `url` | Стена и загрузка ленты запрашивают `photo_sizes=1`. Общий `PostPhoto.bestUrl` выбирает крупный вариант с исходными пропорциями, поддерживает legacy-поля и сохраняет URL с параметрами целиком |
| Пропорции | `Photo.java:285–352`; `attachments/PhotoAttachment.java:130–145,266–273` — выбор размера, отношение сторон, `FIT_CENTER` | `PostPhotoView` берёт фактическую ширину контейнера и пропорции загруженного изображения; `PostPhotosRenderer` используется стеной профиля, лентой, репостами и открытым постом |

Несколько фото идут последовательно в исходном порядке и открываются в существующем `PhotoViewerActivity` с полным списком и выбранным индексом. Очень длинные изображения целиком вписываются в рамку высотой не более двух доступных ширин; края не обрезаются. Декодирование ограничено размером этой рамки, прежние Glide-запросы отменяются при смене поста и переиспользовании строки.

Проверка: build / 156 unit-тестов / lint завершились успешно (0 ошибок, 218 предупреждений проекта). На офлайн API 37 проверены настоящие Profile / Feed / Post bind-пути с синтетическими фото 9:16, 1:1, 16:9 и 1:10, все четыре цветные метки по углам, выбор крупного источника вместо контрольной миниатюры, ширина 320dp / альбом, порядок пяти фото и открытие с ненулевого индекса. QA APK не имеет INTERNET и не содержит реальной сессии. Обычный APK проверен на отсутствие QA экранов / изображений и установлен на Pixel 10 через `adb install -r` 23.10.2026 с сохранением данных.

## Аватарки сообщений (23.10.2026)

В `DialogsAdapter.applyUser` обновление фото ошибочно зависело от пустого заголовка, а при повторном использовании строки отсутствующий URL оставлял старый Glide target. Фото теперь обновляется независимо от имени; общий `MessageAvatars` отменяет прежний запрос, задаёт круглую заглушку и загружает изображение с `circleCrop` по размеру ImageView. Фиксированный decode 120px удалён. Тот же binder используется шапкой чата и загрузчиком аватарок отправителей; размеры выбираются через `photo_200 → photo_100 → photo_50` с пропуском пустых полей.

`DialogsParser` сохраняет правильную личность диалога: legacy `chat_id` имеет приоритет над `user_id` отправителя, беседа получает peer `2000000000 + chat_id`, сообщество — отрицательный ID. Поддерживаются inline фото и `conversation.chat_settings.photo`. Существующий Tea-запрос `messages.getDialogs(count=40,preview_length=64)` сохранён. Отдельные read-only `users.get` (`user_ids`, `fields=photo_200,photo_100,photo_50`) и `groups.getById` (`group_ids`) заполняют фото людей и сообществ. Устаревшие callbacks после обновления списка или уничтожения view игнорируются.

Проверенные Coffee-пути относительно `jadx-code/sources/su/operator555/vkcoffee/`:

- `ui/holder/messages/DialogHolder.java:36–51` → `ui/DialogEntryView.java:184–202,360,369–375`: круглая форма, placeholder, передача фото в чат, очистка пустого URL.
- `ui/holder/messages/ChatMessageHolder.java:183,309–318`: круглый senderPhoto и видимость аватарки входящего сообщения.
- `api/messages/MessagesGetDialogs.java:32–43,50–69,101–132,178–236`; `Message.java:251–271`: профили, беседы и идентификатор peer. Coffee использует `messages.getConversations` либо legacy `execute.getDialogsWithProfilesNewFixGroups` (`caffeine/BadMethods.java:15`); совместимость формы ответа в Tea не меняет его текущий endpoint.
- `api/groups/GroupsGetById.java:12–22`, `api/Group.java:77–94`: фото группы и legacy `response`-массив. Batch `group_ids` и современный `response.groups` подтверждены в частичном официальном клиенте `official-jadx/sources/xsna/ruu.java:24–37,70–80`.

Проверка: build / 176 unit-тестов / lint прошли успешно (0 ошибок, 218 предупреждений проекта). Офлайн API 37: 134 проверки цвета и круглой маски на синтетических аватарках, заполненное имя до загрузки фото, отсутствующий / недоступный URL, личные диалоги / беседы / сообщества, переход в шапку чата, прокрутка и ширина 320dp. QA APK без INTERNET; реальные диалоги, `markAsRead` и отправка в проверках не используются. Обычная сборка без QA экранов / фикстур установлена на Pixel 10 через `adb install -r` 23.10.2026 с сохранением данных.

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
