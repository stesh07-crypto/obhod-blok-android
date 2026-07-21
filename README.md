# OBhoD_BLOK Android Client

Android-клиент для VPN-туннеля WireGuard, который подключается через TURN-ретранслятор VK для обхода блокировок. Снаружи трафик выглядит как зашифрованный медиапоток звонка WebRTC, а не как классический VPN.

**Пакет:** `net.qwdtt.client` · **Версия:** 1.3.4  
**Telegram-бот для получения конфигов:** [@obhod_int_bot](https://t.me/obhod_int_bot)

---

## Возможные проблемы и решения

### На компиляции пишет "Native library missing"

- Должна быть собрана Go-библиотека `libclient.so`.
- В скрипте `scripts/build-native-libs.sh` запускается компиляция `go_client` через `cgo` для `arm64-v8a`, `armeabi-v7a`, `x86_64`.
- Если NDK не установлен, поставьте его через SDK Manager в Android Studio.

---

## Архитектура проекта

```
com.wdtt.client/
├── MainActivity.kt               - Главная Compose Activity
├── WdttApplication.kt            - Application класс
├── TunnelManager.kt              - Мененджер туннеля (управляет Go-процессом)
├── TunnelService.kt              - VpnService
├── WireGuardHelper.kt            - Помощник для работы с WireGuard-туннелем
├── SubscriptionImport.kt         - Импорт подписок по URL / qwdtt:// scheme
├── AppUpdate.kt                  - Проверка обновлений
├── SettingsStore.kt              - DataStore с настройками
├── ProfilesStore.kt              - Хранилище профилей
├── ConnectionPipeline.kt         - Состояние процесса подключения
├── VpnPermissionActivity.kt      - Разрешения VPN
├── RunetDirectHelper.kt          - Оптимизация маршрутизации
└── ui/                           - Jetpack Compose UI компоненты
```
