// Disposable loopback Paper 1.21.11 + LuckPerms regression. Not a graphical-client test.
// Install SeasonalTitles and the optional integrationFixtureJar, never on a production server.
// NODE_PATH=<minecraft-protocol installation>/node_modules JAVA_HOME=<jdk21> node scripts/permission-regression.cjs <test-server>
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {spawn} = require('node:child_process');
const mc = require('minecraft-protocol');
const dir = path.resolve(process.argv[2]);
const properties = fs.readFileSync(path.join(dir,'server.properties'),'utf8');
assert.match(properties,/^server-ip=127\.0\.0\.1$/m);
assert.match(properties,/^server-port=25585$/m);
assert.match(properties,/^online-mode=false$/m);
const configPath = path.join(dir,'plugins/SeasonalTitles/config.yml');
const config = fs.readFileSync(configPath,'utf8');
assert(!/^diagnostics:/m.test(config),'use a fixture config without a diagnostics section');
fs.appendFileSync(configPath,'\ndiagnostics:\n  command-trace: true\n');
const server = spawn(path.join(process.env.JAVA_HOME,'bin/java'),['-Xms512M','-Xmx1G',
  '-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts','-Dhttps.proxyHost=proxy','-Dhttps.proxyPort=8080',
  '-Dhttp.proxyHost=proxy','-Dhttp.proxyPort=8080','-jar','paper.jar','--nogui'], {cwd:dir,stdio:['pipe','pipe','pipe']});
