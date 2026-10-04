// Real protocol clients for the 1.16–1.20 compatibility matrix; no translator required.
// The healthy hold starts at the first world chunk, not at login: a session that
// never renders terrain (server-side chunk generation delay) must not count as healthy.
// Before disconnecting the bot waits for the chunk stream to go quiet, so no
// in-flight tail is lost and end-to-end byte counters can be compared exactly.
import mc from 'minecraft-protocol';
const seconds = Number(process.env.BOT_SECONDS || 30);
const version = process.env.BOT_VERSION;
if (!version || !Number.isInteger(seconds) || seconds < 15 || seconds > 120) throw Error('invalid bot configuration');
const client = mc.createClient({
  host: '127.0.0.1', port: Number(process.env.BOT_PORT),
  username: 'VeloZipBot', auth: 'offline', version, hideErrors: false,
});
let playAt = 0;
let success = false;
let timer;
let worldGate;
let lastChunk = 0;
const watchdog = setTimeout(() => { console.error('BOT timeout'); client.end(); }, (2 * seconds + 75) * 1000);
client.once('login', () => {
  playAt = Date.now();
  console.log(`BOT reached PLAY state (${version})`);
  worldGate = setTimeout(() => { console.error('BOT no world chunk before deadline'); client.end(); }, (seconds + 30) * 1000);
});
const chunkGate = () => {
  lastChunk = Date.now();
  if (playAt && !timer) {
    clearTimeout(worldGate);
    const holdStart = Date.now();
    console.log(`BOT first world chunk after ${holdStart - playAt} ms`);
    timer = setTimeout(() => {
      success = true;
      console.log(`BOT healthy hold milliseconds=${Date.now() - holdStart}`);
      const quietStart = Date.now();
      const quiet = setInterval(() => {
        if (Date.now() - lastChunk >= 5000) {
          clearInterval(quiet);
          console.log(`BOT stream quiet after ${Date.now() - quietStart} ms`);
          client.end();
        }
      }, 500);
      setTimeout(() => { clearInterval(quiet); client.end(); }, 30000);
    }, seconds * 1000);
  }
};
client.on('map_chunk', chunkGate);
client.on('level_chunk_with_light', chunkGate);
client.on('position', packet => {
  client.write('teleport_confirm', {teleportId: packet.teleportId});
  client.write('position_look', {x: packet.x, y: packet.y, z: packet.z,
    yaw: packet.yaw, pitch: packet.pitch, onGround: false});
});
client.on('error', error => { success = false; console.error(error.message); client.end(); });
client.on('disconnect', packet => { success = false; console.error('BOT disconnect:', JSON.stringify(packet)); });
client.once('end', () => {
  clearTimeout(timer);
  clearTimeout(worldGate);
  clearTimeout(watchdog);
  console.log(`BOT finished: success=${success}`);
  process.exitCode = success ? 0 : 1;
});
