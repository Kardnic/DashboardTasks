import { createClient } from "npm:@supabase/supabase-js@2";
import webpush from "npm:web-push@3.6.7";

const VAPID_PUBLIC_KEY = "BLi3pd0LO6c-v87cuQ9Htd1vtRGegpYUBS6WHSqn2oh0DP0ABFE9BW2shmu3hp5L9lSJ_VLExI-MxAc5O5kANrA";

const publishableKeys = JSON.parse(Deno.env.get("SUPABASE_PUBLISHABLE_KEYS") ?? "{}");
const validPublishableKeys = new Set(Object.values(publishableKeys));

const secretKeys = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") ?? "{}");
const serviceKey = secretKeys.default ?? Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
if (!serviceKey) throw new Error("Supabase admin key unavailable");

const supabase = createClient(
  Deno.env.get("SUPABASE_URL")!,
  serviceKey,
  { auth: { persistSession: false } }
);

Deno.serve(async (req) => {
  const apiKey = req.headers.get("apikey");
  if (!apiKey || !validPublishableKeys.has(apiKey)) {
    return new Response("Unauthorized", { status: 401 });
  }

  const { data: vapidPrivateKey, error: vapidError } = await supabase.rpc("get_vapid_private");
  if (vapidError || !vapidPrivateKey) {
    return Response.json(
      { error: vapidError?.message ?? "VAPID private key unavailable", vapidConfigured: false },
      { status: 503 }
    );
  }

  webpush.setVapidDetails(
    "https://github.com/Kardnic/DashboardTasks",
    VAPID_PUBLIC_KEY,
    vapidPrivateKey
  );

  const now = new Date();
  const oneDayAgo = new Date(now.getTime() - 24 * 60 * 60 * 1000);

  const { data: tasks, error: taskError } = await supabase
    .from("tasks")
    .select("id,user_id,title,reminder_at")
    .eq("completed", false)
    .not("reminder_at", "is", null)
    .is("reminded_at", null)
    .lte("reminder_at", now.toISOString())
    .gte("reminder_at", oneDayAgo.toISOString())
    .limit(100);

  if (taskError) {
    return Response.json({ error: taskError.message, vapidConfigured: true }, { status: 500 });
  }

  let notificationsSent = 0;
  let tasksMarked = 0;
  let expiredSubscriptionsRemoved = 0;

  for (const task of tasks ?? []) {
    const { data: subscriptions, error: subError } = await supabase
      .from("push_subscriptions")
      .select("id,endpoint,p256dh,auth")
      .eq("user_id", task.user_id);

    if (subError) continue;

    let delivered = false;
    for (const sub of subscriptions ?? []) {
      try {
        await webpush.sendNotification(
          { endpoint: sub.endpoint, keys: { p256dh: sub.p256dh, auth: sub.auth } },
          JSON.stringify({
            title: "Aufgaben-Erinnerung",
            body: task.title,
            tag: "task-" + task.id,
            taskId: task.id
          }),
          { TTL: 3600 }
        );
        notificationsSent++;
        delivered = true;
      } catch (error) {
        const statusCode =
          typeof error === "object" && error && "statusCode" in error
            ? Number(error.statusCode)
            : 0;

        if (statusCode === 404 || statusCode === 410) {
          await supabase.from("push_subscriptions").delete().eq("id", sub.id);
          expiredSubscriptionsRemoved++;
        } else {
          console.error("Push failed", task.id, statusCode, error);
        }
      }
    }

    if (delivered) {
      const { error: markError } = await supabase
        .from("tasks")
        .update({ reminded_at: new Date().toISOString() })
        .eq("id", task.id)
        .is("reminded_at", null);
      if (!markError) tasksMarked++;
    }
  }

  return Response.json({
    vapidConfigured: true,
    checked: tasks?.length ?? 0,
    notificationsSent,
    tasksMarked,
    expiredSubscriptionsRemoved
  });
});