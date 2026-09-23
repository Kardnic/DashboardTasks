import { createClient } from "npm:@supabase/supabase-js@2";
import webpush from "npm:web-push@3.6.7";

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

  const { data: existing, error: existingError } = await supabase
    .from("push_server_config")
    .select("vapid_public_key")
    .eq("id", true)
    .maybeSingle();

  if (existingError) {
    return Response.json({ error: existingError.message }, { status: 500 });
  }

  if (existing?.vapid_public_key) {
    return Response.json({ vapidPublicKey: existing.vapid_public_key, created: false });
  }

  const keys = webpush.generateVAPIDKeys();
  const { error } = await supabase.rpc("configure_vapid", {
    p_private_key: keys.privateKey,
    p_public_key: keys.publicKey
  });

  if (error) {
    return Response.json({ error: error.message }, { status: 500 });
  }

  return Response.json({ vapidPublicKey: keys.publicKey, created: true });
});