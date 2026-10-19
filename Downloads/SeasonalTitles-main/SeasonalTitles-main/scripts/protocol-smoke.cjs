// Optional local integration observer: NODE_PATH=<minecraft-protocol node_modules> node scripts/protocol-smoke.cjs
// Use an isolated loopback-only offline-mode test server, NEVER a public offline-mode server.
const mc = require('minecraft-protocol');
const data = require('minecraft-data')('1.21.11');
const readline = require('readline');
const clients = {};
for (const username of ['TitleOwner', 'TitleViewer']) {
  const client = mc.createClient({host: '127.0.0.1', port: 25585, username, auth: 'offline', version: '1.21.11'});
  clients[username] = client;
  client.on('login', p => console.log(username, 'LOGIN', p.entityId));
  client.on('position', p => {
    client.write('teleport_confirm', {teleportId: p.teleportId});
    client.write('position', {x: p.x, y: p.y, z: p.z, flags: {onGround: true, hasHorizontalCollision: false}});
    console.log(username, 'POSITION', p.x, p.y, p.z);
  });
  client.on('packet', (p, meta) => {
    if (meta.name === 'set_passengers' || meta.name === 'entity_destroy') console.log(username, meta.name, JSON.stringify(p));
    if (meta.name === 'spawn_entity' && p.type === data.entitiesByName.text_display.id) console.log(username, 'TEXT_DISPLAY', p.entityId);
    if (meta.name === 'system_chat') console.log(username, 'CHAT', JSON.stringify(p.content).slice(0,350));
  });
  client.on('error', e => console.error(username, e.message));
  client.on('end', reason => console.log(username, 'END', reason));
}
console.log('Enter: TitleOwner /titles equip <id> (or quit)');
readline.createInterface({input: process.stdin}).on('line', line => {
  if (line === 'quit') { Object.values(clients).forEach(c => c.end()); setTimeout(() => process.exit(), 500); return; }
  const space = line.indexOf(' '), client = clients[line.slice(0,space)];
  if (client) {
    const command = line.slice(space+1);
    if (command === ':sneak' || command === ':stand') client.write('player_input', {inputs: {shift: command === ':sneak'}});
    else client.chat(command);
  }
});
