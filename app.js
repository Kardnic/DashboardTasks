const SUPABASE_URL='https://hfpryzswevnpmqdaidzj.supabase.co';
const SUPABASE_KEY='sb_publishable_odMpT_m3G4RihPHoaYFMKA_fz7NPVsy';
const VAPID_PUBLIC_KEY='BLi3pd0LO6c-v87cuQ9Htd1vtRGegpYUBS6WHSqn2oh0DP0ABFE9BW2shmu3hp5L9lSJ_VLExI-MxAc5O5kANrA';

if(window.top!==window.self){
  document.documentElement.textContent='';
  throw new Error('Framing blocked');
}

const db=supabase.createClient(SUPABASE_URL,SUPABASE_KEY,{
  auth:{
    persistSession:true,
    autoRefreshToken:true,
    detectSessionInUrl:false,
    storage:window.localStorage
  }
});

const $=s=>document.querySelector(s);
const $$=s=>[...document.querySelectorAll(s)];
const authView=$('#authView'),mfaView=$('#mfaView'),appView=$('#appView');
const authMsg=$('#authMsg'),mfaChallengeMsg=$('#mfaChallengeMsg'),taskMsg=$('#taskMsg'),smartMsg=$('#smartMsg');
const workTaskList=$('#workTaskList'),privateTaskList=$('#privateTaskList');
const focusList=$('#focusList'),reminderBanner=$('#reminderBanner'),securityMsg=$('#securityMsg');
let tasks=[],activeFilter='today',reminderTimer=null,pendingEnrollmentFactorId=null;
const WORK_LOCATION_KEY='dashboardtasks_work_location_v1';
const MOBILE_AREA_MODE_KEY='dashboardtasks_mobile_area_mode_v1';
let mobileAreaMode=localStorage.getItem(MOBILE_AREA_MODE_KEY)||'auto';
let detectedMobileArea=null;
let mobileLocationTimer=null;
let locationCheckInFlight=false;

function showMsg(el,msg,type=''){
  el.textContent=msg;
  el.className='notice '+type;
  el.classList.remove('hidden');
}
function hideMsg(el){el.classList.add('hidden')}
function schemaHint(error){
  const msg=error&&error.message?error.message:String(error||'');
  if(/waiting_for|recurrence|reminder_at|reminded_at|next_recurrence_created|push_subscriptions|p256dh/i.test(msg)){
    return ' Die Supabase-Datenbank muss noch mit der aktuellen supabase/schema.sql erweitert werden.';
  }
  return '';
}

function startOfToday(){const d=new Date();d.setHours(0,0,0,0);return d}
function endOfToday(){const d=new Date();d.setHours(23,59,59,999);return d}
function endOfWeek(){const d=endOfToday();d.setDate(d.getDate()+7);return d}
function atLocalTime(date,hour=12,minute=0){const d=new Date(date);d.setHours(hour,minute,0,0);return d}
function toIsoDateLocal(date){return atLocalTime(date,12,0).toISOString()}
function localInputToIso(v){if(!v)return null;const d=new Date(v);return Number.isNaN(d.getTime())?null:d.toISOString()}

function dueForMode(){
  const mode=$('#dueMode').value;
  if(mode==='inbox')return null;
  const d=startOfToday();
  if(mode==='tomorrow')d.setDate(d.getDate()+1);
  if(mode==='date'){
    const v=$('#dueDate').value;
    if(!v)return null;
    const [y,m,day]=v.split('-').map(Number);
    return toIsoDateLocal(new Date(y,m-1,day));
  }
  return toIsoDateLocal(d);
}

function isTodayRaw(t){
  if(!t.due_at||t.completed)return false;
  const d=new Date(t.due_at);return d>=startOfToday()&&d<=endOfToday();
}
function isToday(t){return !t.waiting_for&&isTodayRaw(t)}
function isOverdueRaw(t){return !!t.due_at&&!t.completed&&new Date(t.due_at)<startOfToday()}
function isOverdue(t){return !t.waiting_for&&isOverdueRaw(t)}
function isWeek(t){
  if(!t.due_at||t.completed||t.waiting_for)return false;
  const d=new Date(t.due_at);return d>=startOfToday()&&d<=endOfWeek();
}
function filteredTasks(){
  return tasks.filter(t=>{
    if(activeFilter==='all')return !t.completed;
    if(activeFilter==='today')return isToday(t);
    if(activeFilter==='overdue')return isOverdue(t);
    if(activeFilter==='inbox')return !t.completed&&!t.waiting_for&&!t.due_at;
    if(activeFilter==='waiting')return !t.completed&&!!t.waiting_for;
    if(activeFilter==='week')return isWeek(t);
    if(activeFilter==='done')return t.completed;
    return true;
  });
}

function formatDate(v){
  if(!v)return 'Inbox';
  const d=new Date(v);
  const hasTime=!(d.getHours()===12&&d.getMinutes()===0);
  return new Intl.DateTimeFormat('de-DE',hasTime
    ?{weekday:'short',day:'2-digit',month:'2-digit',hour:'2-digit',minute:'2-digit'}
    :{weekday:'short',day:'2-digit',month:'2-digit'}).format(d);
}
function formatDateTime(v){
  if(!v)return '';
  return new Intl.DateTimeFormat('de-DE',{day:'2-digit',month:'2-digit',hour:'2-digit',minute:'2-digit'}).format(new Date(v));
}
function recurrenceLabel(v){
  return({daily:'Täglich',weekly:'Wöchentlich',monthly:'Monatlich'})[v]||'';
}
function taskArea(t){
  return t.area==='Arbeit'||t.category==='Arbeit'?'Arbeit':'Privat';
}

function isMobileLayout(){
  return window.matchMedia('(max-width: 780px)').matches;
}

function readWorkLocation(){
  try{
    const value=JSON.parse(localStorage.getItem(WORK_LOCATION_KEY)||'null');
    if(!value||!Number.isFinite(value.lat)||!Number.isFinite(value.lon))return null;
    const radius=Number(value.radius)||300;
    return{lat:value.lat,lon:value.lon,radius};
  }catch{
    return null;
  }
}

function saveWorkLocation(lat,lon,radius){
  const coarse=v=>Math.round(Number(v)*10000)/10000;
  localStorage.setItem(WORK_LOCATION_KEY,JSON.stringify({
    lat:coarse(lat),
    lon:coarse(lon),
    radius:Number(radius)||300,
    savedAt:new Date().toISOString()
  }));
}

