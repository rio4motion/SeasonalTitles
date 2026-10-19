// Real Paper protocol regression, not a visual Minecraft client test.
// Prerequisites: disposable Paper 1.21.11/LuckPerms/SeasonalTitles server, accepted EULA,
// loopback binding 127.0.0.1:25585, offline-mode; install minecraft-protocol@1.68.0 separately.
// Build ./gradlew integrationFixtureJar and install build/integration/seasonaltitles-rendering-fixture.jar here only.
// NODE_PATH=<node_modules> JAVA_HOME=<jdk21> node scripts/render-regression.cjs <server-dir> [--expect-hidden]
// Uses synthetic TitleOwner and creates a tagged stats passenger / scoreboard fixture.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {spawn} = require('node:child_process');
const mc = require('minecraft-protocol');
const data = require('minecraft-data')('1.21.11');
const dir = path.resolve(process.argv[2]);
const beforeFix = process.argv.includes('--expect-hidden');
const beforeTeamFix = process.argv.includes('--expect-team-hidden');
const properties = fs.readFileSync(path.join(dir, 'server.properties'), 'utf8');
assert.match(properties, /^server-ip=127\.0\.0\.1$/m);
assert.match(properties, /^server-port=25585$/m);
assert.match(properties, /^online-mode=false$/m);
const title = process.env.ST_TEST_TITLE || '2027-spring-legend';
const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME,'bin/java') : 'java';
const server = spawn(java, ['-Xms512M','-Xmx1G','-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts',
  '-Dhttps.proxyHost=proxy','-Dhttps.proxyPort=8080','-Dhttp.proxyHost=proxy','-Dhttp.proxyPort=8080',
  '-jar','paper.jar','--nogui'], {cwd:dir,stdio:['pipe','pipe','pipe']});
