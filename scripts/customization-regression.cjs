// Isolated Paper 1.21.11/LuckPerms + optional rendering fixture. Two vanilla protocol clients.
// NODE_PATH=<node_modules> JAVA_HOME=<jdk21> node scripts/customization-regression.cjs <loopback-server> [--crouch-visible]
// Changes synthetic clients, definitions and test-world blocks; never use on production.
const assert=require('node:assert/strict'), fs=require('node:fs'), path=require('node:path');
const {spawn,spawnSync}=require('node:child_process');
const mc=require('minecraft-protocol'), data=require('minecraft-data')('1.21.11');
const dir=path.resolve(process.argv[2]), crouchVisible=process.argv.includes('--crouch-visible');
const properties=fs.readFileSync(path.join(dir,'server.properties'),'utf8');
for(const line of [/^server-ip=127\.0\.0\.1$/m,/^server-port=25585$/m,/^online-mode=false$/m])assert.match(properties,line);
let server, log='', actors=[];
const pause=ms=>new Promise(r=>setTimeout(r,ms));
async function until(f,label,timeout=12000){const end=Date.now()+timeout;while(!f()){if(Date.now()>end)throw Error('Timed out: '+label);if(server.exitCode!==null)throw Error('Server exited');await pause(50);}}
function consoleCommand(text){server.stdin.write(text+'\n');}
async function consoleAck(text,ack){const start=log.length;consoleCommand(text);await until(()=>log.slice(start).includes(ack),text);}
async function cmd(actor,text,ack){const delay=1050-(Date.now()-actor.last);if(delay>0)await pause(delay);const start=actor.chat.length;actor.client.chat('/'+text);actor.last=Date.now();await until(()=>actor.chat.slice(start).some(c=>c.includes(ack)),text);}
function sql(query){const code='import sqlite3,json,sys; c=sqlite3.connect(sys.argv[1]); print(json.dumps(c.execute(sys.argv[2]).fetchall()))';const result=spawnSync('python3',['-c',code,path.join(dir,'plugins/SeasonalTitles/titles.db'),query],{encoding:'utf8'});assert.equal(result.status,0,result.stderr);return JSON.parse(result.stdout);}
function visible(actor,owner){return (actor.mounts.get(owner.eid)||[]).filter(id=>actor.entities.get(id)?.type===data.entitiesByName.text_display.id);}
function frame(actor,id){return JSON.stringify(actor.metadata.get(id)?.findLast(m=>m.key===23)?.value);}
function updates(actor,id,from){return actor.packets.slice(from).filter(p=>p.name==='entity_metadata'&&p.data.entityId===id&&p.data.metadata.some(m=>m.key===23)).length;}
async function connect(name){
 const actor={name,last:0,chat:[],entities:new Map(),metadata:new Map(),mounts:new Map(),packets:[]};
 actor.client=mc.createClient({host:'127.0.0.1',port:25585,username:name,auth:'offline',version:'1.21.11'});actors.push(actor);
 actor.client.on('error',e=>{actor.error=e;});
 actor.client.on('login',p=>actor.eid=p.entityId);
 actor.client.on('position',p=>{actor.position=p;actor.client.write('teleport_confirm',{teleportId:p.teleportId});actor.client.write('position',{x:p.x,y:p.y,z:p.z,flags:{onGround:true,hasHorizontalCollision:false}});});
 actor.client.on('packet',(p,m)=>{
  if(m.name==='system_chat')actor.chat.push(JSON.stringify(p.content));
  if(m.name==='spawn_entity')actor.entities.set(p.entityId,p);
  if(m.name==='entity_destroy')p.entityIds.forEach(id=>actor.entities.delete(id));
  if(m.name==='entity_metadata')actor.metadata.set(p.entityId,[...(actor.metadata.get(p.entityId)||[]),...p.metadata]);
  if(m.name==='set_passengers')actor.mounts.set(p.entityId,p.passengers);
  if(['spawn_entity','entity_destroy','entity_metadata','set_passengers'].includes(m.name))actor.packets.push({name:m.name,data:p});
 });
 await until(()=>actor.eid!==undefined&&actor.position,name+' login');return actor;
}
async function start(){
 log='';actors=[];
 server=spawn(path.join(process.env.JAVA_HOME,'bin/java'),['-Xms512M','-Xmx1G','-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts','-Dhttps.proxyHost=proxy','-Dhttps.proxyPort=8080','-Dhttp.proxyHost=proxy','-Dhttp.proxyPort=8080','-jar','paper.jar','--nogui'],{cwd:dir,stdio:['pipe','pipe','pipe']});
 const collect=b=>log+=b.toString().replace(/\x1b\[[0-9;]*m/g,'');server.stdout.on('data',collect);server.stderr.on('data',collect);
 await until(()=>log.includes('[SeasonalTitles] Ready:'),'startup',60000);
}
async function stop(label){actors.forEach(a=>a.client.end());if(server.exitCode===null){consoleCommand('stop');await Promise.race([new Promise(r=>server.once('exit',r)),pause(30000)]);}fs.writeFileSync(path.join(dir,'customization-'+label+'.log'),log);if(server.exitCode===null)server.kill();}
async function run(){
 const oldAwards=sql('SELECT uuid,title,awarded_at FROM awards ORDER BY uuid,title');
 const oldSeason=sql('SELECT season,year FROM state');
 await start();
 const owner=await connect('TitleOwner'), viewer=await connect('TitleObserver');
 for(const a of [owner,viewer]){consoleCommand('op '+a.name);consoleCommand('gamemode creative '+a.name);}
 consoleCommand('tp TitleOwner 0.5 80 0.5');consoleCommand('tp TitleObserver 4.5 80 0.5');consoleCommand('fill -5 79 -5 6 79 5 stone');consoleCommand('effect clear TitleOwner');
 await cmd(owner,'titles animation static','Saved.');await cmd(owner,'titles on','titles: on');await cmd(owner,'titles animations on','animations: on');
 await cmd(viewer,'titles unequip','Title hidden.');await cmd(viewer,'titles on','titles: on');await cmd(viewer,'titles animations on','animations: on');
 const id='overhaul_'+Date.now(), preview=id+'_preview';
 const text='<gradient:#00AAFF:#AA00FF><bold>✦ Beta Supporter</bold></gradient>';
 await consoleAck('titles admin create '+id+' '+text,'Saved.');await consoleAck('titles admin create '+preview+' <gold>Preview Only','Saved.');
 await consoleAck('titles admin give TitleOwner '+id,'Saved.');
 await cmd(owner,'titles equip custom:'+id,'Saved.');await until(()=>visible(owner,owner).length===1&&visible(viewer,owner).length===1,'static tracking');
 let primary=visible(owner,owner)[0];await until(()=>frame(owner,primary)!==undefined,'initial text');const staticText=frame(owner,primary), staticStart=owner.packets.length;
 await pause(1200);assert.equal(updates(owner,primary,staticStart),0,'static title has no repeated text packets');
 await cmd(owner,'titles animation rainbow','Saved.');const animatedStart=owner.packets.length;
 await pause(1200);assert(updates(owner,primary,animatedStart)>=2,'shared task animates owner');
 await cmd(viewer,'titles animations off','animations: off');await until(()=>visible(viewer,owner).length===1&&visible(viewer,owner)[0]!==primary,'static alternative');
 let alternate=visible(viewer,owner)[0];await until(()=>frame(viewer,alternate)!==undefined,'static metadata');assert.equal(frame(viewer,alternate),staticText,'exact original static formatting');
 const vStart=viewer.packets.length,oStart=owner.packets.length;await pause(1300);
 assert.equal(updates(viewer,primary,vStart),0,'disabled viewer receives no primary text updates');assert.equal(updates(viewer,alternate,vStart),0,'static fallback unchanged');assert(updates(owner,primary,oStart)>=2,'owner animation unaffected');
 assert.equal(visible(owner,owner).length,1);assert(!owner.entities.has(alternate),'owner not tracking fallback');
 console.log('PASS: static stays idle; two viewers receive different title entities, exact static styling, and no animated text metadata at the disabled client.');
 await cmd(viewer,'titles equip custom:'+id,'Saved.');await cmd(viewer,'titles animation pulse','Saved.');
 await until(()=>visible(viewer,viewer).length===1,'second animated owner');const second=visible(viewer,viewer)[0],secondStart=viewer.packets.length;await pause(1100);assert(updates(viewer,second,secondStart)>0,'two animated owners progress independently');
 await cmd(viewer,'titles off','titles: off');await until(()=>visible(viewer,owner).length===0,'titles hidden only for viewer');assert.equal(visible(owner,owner).length,1);
 await cmd(owner,'titles animations off','animations: off');let from=owner.packets.length;await pause(1100);assert(updates(owner,primary,from)>0,'own animation ignores others-animation preference');
 await cmd(viewer,'titles on','titles: on');await until(()=>visible(viewer,owner).length===1,'other titles restored');
 consoleCommand('tp TitleOwner 0.6 80 0.5');await until(()=>owner.position.x===0.6&&visible(owner,owner).length===1&&visible(viewer,owner).length===1,'attached teleport recreates variants');
 await consoleAck('stfixture hide TitleOwner','fixture-ok:hide');await until(()=>visible(owner,owner).length===0&&visible(viewer,owner).length===0,'vanish hook hides variants');
 await consoleAck('stfixture reveal TitleOwner','fixture-ok:reveal');await until(()=>visible(owner,owner).length===1&&visible(viewer,owner).length===1,'vanish hook restores variants');
 await consoleAck('stfixture stats TitleOwner','fixture-ok:stats');await consoleAck('stfixture check TitleOwner','fixture-ok:check');
 consoleCommand('fill 2 80 -2 2 84 2 stone');await until(()=>visible(viewer,owner).length===0,'body/title line-of-sight');consoleCommand('fill 2 80 -2 2 84 2 air');await until(()=>visible(viewer,owner).length===1,'line-of-sight restores');
 consoleCommand('effect give TitleOwner invisibility 30 0 true');await until(()=>visible(owner,owner).length===0&&visible(viewer,owner).length===0,'invisibility hides both variants');consoleCommand('effect clear TitleOwner');await until(()=>visible(owner,owner).length===1&&visible(viewer,owner).length===1,'invisibility restore');
 owner.client.write('player_input',{inputs:{shift:true}});if(!crouchVisible)await until(()=>visible(owner,owner).length===0&&visible(viewer,owner).length===0,'crouch concealment');else await pause(200);
 owner.client.write('player_input',{inputs:{shift:false}});await until(()=>visible(owner,owner).length===1&&visible(viewer,owner).length===1,'standing restore');
 consoleCommand('gamemode spectator TitleOwner');await until(()=>visible(owner,owner).length===0&&visible(viewer,owner).length===0,'spectator hides variants');consoleCommand('gamemode creative TitleOwner');await until(()=>visible(owner,owner).length===1,'spectator restore');
 console.log('PASS: per-viewer title toggles, owner independence, LOS/invisibility/crouch/spectator protections, stats/prefix compatibility.');
 await cmd(owner,'titles preview custom:'+preview,'Preview started');assert.equal(sql("SELECT title FROM equipped WHERE uuid IN (SELECT uuid FROM players WHERE lower(name)='titleowner')")[0][0],'custom:'+id);
 await pause(10500);await until(()=>frame(viewer,visible(viewer,owner)[0])===staticText,'preview restores current equipped title');
 await consoleAck('lp group default permission set seasonaltitles.animation.* true','[LP]');
 consoleCommand('deop TitleOwner');await consoleAck('lp user TitleOwner permission set seasonaltitles.animation.rainbow false','[LP]');await until(()=>visible(owner,owner).length===1&&visible(viewer,owner).length===1&&visible(owner,owner)[0]===visible(viewer,owner)[0],'permission loss static');
 assert.equal(sql("SELECT animation FROM preferences WHERE uuid IN (SELECT uuid FROM players WHERE lower(name)='titleowner')")[0][0],'rainbow');
 await consoleAck('lp user TitleOwner permission unset seasonaltitles.animation.rainbow','[LP]');await until(()=>visible(owner,owner)[0]!==visible(viewer,owner)[0],'permission regain animation restores');
 await consoleAck('titles admin edit '+id+' '+text,'Saved.');assert(sql("SELECT title FROM awards WHERE uuid IN (SELECT uuid FROM players WHERE lower(name)='titleowner')").some(r=>r[0]==='custom:'+id));
 await consoleAck('titles admin rank add bronze temporary_old','Saved.');await consoleAck('titles admin rank add bronze temporary_new','Saved.');await consoleAck('titles admin rank remove bronze temporary_old','Saved.');
 assert(fs.readFileSync(path.join(dir,'plugins/SeasonalTitles/tiers.yml'),'utf8').includes('temporary_new'));
 const tierBytes=fs.readFileSync(path.join(dir,'plugins/SeasonalTitles/tiers.yml'),'utf8');await consoleAck('titles admin rank add legend temporary_new','Conflicting group mapping');assert.equal(fs.readFileSync(path.join(dir,'plugins/SeasonalTitles/tiers.yml'),'utf8'),tierBytes,'invalid mapping not saved');
 console.log('PASS: previews preserve selection, permission loss/regain preserves animation preference, editing retains grants, rank renames/conflict validation.');
 await stop('first');
 const afterAwards=sql('SELECT uuid,title,awarded_at FROM awards ORDER BY uuid,title');oldAwards.forEach(row=>assert(afterAwards.some(r=>JSON.stringify(r)===JSON.stringify(row)),'old award unchanged'));
 assert.deepEqual(sql('SELECT season,year FROM state'),oldSeason,'active season unchanged');
 await start();const owner2=await connect('TitleOwner'),viewer2=await connect('TitleObserver');
 await until(()=>visible(owner2,owner2).length===1&&visible(viewer2,owner2).length===1&&visible(owner2,owner2)[0]!==visible(viewer2,owner2)[0],'persisted per-viewer setting after restart');
 assert.equal(sql("SELECT show_animations FROM preferences WHERE uuid IN (SELECT uuid FROM players WHERE lower(name)='titleobserver')")[0][0],0);
 await cmd(viewer2,'titles off','titles: off');await until(()=>visible(viewer2,owner2).length===0,'title off persists to next session');
 const disconnectStart=log.length;viewer2.client.end();await until(()=>log.slice(disconnectStart).includes('TitleObserver lost connection'),'disconnect');
 const viewer3=await connect('TitleObserver');await pause(500);assert.equal(visible(viewer3,owner2).length,0,'title off survives reconnect');
 await cmd(viewer3,'titles on','titles: on');await until(()=>visible(viewer3,owner2).length===1,'restore after reconnect');
 console.log('PASS: schema migration preserves all prior award timestamps/season; preferences, selections and custom definitions survive restart/reconnect.');
 await stop('restart');
}
(async()=>{try{await run();}catch(e){console.error(e);if(server)await stop('failure');process.exitCode=1;}})();