function distanceMeters(lat1,lon1,lat2,lon2){
  const R=6371000;
  const toRad=v=>v*Math.PI/180;
  const dLat=toRad(lat2-lat1);
  const dLon=toRad(lon2-lon1);
  const a=Math.sin(dLat/2)**2+
    Math.cos(toRad(lat1))*Math.cos(toRad(lat2))*Math.sin(dLon/2)**2;
  return 2*R*Math.atan2(Math.sqrt(a),Math.sqrt(1-a));
}

function updateAreaModeButtons(){
  [...document.querySelectorAll('.area-mode')].forEach(btn=>{
    btn.classList.toggle('active',btn.dataset.areaMode===mobileAreaMode);
  });
}

function applyMobileAreaPreference(){
  const board=document.querySelector('.task-board');
  const workColumn=document.querySelector('.work-column');
  const privateColumn=document.querySelector('.private-column');
  const status=$('#locationContextStatus');
  const badge=$('#locationBadge');
  if(!board||!workColumn||!privateColumn||!status||!badge)return;

  board.classList.remove('prefer-work','prefer-private','manual-work','manual-private');
  workColumn.classList.remove('preferred');
  privateColumn.classList.remove('preferred');
  updateAreaModeButtons();

  if(mobileAreaMode==='work'){
    board.classList.add('manual-work');
    workColumn.classList.add('preferred');
    badge.textContent='Arbeit';
    status.textContent='Manuell auf Arbeit gestellt.';
    return;
  }

  if(mobileAreaMode==='private'){
    board.classList.add('manual-private');
    privateColumn.classList.add('preferred');
    badge.textContent='Privat';
    status.textContent='Manuell auf Privat gestellt.';
    return;
  }

  badge.textContent='Auto';
  const workLocation=readWorkLocation();
  if(!workLocation){
    status.textContent='Arbeitsort noch nicht festgelegt. Unter „Standorterkennung einstellen“ kannst du ihn einmal speichern.';
    return;
  }

  if(detectedMobileArea==='work'){
    board.classList.add('prefer-work');
    workColumn.classList.add('preferred');
    status.textContent='📍 Arbeitsort erkannt · Arbeit wird bevorzugt angezeigt.';
  }else if(detectedMobileArea==='private'){
    board.classList.add('prefer-private');
    privateColumn.classList.add('preferred');
    status.textContent='📍 Nicht am Arbeitsort · Privat wird bevorzugt angezeigt.';
  }else{
    status.textContent='Arbeitsort gespeichert · Standort wird beim Öffnen der App geprüft.';
  }
}

function setMobileAreaMode(mode){
  if(!['auto','work','private'].includes(mode))mode='auto';
  mobileAreaMode=mode;
  localStorage.setItem(MOBILE_AREA_MODE_KEY,mode);
  applyMobileAreaPreference();
  if(mode==='auto')checkWorkLocation(true);
}

function geolocationErrorText(error){
  if(!error)return 'Standort konnte nicht ermittelt werden.';
  if(error.code===1)return 'Standortzugriff wurde nicht erlaubt. Du kannst weiterhin manuell zwischen Arbeit und Privat umschalten.';
  if(error.code===2)return 'Der Standort ist momentan nicht verfügbar.';
  if(error.code===3)return 'Die Standortabfrage hat zu lange gedauert.';
  return 'Standort konnte nicht ermittelt werden.';
}

function checkWorkLocation(force=false){
  if(!isMobileLayout()||mobileAreaMode!=='auto'||locationCheckInFlight)return;
  const workLocation=readWorkLocation();
  if(!workLocation){
    detectedMobileArea=null;
    applyMobileAreaPreference();
    return;
  }
  if(!navigator.geolocation){
    detectedMobileArea=null;
    $('#locationContextStatus').textContent='Dieser Browser unterstützt keine Standorterkennung.';
    return;
  }

  locationCheckInFlight=true;
  navigator.geolocation.getCurrentPosition(position=>{
    locationCheckInFlight=false;
    const {latitude,longitude,accuracy}=position.coords;
    const distance=distanceMeters(latitude,longitude,workLocation.lat,workLocation.lon);
    const tolerance=Math.min(Number(accuracy)||0,100);
    detectedMobileArea=distance<=workLocation.radius+tolerance?'work':'private';
    applyMobileAreaPreference();
  },error=>{
    locationCheckInFlight=false;
    detectedMobileArea=null;
    applyMobileAreaPreference();
    $('#locationContextStatus').textContent=geolocationErrorText(error);
  },{
    enableHighAccuracy:false,
    timeout:10000,
    maximumAge:force?0:300000
  });
}

function startMobileLocationChecks(){
  if(mobileLocationTimer)clearInterval(mobileLocationTimer);
  applyMobileAreaPreference();
  checkWorkLocation();
  mobileLocationTimer=setInterval(()=>{
    if(document.visibilityState==='visible')checkWorkLocation();
  },10*60*1000);
}

