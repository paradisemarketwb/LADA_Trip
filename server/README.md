# LADA Trip Gateway

Node.js 20+ backend. No third-party npm packages.

## Безопасное развертывание

1. Получить `TG_BOT_TOKEN` через BotFather, определить собственный `TG_CHAT_ID`; не вставлять их в приложение.
2. Сгенерировать 32-байтовый **случайный ключ устройства** через `node -e "console.log(require('node:crypto').randomBytes(32).toString('hex'))"`.
3. Установить переменные `TG_BOT_TOKEN`, `TG_CHAT_ID`, `DEVICE_KEYS_JSON='{"lada-vesta-01":"<random-key>"}'`, `DATA_DIR`. Не сохранять в репозитории или APK.
4. Запустить `node index.js`: по умолчанию API слушает `127.0.0.1:8787` (не открыт в интернет).
5. Сконфигурировать TLS reverse proxy на публичном домене: **только HTTPS**, `/v1/events` → localhost:8787. Ограничить частоту POST на прокси; по возможности firewall/allowlist IP.
6. В Android Settings указать `https://your-domain`, `lada-vesta-01` и ключ из п.2.

Важно: HTTPS сертификат должен доверяться Android 9 (чистый TLS, без self-signed для первой версии). В Hostland с Passenger проксирование/запуск могут потребовать адаптации конфигурации. Не выкладывайте `.env` в GitHub. Не используйте реальный Telegram bot token в тестовых логах.

## API

`POST /v1/events` с `Authorization: Bearer <device-key>`, `X-Device-ID: lada-vesta-01`, `Content-Type: application/json`.

```json
{
  "event_id": "c0a80101-1234-4abc-8abc-123456789abc",
  "type": "location",
  "latitude": 55.7558,
  "longitude": 37.6173,
  "accuracy_m": 12,
  "captured_at": "2026-11-15T00:00:00.000Z"
}
```

Ответ 200 **только после подтверждения Telegram**, например `{"ok":true,"event_id":"...","delivery":"delivered"}`. Ответ 503 не является подтверждением, клиент оставляет событие в очереди. `GET /healthz` для проверки состояния без приватных данных.

На диске сохраняются `pending/delivered` события в `DATA_DIR`, дубликаты одного `device_id:event_id` подавляются. Перезапуск сервера сохраняет журнал доставки. После подтверждения Telegram и до записи статуса на диск существует короткое окно неопределённости: **строго exactly-once невозможно гарантировать без транзакционной поддержки со стороны Telegram**. Это честное ограничение протокола.

Сервер хранит координаты в журнале, поэтому защитите его файловое хранилище, резервные копии и доступ администратора. Предусмотрите политику срока хранения данных при публичном развёртывании.

## Тесты

`node --test` — тесты авторизации, ACK, повторов, конкурентных запросов и форматирования Telegram сообщения.
