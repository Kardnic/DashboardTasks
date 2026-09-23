const SW_VERSION='20260923-1120';

self.addEventListener('install',event=>{
  self.skipWaiting();
});

self.addEventListener('activate',event=>{
  event.waitUntil((async()=>{
    const keys=await caches.keys();
    await Promise.all(keys.map(key=>caches.delete(key)));
    await self.clients.claim();

    const clients=await self.clients.matchAll({type:'window',includeUncontrolled:true});
    for(const client of clients){
      try{
        const url=new URL(client.url);
        if(url.origin===self.location.origin){
          url.searchParams.set('appv',SW_VERSION);
          await client.navigate(url.href);
        }
      }catch(e){}
    }
  })());
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