let log='', client, chat=[], tree, request=0, lastCommand=0, kicked;
function collect(b){log+=b.toString().replace(/\x1b\[[0-9;]*m/g,'');}
server.stdout.on('data',collect); server.stderr.on('data',collect);
const pause=ms=>new Promise(r=>setTimeout(r,ms));
async function until(check,label,timeout=12000) {
  const end=Date.now()+timeout;
  while(!check()) {if(kicked)throw Error('Test client kicked: '+kicked);if(Date.now()>end)throw Error('Timed out: '+label);if(server.exitCode!==null)throw Error('Server exited');await pause(50);}
}
function consoleCommand(c){server.stdin.write(c+'\n');}
async function consoleAck(c,ack){const start=log.length;consoleCommand(c);await until(()=>log.slice(start).includes(ack),c);}
async function command(c,ack){
  // Non-OP clients obey Paper's normal chat spam limiter; space synthetic commands.
  const wait=1050-(Date.now()-lastCommand);if(wait>0)await pause(wait);
  const start=chat.length;client.chat('/'+c);lastCommand=Date.now();await until(()=>chat.slice(start).some(t=>t.includes(ack)),c);}
function roots(){return tree.nodes[tree.rootIndex].children.map(i=>tree.nodes[i].extraNodeData.name);}
async function suggestions(text){
  const transactionId=++request;
  return await new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>{client.off('tab_complete',listener);reject(Error('suggestion timeout: '+text));},5000);
    function listener(p){if(p.transactionId===transactionId){clearTimeout(timer);client.off('tab_complete',listener);resolve(p.matches.map(m=>m.match));}}
    client.on('tab_complete',listener);client.write('tab_complete',{transactionId,text});
  });
}
async function run(){
  await until(()=>log.includes('[SeasonalTitles] Ready:'),'startup',60000);
  for(const [permission,value] of [['use',true],['preview',false],['admin',false]])
    await consoleAck('lp group default permission set seasonaltitles.'+permission+' '+value,'[LP]');
  consoleCommand('deop PermissionUser');
  client=mc.createClient({host:'127.0.0.1',port:25585,username:'PermissionUser',auth:'offline',version:'1.21.11'});
  client.on('error',e=>console.error(e));
  client.on('kick_disconnect',p=>{kicked=JSON.stringify(p);});
  client.on('declare_commands',p=>tree=p);
  client.on('system_chat',p=>chat.push(JSON.stringify(p.content)));
  client.on('position',p=>{client.write('teleport_confirm',{teleportId:p.teleportId});client.write('position',{x:p.x,y:p.y,z:p.z,flags:{onGround:true,hasHorizontalCollision:false}});});
  await until(()=>tree,'command tree');
  const labels=['title','titles','seasonaltitles:title','seasonaltitles:titles'];
  labels.forEach(label=>assert(roots().includes(label),label+' visible'));
  assert(!roots().includes('sti')&&!roots().includes('seasonaltitles:sti'),'admin roots hidden');
  await consoleAck('titles debug PermissionUser','op=false, use=true, preview=false');
  await consoleAck('stfixture checkpermissions PermissionUser','fixture-ok:checkpermissions');
  for(const label of labels){
    await command(label+' list','Titles 1/');
    assert.deepEqual(await suggestions('/'+label+' '),['animation','animations','equip','help','list','off','on','unequip']);
  }
  await command('title preview 2027-spring-legend','You do not have permission.');
  await command('title reload','You do not have permission.');
  await command('seasonaltitles:title update Summer 2027','You do not have permission.');
  await command('title equip 2027-spring-legend','You do not have access');
  const id='permission_'+Date.now();
  await consoleAck('sti create '+id+' Permission fixture','Saved.');
  await consoleAck('sti give PermissionUser '+id,'Saved.');
  assert((await suggestions('/title equip custom:permission_')).includes('custom:'+id),'owned custom title suggested');
  await command('seasonaltitles:title equip custom:'+id,'Saved.');
  await command('title unequip','Title hidden.');
  console.log('PASS: non-OP inherited default-group use, all aliases, owned equip/unequip, denied unowned/admin/preview, filtered suggestions and root visibility.');
  await consoleAck('lp group default permission set seasonaltitles.preview true','[LP]');
  await consoleAck('lp group default permission set seasonaltitles.use false','[LP]');
  await command('titles list','You do not have permission.');
  await command('title preview 2027-spring-legend','Preview started');
  await consoleAck('stfixture checkpreview PermissionUser','fixture-ok:checkpreview');
  assert.deepEqual(await suggestions('/title p'),['preview']);
  await consoleAck('lp group default permission set seasonaltitles.preview false','[LP]');
  await until(()=>!roots().some(r=>labels.includes(r)),'permission refresh hides roots');
  await consoleAck('lp group default permission set seasonaltitles.use true','[LP]');
  await until(()=>roots().includes('title'),'permission refresh restores root');
  await command('title list','Titles 1/');
  console.log('PASS: preview-only permission, revocations and live client command-tree refresh.');
  await consoleAck('stfixture blockcommand PermissionUser','fixture-ok:blockcommand');
  await command('seasonaltitles:title list','Fixture command blocked.');
  await until(()=>log.includes('requested=/seasonaltitles:title, final=/seasonaltitles:title, cancelled=true')&&log.includes('root-allowed=true, use=true'),'external block trace');
  await consoleAck('stfixture allowcommand PermissionUser','fixture-ok:allowcommand');
  await command('seasonaltitles:title list','Titles 1/');
  console.log('PASS: external cancellation reproduced despite inherited use; scoped trace distinguishes it from root denial.');
  await consoleAck('sti revoke PermissionUser '+id,'Confirm within 30s');
  await consoleAck('sti revoke PermissionUser '+id+' confirm','Saved.');
  await consoleAck('sti delete '+id,'Confirm within 30s');
  await consoleAck('sti delete '+id+' confirm','Saved.');
}
(async()=>{try{await run();}catch(e){console.error(e);process.exitCode=1;}finally{
  if(client)client.end();
  if(server.exitCode===null){consoleCommand('stop');await Promise.race([new Promise(r=>server.once('exit',r)),pause(30000)]);}
  fs.writeFileSync(path.join(dir,'permission-regression-server.log'),log);
  fs.writeFileSync(configPath,config);
  if(server.exitCode===null){server.kill();process.exitCode=1;}
}})();
