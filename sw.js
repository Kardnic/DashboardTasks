const CACHE='dashboardtasks-v7-settings';
const ASSETS=['./','./index.html','./styles.css','./app.js','./manifest.webmanifest','./icon.svg'];

self.addEventListener('install',event=>{
  event.waitUntil(caches.open(CACHE).then(cache=>cache.addAll(ASSETS)));
  self.skipWaiting();
});

self.addEventListener('activate',event=>{
  event.waitUntil(
    caches.keys()
      .then(keys=>Promise.all(keys.filter(key=>key!==CACHE).map(key=>caches.delete(key))))
      .then(()=>self.clients.claim())
  );
});

self.addEventListener('fetch',event=>{
  if(event.request.method!=='GET')return;
  event.respondWith(
    fetch(event.request)
      .then(response=>{
        const copy=response.clone();
        caches.open(CACHE).then(cache=>cache.put(event.request,copy)).catch(()=>{});
        return response;
      })
      .catch(()=>caches.match(event.request).then(r=>r||caches.match('./index.html')))
  );
});

self.addEventListener('push',event=>{
  let payload={};
  try{
    payload=event.data?event.data.json():{};
  }catch{
    payload={body:event.data?event.data.text():'Eine Aufgabe ist fällig.'};
  }

  const title=payload.title||'Aufgaben-Erinnerung';
  const options={
    body:payload.body||'Eine Aufgabe ist fällig.',
    icon:'./icon.svg',
    badge:'./icon.svg',
    tag:payload.tag||'dashboardtasks-reminder',
    data:{
      taskId:payload.taskId||null,
      url:self.registration.scope
    }
  };

  event.waitUntil(self.registration.showNotification(title,options));
});

self.addEventListener('notificationclick',event=>{
  event.notification.close();
  const targetUrl=(event.notification.data&&event.notification.data.url)||self.registration.scope;

  event.waitUntil((async()=>{
    const windows=await self.clients.matchAll({type:'window',includeUncontrolled:true});
    for(const client of windows){
      if(client.url.startsWith(self.registration.scope)&&'focus'in client){
        await client.focus();
        return;
      }
    }
    if(self.clients.openWindow)await self.clients.openWindow(targetUrl);
  })());
});