function stopMobileLocationChecks(){
  if(mobileLocationTimer){
    clearInterval(mobileLocationTimer);
    mobileLocationTimer=null;
  }
}
function escapeHtml(s=''){return String(s).replace(/[&<>"']/g,m=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m]))}

function renderFocus(){
  const p={hoch:0,normal:2,niedrig:4};
  const candidates=tasks
    .filter(t=>!t.completed&&!t.waiting_for&&(isOverdueRaw(t)||isTodayRaw(t)))
    .sort((a,b)=>{
      const aScore=(isOverdueRaw(a)?0:10)+(p[a.priority]??2);
      const bScore=(isOverdueRaw(b)?0:10)+(p[b.priority]??2);
      if(aScore!==bScore)return aScore-bScore;
      return(a.due_at||'9999').localeCompare(b.due_at||'9999');
    })
    .slice(0,3);

  focusList.innerHTML='';
  if(!candidates.length){
    const e=document.createElement('div');
    e.className='empty';
    e.textContent='Für heute ist aktuell nichts Dringendes offen.';
    focusList.append(e);
    return;
  }
  candidates.forEach((t,i)=>{
    const item=document.createElement('div');
    item.className='focus-item';
    const n=document.createElement('div');n.className='focus-number';n.textContent='FOKUS '+(i+1);
    const title=document.createElement('div');title.className='focus-title';title.textContent=t.title;
    const meta=document.createElement('div');meta.className='focus-meta';
    meta.textContent=(taskArea(t)==='Arbeit'?'💼 Arbeit · ':'🏠 Privat · ')+(isOverdueRaw(t)?'Überfällig · ':'')+formatDate(t.due_at)+' · '+t.priority;
    item.append(n,title,meta);
    focusList.append(item);
  });
}

function createTaskRow(t){
  const row=document.createElement('article');
  row.className='task'+(t.completed?' done':'')+(t.waiting_for?' waiting':'');

  const cb=document.createElement('input');
  cb.type='checkbox';
  cb.checked=t.completed;
  cb.setAttribute('aria-label','Aufgabe erledigt');
  cb.addEventListener('change',()=>toggleTask(t,cb.checked));

  const body=document.createElement('div');
  const title=document.createElement('div');
  title.className='task-title';
  title.textContent=t.title;

  const meta=document.createElement('div');
  meta.className='meta';
  const addPill=(text,extraClass='')=>{
    const pill=document.createElement('span');
    pill.className='pill'+(extraClass?' '+extraClass:'');
    pill.textContent=text;
    meta.append(pill);
  };

  if(t.category&&t.category!=='Sonstiges'&&t.category!==taskArea(t))addPill(t.category);
  addPill(t.priority||'normal');
  addPill(formatDate(t.due_at));
  if(t.waiting_for)addPill('⏳ Warten auf','waiting');
  if(t.recurrence&&t.recurrence!=='none')addPill('↻ '+recurrenceLabel(t.recurrence));
  if(t.reminder_at&&!t.reminded_at)addPill('🔔 '+formatDateTime(t.reminder_at));

  body.append(title,meta);
  if(t.description){
    const desc=document.createElement('div');
    desc.className='meta';
    desc.textContent=t.description;
    body.append(desc);
  }

  const actions=document.createElement('div');
  actions.className='task-actions';
  if(!t.completed){
    const wait=document.createElement('button');
    wait.type='button';
    wait.className='ghost';
    wait.textContent=t.waiting_for?'Aktivieren':'Warten';
    wait.addEventListener('click',()=>toggleWaiting(t));
    actions.append(wait);
  }

  const del=document.createElement('button');
  del.type='button';
  del.className='danger';
  del.textContent='Löschen';
  del.addEventListener('click',()=>deleteTask(t));
  actions.append(del);

  row.append(cb,body,actions);
  return row;
}

function renderColumn(container,list,emptyText){
  container.innerHTML='';
  if(!list.length){
    const e=document.createElement('div');
    e.className='empty';
    e.textContent=emptyText;
    container.append(e);
    return;
  }
  for(const t of list)container.append(createTaskRow(t));
}

function render(){
  $('#statToday').textContent=tasks.filter(isToday).length;
  $('#statOverdue').textContent=tasks.filter(isOverdue).length;
  $('#statInbox').textContent=tasks.filter(t=>!t.completed&&!t.waiting_for&&!t.due_at).length;
  $('#statWaiting').textContent=tasks.filter(t=>!t.completed&&t.waiting_for).length;
  $('#statWeek').textContent=tasks.filter(isWeek).length;
  renderFocus();

  const list=filteredTasks().sort((a,b)=>{
    if(a.priority!==b.priority){
      const p={hoch:0,normal:1,niedrig:2};
      return(p[a.priority]??1)-(p[b.priority]??1);
    }
    return(a.due_at||'9999').localeCompare(b.due_at||'9999');
  });

  const workList=list.filter(t=>taskArea(t)==='Arbeit');
  const privateList=list.filter(t=>taskArea(t)==='Privat');

  $('#workCount').textContent=workList.length;
  $('#privateCount').textContent=privateList.length;

  renderColumn(workTaskList,workList,'Keine Arbeitsaufgaben in dieser Ansicht.');
  renderColumn(privateTaskList,privateList,'Keine privaten Aufgaben in dieser Ansicht.');
  applyMobileAreaPreference();
}

async function loadTasks(){
  const {data,error}=await db.from('tasks').select('*').order('created_at',{ascending:false});
  if(error){showMsg(taskMsg,'Fehler beim Laden: '+error.message+schemaHint(error),'error');return}
  tasks=data||[];
  render();
  await updateNotifyButton();
  checkReminders();
}

function nextOccurrence(baseIso,recurrence){
  const d=baseIso?new Date(baseIso):new Date();
  if(recurrence==='daily')d.setDate(d.getDate()+1);
  if(recurrence==='weekly')d.setDate(d.getDate()+7);
  if(recurrence==='monthly')d.setMonth(d.getMonth()+1);
  return d;
}
function buildNextRecurring(t){
  const base=t.due_at||new Date().toISOString();
  const nextDue=nextOccurrence(base,t.recurrence);
  let nextReminder=null;
  if(t.reminder_at&&t.due_at){
    const delta=new Date(t.due_at).getTime()-new Date(t.reminder_at).getTime();
    nextReminder=new Date(nextDue.getTime()-delta).toISOString();
  }else if(t.reminder_at){
    nextReminder=nextOccurrence(t.reminder_at,t.recurrence).toISOString();
  }
  return{
    title:t.title,
    description:t.description||null,
    category:t.category||'Sonstiges',
    area:taskArea(t),
    priority:t.priority||'normal',
    due_at:nextDue.toISOString(),
    reminder_at:nextReminder,
    recurrence:t.recurrence,
    waiting_for:false,
    source:t.source||'text'
  };
}

async function toggleTask(t,completed){
  if(completed&&!t.completed&&t.recurrence&&t.recurrence!=='none'&&!t.next_recurrence_created){
    const nextPayload=buildNextRecurring(t);
    const {data:next,error:insertError}=await db.from('tasks').insert(nextPayload).select().single();
    if(insertError){
      showMsg(taskMsg,'Nächste Wiederholung konnte nicht angelegt werden: '+insertError.message+schemaHint(insertError),'error');
      render();return;
    }
    const {error:updateError}=await db.from('tasks').update({completed:true,next_recurrence_created:true}).eq('id',t.id);
    if(updateError){
      await db.from('tasks').delete().eq('id',next.id);
      showMsg(taskMsg,updateError.message+schemaHint(updateError),'error');render();return;
    }
    t.completed=true;t.next_recurrence_created=true;
    tasks.unshift(next);
    render();
    return;
  }

  const {error}=await db.from('tasks').update({completed}).eq('id',t.id);
  if(error){showMsg(taskMsg,error.message+schemaHint(error),'error');render();return}
  t.completed=completed;
  render();
}

async function toggleWaiting(t){
  const waiting_for=!t.waiting_for;
  const {error}=await db.from('tasks').update({waiting_for}).eq('id',t.id);
  if(error){showMsg(taskMsg,error.message+schemaHint(error),'error');return}
  t.waiting_for=waiting_for;
  render();
}

async function deleteTask(t){
  if(!confirm('Aufgabe wirklich löschen?'))return;
  const {error}=await db.from('tasks').delete().eq('id',t.id);
  if(error){showMsg(taskMsg,error.message+schemaHint(error),'error');return}
  tasks=tasks.filter(x=>x.id!==t.id);render();
}

function nextWeekday(target){
  const today=startOfToday();
  const diff=(target-today.getDay()+7)%7;
  const d=new Date(today);d.setDate(d.getDate()+diff);return d;
}

function parseSmartTask(raw){
  const original=raw.trim();
  let working=original;
  const lower=original.toLocaleLowerCase('de-DE');
  let category='Sonstiges';
  let area='Privat';
  let priority='normal';
  let waiting_for=false;
  let recurrence='none';
  let due=null;
  let reminder_at=null;
  let hour=12,minute=0,timeFound=false;
  let timeToken=null;
  let reminderToken=null;

  if(/\b(js\s*wenau|wenau|verein|fußball|fussball)\b/i.test(lower))category='JS Wenau';
  else if(/\b(projekt|projekte)\b/i.test(lower))category='Projekte';
  else if(/\b(arbeit|firma|betrieb|maschine|anlage|produktion|wartung|instandhaltung|sps|codesys|menke|hydraulik|audit|lieferant|kunde|schicht)\b/i.test(lower))category='Arbeit';
  else if(/\b(privat|zuhause|haushalt|einkaufen|familie|garten|fahrrad|freizeit)\b/i.test(lower))category='Privat';

  const explicitPrivate=/\b(privat|persönlich|persoenlich)\b/i.test(lower);
  const explicitWork=/\b(arbeit|beruflich|dienstlich)\b/i.test(lower);
  const workContext=/\b(firma|betrieb|maschine|anlage|produktion|wartung|instandhaltung|sps|codesys|menke|hydraulik|audit|lieferant|kunde|schicht|werkzeug)\b/i.test(lower);
  const privateContext=/\b(zuhause|haushalt|einkaufen|familie|garten|fahrrad|freizeit|wenau|verein|fußball|fussball)\b/i.test(lower);

  if(explicitPrivate)area='Privat';
  else if(explicitWork)area='Arbeit';
  else if(workContext)area='Arbeit';
  else if(privateContext||category==='JS Wenau')area='Privat';
  else if(category==='Arbeit')area='Arbeit';

  if(/\b(hohe?n?\s+priorit[aä]t|priorit[aä]t\s+hoch|dringend|sehr\s+wichtig)\b/i.test(lower))priority='hoch';
  else if(/\b(niedrige?n?\s+priorit[aä]t|priorit[aä]t\s+niedrig|nicht\s+dringend)\b/i.test(lower))priority='niedrig';

  if(/\b(warten\s+auf|warte\s+auf|rückmeldung\s+von|antwort\s+von)\b/i.test(lower))waiting_for=true;

  if(/\b(täglich|jeden\s+tag)\b/i.test(lower))recurrence='daily';
  else if(/\b(wöchentlich|jede\s+woche)\b/i.test(lower)||/\bjeden\s+(montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag)\b/i.test(lower))recurrence='weekly';
  else if(/\b(monatlich|jeden\s+monat)\b/i.test(lower))recurrence='monthly';

  let timeMatch=lower.match(/\bum\s+([01]?\d|2[0-3])(?:[:.]([0-5]\d))?(?:\s*uhr)?\b/i);
  if(timeMatch){
    hour=Number(timeMatch[1]);minute=Number(timeMatch[2]||0);timeFound=true;timeToken=timeMatch[0];
  }else{
    timeMatch=lower.match(/\b([01]?\d|2[0-3]):([0-5]\d)(?:\s*uhr)?\b/i)
      ||lower.match(/\b([01]?\d|2[0-3])\s*uhr\b/i);
    if(timeMatch){
      hour=Number(timeMatch[1]);minute=Number(timeMatch[2]||0);timeFound=true;timeToken=timeMatch[0];
    }
  }

  const explicit=lower.match(/\b(0?[1-9]|[12]\d|3[01])\.(0?[1-9]|1[0-2])\.(\d{2,4})\b/);
  const shortDate=lower.match(/\b(0?[1-9]|[12]\d|3[01])\.(0?[1-9]|1[0-2])\.?\b/);

  if(/\bübermorgen\b/i.test(lower)){
    due=startOfToday();due.setDate(due.getDate()+2);
  }else if(/\bmorgen\b/i.test(lower)){
    due=startOfToday();due.setDate(due.getDate()+1);
  }else if(/\bheute\b/i.test(lower)){
    due=startOfToday();
  }else if(explicit){
    let y=Number(explicit[3]);if(y<100)y+=2000;
    due=new Date(y,Number(explicit[2])-1,Number(explicit[1]));
  }else if(shortDate){
    const now=new Date();const y=now.getFullYear();
    due=new Date(y,Number(shortDate[2])-1,Number(shortDate[1]));
    if(due<startOfToday())due.setFullYear(y+1);
  }else{
    const days=[['sonntag',0],['montag',1],['dienstag',2],['mittwoch',3],['donnerstag',4],['freitag',5],['samstag',6]];
    const hit=days.find(([name])=>new RegExp('\\b'+name+'\\b','i').test(lower));
    if(hit)due=nextWeekday(hit[1]);
  }

  if(due)due=atLocalTime(due,timeFound?hour:12,timeFound?minute:0);

  const reminderMatch=lower.match(/\b(?:erinnere?\s+mich|erinnerung)\s+(\d+)\s*(minuten?|min|stunden?|std)\s*(?:vorher|vor)\b/i);
  if(reminderMatch&&due){
    const amount=Number(reminderMatch[1]);
    const isHour=/stunden?|std/i.test(reminderMatch[2]);
    const ms=amount*(isHour?60:1)*60*1000;
    reminder_at=new Date(due.getTime()-ms);
    reminderToken=reminderMatch[0];
  }

  const removals=[
    /\b(hohe?n?\s+priorit[aä]t|priorit[aä]t\s+hoch|dringend|sehr\s+wichtig)\b/ig,
    /\b(niedrige?n?\s+priorit[aä]t|priorit[aä]t\s+niedrig|nicht\s+dringend)\b/ig,
    /\b(arbeit|beruflich|dienstlich|privat|persönlich|persoenlich)\b/ig,
    /\b(heute|morgen|übermorgen)\b/ig,
    /\b(jeden\s+tag|täglich|jede\s+woche|wöchentlich|jeden\s+monat|monatlich)\b/ig,
    /\bjeden\s+(montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag)\b/ig,
    /\b(montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag)\b/ig,
    /\b(0?[1-9]|[12]\d|3[01])\.(0?[1-9]|1[0-2])\.(\d{2,4})\b/g,
    /\b(0?[1-9]|[12]\d|3[01])\.(0?[1-9]|1[0-2])\.?\b/g,
    /(?:\bum\s*)?\b([01]?\d|2[0-3])(?:[:.]([0-5]\d))?\s*uhr\b/ig
  ];
  for(const r of removals)working=working.replace(r,' ');
  if(timeToken)working=working.replace(timeToken,' ');
  if(reminderToken)working=working.replace(reminderToken,' ');
  working=working
    .replace(/\b(am|um)\b(?=\s*[,.;-]|\s*$)/ig,' ')
    .replace(/\s*[,;]+\s*/g,' ')
    .replace(/\s{2,}/g,' ')
    .replace(/^[\s,.;:-]+|[\s,.;:-]+$/g,'')
    .trim();

  if(!working)working=original;
  working=working.charAt(0).toUpperCase()+working.slice(1);

  return{
    title:working,
    category,
    area,
    priority,
    due_at:due?due.toISOString():null,
    reminder_at:reminder_at?reminder_at.toISOString():null,
    waiting_for,
    recurrence,
    source:'text'
  };
}

function setPreviewPill(selector,text,visible){
  const el=$(selector);
  el.textContent=text;
  el.classList.toggle('hidden',!visible);
}
function updateSmartPreview(){
  const value=$('#smartInput').value.trim();
  const preview=$('#smartPreview');
  if(!value){preview.classList.add('hidden');hideMsg(smartMsg);return}
  const parsed=parseSmartTask(value);
  $('#previewTitle').textContent=parsed.title;
  $('#previewDue').textContent=formatDate(parsed.due_at);
  $('#previewArea').textContent=parsed.area==='Arbeit'?'💼 Arbeit':'🏠 Privat';
  $('#previewCategory').textContent=parsed.category;
  $('#previewPriority').textContent=parsed.priority==='hoch'?'Hohe Priorität':parsed.priority==='niedrig'?'Niedrige Priorität':'Normale Priorität';
  setPreviewPill('#previewState','⏳ Warten auf',parsed.waiting_for);
  setPreviewPill('#previewRecurrence','↻ '+recurrenceLabel(parsed.recurrence),parsed.recurrence!=='none');
  preview.classList.remove('hidden');
}

let smartInputMode='text';
let speechRecognition=null;
let speechBase='';
let speechProgrammatic=false;
const SpeechRecognitionApi=window.SpeechRecognition||window.webkitSpeechRecognition;
const smartInput=$('#smartInput');
const micBtn=$('#micBtn');
const micLabel=$('#micLabel');
const micStatus=$('#micStatus');

function setMicState(listening){
  micBtn.classList.toggle('listening',listening);
  micBtn.setAttribute('aria-label',listening?'Spracheingabe stoppen':'Spracheingabe starten');
  micLabel.textContent=listening?'Stopp':'Sprechen';
}
function showMicStatus(text,isError=false){
  micStatus.textContent=text;
  micStatus.classList.toggle('error',isError);
  micStatus.classList.remove('hidden');
}

if(!SpeechRecognitionApi){
  micBtn.disabled=true;
  micLabel.textContent='Nicht verfügbar';
  showMicStatus('Direkte Spracheingabe wird von diesem Browser nicht unterstützt. Das Mikrofon der Smartphone-Tastatur funktioniert weiterhin.');
}else{
  speechRecognition=new SpeechRecognitionApi();
  speechRecognition.lang='de-DE';
  speechRecognition.interimResults=true;
  speechRecognition.continuous=false;

  micBtn.addEventListener('click',()=>{
    if(micBtn.classList.contains('listening')){speechRecognition.stop();return}
    speechBase=smartInput.value.trim();
    try{speechRecognition.start()}
    catch(err){showMicStatus('Die Spracheingabe konnte nicht gestartet werden. Bitte kurz erneut versuchen.',true)}
  });
  speechRecognition.onstart=()=>{setMicState(true);showMicStatus('Ich höre zu … sprich deine Aufgabe.')};
  speechRecognition.onresult=event=>{
    let transcript='';
    for(let i=0;i<event.results.length;i++)transcript+=event.results[i][0].transcript;
    speechProgrammatic=true;
    smartInput.value=[speechBase,transcript.trim()].filter(Boolean).join(' ');
    speechProgrammatic=false;
    smartInputMode='voice';
    updateSmartPreview();
  };
  speechRecognition.onerror=event=>{
    setMicState(false);
    const messages={
      'not-allowed':'Mikrofonzugriff wurde nicht erlaubt. Bitte erlaube das Mikrofon für diese Seite in den Browser-Einstellungen.',
      'service-not-allowed':'Der Browser hat die Spracheingabe blockiert.',
      'audio-capture':'Es wurde kein verfügbares Mikrofon gefunden.',
      'no-speech':'Ich habe keine Sprache erkannt. Tippe auf das Mikrofon und versuche es erneut.',
      'network':'Die Spracherkennung hatte ein Netzwerkproblem.'
    };
    showMicStatus(messages[event.error]||('Spracheingabe fehlgeschlagen: '+event.error),true);
  };
  speechRecognition.onend=()=>{
    setMicState(false);
    if(smartInputMode==='voice'&&smartInput.value.trim())showMicStatus('Sprache erkannt. Prüfe kurz die Vorschau und speichere die Aufgabe.');
  };
}

smartInput.addEventListener('input',()=>{
  if(!speechProgrammatic)smartInputMode='text';
  updateSmartPreview();
});

$('#smartForm').addEventListener('submit',async e=>{
  e.preventDefault();hideMsg(smartMsg);
  const value=smartInput.value.trim();
  if(!value){showMsg(smartMsg,'Bitte zuerst eine Aufgabe eingeben.','error');return}
  const payload=parseSmartTask(value);
  payload.source=smartInputMode;
  const {data,error}=await db.from('tasks').insert(payload).select().single();
  if(error){showMsg(smartMsg,'Speichern fehlgeschlagen: '+error.message+schemaHint(error),'error');return}
  tasks.unshift(data);
  smartInput.value='';
  smartInputMode='text';
  $('#smartPreview').classList.add('hidden');
  micStatus.classList.add('hidden');
  showMsg(smartMsg,'Aufgabe gespeichert.','success');
  render();
  smartInput.focus();
});

$('#taskForm').addEventListener('submit',async e=>{
  e.preventDefault();hideMsg(taskMsg);
  const title=$('#taskTitle').value.trim();if(!title)return;
  const payload={
    title,
    description:$('#description').value.trim()||null,
    category:$('#category').value,
    area:$('#area').value,
    priority:$('#priority').value,
    due_at:dueForMode(),
    waiting_for:$('#waitingFor').checked,
    recurrence:$('#recurrence').value,
    reminder_at:localInputToIso($('#reminderAt').value),
    source:'text'
  };
  const {data,error}=await db.from('tasks').insert(payload).select().single();
  if(error){showMsg(taskMsg,'Speichern fehlgeschlagen: '+error.message+schemaHint(error),'error');return}
  tasks.unshift(data);
  e.target.reset();$('#dueDateWrap').classList.add('hidden');
  showMsg(taskMsg,'Aufgabe gespeichert.','success');
  render();$('#taskTitle').focus();
});

$('#dueMode').addEventListener('change',e=>$('#dueDateWrap').classList.toggle('hidden',e.target.value!=='date'));

$('.filter').forEach(btn=>btn.addEventListener('click',()=>{
  $('.filter').forEach(b=>b.classList.remove('active'));btn.classList.add('active');
  activeFilter=btn.dataset.filter;render();
}));

$('.area-mode').forEach(btn=>btn.addEventListener('click',()=>{
  setMobileAreaMode(btn.dataset.areaMode);
}));

$('#setWorkLocationBtn').addEventListener('click',()=>{
  hideMsg($('#locationMsg'));
  if(!navigator.geolocation){
    showMsg($('#locationMsg'),'Dieser Browser unterstützt keine Standorterkennung.','error');
    return;
  }

  $('#setWorkLocationBtn').disabled=true;
  $('#setWorkLocationBtn').textContent='Standort wird ermittelt …';

  navigator.geolocation.getCurrentPosition(position=>{
    $('#setWorkLocationBtn').disabled=false;
    $('#setWorkLocationBtn').textContent='Arbeitsort hier festlegen';

    const radius=Number($('#workRadius').value)||300;
    saveWorkLocation(position.coords.latitude,position.coords.longitude,radius);
    detectedMobileArea='work';
    mobileAreaMode='auto';
    localStorage.setItem(MOBILE_AREA_MODE_KEY,'auto');
    showMsg($('#locationMsg'),'Arbeitsort wurde nur auf diesem Smartphone gespeichert. Automatik ist aktiv.','success');
    applyMobileAreaPreference();
  },error=>{
    $('#setWorkLocationBtn').disabled=false;
    $('#setWorkLocationBtn').textContent='Arbeitsort hier festlegen';
    showMsg($('#locationMsg'),geolocationErrorText(error),'error');
  },{
    enableHighAccuracy:true,
    timeout:15000,
    maximumAge:0
  });
});

$('#clearWorkLocationBtn').addEventListener('click',()=>{
  localStorage.removeItem(WORK_LOCATION_KEY);
  detectedMobileArea=null;
  const msg=$('#locationMsg');
  showMsg(msg,'Der lokal gespeicherte Arbeitsort wurde gelöscht.','success');
  applyMobileAreaPreference();
});

$('#workRadius').addEventListener('change',()=>{
  const workLocation=readWorkLocation();
  if(!workLocation)return;
  saveWorkLocation(workLocation.lat,workLocation.lon,Number($('#workRadius').value)||300);
  checkWorkLocation(true);
});

const savedWorkLocation=readWorkLocation();
if(savedWorkLocation)$('#workRadius').value=String(savedWorkLocation.radius);
updateAreaModeButtons();

document.addEventListener('visibilitychange',()=>{
  if(document.visibilityState==='visible')checkWorkLocation(true);
});
window.addEventListener('focus',()=>checkWorkLocation());
window.matchMedia('(max-width: 780px)').addEventListener?.('change',()=>{
  applyMobileAreaPreference();
  checkWorkLocation();
});

function pushSupported(){
  return 'Notification'in window&&'serviceWorker'in navigator&&'PushManager'in window;
}
function urlBase64ToUint8Array(base64String){
  const padding='='.repeat((4-base64String.length%4)%4);
  const base64=(base64String+padding).replace(/-/g,'+').replace(/_/g,'/');
  const raw=atob(base64);
  return Uint8Array.from([...raw].map(ch=>ch.charCodeAt(0)));
}
async function savePushSubscription(subscription){
  const {data:{session}}=await db.auth.getSession();
  if(!session)throw new Error('Nicht angemeldet');
  const json=subscription.toJSON();
  const keys=json.keys||{};
  if(!json.endpoint||!keys.p256dh||!keys.auth)throw new Error('Push-Abo ist unvollständig');
  const {error}=await db.from('push_subscriptions').upsert({
    endpoint:json.endpoint,
    p256dh:keys.p256dh,
    auth:keys.auth,
    updated_at:new Date().toISOString()
  },{onConflict:'user_id,endpoint'});
  if(error)throw error;
}
function sameBytes(a,b){
  if(a.length!==b.length)return false;
  for(let i=0;i<a.length;i++)if(a[i]!==b[i])return false;
  return true;
}
async function ensurePushSubscription(){
  if(!pushSupported())throw new Error('Web Push wird von diesem Browser nicht unterstützt.');
  const registration=await navigator.serviceWorker.ready;
  const expectedKey=urlBase64ToUint8Array(VAPID_PUBLIC_KEY);
  let subscription=await registration.pushManager.getSubscription();

  if(subscription&&subscription.options&&subscription.options.applicationServerKey){
    const currentKey=new Uint8Array(subscription.options.applicationServerKey);
    if(!sameBytes(currentKey,expectedKey)){
      try{await db.from('push_subscriptions').delete().eq('endpoint',subscription.endpoint)}catch(e){}
      try{await subscription.unsubscribe()}catch(e){}
      subscription=null;
    }
  }

  if(!subscription){
    subscription=await registration.pushManager.subscribe({
      userVisibleOnly:true,
      applicationServerKey:expectedKey
    });
  }
  await savePushSubscription(subscription);
  return subscription;
}
async function removePushSubscription(){
  if(!pushSupported())return;
  const registration=await navigator.serviceWorker.ready;
  const subscription=await registration.pushManager.getSubscription();
  if(!subscription)return;
  try{
    await db.from('push_subscriptions').delete().eq('endpoint',subscription.endpoint);
  }catch(e){}
  try{await subscription.unsubscribe()}catch(e){}
}
async function updateNotifyButton(){
  const btn=$('#notifyBtn');
  if(!pushSupported()){
    btn.disabled=true;btn.textContent='🔕 Push nicht unterstützt';return;
  }
  btn.disabled=false;
  if(Notification.permission==='denied'){
    btn.textContent='🔕 Push blockiert';return;
  }
  if(Notification.permission!=='granted'){
    btn.textContent='🔔 Push aktivieren';return;
  }
  try{
    const registration=await navigator.serviceWorker.ready;
    const subscription=await registration.pushManager.getSubscription();
    btn.textContent=subscription?'🔔 Push aktiv':'🔔 Push verbinden';
  }catch{
    btn.textContent='🔔 Push verbinden';
  }
}
$('#notifyBtn').addEventListener('click',async()=>{
  if(!pushSupported()){
    reminderBanner.textContent='Dieser Browser unterstützt Web Push nicht.';
    reminderBanner.classList.remove('hidden');
    return;
  }
  try{
    let permission=Notification.permission;
    if(permission!=='granted')permission=await Notification.requestPermission();
    if(permission!=='granted'){
      reminderBanner.textContent='Benachrichtigungen wurden nicht erlaubt. Erinnerungen funktionieren weiterhin, solange das Dashboard geöffnet ist.';
      reminderBanner.classList.remove('hidden');
      await updateNotifyButton();
      return;
    }
    await ensurePushSubscription();
    await updateNotifyButton();
    reminderBanner.textContent='Push-Erinnerungen sind für dieses Gerät aktiviert – auch bei geschlossener App.';
    reminderBanner.classList.remove('hidden');
    setTimeout(()=>reminderBanner.classList.add('hidden'),4500);
    checkReminders();
  }catch(error){
    reminderBanner.textContent='Push konnte nicht aktiviert werden: '+(error.message||error)+schemaHint(error);
    reminderBanner.classList.remove('hidden');
  }
});

async function showReminder(task){
  const text='Erinnerung: '+task.title;
  reminderBanner.textContent=text;
  reminderBanner.classList.remove('hidden');

  if('Notification'in window&&Notification.permission==='granted'){
    try{
      if('serviceWorker'in navigator){
        const reg=await navigator.serviceWorker.ready;
        await reg.showNotification('Aufgaben-Erinnerung',{
          body:task.title,
          icon:'./icon.svg',
          badge:'./icon.svg',
          tag:'task-'+task.id
        });
      }else{
        new Notification('Aufgaben-Erinnerung',{body:task.title});
      }
    }catch(e){}
  }
}

async function checkReminders(){
  const now=Date.now();
  const due=tasks.filter(t=>!t.completed&&t.reminder_at&&!t.reminded_at&&new Date(t.reminder_at).getTime()<=now);
  for(const t of due){
    await showReminder(t);
    const stamp=new Date().toISOString();
    const {error}=await db.from('tasks').update({reminded_at:stamp}).eq('id',t.id);
    if(!error)t.reminded_at=stamp;
  }
  if(due.length)render();
}
function startReminderTimer(){
  if(reminderTimer)clearInterval(reminderTimer);
  reminderTimer=setInterval(checkReminders,30000);
}
function stopReminderTimer(){
  if(reminderTimer){clearInterval(reminderTimer);reminderTimer=null}
}

async function getVerifiedTotpFactor(){
  const {data,error}=await db.auth.mfa.listFactors();
  if(error)throw error;
  return (data?.totp||[]).find(f=>f.status==='verified')||null;
}

async function refreshSecurityStatus(){
  try{
    const factor=await getVerifiedTotpFactor();
    $('#mfaStatus').textContent=factor
      ? '2FA ist aktiv. Datenzugriffe erfordern nach dem Passwort zusätzlich einen Authenticator-Code.'
      : '2FA ist noch nicht eingerichtet. Für maximalen Kontoschutz solltest du sie aktivieren.';
    $('#enrollMfaBtn').classList.toggle('hidden',!!factor);
  }catch(error){
    $('#mfaStatus').textContent='Sicherheitsstatus konnte nicht geladen werden.';
  }
}

async function needsMfaChallenge(){
  const {data,error}=await db.auth.mfa.getAuthenticatorAssuranceLevel();
  if(error)throw error;
  return data?.currentLevel==='aal1'&&data?.nextLevel==='aal2';
}

async function verifyTotpCode(code){
  const factor=await getVerifiedTotpFactor();
  if(!factor)throw new Error('Kein verifizierter Authenticator-Faktor gefunden.');
  const {data:challenge,error:challengeError}=await db.auth.mfa.challenge({factorId:factor.id});
  if(challengeError)throw challengeError;
  const {error:verifyError}=await db.auth.mfa.verify({
    factorId:factor.id,
    challengeId:challenge.id,
    code
  });
  if(verifyError)throw verifyError;
}

$('#mfaChallengeForm').addEventListener('submit',async e=>{
  e.preventDefault();hideMsg(mfaChallengeMsg);
  const code=$('#mfaChallengeCode').value.trim();
  if(!/^\d{6}$/.test(code)){
    showMsg(mfaChallengeMsg,'Bitte einen 6-stelligen Code eingeben.','error');
    return;
  }
  try{
    await verifyTotpCode(code);
    $('#mfaChallengeCode').value='';
    const {data:{session}}=await db.auth.getSession();
    await syncSession(session);
  }catch(error){
    showMsg(mfaChallengeMsg,'Code ungültig oder abgelaufen. Bitte erneut versuchen.','error');
  }
});

$('#mfaLogoutBtn').addEventListener('click',async()=>{
  try{await removePushSubscription()}catch(e){}
  await db.auth.signOut();
});

function openSettings(){
  $('#settingsOverlay').classList.remove('hidden');
  document.body.classList.add('settings-open');
  $('#settingsBtn').setAttribute('aria-expanded','true');
  refreshSecurityStatus();
  applyMobileAreaPreference();
  updateNotifyButton();
}

function closeSettings(){
  $('#settingsOverlay').classList.add('hidden');
  document.body.classList.remove('settings-open');
  $('#settingsBtn').setAttribute('aria-expanded','false');
}

$('#settingsBtn').setAttribute('aria-expanded','false');
$('#settingsBtn').addEventListener('click',openSettings);
$('#closeSettingsBtn').addEventListener('click',closeSettings);
$('#settingsOverlay').addEventListener('click',event=>{
  if(event.target===$('#settingsOverlay'))closeSettings();
});
document.addEventListener('keydown',event=>{
  if(event.key==='Escape'&&!$('#settingsOverlay').classList.contains('hidden'))closeSettings();
});

$('#enrollMfaBtn').addEventListener('click',async()=>{
  hideMsg(securityMsg);
  try{
    const {data:factors,error:listError}=await db.auth.mfa.listFactors();
    if(listError)throw listError;
    for(const factor of (factors?.totp||[])){
      if(factor.status!=='verified'){
        try{await db.auth.mfa.unenroll({factorId:factor.id})}catch(e){}
      }
    }

    const {data,error}=await db.auth.mfa.enroll({
      factorType:'totp',
      friendlyName:'DashboardTasks'
    });
    if(error)throw error;

    pendingEnrollmentFactorId=data.id;
    $('#mfaQr').src=data.totp.qr_code;
    $('#mfaSecret').textContent=data.totp.secret;
    $('#mfaEnrollCode').value='';
    $('#mfaEnrollBox').classList.remove('hidden');
    $('#mfaEnrollCode').focus();
  }catch(error){
    showMsg(securityMsg,'2FA-Einrichtung konnte nicht gestartet werden: '+(error.message||error),'error');
  }
});

$('#mfaEnrollForm').addEventListener('submit',async e=>{
  e.preventDefault();hideMsg(securityMsg);
  const code=$('#mfaEnrollCode').value.trim();
  if(!pendingEnrollmentFactorId||!/^\d{6}$/.test(code)){
    showMsg(securityMsg,'Bitte einen gültigen 6-stelligen Code eingeben.','error');
    return;
  }

  try{
    const {data:challenge,error:challengeError}=await db.auth.mfa.challenge({
      factorId:pendingEnrollmentFactorId
    });
    if(challengeError)throw challengeError;

    const {error:verifyError}=await db.auth.mfa.verify({
      factorId:pendingEnrollmentFactorId,
      challengeId:challenge.id,
      code
    });
    if(verifyError)throw verifyError;

    pendingEnrollmentFactorId=null;
    $('#mfaEnrollBox').classList.add('hidden');
    $('#mfaQr').removeAttribute('src');
    $('#mfaSecret').textContent='';
    showMsg(securityMsg,'2FA ist aktiv. Künftige Anmeldungen benötigen zusätzlich deinen Authenticator-Code.','success');
    await refreshSecurityStatus();

    const {data:{session}}=await db.auth.getSession();
    await syncSession(session);
  }catch(error){
    showMsg(securityMsg,'Der Code konnte nicht bestätigt werden. Bitte prüfe ihn und versuche es erneut.','error');
  }
});

$('#loginForm').addEventListener('submit',async e=>{
  e.preventDefault();hideMsg(authMsg);
  const email=$('#email').value.trim(),password=$('#password').value;
  const {error}=await db.auth.signInWithPassword({email,password});
  if(error)showMsg(authMsg,'Anmeldung fehlgeschlagen. Bitte Zugangsdaten prüfen.','error');
});

$('#logoutBtn').addEventListener('click',async()=>{
  closeSettings();
  await removePushSubscription();
  await db.auth.signOut();
});

async function syncSession(session){
  const loggedIn=!!session;

  if(!loggedIn){
    $('#settingsOverlay')?.classList.add('hidden');
    document.body.classList.remove('settings-open');
    authView.classList.remove('hidden');
    mfaView.classList.add('hidden');
    appView.classList.add('hidden');
    stopReminderTimer();
    stopMobileLocationChecks();
    tasks=[];
    render();
    return;
  }

  authView.classList.add('hidden');

  try{
    if(await needsMfaChallenge()){
      mfaView.classList.remove('hidden');
      appView.classList.add('hidden');
      stopReminderTimer();
      tasks=[];
      render();
      $('#mfaChallengeCode').focus();
      return;
    }
  }catch(error){
    mfaView.classList.remove('hidden');
    appView.classList.add('hidden');
    stopReminderTimer();
    showMsg(mfaChallengeMsg,'Die Sicherheitsprüfung konnte nicht abgeschlossen werden. Bitte erneut anmelden.','error');
    return;
  }

  mfaView.classList.add('hidden');
  appView.classList.remove('hidden');
  await loadTasks();
  await refreshSecurityStatus();

  if(pushSupported()&&Notification.permission==='granted'){
    ensurePushSubscription().then(updateNotifyButton).catch(()=>updateNotifyButton());
  }
  startReminderTimer();
  startMobileLocationChecks();
}

db.auth.onAuthStateChange((_event,session)=>{
  setTimeout(()=>syncSession(session),0);
});

(async()=>{
  const {data:{session}}=await db.auth.getSession();
  if(session){
    const {data:{user},error}=await db.auth.getUser();
    if(error||!user){
      await db.auth.signOut();
      await syncSession(null);
      return;
    }
  }
  await syncSession(session);
})();

$('#todayLabel').textContent=new Intl.DateTimeFormat('de-DE',{weekday:'long',day:'2-digit',month:'long',year:'numeric'}).format(new Date());
updateNotifyButton();

if('serviceWorker'in navigator)window.addEventListener('load',()=>navigator.serviceWorker.register('./sw.js').catch(()=>{}));
