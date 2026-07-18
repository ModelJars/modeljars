import {
  SESSION_COOKIE,
  appendCookie,
  clearCookie,
  redirect,
} from "../_lib/auth.js";

export function onRequestPost() {
  const headers = new Headers();
  appendCookie(headers, clearCookie(SESSION_COOKIE));
  return redirect("/login", 303, headers);
}

export function onRequestGet() {
  return new Response("Method not allowed", {
    status: 405,
    headers: { Allow: "POST", "Cache-Control": "private, no-store" },
  });
}
