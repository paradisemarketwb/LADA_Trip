# LADA Trip v0.2 Engineering — сборка на Windows 10/11 без GitHub

1. Установите Android Studio: https://developer.android.com/studio и запустите один раз.
2. Откройте SDK Manager, установите **Android SDK Platform 35** и **Android SDK Build-Tools 35.0.0**. Примите лицензии.
3. Распакуйте ZIP полностью. Зайдите в `lada-trip` и дважды нажмите **build-lada-trip.cmd**.
4. Скрипт использует встроенную Java из Android Studio, найдёт Android SDK, скачает официальный Gradle 8.7 и проверит SHA256.
5. Готовый файл будет лежать: `lada-trip/APK/LADA-Trip-v0.2-Engineering-debug.apk`.
6. Если произошла ошибка, отправьте содержимое `lada-trip/build-logs/last-build.txt` (удалите секреты, если они вдруг появятся в журнале).

Проект имеет Android Gradle Plugin 8.6.1, Gradle 8.7, compile SDK 35, min/target SDK 28, Java 17.
Билдер ничего не загружает в GitHub; интернет нужен только для скачивания Gradle и Gradle/Android зависимостей. Он не устанавливает APK в автомобиль автоматически и не меняет прошивку.

**Предупреждение:** APK ещё не прошёл полную Gradle-компиляцию и испытания на магнитоле. Если обнаружатся ошибки Java/AAPT, сохраните полный log и передайте для исправления.
