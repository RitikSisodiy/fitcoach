# Research

Research snapshot taken **2026-10-05**. Prices, model names, and API availability change often; re-verify before relying on any number here. Items marked *(unverified)* could not be confirmed against a primary source.

---

## 1. Android data collection

### 1.1 Health Connect (HC)
- System module on Android 14+; Play Store APK on Android 13 and below. ~30+ record types: Steps, Distance, ExerciseSession, SleepSession (with stages), Weight, BodyFat, Nutrition, Hydration, HeartRate, RestingHeartRate, Active/Total calories. [data types](https://developer.android.com/health-and-fitness/guides/health-connect/plan/data-types)
- Per-type runtime permissions (`android.permission.health.READ_STEPS`, ...). Extra permissions:
  - `READ_HEALTH_DATA_IN_BACKGROUND` — needed for periodic background reads (check `FEATURE_READ_HEALTH_DATA_IN_BACKGROUND`).
  - `READ_HEALTH_DATA_HISTORY` — otherwise only the last 30 days of other apps' data are readable. [read-data](https://developer.android.com/health-and-fitness/health-connect/read-data)
- Changes API (`getChangesToken` / `getChanges`) supports incremental on-device sync; tokens expire after ~30 days unused. [sync-data](https://developer.android.com/health-and-fitness/health-connect/sync-data)
- Use `aggregate()` for cumulative types (steps) — read rate limits apply.
- **HC has no cloud/REST API.** Data must leave the phone through an app running on the phone.

### 1.2 Cloud health APIs (status Oct 2026)
- **Google Fit REST/Android APIs**: closed to new sign-ups since May 2024; support ends end of 2026. Do not build on them. [migration FAQ](https://developer.android.com/health-and-fitness/health-connect/migration/fit/faq)
- **Fitbit Web API**: shuts down 30 Oct 2026. [openwearables](https://openwearables.io/blog/fitbit-web-api-shutdown-2026-migration-guide)
- **Google Health API** (`health.googleapis.com/v4`): cloud replacement for Fitbit/Pixel Watch data; restricted scopes, still maturing; does not cover phone-only HC data (e.g. Samsung Health steps, third-party scales). Useful only if the user wears a Fitbit/Pixel Watch. *(testing-mode access for a single personal user unverified)*

### 1.3 Phone-to-server bridges
| Option | Effort | Notes |
|---|---|---|
| **Health Connect Webhook** app ([github](https://github.com/mcnaveen/health-connect-webhook), AGPL-3.0, active in 2026) | Install + configure | 31 types, POSTs JSON to a URL on an interval (min 15 min, WorkManager) or fixed times, 48 h lookback, retries. **Best first option.** |
| Home Assistant companion app sensors | Only if HA already runs | Daily steps, sleep duration, weight, activity state, screen on/off. |
| Custom Kotlin companion app | ~1 weekend | WorkManager + changes token + aggregate → HTTPS POST. Fallback if the webhook app's schema/reliability is insufficient. |

### 1.4 Other device signals
- **Activity Recognition Transition API**: low-battery enter/exit events (still/walking/vehicle), needs `ACTIVITY_RECOGNITION`; events can be minutes late. Deferred — hourly steps from HC already reveal sitting blocks.
- **Geofencing / background location**: heavy permission flow, little value for a WFH user. Deferred; conversation already gives location context ("chess ja raha hu").
- **UsageStatsManager** (screen time): feasible via special access, but privacy-heavy and needs custom code. Deferred.
- **Background limits**: WorkManager periodic ≥ 15 min; Doze and OEM battery killers (Xiaomi, Oppo, Vivo, Realme, Samsung) frequently kill background work — the user must disable battery optimisation for the bridge app. Android 15 limits `dataSync` foreground services to 6 h/24 h. Server-driven timing is more reliable than on-device alarms.
- Samsung Health → HC sync can lag by hours. The server must tolerate late, duplicate, and out-of-order data.

**Decision input:** server receives HC data via the webhook bridge; data is upserted idempotently; staleness is detected and surfaced, never silently assumed to be zero.

---

## 2. Communication channel

| | Telegram Bot | WhatsApp Business Cloud API | Native app |
|---|---|---|---|
| Cost | Free | Per-message (INR billing from Jan 2026); free-form only inside 24 h window | Free, but must be built |
| Bot-initiated messages | Any time after `/start` | Outside 24 h window: pre-approved templates only | FCM push |
| One-tap actions | Inline keyboards, callbacks, message edits | Max 3 reply buttons | Anything |
| Voice / photo | Yes (OGG/Opus, 20 MB download) | Yes | Yes |
| Setup friction | Minutes (@BotFather) | Meta business verification, dedicated number, template approval; Meta's Jan 2026 terms restrict general-purpose AI chatbots | Weeks |

Sources: [Telegram bot FAQ](https://core.telegram.org/bots/faq), [WhatsApp pricing 2026](https://m.aisensy.com/blog/whatsapp-per-message-pricing-update-effective-january-1-2026/), [Meta AI chatbot policy](https://www.dataslayer.ai/blog/meta-bans-general-purpose-ai-chatbots-on-whatsapp-business)

**Conclusion:** Telegram. A proactive coach *must* message first at arbitrary times; WhatsApp's 24 h window + template approval directly conflicts with that. A native app adds weeks of work with no adherence benefit at this stage. Long polling means no public URL is required for the bot.

---

## 3. Low-effort food tracking

### 3.1 Accuracy reality
- LLM vision calorie estimation from photos: MAPE ≈ 40 % (ChatGPT, Claude) to 65–70 % (Gemini) on 52 foods; protein error > 60 %; error grows with portion size. [Fridolfsson 2025](https://pmc.ncbi.nlm.nih.gov/articles/PMC12513282)
- Context (time, location, list of known foods) measurably reduces error. [arXiv 2507.07048](https://arxiv.org/abs/2507.07048)
- Text meal descriptions (NutriBench) are estimated reasonably well by frontier models. [NutriBench](https://arxiv.org/html/2407.12843v6)
- No rigorous study exists for Indian-food photos. Hidden oil/ghee, gravies, and roti size make ±30–50 % error a realistic expectation.

### 3.2 Databases
- **INDB (Anuvaad, 2024)** — 1,095 raw foods + **1,014 Indian recipes**, CC BY, CSV on GitHub. Best primary table. [paper](https://pmc.ncbi.nlm.nih.gov/articles/PMC11277795), [repo](https://github.com/lindsayjaacks/Indian-Nutrient-Databank-INDB-)
- **IFCT 2017 (ICMR-NIN)** — authoritative, raw ingredients only.
- **USDA FoodData Central** — fallback for generic/Western items.
- **Open Food Facts** — barcode lookups; ~10k Indian products, often incomplete.

### 3.3 Voice
Telegram voice notes are OGG/Opus; Gemini accepts `audio/ogg` natively, so transcription + extraction happen in one call. [Gemini audio](https://ai.google.dev/gemini-api/docs/audio)

### 3.4 Implications
1. Accuracy is limited, so the product must optimise **logging consistency, not precision** (self-monitoring frequency predicts weight loss more than precision — see §6).
2. The LLM does perception and parsing into household units (roti, katori, piece, cup); **deterministic code does the nutrition math** from a food table, returning **ranges**, not point values.
3. Unknown dishes fall back to an explicit LLM estimate flagged `estimated_by_llm`.
4. One-tap correction ("kam tha / zyada tha") updates personal portion priors.

---

## 4. LLM providers (official pricing pages, Oct 2026)

| Provider | Models (examples) | Notes |
|---|---|---|
| Gemini (`google-genai`) | Flash and Flash-Lite tiers, Pro preview | Text + image + **native audio** in one call; JSON-schema structured output; cheapest multimodal option. Promo pricing ends 31 Dec 2026. Free tier may use prompts for product improvement → use paid tier for health data. |
| Claude (`anthropic`) | Haiku / Sonnet / Opus tiers | Strong tool use and vision; **no audio input**. |
| OpenAI (`openai`) | GPT-5.x tiers, GPT-Transcribe | Separate transcription model available. |
| OpenRouter | Any of the above | OpenAI-compatible endpoint; useful for failover. |

Hinglish: frontier models handle romanised Hindi-English well; few-shot examples improve extraction noticeably. [COMI-LINGUA](https://arxiv.org/pdf/2503.21670)

Model IDs are **configuration, not code** — they change too often to hardcode. Estimated cost for one user at ~50 calls/day: under US$5/month.

---

## 5. Agent and memory architecture

- Mem0-style extraction + consolidation beats full-context on LoCoMo; Letta showed a plain filesystem agent does as well or better → *how* context is managed matters more than the retrieval mechanism. [Mem0](https://arxiv.org/abs/2504.19413), [Letta](https://www.letta.com/blog/benchmarking-ai-agent-memory)
- A fitness coach's important state is **structured and relational** (meals, weights, steps, commitments, intervention outcomes) and needs aggregation (7-day protein average, reminder success rate per time slot). SQL does this; vectors don't.
- One user produces < 100k rows over years.

**Conclusion:** SQLite (WAL) with typed tables, a `facts` table with source/confidence/timestamps/supersession, FTS5 over messages, and a compact "core profile" always injected into prompts. **No vector database.** Revisit only if semantic recall over chat history proves necessary (`sqlite-vec` would be the minimal step).

Agent pattern: **event-driven** (each inbound message → extract → validate → store → respond) **plus scheduled** (periodic planner that may decide to stay silent, nightly consolidation, weekly review). The LLM never decides *whether* we are allowed to message; deterministic policy does (budget, quiet hours, pause, intensity).

---

## 6. Behavioral science

| Finding | Evidence | Design rule |
|---|---|---|
| Implementation intentions (if-then plans) | d ≈ 0.65 overall (Gollwitzer & Sheeran 2006); for reducing unhealthy eating d ≈ 0.29; several plans at once weaken the effect | Store if-then commitments in the user's own words; keep 1–2 *active focus* plans; target the critical moment (cue), not the general goal |
| WOOP / MCII | g ≈ 0.34 (Wang 2021) | Weekly review asks for the user's own main obstacle |
| JITAI | Nahum-Shani 2018: decision points, tailoring variables, options, rules; "do nothing" is a valid option | Planner explicitly considers silence |
| HeartSteps MRT | +14 % steps in next 30 min; effect decayed to ~0 by day 28 (habituation); "boring after 2–4 weeks" | Hard notification budget (≤ 2–3/day), vary content, back off when ignored, measure proximal outcomes |
| Receptivity | Response depends on time, day, phone use; ML timing up to +40 % | Learn per-slot success rates; prefer slots that worked |
| Habit formation | Median 66 days (18–254); missing one day doesn't derail (Lally 2010) | Minimum viable versions count as success; "never miss twice" |
| Self-monitoring | Logging *frequency* predicts ≥ 10 % loss (Harvey 2019); daily weighing + feedback ≈ 6.6 % vs 0.4 % at 6 months (Steinberg) | Minimise logging effort; a partial log beats none; show trend weight |
| Abstinence violation / what-the-hell effect | Rigid dieters disinhibit after a lapse (Marlatt; Polivy & Herman) | Lapse protocol: normalise → one question → update plan → next action; no compensation restriction |
| Self-compassion | Self-compassion after a lapse reduced subsequent eating (Adams & Leary 2007) | No guilt or shame language — enforced by a deterministic tone guard |
| Commitment devices | Work while active, regain after (Volpp 2008); low take-up; can harm | Only voluntary, reversible, low-stakes commitments; user can pause any time |
| Supportive accountability | Mohr 2011: accountability to a coach seen as benevolent, competent, trustworthy | Agreed expectations; honest, non-controlling tone |
| Bandits / RL personalisation | Thompson sampling in HeartSteps v2; RL JITAI matched human coaches (Forman 2019) | Beta-Bernoulli Thompson sampling over time slots and styles with exploration floor and forgetting |
| Safe rate | 0.5–1 % bodyweight/week; protein 1.2–1.6 g/kg in a deficit; daily scale noise 1–2 kg | EWMA trend weight; no calorie target change on < 14 days of data; ±100–200 kcal steps |
| Indian context | ICMR-NIN 2024 guidelines; hidden oil/ghee, chai sugar, fried snacks, roti count | Swaps over bans; household units; explicit oil uncertainty |

Sources: [Gollwitzer & Sheeran](https://kops.uni-konstanz.de/entities/publication/2e749bfb-8533-437c-8203-7e788c910c5f), [Adriaanse 2011](https://repositorio.comillas.edu/items/65543357-50e8-4b2c-951b-be88fd203fe7), [WOOP meta](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC8149892/), [JITAI](https://pmc.ncbi.nlm.nih.gov/articles/PMC5364076), [HeartSteps](https://pmc.ncbi.nlm.nih.gov/articles/PMC6401341), [receptivity](https://arxiv.org/abs/2011.08302), [Lally](https://www.ucl.ac.uk/news/2009/aug/how-long-does-it-take-form-habit), [Harvey 2019](https://pubmed.ncbi.nlm.nih.gov/30801989/), [Volpp 2008](https://jamanetwork.com/journals/jama/fullarticle/10.1001/jama.2008.804), [Mohr 2011](https://jmir.org/2011/1/e30), [HeartSteps v2 bandit](https://pmc.ncbi.nlm.nih.gov/articles/PMC8200090), [protein](https://pmc.ncbi.nlm.nih.gov/articles/PMC4892287), [ICMR-NIN 2024](https://nutritionconnect.org/resource-center/dietary-guidelines-indians-2024-edition-icmr-nin)

---

## 7. Assumptions challenged

| Original assumption | Finding | Change |
|---|---|---|
| Android app is phase 1–4 core | No HC cloud API, but a maintained open-source bridge exists | **No custom Android app initially.** Use the HC Webhook bridge; build Kotlin only if it proves insufficient |
| Location tracking for context | Background location is costly in permissions/battery and low value for WFH | Conversation is the primary context sensor; location deferred |
| Food photo = accurate calories | 40–70 % error | Ranges + confidence; consistency over precision |
| More reminders = more adherence | Habituation within weeks | Budget + rotation + back-off; escalation changes the *strategy*, not frequency |
| Vector memory needed | Data is relational and small | SQLite + FTS5 + facts table |
| WhatsApp may be better (more used in India) | 24 h window and template rules block proactive coaching | Telegram |
| Gemini as the only provider | Gemini is the only one with native audio, so it stays default | Provider abstraction; voice degrades gracefully on providers without audio |

---

## 8. Round 2 (2026-10-06): proactive LLM coaching, re-engagement, passive food signals

### 8.1 Who decides when to message
- **Google PHIA** (2024): an agent that queries wearable data through code beats free-text answers. Lesson: numbers must come from tools/code, never from LLM arithmetic. [arXiv 2406.06464](https://arxiv.org/abs/2406.06464v3)
- **Google Personal Health Agent** (2025): orchestrator with data-science, domain and coach sub-agents; beats single-agent baselines. [arXiv 2508.20148](https://arxiv.org/abs/2508.20148)
- **GPTCoach** (CHI 2025): uses a state machine plus MI strategy selection. Its failure mode was not bringing wearable data into advice unprompted. [arXiv 2405.06061](https://arxiv.org/abs/2405.06061)
- **"The Last JITAI?"** (2024): GPT-4 chose when and what to send for vignettes and was rated better than clinicians. This was hypothetical, with no field effect measured. [arXiv 2402.08658](https://arxiv.org/abs/2402.08658)
- **Bloom RCT** (2025): adding an LLM to a structured coaching app did not add activity on top of the scaffolding; it improved attitudes. [arXiv 2510.05449](https://arxiv.org/abs/2510.05449)
- **Synthesis:** the hybrid works best. Rules gate and enforce limits; the LLM picks among candidates (or silence) and writes the message.

### 8.2 Engagement decay
- **HeartSteps:** the effect decayed to ~0 by day 28 (habituation). [PMC6401341](https://pmc.ncbi.nlm.nih.gov/articles/PMC6401341)
- **Drink Less MRT:** notifications raised next-hour opens 3.5×, but did not change time to disengagement. [JMIR 2023](https://mhealth.jmir.org/2023/1/e38342)
- **Return probability:** after silent week 1 it is 89%; after 20 weeks it is < 10%. The first 1–3 silent weeks are the window to act. [PACIS 2026](https://aisel.aisnet.org/pacis2026/ishealthcare/ishealthcare/9)
- **Reminders:** they are the persuasive feature least associated with continued use, often felt as intrusive. [PMC12004308](https://pmc.ncbi.nlm.nih.gov/articles/PMC12004308/)
- **"Effective engagement"** (Yardley 2016): enough to reach the outcome, not the maximum possible.

### 8.3 Reflection / pattern memory
- **Generative Agents:** reflections cite evidence ids. Known failures: hallucinated details, and self-reinforcing false beliefs. [arXiv 2304.03442](https://arxiv.org/abs/2304.03442), [arXiv 2603.07670](https://arxiv.org/abs/2603.07670)
- **Mitigations adopted:**
  - deterministic support/contradict counts
  - a distinct-weeks requirement
  - a Wilson lower bound
  - user rejection that is never auto-resurrected
  - missing data counted as unknown, not as absence

### 8.4 Recall-lite
- **Self-report 24 h recall** under-reports by ~22–25% versus doubly labelled water. That is similar to food diaries, so real-time logging is not clearly more accurate. [Cambridge repository](https://www.repository.cam.ac.uk/handle/1810/296969)
- **Short repeated recalls (Traqq)** reduce under-reporting slightly. [WUR](https://edepot.wur.nl/632645)
- **Habitual meals** (MyFitnessPal, 2,758 users): breakfast is the most habitual meal, dinner the least. [MDPI Sensors](https://www.mdpi.com/1424-8220/22/7/2753)
- **Evidence gap:** "usual meal?" defaults are **not validated**, and acquiescence bias is a risk. So defaults are stored with lower confidence and labelled `default_confirmed`.

### 8.5 Passive food signals in India
- **Notification/SMS forwarders:**
  - **SmsForwarder** (open source): forwards app notifications and SMS, with package filters and a custom webhook body.
  - **MacroDroid/Tasker:** an alternative.
  - **Android 15 restriction:** sideloaded apps need "Allow restricted settings" before they get notification-listener access.
  - Sources: [SmsForwarder](https://github.com/pppscn/SmsForwarder), [9to5google](https://9to5google.com/2024/09/12/android-15-sideloaded-apps-restrictions/)
- **Swiggy/Zomato push notifications:** they carry status and restaurant, rarely items (emails have items).
- **UPI SMS and notifications:** they carry amount, payee, time and ref, but no merchant category. "Street snack" is therefore only a probabilistic inference: small amount + snack window + repeated payee.
- **Calendar:** the Google Calendar secret iCal address still works; it is parsed with `icalendar` + `recurring_ical_events`.
- **Gemini (Oct 2026):** 3.x Flash models are stable; 2.5 is limited to past users. `thinking_level` (minimal/low/medium/high) replaces `thinking_budget`. Structured output and function calling can be combined, and `audio/ogg` is supported. [models](https://ai.google.dev/gemini-api/docs/models), [thinking](https://ai.google.dev/gemini-api/docs/thinking)
- **Telegram Bot API 10.x:**
  - Persistent reply keyboards (`is_persistent`) give a one-tap quick-log bar.
  - Inline keyboards are used to confirm inferred events.
  - Reaction updates in private chats are unverified.

### 8.6 Assumptions challenged in round 2
| v0.1 assumption | Finding | Change |
|---|---|---|
| Proactivity can hang off user-defined commitments | A lazy user never configures them | Candidate intents that don't need setup (recap, predicted situation, re-engagement, onboarding, inactivity) |
| Rules decide everything; the LLM only writes | Research favours a hybrid in which the LLM chooses among gated candidates or silence | Brain: code proposes and gates, the LLM chooses or stays silent and writes |
| Re-engage by repeating reminders | Notifications don't prevent disengagement; they annoy | Engagement state machine shrinks the budget; one-tap re-entry; message types retired; recap gets rarer instead of disappearing |
| Food tracking needs the user to log | Lazy users log < 25% | Usual-meal defaults, one-tap recap, payment/order inference, honest "assumed usual" intake estimate |
| Patterns come from what the user says | The user mentions chess only half the time | Passive payee/weekday patterns from payments reveal the same routine |
