# Бэклог портирования: что брать из VK Coffee

> Статус на 2026-10-23: лента, профиль, чат, авторизация и аудио-движок (Media3) - готовы.
> Детальный текущий статус см. в README (раздел Roadmap). Ниже - полная карта референса.

Полный список модулей оригинала (`su.operator555.vkcoffee`), с приоритетами и ссылками на референс-код.
Пути указаны от `vk-tea-reference/jadx-code/sources/su/operator555/vkcoffee/` (Java) и `vk-tea-reference/apktool-res/res/` (ресурсы).

Приоритеты: **P0** - ядро без которого приложение не приложение; **P1** - то за что любили Coffee; **P2** - приятные бонусы; **DROP** - не портируем.

## 1. Хост и навигация

- [ ] P0 **Тулбар-дропдаун** «Новости ▼» - переключение разделов из заголовка + бейдж уведомлений на бургере - `ui/navigation/ToolbarHelper`, `navigation/Navigator`
- [ ] P0 **Быстрый поиск** по кнопке лупы - `fragments/search/QuickSearchActivity`, `fragments/search/ExtendedSearchFragment`
- [ ] P1 **Мини-плеер в меню** (закреплён над пунктами) - layout `menu_audio_player.xml`, логика в `ui/widget/MenuListView` (~строка 900: play/pause, прогресс, обложка)
- [ ] P1 **Пользовательская сортировка меню** - `ui/widget/MenuListData` (порядок пунктов хранится в prefs)
- [ ] P1 **Друзья/группы в меню** - раскрытые секции с онлайн-статусами - `MenuListView$UserViewHolder`, `left_menu_item2.xml`
- [ ] P1 **Дни рождения в меню** - `left_menu_item_birthdays.xml`, `BirthdayViewHolder`
- [ ] P2 **Иконки приложения на выбор** - activity-aliases `.VKCoffee*` в манифесте
- [ ] P2 **Полноэкранный свайп открытия меню** (fullSwipe) - `slidingmenu/` + `CoffeeSimpleGestureFilter` + настройки скорости свайпа

## 2. Авторизация и аккаунты

- [ ] P0 **VK ID вход** - современная замена `AuthActivity`, `WelcomeActivity`, `CaptchaActivity`, `ValidationActivity`, `activities/SignupActivity`, `activities/RestoreActivity` (свой код, VK ID SDK; работает без GMS)
- [ ] P1 **Мультиаккаунт** - иконка «люди» в хедере меню - `ic_menu_multi`, `auth/VKAccountManager`
- [ ] P1 **Хедер меню с данными аккаунта** - аватар, имя, статус, размытая шапка - `left_menu_item_me.xml`, `updateUserInfo()` в MenuListView
- [ ] P1 **Пин-код блокировка приложения** - `PinCodeViewCoffee`, пункт меню «Заблокировать» (`ic_menu_lock`)
- [ ] DROP `SDKAuthActivity`, `sdk/*` (VK SDK для сторонних приложений мёртв)

## 3. Аудио - приоритет №1

- [x] P0 **Ядро плеера** на Media3: свой `AudioPlaybackService` (`MediaSessionService` + ExoPlayer), очередь, состояние, repeat/shuffle, контроллер. «Мои аудиозаписи» загружает библиотеку текущего аккаунта через `audio.get` с пагинацией; `audio.getById` получает актуальный URL при подготовке трека. Импорт локальных файлов удалён из этого экрана. Контракт и ограничения - [audio-playback.md](audio-playback.md); сохранение очереди между запусками ещё не реализовано.
- [ ] P0 **DataSource-слой**: `player/exo/AudioOkHttpDataSource` (сеть), `AudioFileDataSource` (локальный кеш), `AudioCacheHelper` - переносится на Media3 почти 1:1
- [ ] P0 **Локальный прокси** (обход истекающих URL + кеш) - `player/proxy/PlayerProxy`, `ClientThread`, `LockFile`, `QueryData`
- [x] P0 **Фоновое воспроизведение + MediaSession** - foreground-сервис, системное управление, audio focus и пауза при отключении проводной гарнитуры через Media3. Эмуляторная проверка описана в [audio-playback.md](audio-playback.md); физическая гарнитура и локскрин телефона требуют отдельной проверки.
- [x] P1 **Полноэкранный плеер** - `AudioPlayerActivity` + layouts (`audio_player_*`): обложка-заглушка, перемотка, основные кнопки и меню по скриншотам Coffee; общий мини-плеер закреплён снизу основного экрана. Подключены список и воспроизведение VK; изменение библиотеки, lyrics/similar/artist и скачивание удалённых треков пока недоступны; см. [audio-playback.md](audio-playback.md).
- [ ] P1 **Скачивание треков / оффлайн** - `SavedTracks`, `SavedTrack`, `DownloadTaskTracks`, `DownloadTrackFileConverter`, `CacheUtils` + сервис `CaffeineDownloader`
- [ ] P1 **Плейлисты** - `fragments/AudioPlaylistFragment`, `AudioListFragment`
- [ ] P2 **Голосовые сообщения** - `AudioMessagePlayerService`
- [ ] P2 **HLS-потоки** - `player/exo/hls/*` (в Media3 встроено, свой парсер не нужен)
- [ ] DROP `player/ads/*` (instream-реклама VK мертва)

## 4. Сообщения

