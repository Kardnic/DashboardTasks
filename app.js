const SUPABASE_URL='https://hfpryzswevnpmqdaidzj.supabase.co';
const SUPABASE_KEY='sb_publishable_odMpT_m3G4RihPHoaYFMKA_fz7NPVsy';
const db=supabase.createClient(SUPABASE_URL,SUPABASE_KEY);

const $=s=>document.querySelector(s);
const $$=s=>[...document.querySelectorAll(s)];
const authView=$('#authView'), appView=$('#appView');
const authMsg=$('#authMsg'), taskMsg=$('#taskMsg'), taskList=$('#taskList');
let tasks=[], activeFilter='today';

function showMsg(el,msg,type=''){
  el.textContent=msg; el.className='notice '+type;
  el.classList.remove('hidden');
}
function hideMsg(el){el.classList.add('hidden')}

function startOfToday(){ const d=new Date(); d.setHours(0,0,0,0); return d; }
function endOfToday(){ const d=new Date(); d.setHours(23,59,59,999); return d; }
function endOfWeek(){ const d=endOfToday(); d.setDate(d.getDate()+7); return d; }
function toIsoDateLocal(date){ return new Date(date.getFullYear(),date.getMonth(),date.getDate(),12,0,0).toISOString(); }

function dueForMode(){
  const mode=$('#dueMode').value;
  if(mode==='inbox') return null;
  const d=startOfToday();
  if(mode==='tomorrow') d.setDate(d.getDate()+1);
  if(mode==='date'){
    const v=$('#dueDate').value;
    if(!v) return null;
    const [y,m,day]=v.split('-').map(Number);
    return toIsoDateLocal(new Date(y,m-1,day));
  }
  return toIsoDateLocal(d);
}

function isToday(t){
  if(!t.due_at || t.completed) return false;
  const d=new Date(t.due_at); return d>=startOfToday() && d<=endOfToday();
}
function isOverdue(t){
  return !!t.due_at && !t.completed && new Date(t.due_at)<startOfToday();
}
function isWeek(t){
  if(!t.due_at || t.completed) return false;
  const d=new Date(t.due_at); return d>=startOfToday() && d<=endOfWeek();
}
function filteredTasks(){
  return tasks.filter(t=>{
    if(activeFilter==='all') return !t.completed;
    if(activeFilter==='today') return isToday(t);
    if(activeFilter==='overdue') return isOverdue(t);
    if(activeFilter==='inbox') return !t.completed && !t.due_at;
    if(activeFilter==='week') return isWeek(t);
    if(activeFilter==='done') return t.completed;
    return true;
  });
}

function formatDate(v){
  if(!v) return 'Inbox';
  return new Intl.DateTimeFormat('de-DE',{weekday:'short',day:'2-digit',month:'2-digit'}).format(new Date(v));
}

