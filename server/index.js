'use strict';
const {createServer} = require('./lib');

const config = {
  host: process.env.HOST || '127.0.0.1',
  port: Number(process.env.PORT || 8787),
  dataDir: process.env.DATA_DIR || require('path').join(__dirname, 'data'),
  telegramToken: process.env.TG_BOT_TOKEN,
  telegramChatId: process.env.TG_CHAT_ID,
  deviceKeys: JSON.parse(process.env.DEVICE_KEYS_JSON || '{}')
};
if (!config.telegramToken || !config.telegramChatId || !Object.keys(config.deviceKeys).length) {
  console.error('Missing TG_BOT_TOKEN, TG_CHAT_ID or DEVICE_KEYS_JSON. Refusing to start.');
  process.exit(1);
}
const server = createServer(config);
server.listen(config.port, config.host, () => console.log(`LADA Trip API bound to ${config.host}:${config.port} (TLS required at reverse proxy)`));