- [ ] P0 **Список диалогов** - `fragments/messages/DialogsFragment`, `CaffeineTabsDialogsFragment`
- [ ] P0 **LongPoll realtime** - `LongPollService` (события сервера: новые сообщения, статусы)
- [ ] P1 **Переписка** (чат, вложения, голосовые) - `fragments/messages/*` [большой пакет]
- [ ] P1 **Пересылка сообщений** - `ForwardMessageActivity`
- [ ] P1 **Отключение «прочитано»/«набирает»** - фирменные фичи Coffee: `ic_menu_disable_read`, `in_menu_disable_type` (реализация в отправке API-флагов, смотреть `OnlineData.setLP` и вызовы markAsRead)
- [ ] P2 **Голосовые сообщения** - запись + `AudioMessagePlayerService`

## 5. Лента, профиль, контент

- [ ] P0 **Лента новостей** - `fragments/NewsFragment` (лента + фильтры `NewsfeedFilterListFragment`), сегмент «Истории | Запись»
- [ ] P1 **Профиль** - `fragments/ProfileFragment` (+ `fragments/discussions/ProfileUtils`)
- [ ] P1 **Друзья** - `fragments/friends/FriendsFragment`, кеш `data/Friends`
- [ ] P1 **Сообщества** - `fragments/groups/GroupsFragment`, кеш `data/Groups`
- [ ] P1 **Фото** - `fragments/photos/*`, `ImagePickerActivity`, `PostPhotoActivity`
- [ ] P1 **Видео** - `activities/VideoPlayerActivity`, `YouTubeVideoPlayerActivity`, `VideoEmbedPlayerActivity`, `fragments/videos/VideosFragment`
- [ ] P1 **Ответы/уведомления** - `fragments/feedback/FeedbackFragment`, `NotificationActivity`
- [ ] P1 **Закладки** - `fragments/fave/FaveFragment`
- [ ] P2 **Документы** - `fragments/documents/DocumentsViewFragment`
- [ ] P2 **Игры** - `fragments/GamesFragment`, `GameCardActivity` (часть роутов мертва → WebView)
- [ ] P2 **Репост** - `RepostActivity`
- [ ] P2 **Загрузка фото/видео** - `upload/*` [27 классов], `UploaderIntentService`, `VideoUploadDialog`

## 6. Фирменные фичи Coffee

- [ ] P1 **Тёмная тема** - `caffeine/ui/Theme` (DARK_MODE + `Theme.Key.*` колорация всех view) - `BaseStyleDark`, пункт меню «Темная тема» `ic_menu_darktheme`
- [ ] P1 **Темы оформления** (тема-движок Coffee, сторонние темы) - `caffeine/ui/Theme` + загрузка тем через `CaffeineDownloader`
- [ ] P1 **Невидимка/offline** - `ic_menu_offline`, `OnlineData`
- [ ] P1 **Слежка** (события друзей: выход в сеть, прочтение) - `caffeine/spyevents/`, `EventsGeneralFragment`, пункт меню `ic_menu_spy`
- [ ] P2 **Обход блокировок аудио** - `caffeine/ProxyUtils`, `NetworkCaffeineProxy`, `vpn/ToyVpnService`, список прокси из OTA-конфига - **осторожно**: легально серо, делать опционально
- [ ] P2 **Жесты** - `CoffeeSimpleGestureFilter` (свайпы между разделами)
- [ ] P2 **Свои уведомления сервера** - `ServerNotification` (аналог - свой канал обновлений)

## 7. Инфраструктура

- [ ] P0 **API-слой VK API v5** - свой клиент поверх OkHttp: модели `api/*` [340 классов] переносить по мере необходимости (не все)
- [ ] P0 **Мёртвые роуты → WebView** - через `LinkRedirActivity`-паттерн: роут не реализован → `m.vk.com/...` во встроенном WebView (`androidx.webkit`)
- [ ] P1 **Локальная БД** - `data/*`, `orm/*` (vk-orm) → Room или свой SQLite-кеш друзей/групп/диалогов
- [ ] P1 **Автообновление приложения** - `CaffeineOTA` → проверка GitHub Releases + скачивание APK
- [ ] P2 **Синхронизация контактов** - `ContactsSyncAdapterService` (скорее DROP)
- [ ] P2 **Шаринг в VK из других приложений** - `SendActivity`, `VkChooserTargetService`
- [ ] DROP Firebase Analytics, Flurry, Facebook SDK, старый GCM, MoPub, BillingActivity, HockeyApp

## 8. UI-кит из оригинала

- [ ] P1 **UsableRecyclerView** + базовые ячейки - `ui/` [442 класса] переносить точечно по мере надобности
- [ ] P1 **Шрифтовая система** - `MediumBold` (sans-serif-medium), размеры из `dimens.xml`
- [ ] P2 **Анимации** - `com/daimajia` (AndroidViewAnimations), переходы `anim/view_transition_*`

## Порядок работы (предложение)

1. VK ID + хедер меню (P0, открывает данные для всего остального)
2. Ядро аудио (P0: сервис + прокси + кеш - музыка = главный приоритет)
3. Лента + LongPoll + диалоги (P0 приложение оживает)
4. Полноэкранный плеер + скачивание (P1 музыка любимая)
5. Тёмная тема + Coffee-фичи (P1 то за что любили)
