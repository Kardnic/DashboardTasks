const SUPABASE_URL='https://hfpryzswevnpmqdaidzj.supabase.co';
const SUPABASE_KEY='sb_publishable_odMpT_m3G4RihPHoaYFMKA_fz7NPVsy';
const db=supabase.createClient(SUPABASE_URL,SUPABASE_KEY);

const $=s=>document.querySelector(s);
const $$=s=>[...document.querySelectorAll(s)];
const authView=$('#authView'), appView=$('#appView');
const authMsg=$('#authMsg'), taskMsg=$('#taskMsg'), smartMsg=$('#smartMsg'), taskList=$('#taskList');
let tasks=[], activeFilter='today';

function showMsg(el,msg,type=''){
  el.textContent=msg; el.className='notice '+type;
  el.classList.remove('hidden');
}
function hideMsg(el){el.classList.add('hidden')}

function startOfToday(){const d=new Date();d.setHours(0,0,0,0);return d}
function endOfToday(){const d=new Date();d.setHours(23,59,59,999);return d}
function endOfWeek(){const d=endOfToday();d.setDate(d.getDate()+7);return d}
function atLocalTime(date,hour=12,minute=0){const d=new Date(date);d.setHours(hour,minute,0,0);return d}
function toIsoDateLocal(date){return atLocalTime(date,12,0).toISOString()}

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

