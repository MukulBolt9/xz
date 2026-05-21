# NowBrief v1.8 — Release Notes

## What's new
- 🌙 **Ocean Dark mode** — deep navy blue night theme (pure cold blue, zero orange warmth)
- ☀️ **Day/Night toggle** — moon/sun icon in top bar switches instantly, persists across restarts
- 🌍 **Country-aware news** — automatically detects your country via GPS; fetches news in your local language (Korean → `ko`, Japanese → `ja`, German → `de`, etc.)
- 🗺️ **Locale fallback** — if GPS hasn't locked yet, uses device locale country so Korean phones always get Korean news
- 🚀 **NowBar Meter** — bottom card renamed, button says "Start NowBar Meter"
- 🔐 **Smarter permission flow** — Start button auto-opens overlay settings if permission not yet granted
- 📡 **News error UI** — shows retry button with clear message when news can't load
- 🐛 **Gemini news fallback** — robust JSON parsing with full error logging

## How to use
1. Tap **Start** (NowBrief card) → allow background/overlay permission → tap Start again
2. Tap **Start NowBar Meter** (bottom card) → allow notification permission
3. Tap **Start** (NowBrief card) one more time → ✅ Now Bar pill appears

## Permissions needed
- Location (for weather + local news)
- Display over other apps (for Now Bar pill)
- Notifications (for NowBar Meter speed display)
- Battery optimization exempt (so service keeps running)
