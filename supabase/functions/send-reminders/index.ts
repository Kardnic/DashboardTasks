import { createClient } from "npm:@supabase/supabase-js@2";
import webpush from "npm:web-push@3.6.7";

const VAPID_PUBLIC_KEY = "BGf1lCSlipuQpSXW6LL6WEMc_xMuI5IdNajm5qGbEW1Z7RlIN4t_YvEbR3sZTA5Ti1AM8Bk5o0D22enP0uYFxmQ";
const VAPID_PRIVATE_KEY = Deno.env.get("VAPID_PRIVATE_KEY");

if (!VAPID_PRIVATE_KEY) {
  throw new Error("VAPID_PRIVATE_KEY is not configured");
}

webpush.setVapidDetails(
  "https://github.com/Kardnic/DashboardTasks",
  VAPID_PUBLIC_KEY,
  VAPID_PRIVATE_KEY,
);

const secretKeys = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") ?? "{}");
const serviceKey =
  secretKeys.default ??
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");

if (!serviceKey) {
  throw new Error("Supabase secret key is unavailable");
}

const supabase = createClient(
  Deno.env.get("SUPABASE_URL")!,
  serviceKey,
  { auth: { persistSession: false } },
);

type Task = {
  id: string;
  user_id: string;
  title: string;
  reminder_at: string | null;
};

Deno.serve(async () => {
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
    return Response.json({ error: taskError.message }, { status: 500 });
  }

  let notificationsSent = 0;
  let tasksMarked = 0;
  let expiredSubscriptionsRemoved = 0;

  for (const task of (tasks ?? []) as Task[]) {
    const { data: subscriptions, error: subError } = await supabase
      .from("push_subscriptions")
      .select("id,endpoint,p256dh,auth")
      .eq("user_id", task.user_id);

    if (subError) continue;

    let delivered = false;

    for (const sub of subscriptions ?? []) {
      try {
        await webpush.sendNotification(
          {
            endpoint: sub.endpoint,
            keys: { p256dh: sub.p256dh, auth: sub.auth },
          },
          JSON.stringify({
            title: "Aufgaben-Erinnerung",
            body: task.title,
            tag: "task-" + task.id,
            taskId: task.id,
          }),
          { TTL: 60 * 60 },
        );
        notificationsSent++;
        delivered = true;
      } catch (error) {
        const statusCode =
          typeof error === "object" && error && "statusCode" in error
            ? Number((error as { statusCode?: number }).statusCode)
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
    checked: tasks?.length ?? 0,
    notificationsSent,
    tasksMarked,
    expiredSubscriptionsRemoved,
  });
});