function isToday(t){
  if(!t.due_at||t.completed)return false;
  const d=new Date(t.due_at);return d>=startOfToday()&&d<=endOfToday();
}
function isOverdue(t){return !!t.due_at&&!t.completed&&new Date(t.due_at)<startOfToday()}
function isWeek(t){
  if(!t.due_at||t.completed)return false;
  const d=new Date(t.due_at);return d>=startOfToday()&&d<=endOfWeek();
}
function filteredTasks(){
  return tasks.filter(t=>{
    if(activeFilter==='all')return !t.completed;
    if(activeFilter==='today')return isToday(t);
    if(activeFilter==='overdue')return isOverdue(t);
    if(activeFilter==='inbox')return !t.completed&&!t.due_at;
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

function render(){
  $('#statToday').textContent=tasks.filter(isToday).length;
  $('#statOverdue').textContent=tasks.filter(isOverdue).length;
  $('#statInbox').textContent=tasks.filter(t=>!t.completed&&!t.due_at).length;
  $('#statWeek').textContent=tasks.filter(isWeek).length;

  const list=filteredTasks().sort((a,b)=>{
    if(a.priority!==b.priority){
      const p={hoch:0,normal:1,niedrig:2};return p[a.priority]-p[b.priority];
    }
    return(a.due_at||'9999').localeCompare(b.due_at||'9999');
  });
  taskList.innerHTML='';
  if(!list.length){
    const e=document.createElement('div');e.className='empty';e.textContent='Keine Aufgaben in dieser Ansicht.';taskList.append(e);return;
  }
  for(const t of list){
    const row=document.createElement('article');row.className='task'+(t.completed?' done':'');
    const cb=document.createElement('input');cb.type='checkbox';cb.checked=t.completed;cb.setAttribute('aria-label','Aufgabe erledigt');
    cb.addEventListener('change',()=>toggleTask(t,cb.checked));
    const body=document.createElement('div');
    const title=document.createElement('div');title.className='task-title';title.textContent=t.title;
    const meta=document.createElement('div');meta.className='meta';
    meta.innerHTML='<span class="pill">'+escapeHtml(t.category)+'</span><span class="pill">'+escapeHtml(t.priority)+'</span><span class="pill">'+escapeHtml(formatDate(t.due_at))+'</span>';
    body.append(title,meta);
    if(t.description){
      const desc=document.createElement('div');desc.className='meta';desc.textContent=t.description;body.append(desc);
    }
    const actions=document.createElement('div');actions.className='task-actions';
    const del=document.createElement('button');del.type='button';del.className='danger';del.textContent='Löschen';
    del.addEventListener('click',()=>deleteTask(t));
    actions.append(del);
    row.append(cb,body,actions);taskList.append(row);
  }
}
function escapeHtml(s=''){return String(s).replace(/[&<>"']/g,m=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m]))}

async function loadTasks(){
  const {data,error}=await db.from('tasks').select('*').order('created_at',{ascending:false});
  if(error){showMsg(taskMsg,'Fehler beim Laden: '+error.message,'error');return}
  tasks=data||[];render();
}
async function toggleTask(t,completed){
  const {error}=await db.from('tasks').update({completed}).eq('id',t.id);
  if(error){showMsg(taskMsg,error.message,'error');return}
  t.completed=completed;render();
}
async function deleteTask(t){
  if(!confirm('Aufgabe wirklich löschen?'))return;
  const {error}=await db.from('tasks').delete().eq('id',t.id);
  if(error){showMsg(taskMsg,error.message,'error');return}
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
  let priority='normal';
  let due=null;
  let hour=12,minute=0,timeFound=false;

  if(/\b(js\s*wenau|wenau)\b/i.test(lower))category='JS Wenau';
  else if(/\b(arbeit|firma|betrieb|maschine)\b/i.test(lower))category='Arbeit';
  else if(/\b(privat|zuhause|haushalt)\b/i.test(lower))category='Privat';
  else if(/\b(projekt|projekte)\b/i.test(lower))category='Projekte';

  if(/\b(hohe?n?\s+priorit[aä]t|priorit[aä]t\s+hoch|dringend|sehr\s+wichtig)\b/i.test(lower))priority='hoch';
  else if(/\b(niedrige?n?\s+priorit[aä]t|priorit[aä]t\s+niedrig|nicht\s+dringend)\b/i.test(lower))priority='niedrig';

  const timeMatch=lower.match(/(?:\bum\s*)?\b([01]?\d|2[0-3])(?:[:.]([0-5]\d))?\s*(?:uhr)?\b/i);
  if(timeMatch&&(/uhr/i.test(timeMatch[0])||/\bum\s/i.test(timeMatch[0])||timeMatch[2])){
    hour=Number(timeMatch[1]);minute=Number(timeMatch[2]||0);timeFound=true;
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
    const now=new Date();let y=now.getFullYear();
    due=new Date(y,Number(shortDate[2])-1,Number(shortDate[1]));
    if(due<startOfToday())due.setFullYear(y+1);
  }else{
    const days=[['sonntag',0],['montag',1],['dienstag',2],['mittwoch',3],['donnerstag',4],['freitag',5],['samstag',6]];
    const hit=days.find(([name])=>new RegExp('\\b'+name+'\\b','i').test(lower));
    if(hit)due=nextWeekday(hit[1]);
  }

  if(due)due=atLocalTime(due,timeFound?hour:12,timeFound?minute:0);

  const removals=[
    /\b(hohe?n?\s+priorit[aä]t|priorit[aä]t\s+hoch|dringend|sehr\s+wichtig)\b/ig,
    /\b(niedrige?n?\s+priorit[aä]t|priorit[aä]t\s+niedrig|nicht\s+dringend)\b/ig,
    /\b(js\s*wenau|wenau|arbeit|privat|projekte?|sonstiges)\b/ig,
    /\b(heute|morgen|übermorgen)\b/ig,
    /\b(montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag)\b/ig,
    /\b(0?[1-9]|[12]\d|3[01])\.(0?[1-9]|1[0-2])\.(\d{2,4})\b/g,
    /\b(0?[1-9]|[12]\d|3[01])\.(0?[1-9]|1[0-2])\.?\b/g,
    /(?:\bum\s*)?\b([01]?\d|2[0-3])(?:[:.]([0-5]\d))?\s*uhr\b/ig
  ];
  for(const r of removals)working=working.replace(r,' ');
  working=working
    .replace(/\b(am|um)\b(?=\s*[,.;-]|\s*$)/ig,' ')
    .replace(/\s*[,;]+\s*/g,' ')
    .replace(/\s{2,}/g,' ')
    .replace(/^[\s,.;:-]+|[\s,.;:-]+$/g,'')
    .trim();

  if(!working)working=original;
  working=working.charAt(0).toUpperCase()+working.slice(1);

  return{title:working,category,priority,due_at:due?due.toISOString():null,source:'text'};
}

function updateSmartPreview(){
  const value=$('#smartInput').value.trim();
  const preview=$('#smartPreview');
  if(!value){preview.classList.add('hidden');hideMsg(smartMsg);return}
  const parsed=parseSmartTask(value);
  $('#previewTitle').textContent=parsed.title;
  $('#previewDue').textContent=formatDate(parsed.due_at);
  $('#previewCategory').textContent=parsed.category;
  $('#previewPriority').textContent=parsed.priority==='hoch'?'Hohe Priorität':parsed.priority==='niedrig'?'Niedrige Priorität':'Normale Priorität';
  preview.classList.remove('hidden');
}

$('#smartInput').addEventListener('input',updateSmartPreview);
$('#smartForm').addEventListener('submit',async e=>{
  e.preventDefault();hideMsg(smartMsg);
  const value=$('#smartInput').value.trim();
  if(!value){showMsg(smartMsg,'Bitte zuerst eine Aufgabe eingeben.','error');return}
  const payload=parseSmartTask(value);
  const {data,error}=await db.from('tasks').insert(payload).select().single();
  if(error){showMsg(smartMsg,'Speichern fehlgeschlagen: '+error.message,'error');return}
  tasks.unshift(data);
  $('#smartInput').value='';
  $('#smartPreview').classList.add('hidden');
  showMsg(smartMsg,'Aufgabe gespeichert.','success');
  render();
  $('#smartInput').focus();
});

$('#taskForm').addEventListener('submit',async e=>{
  e.preventDefault();hideMsg(taskMsg);
  const title=$('#taskTitle').value.trim();if(!title)return;
  const payload={
    title,
    description:$('#description').value.trim()||null,
    category:$('#category').value,
    priority:$('#priority').value,
    due_at:dueForMode(),
    source:'text'
  };
  const {data,error}=await db.from('tasks').insert(payload).select().single();
  if(error){showMsg(taskMsg,'Speichern fehlgeschlagen: '+error.message,'error');return}
  tasks.unshift(data);
  e.target.reset();$('#dueDateWrap').classList.add('hidden');
  showMsg(taskMsg,'Aufgabe gespeichert.','success');
  render();$('#taskTitle').focus();
});

$('#dueMode').addEventListener('change',e=>{
  $('#dueDateWrap').classList.toggle('hidden',e.target.value!=='date');
});

$$('.filter').forEach(btn=>btn.addEventListener('click',()=>{
  $$('.filter').forEach(b=>b.classList.remove('active'));btn.classList.add('active');
  activeFilter=btn.dataset.filter;render();
}));

$('#loginForm').addEventListener('submit',async e=>{
  e.preventDefault();hideMsg(authMsg);
  const email=$('#email').value.trim(),password=$('#password').value;
  const {error}=await db.auth.signInWithPassword({email,password});
  if(error)showMsg(authMsg,error.message,'error');
});

$('#signupBtn').addEventListener('click',async()=>{
  hideMsg(authMsg);
  const email=$('#email').value.trim(),password=$('#password').value;
  if(!email||password.length<6){showMsg(authMsg,'E-Mail eingeben und ein Passwort mit mindestens 6 Zeichen wählen.','error');return}
  const {error}=await db.auth.signUp({email,password});
  if(error)showMsg(authMsg,error.message,'error');
  else showMsg(authMsg,'Konto angelegt. Falls E-Mail-Bestätigung aktiviert ist, bestätige zuerst die Mail.','success');
});

$('#logoutBtn').addEventListener('click',()=>db.auth.signOut());

async function syncSession(session){
  const loggedIn=!!session;
  authView.classList.toggle('hidden',loggedIn);
  appView.classList.toggle('hidden',!loggedIn);
  if(loggedIn)await loadTasks();else{tasks=[];render()}
}

db.auth.onAuthStateChange((_event,session)=>syncSession(session));
db.auth.getSession().then(({data})=>syncSession(data.session));

$('#todayLabel').textContent=new Intl.DateTimeFormat('de-DE',{weekday:'long',day:'2-digit',month:'long',year:'numeric'}).format(new Date());

if('serviceWorker'in navigator)window.addEventListener('load',()=>navigator.serviceWorker.register('./sw.js').catch(()=>{}));
