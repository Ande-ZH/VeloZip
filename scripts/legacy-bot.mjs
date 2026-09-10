// Real protocol clients for the 1.18–1.20 compatibility matrix; no translator required.
import mc from 'minecraft-protocol';
const seconds = Number(process.env.BOT_SECONDS || 30);
const version = process.env.BOT_VERSION;
if (!version || !Number.isInteger(seconds) || seconds < 15 || seconds > 120) throw Error('invalid bot configuration');
const client = mc.createClient({
  host: '127.0.0.1', port: Number(process.env.BOT_PORT),
  username: 'VeloZipBot', auth: 'offline', version, hideErrors: false,
});
let enteredPlay = 0;
let success = false;
let timer;
const watchdog = setTimeout(() => { console.error('BOT timeout'); client.end(); }, (seconds + 40) * 1000);
client.once('login', () => {
  enteredPlay = Date.now();
  console.log(`BOT reached PLAY state (${version})`);
  timer = setTimeout(() => {
    success = true;
    console.log(`BOT healthy hold milliseconds=${Date.now() - enteredPlay}`);
    client.end();
  }, seconds * 1000);
});
client.on('position', packet => {
  client.write('teleport_confirm', {teleportId: packet.teleportId});
  client.write('position_look', {x: packet.x, y: packet.y, z: packet.z,
    yaw: packet.yaw, pitch: packet.pitch, onGround: false});
});
client.on('error', error => { success = false; console.error(error.message); client.end(); });
client.on('disconnect', packet => { success = false; console.error('BOT disconnect:', JSON.stringify(packet)); });
client.once('end', () => {
  clearTimeout(timer);
  clearTimeout(watchdog);
  console.log(`BOT finished: success=${success}`);
  process.exitCode = success ? 0 : 1;
});