function render(){
  $('#statToday').textContent=tasks.filter(isToday).length;
  $('#statOverdue').textContent=tasks.filter(isOverdue).length;
  $('#statInbox').textContent=tasks.filter(t=>!t.completed&&!t.due_at).length;
  $('#statWeek').textContent=tasks.filter(isWeek).length;

  const list=filteredTasks().sort((a,b)=>{
    if(a.priority!==b.priority){
      const p={hoch:0,normal:1,niedrig:2}; return p[a.priority]-p[b.priority];
    }
    return (a.due_at||'9999').localeCompare(b.due_at||'9999');
  });
  taskList.innerHTML='';
  if(!list.length){
    const e=document.createElement('div'); e.className='empty'; e.textContent='Keine Aufgaben in dieser Ansicht.'; taskList.append(e); return;
  }
  for(const t of list){
    const row=document.createElement('article'); row.className='task'+(t.completed?' done':'');
    const cb=document.createElement('input'); cb.type='checkbox'; cb.checked=t.completed; cb.setAttribute('aria-label','Aufgabe erledigt');
    cb.addEventListener('change',()=>toggleTask(t,cb.checked));
    const body=document.createElement('div');
    const title=document.createElement('div'); title.className='task-title'; title.textContent=t.title;
    const meta=document.createElement('div'); meta.className='meta';
    meta.innerHTML='<span class="pill">'+escapeHtml(t.category)+'</span><span class="pill">'+escapeHtml(t.priority)+'</span><span class="pill">'+escapeHtml(formatDate(t.due_at))+'</span>';
    body.append(title,meta);
    if(t.description){
      const desc=document.createElement('div'); desc.className='meta'; desc.textContent=t.description; body.append(desc);
    }
    const actions=document.createElement('div'); actions.className='task-actions';
    const del=document.createElement('button'); del.type='button'; del.className='danger'; del.textContent='Löschen';
    del.addEventListener('click',()=>deleteTask(t));
    actions.append(del);
    row.append(cb,body,actions); taskList.append(row);
  }
}
function escapeHtml(s=''){return String(s).replace(/[&<>"']/g,m=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m]))}

async function loadTasks(){
  const {data,error}=await db.from('tasks').select('*').order('created_at',{ascending:false});
  if(error){ showMsg(taskMsg,'Fehler beim Laden: '+error.message,'error'); return; }
  tasks=data||[]; render();
}
async function toggleTask(t,completed){
  const {error}=await db.from('tasks').update({completed}).eq('id',t.id);
  if(error){showMsg(taskMsg,error.message,'error'); return}
  t.completed=completed; render();
}
async function deleteTask(t){
  if(!confirm('Aufgabe wirklich löschen?')) return;
  const {error}=await db.from('tasks').delete().eq('id',t.id);
  if(error){showMsg(taskMsg,error.message,'error');return}
  tasks=tasks.filter(x=>x.id!==t.id); render();
}

$('#taskForm').addEventListener('submit',async e=>{
  e.preventDefault(); hideMsg(taskMsg);
  const title=$('#taskTitle').value.trim(); if(!title)return;
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
  e.target.reset(); $('#dueDateWrap').classList.add('hidden');
  showMsg(taskMsg,'Aufgabe gespeichert.','success');
  render(); $('#taskTitle').focus();
});

$('#dueMode').addEventListener('change',e=>{
  $('#dueDateWrap').classList.toggle('hidden',e.target.value!=='date');
});

$$('.filter').forEach(btn=>btn.addEventListener('click',()=>{
  $$('.filter').forEach(b=>b.classList.remove('active')); btn.classList.add('active');
  activeFilter=btn.dataset.filter; render();
}));

$('#loginForm').addEventListener('submit',async e=>{
  e.preventDefault(); hideMsg(authMsg);
  const email=$('#email').value.trim(), password=$('#password').value;
  const {error}=await db.auth.signInWithPassword({email,password});
  if(error) showMsg(authMsg,error.message,'error');
});

$('#signupBtn').addEventListener('click',async()=>{
  hideMsg(authMsg);
  const email=$('#email').value.trim(), password=$('#password').value;
  if(!email||password.length<6){showMsg(authMsg,'E-Mail eingeben und ein Passwort mit mindestens 6 Zeichen wählen.','error');return}
  const {error}=await db.auth.signUp({email,password});
  if(error) showMsg(authMsg,error.message,'error');
  else showMsg(authMsg,'Konto angelegt. Falls E-Mail-Bestätigung aktiviert ist, bestätige zuerst die Mail.','success');
});

$('#logoutBtn').addEventListener('click',()=>db.auth.signOut());

async function syncSession(session){
  const loggedIn=!!session;
  authView.classList.toggle('hidden',loggedIn);
  appView.classList.toggle('hidden',!loggedIn);
  if(loggedIn) await loadTasks(); else {tasks=[];render()}
}

db.auth.onAuthStateChange((_event,session)=>syncSession(session));
db.auth.getSession().then(({data})=>syncSession(data.session));

$('#todayLabel').textContent=new Intl.DateTimeFormat('de-DE',{weekday:'long',day:'2-digit',month:'long',year:'numeric'}).format(new Date());

if('serviceWorker' in navigator) window.addEventListener('load',()=>navigator.serviceWorker.register('./sw.js').catch(()=>{}));
