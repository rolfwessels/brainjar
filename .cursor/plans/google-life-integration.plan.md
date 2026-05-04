# Google + life-logging ideas for Perry

Backlog of "what to hook Perry up to next". Rolf is Google-first on Pixel 6 and
wants this built into Perry. Core goals: remembering **people**, **books**,
**wine**, and maybe some light **life logging**. Forgetting names is extremely
human — Perry should cover for that.

## Phase 1 — highest ROI, lowest creep

### 1. Google Contacts → "People memory"
Ingest: name, nicknames, company, labels, birthday, notes, last updated.

Use cases:
- Birthday / anniversary reminders.
- "You haven't spoken to X in 60 / 90 days" (if we can infer it).
- When a meeting appears in Calendar: "here's who this is + last notes".

### 2. Google Calendar → daily / weekly digests
Ingest: event title, time, attendees, location, description (optional).

Use cases:
- Morning: today's agenda + 1–2 prep bullets per meeting.
- Night: tomorrow preview.
- "Travel buffer" reminders based on location.

### 3. Gmail metadata → relationship + follow-up
Ingest metadata only: from / to, subject, date, thread id, labels.

Use cases:
- "Waiting on reply" queue.
- "Follow up with X" if no response in N days.
- Detect receipts / newsletters via labels to avoid scanning the whole
  inbox like a raccoon in a filing cabinet.

## Phase 2 — the "I forget names / context" superpower

### 4. Tiny "People log" capture flow (manual, fast)
Need a frictionless way to log things like:
> "Met Sarah at Tom's BBQ, works at X, likes natural wine, partner Y."

Options:
- Discord command: `/met Sarah …`
- Telegram / Discord DM to Perry.
- Google Form → Sheet (shockingly effective).

Perry can then answer later: "Who was that Sarah from the BBQ?"

### 5. Books + wine (simple database)
Pick one store of truth: Google Sheet (easy) or Notion (richer).

Fields for wine: name, grape, region, producer, where you had it, rating,
notes, photo.

Fields for books: title, author, status, rating, notes, quotes.

## Phase 3 — life logging (only if actually wanted)

- Google Maps Timeline (places) + Photos "On this day".
- Health (sleep / activity).
- Value exists here, but so does the "why do I feel observed by my own
  software" feeling. Opt-in, eyes open.

## Architecture suggestion (fits the Java Perry)

- **Nightly sync jobs**: Contacts + Calendar + Gmail metadata.
- Store in the DB with `source` + `last_synced_at`.
- **Daily job** generates:
  - Birthdays today / this week.
  - Today agenda + prep nudges.
  - Follow-ups.
- Perry uses that DB as retrieval context — not raw API calls every turn.