let log = '', client, entityId, passengers = [], chat = [], currentPosition;
const entities = new Map(), metadata = new Map(), packets = [];
server.stdout.on('data', b => {log += b;});
server.stderr.on('data', b => {log += b;});
const pause = ms => new Promise(r=>setTimeout(r,ms));
async function until(test,label,timeout=12000) {
  const end = Date.now()+timeout;
  while(Date.now()<end) { if(test()) return; if(server.exitCode!==null) throw Error('Server exited: '+server.exitCode); await pause(50); }
  throw Error('Timed out: '+label);
}
function consoleCommand(s) { server.stdin.write(s+'\n'); }
async function command(s, ack) {
  const start=chat.length; client.chat('/'+s);
  await until(()=>chat.slice(start).some(c=>c.includes(ack)),s);
}
function activeTitle() { return passengers.find(id=>entities.get(id)?.type===data.entitiesByName.text_display.id); }
function dump(reason) { fs.writeFileSync(path.join(dir,'render-regression-'+reason+'.json'),JSON.stringify(packets,null,2)); }
async function run() {
  await until(()=>log.includes('[SeasonalTitles] Ready:'),'startup',60000);
  client=mc.createClient({host:'127.0.0.1',port:25585,username:'TitleOwner',auth:'offline',version:'1.21.11'});
  client.on('error',e=>{throw e;});
  client.on('login',p=>{entityId=p.entityId;});
  client.on('position',p=>{
    currentPosition={x:p.x,y:p.y,z:p.z};
    client.write('teleport_confirm',{teleportId:p.teleportId});
    client.write('position',{...currentPosition,flags:{onGround:true,hasHorizontalCollision:false}});
  });
  client.on('packet',(p,m)=>{
    if(m.name==='system_chat') chat.push(JSON.stringify(p.content));
    if(m.name==='spawn_entity') entities.set(p.entityId,p);
    if(m.name==='entity_metadata') metadata.set(p.entityId, [...(metadata.get(p.entityId)||[]), ...p.metadata]);
    if(m.name==='set_passengers'&&p.entityId===entityId) passengers=p.passengers;
    if(m.name==='entity_destroy') p.entityIds.forEach(id=>entities.delete(id));
    if(['spawn_entity','entity_metadata','set_passengers','entity_destroy','entity_teleport','entity_position_sync'].includes(m.name)) packets.push({name:m.name,data:p});
  });
  await until(()=>entityId!==undefined&&currentPosition,'login');
  consoleCommand('op TitleOwner');
  consoleCommand('gamemode creative TitleOwner');
  consoleCommand('tp TitleOwner 0.5 80 0.5');
  await until(()=>currentPosition.y===80,'fixture teleport');
  consoleCommand('fill -5 79 -5 5 79 5 stone');
  consoleCommand('effect clear TitleOwner');
  await command('titles unequip',beforeFix ? 'Saved.' : 'Title hidden.');
  await command('titles equip '+title,'Saved.');
  if(beforeFix) {
    await until(()=>passengers.length>0,'attached entity exists');
    await pause(1000);
    assert.equal(activeTitle(),undefined,'old code unexpectedly sent the owner a display');
    console.log('REPRODUCED: equipped title is attached, but its spawn is missing from the owner client.');
    dump('before'); return;
  }
  await until(()=>activeTitle()!==undefined,'normal self-visible title');
  let id=activeTitle();
  await until(()=>JSON.stringify(metadata.get(id)).includes('Legend'),'title text metadata');
  const spawnIndex=packets.findIndex(p=>p.name==='spawn_entity'&&p.data.entityId===id);
  assert(packets.slice(spawnIndex+1).some(p=>p.name==='set_passengers'&&p.data.passengers.includes(id)), 'passenger packet must follow spawn');
  assert(metadata.get(id).some(m=>m.key===11&&Math.abs(m.value.y-0.55)<0.001),'configured translation');
  const scale = metadata.get(id).findLast(m=>m.key===12)?.value || {x:1,y:1,z:1}; // omitted = vanilla default
  assert.deepEqual(scale,{x:1,y:1,z:1},'nonzero unit scale');
  assert(metadata.get(id).some(m=>m.key===15&&m.value===3),'CENTER billboard');
  const textFlags=metadata.get(id).findLast(m=>m.key===27)?.value || 0;
  assert.equal(textFlags & 2,0,'text must remain depth-tested');
  console.log('PASS: owner receives spawn, text, translation, scale, and subsequent attachment.');
  await command('titles debug','tracked=true');
  // Creative flight is STANDING, unlike elytra flight. Do not suppress it.
  client.write('abilities',{flags:2});
  client.write('position',{x:0.6,y:81,z:0.5,flags:{onGround:false,hasHorizontalCollision:false}});
  await command('titles debug','flying=true');
  assert.equal(activeTitle(),id,'creative flight must preserve the attachment');
  client.write('abilities',{flags:0});
  consoleCommand('tp TitleOwner 1.5 80 0.5');
  await until(()=>currentPosition.x===1.5,'attached teleport');
  await until(()=>activeTitle()!==undefined&&activeTitle()!==id,'attachment after teleport');
  id=activeTitle();
  client.write('player_input',{inputs:{shift:true}});
  await until(()=>!entities.has(id),'sneak destroys title');
  client.write('player_input',{inputs:{shift:false}});
  await until(()=>activeTitle()!==undefined,'stand restores title');
  id=activeTitle();
  consoleCommand('effect give TitleOwner invisibility 30 0 true');
  await until(()=>!entities.has(id),'invisibility destroys title');
  consoleCommand('effect clear TitleOwner');
  await until(()=>activeTitle()!==undefined,'clear invisibility restores title');
  console.log('PASS: creative flight, teleport, crouching, standing and invisibility.');
  // Start with an unrelated passenger, as a hologram/stats plugin might do.
  await command('titles unequip','Title hidden.');
  await command('stfixture stats TitleOwner','fixture-ok:stats');
  await until(()=>passengers.some(id=>entities.get(id)?.type===data.entitiesByName.armor_stand.id),'stats fixture attachment');
  const stats=passengers.find(id=>entities.get(id)?.type===data.entitiesByName.armor_stand.id);
  await command('titles equip '+title,'Saved.');
  if (beforeTeamFix) {
    await command('titles debug','team-name-hidden');
    assert.equal(activeTitle(), undefined, 'old team policy unexpectedly showed the title');
    console.log('REPRODUCED: valid attached title blocked by team-name-hidden with a custom-label NEVER team.');
    dump('before-team-fix'); return;
  }
  await until(()=>activeTitle()!==undefined,'title beside existing stats');
  await command('titles debug','vanilla-name-visible=false');
  await command('titles debug','mode=informational');
  await command('titles debug','policy=visible');
  await command('titles debug','tracked=true');
  // Run stealth transitions while the custom-label team remains NEVER.
  id=activeTitle();
  client.write('player_input',{inputs:{shift:true}});
  await until(()=>!entities.has(id),'NEVER team does not bypass crouching');
  client.write('player_input',{inputs:{shift:false}});
  await until(()=>activeTitle()!==undefined,'standing with NEVER team');
  id=activeTitle();
  consoleCommand('effect give TitleOwner invisibility 30 0 true');
  await until(()=>!entities.has(id),'NEVER team does not bypass invisibility');
  consoleCommand('effect clear TitleOwner');
  await until(()=>activeTitle()!==undefined,'visible again with NEVER team');
  console.log('PASS: NEVER team is informational; self tracking works and stealth still suppresses the title.');
  assert(passengers.includes(stats),'existing stats passenger was replaced');
  assert.equal(passengers[0],stats,'existing passenger order changed');
  await command('titles unequip','Title hidden.');
  await until(()=>activeTitle()===undefined,'unequip removes only title');
  assert(passengers.includes(stats)&&entities.has(stats),'unequip touched stats');
  await command('stfixture check TitleOwner','fixture-ok:check');
  await command('stfixture clear TitleOwner','fixture-ok:clear');
  console.log('PASS: stats passenger, rank prefix and below-name objective survive equip and unequip.');
  await command('titles equip '+title,'Saved.');
  await until(()=>activeTitle()!==undefined,'equipped title');
  id=activeTitle();
  await command('stfixture hide TitleOwner','fixture-ok:hide');
  await until(()=>!entities.has(id),'vanish integration suppresses title');
  await command('stfixture reveal TitleOwner','fixture-ok:reveal');
  await until(()=>activeTitle()!==undefined,'vanish reveal restores title');
  id=activeTitle();
  consoleCommand('gamemode spectator TitleOwner');
  await until(()=>!entities.has(id),'spectator suppresses title');
  consoleCommand('gamemode creative TitleOwner');
  await until(()=>activeTitle()!==undefined,'exit spectator restores title');
  console.log('PASS: integration concealment and spectator restrictions retained.');
  dump('after');
}
(async()=>{
  try { await run(); }
  catch(e) {console.error(e);dump('failure');process.exitCode=1;}
  finally {
    if(client) client.end();
    if(server.exitCode===null) {consoleCommand('stop'); await Promise.race([new Promise(r=>server.once('exit',r)),pause(30000)]);}
    fs.writeFileSync(path.join(dir,'render-regression-server.log'),log);
    if(server.exitCode===null) {server.kill();process.exitCode=1;}
  }
})();
