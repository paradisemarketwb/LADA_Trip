# LADA Trip Engineering v0.2 · 15.11.2026

## Добавлено
- Отдельный `EngineeringActivity`, переход с главного экрана, тестовый JSON через SAF.
- Реальная проверка объявленных CAR permissions и запрос только реально dangerous разрешений.
- Reflection-based Car.createCar / CarPropertyManager: опрос стандартных свойств на рабочей нити, попытка callback, статусы / ошибки / значения.
- Проверка metadata сервиса Yandex CAN и Binder descriptor (без AIDL transact).
- Явно opt-in Accessibility только для пакета OEM EngineeringMode; ограниченная сессия в памяти без автоматических кликов.
- README и docs/ENGINEERING_TEST_PLAN.md, версия приложения 0.2.0-engineering, имя GitHub Actions artifact.

## Проверено
- Синтаксис всех Java: 11 файлов, 0 ошибок (`scripts/ParseJava.java`).
- XML файлы Android: 5 корректных XML.
- Node server tests: 6/6 PASS (повторный запуск).
- Static checks отсутствия Binder transact, наличия UI и манифеста.

## Не проверено
- Android Gradle компиляция и реальный APK: здесь отсутствует Android SDK 35 и Gradle.
- Runtime чтение R2 и подтверждение значений CAN.
- Срабатывание OEM Accessibility, экспорт SAF на USB, работа v0.2 на реальной магнитоле.

Следующий шаг: собрать Android debug APK в Android Studio / GitHub Actions, протестировать на машине согласно docs/ENGINEERING_TEST_PLAN.md и отправить JSON отчёт. Никаких замен firmware, root и записи в CAN.

---

# LADA Trip · отчёт этапа 1 · 2026-11-15

## Готово в исходном коде
- Отдельное Android приложение minSdk/targetSdk 28, для вертикального экрана; foreground GPS service без Play Services.
- Boot receiver (автостарт только по разрешению пользователя) и Android JobScheduler для сетевой отправки.
- Offline SQLite: поездки, координаты, остановки, длительность, пробег, последняя позиция, очередь событий.
- Три HTTP попытки на событие; ACK сверяется по event_id, `ok:true` и `delivery:delivered`.
- Защищённое хранение ключа устройства в Android Keystore (AES-GCM); Telegram bot token вне APK.
- Node.js backend: авторизация устройства, Telegram уведомления, запись на диск и серверная дедупликация.
- Native UI, настройки, экспорт поездок/остановок/точек CSV, GPX, диагностический JSON через SAF на USB.
- Сборочный workflow GitHub Actions и Windows скрипт создания **нового приватного** репозитория.

## Проверено в текущей среде
- `node --test` — **6/6 успешно** (API, ACK, дубликаты, повтор после отказа, перезапуск сервера, конфликт одного event_id).
- Парсинг Java-исходников — **7/7 без синтаксических ошибок**. Это не компиляция Android.
- Парсинг ресурсов Android XML — **3/3 корректные XML**. Это не сборка AAPT2.

## Не проверено
- Компиляция через Android SDK/Gradle и APK: инструменты не установлены в текущей среде, загрузить их отсюда нельзя (нет сетевого доступа к Google SDK).
- Реальное GNSS, реакция магнитолы на BOOT_COMPLETED, переход в сон, USB SAF, Android 9 OEM фоновые политики.
- Отправка настоящего Telegram-уведомления: нет пользовательских секретов бота и развернутого HTTPS домена.
- Строгая exactly-once доставка при падении Telegram/сервера не гарантируется, как задокументировано.

## До проверки на магнитоле
1. Извлечь архив и запустить `scripts/publish-github.ps1`: создать приватный GitHub репозиторий без изменений в CarPlay проектах.
2. Скачать debug APK из GitHub Actions после успешной сборки; при ошибке CI исправить компиляцию.
3. Установить APK на магнитолу и выполнить `docs/TEST_PLAN.md`.
4. Для проверки Telegram — развернуть сервер за HTTPS и прописать бот/ключ в окружении.
