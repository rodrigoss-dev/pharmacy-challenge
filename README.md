# Greenleaf Pharmacy: prescription ordering system

A prescription fulfillment system for a pharmacy, built around the patient sitting in the waiting room.

A patient checks in with their prescription and gets a **ticket number**. A **live page on their phone** follows the
prescription through the stock check, the pharmacist's review and packing, and tells them the moment their number is
called. Pharmacists and packagers work from a plain staff console. A **waiting-room board** shows the numbers for
anyone without a phone.

Kotlin, Quarkus, jOOQ, PostgreSQL and RabbitMQ on the backend, in two modules (the domain, and the infrastructure
around it); a SvelteKit web app on the front. A patient can also check in with the number of a **government
prescription**, which the backend loads from the government's system (`gov-api/`, a Node.js mock of it) and later tells
what was collected.

---

## Run it

Requirements: **JDK 21** (the backend), **Node.js 22.17+** (the web app) and **Docker**.

The backend and the web app are built and run separately; Docker Compose only runs the infrastructure.

```bash
docker compose up -d                       # PostgreSQL, RabbitMQ (management UI: http://localhost:15672, pharmacy / pharmacy),
                                           # and the observability stack (Grafana: http://localhost:3000, pharmacy / pharmacy)
./gradlew :infra:quarkusDev                # the backend: Quarkus dev mode, live reload. http://localhost:8080
cd web && npm install && npm run dev       # the web app: Vite dev server, hot reload. http://localhost:5173
cd gov-api && npm start                    # the government prescription system (a mock): http://localhost:4000, no install needed
```

`make dev` starts all of it (see `make` for the other targets).

| Page | URL | Who |
|---|---|---|
| Check-in | http://localhost:5173 | Patient (open it at phone size) |
| Live tracking | `/track/{id}` (you land there after check-in) | Patient |
| Waiting-room board | http://localhost:5173/board | Screen in the waiting room |
| Staff console | http://localhost:5173/staff | Pharmacists and packagers |

Tests: `make test` runs all of these.

| | Where | What |
|---|---|---|
| `./gradlew test` | `domain/` | The business rules as Cucumber scenarios (`src/test/resources`): the prescription's life cycle, check-in, government prescriptions, and the use cases themselves (first come first served, what goes back on the shelf, what the patient's page and the staff queues show) against the ports in memory, so they run in milliseconds. |
| | `infra/` | Against a real PostgreSQL: the use cases, the HTTP API (errors, live streams, CORS), the last pack raced by many stock checks, the government integration against a fake government system (also slow, unreadable and refusing), the stock check consumer, the outbox relay and the supervised consumers against a fake broker, the metrics, and the app started against a database that is not there. |
| `npm run check`, `npm test` | `web/` | TypeScript and Svelte type checks; then Vitest: the logic (`src/lib`), and every page rendered in jsdom against a fake backend and a fake event stream. |
| `npm test` | `gov-api/` | The government mock. |
| `npm run e2e` | `web/` | Playwright, real browsers against the **running** system (`make dev`): check-in to hand-over, rejection, no show, two phones on one government number, and the commit reaching the government. It checks in as "E2E ..." patients and leaves their tickets behind: use a development database. The Playwright version is pinned to the one whose Chromium is installed with `npx playwright install chromium`. |

The backend tests need Docker: Quarkus Dev Services starts a throwaway PostgreSQL for them, so they never
touch the compose database. They run without a broker.

Backend configuration (environment variables): `PORT` (8080), `DATABASE_JDBC_URL`
(`jdbc:postgresql://localhost:5432/pharmacy`), `DATABASE_USER` and `DATABASE_PASSWORD` (`pharmacy`), `RABBITMQ_URI`
(`amqp://pharmacy:pharmacy@localhost:5672`), `WEB_ORIGINS`: where the web app runs, allowed to call the API
(`http://localhost:5173,http://localhost:4173`), `GOVERNMENT_API_URL`: the government prescription system
(`http://localhost:4000`), `LOG_FILE`: the JSON log file the observability stack reads
(`logs/pharmacy.log`, relative to where the backend starts: the repository root in dev mode). The schema is created and migrated at startup by Flyway. The timeouts,
pool and sweeper settings are in `infra/src/main/resources/application.properties`, each explained there.

Web app configuration, at build time: `VITE_API_URL`, the backend (`http://localhost:8080`).

Each part has its own image:

```bash
docker build -t pharmacy-api .                                                  # the backend, on port 8080
docker build -t pharmacy-web --build-arg API_URL=https://api.example.com web    # the web app (nginx), on port 80
```

### A two-minute demo

1. Open the check-in page in a phone-sized window, enter a name, pick a couple of medications, tap **Get my number**.
2. Open the staff console next to it. The prescription has already passed the stock check and waits in the
   pharmacist queue. **Approve** it, mark it **Packed**, **Call** the number. The patient's page follows each step
   live; on the call it turns green, vibrates and beeps (if sound was enabled). If you do not hand it over and press
   **No show** instead, the patient's page says nobody answered, and the medication is back in the inventory table.
3. Open the check-in page in two windows. In the first, check in for 3 packs of Atorvastatin (all 3 in stock). The
   second still shows 3 available: check in for 3 there too. The stock check finds none left, and the patient is told
   right away which medication is short and what to do next. (The page does not let anyone pick more than what is in
   stock, so this takes two patients at once.)
4. Stop RabbitMQ (`docker compose stop rabbitmq`) and keep going. Check-ins and staff actions still work, the staff
   console shows a warning, new check-ins still reach the pharmacist queue (within about 25 seconds, through the
   sweeper) and patient pages still catch up within 10 seconds. Start it again: the queued events are delivered and
   everything carries on.
5. Check in with a government prescription: under the box at the top of the check-in page, tap the sample `GOV-1001` (each
   sample says what to expect from it; tapping fills the box) and press **Look up**. Amoxicillin is
   prescribed as it is (1 pack), ibuprofen has a **maximum** of 2: take 1. Take it through the staff console to *Handed
   over*, then look at what the government has left: `curl localhost:4000/prescriptions/GOV-1001` shows ibuprofen with
   1 pack left. To see the outbox work, stop the mock (Ctrl-C) before *Handed over*: the counter is not held up, the
   commit waits in the database and the staff console warns once it has waited a minute; start the mock again and the
   commit goes through by itself. `GOV-1003` offers up to 5 atorvastatin when the pharmacy has 3 (the page stops at 3), `GOV-1004` has been used
   already.
6. Stop PostgreSQL (`docker compose stop postgres`). Nothing can be saved now: a check-in answers 503 straight away
   (the page retries by itself), patient pages keep what they show and say their updates are delayed, and the staff
   console says the database is unreachable. Start it again: within a few seconds everything carries on by itself,
   including the stock checks whose messages arrived meanwhile.

---

## The patient experience

The brief says the patients are the users who need convincing. These are the decisions made for them:

- **One number, no account.** Check-in asks only for a first name and the medication. The patient gets a short
  ticket number: the thing the pharmacist calls out.
- **Live, without refreshing.** The tracking page holds a Server-Sent Events connection. Every change is pushed:
  a progress stepper (*Checked in → Pharmacist review → Packing → Ready*) and one plain sentence about what is
  happening now.
- **"How long?"** answered as *"2 people ahead of you"*, recomputed whenever anybody's prescription moves.
- **The moment that matters can't be missed.** When the number is called, the status card turns into a pulsing green
  *"It's your turn!"*. The phone vibrates, the browser tab title changes, and there is a beep if the patient opted in
  (browsers only allow sound after a tap).
- **Bad news comes with a next step.** Out of stock names the medication and says a pharmacist can suggest an
  alternative. A rejection shows the pharmacist's reason and asks the patient to come to the counter. A number that
  was called and not answered says so, and that the medication went back on the shelf. However a prescription ends
  (collected, out of stock, rejected, nobody came) the page offers a **Start a new check-in** button. A stock check that
  takes too long says so, and says staff are on it.
- **Honest connection state.** *"Live"*, *"Reconnecting..."* or, when the pharmacy's system cannot read its data,
  *"Updates are delayed..."*, each followed by *"your place in line is safe"*. The page reconnects on its own (with
  randomized pauses, so a room full of phones does not reconnect in step), including when a phone wakes a suspended
  tab.
- **Small robustness touches.** Check-in can be retried safely (no duplicate tickets on flaky Wi-Fi), and the page
  retries it by itself when the network or the backend hiccups. The page
  remembers your ticket if you close the tab. Tracking links are unguessable UUIDs, because the page lists
  medication. The public board shows numbers only, never names.
- Mobile first, dark mode, keyboard and screen-reader friendly (status changes are announced through a live region),
  no animation for users who prefer reduced motion.

## Architecture

```
domain/   Entities, business rules, use cases and the ports (interfaces) they need. Plain Kotlin, no dependencies.
infra/    Quarkus. Everything that talks to the outside world:
            api/          REST resources, Server-Sent Events, health checks, request logging
            logging/      Marks every line of the log file as business or system (see Observability)
            messaging/    RabbitMQ: outbox relay, stock check consumer and sweeper, change feed for the live screens
            metrics/      The Prometheus metrics: business (prescriptions, stock) and system (outbox, broker, database)
            government/   The government prescription system over HTTP, and the relay that commits to it from the outbox
            persistence/  jOOQ repositories on PostgreSQL; Transactions and DatabaseHealth (see Failure handling);
                          Backlogs (what is waiting for the broker and the government, for /health and the metrics);
                          Flyway migrations in src/main/resources/db/migration
web/      SvelteKit web app: check-in, live tracking, waiting-room board, staff console. An npm project of its own.
gov-api/  A mock of the government prescription system. Node.js, no dependencies; not part of the pharmacy's own code.
```

Domain and infra are **Gradle modules**, so the dependency rule is enforced by the compiler: `domain` cannot import Quarkus,
jOOQ or the RabbitMQ client because they are not on its classpath. Dependencies point one way only (`infra → domain`).

**The domain is only ever called from the outside in**, through one of two doors in `infra`: an HTTP request (a REST
resource) or a RabbitMQ message (the stock check consumer, and the sweeper that backs it up). Both call the same use
cases, and each door opens the transaction the use case runs in, through `persistence/Transactions`: the domain does
not know transactions exist. On the way out, the use cases go through the ports
(`PrescriptionRepository`, `InventoryRepository`, `EventOutbox`, `GovernmentCommitOutbox`), which `infra` implements
with jOOQ, and `GovernmentPrescriptions`, which it implements with an HTTP client. The use cases
are plain classes: `infra/.../UseCases.kt`
makes them injectable with CDI producer methods, so the domain itself knows nothing about the framework.

The web app only talks to the HTTP API:

```
web/src/routes/            /  (check-in) · /track/[id] · /board · /staff
web/src/lib/api.ts         fetch wrapper (timeout, retries) and the API's JSON types (mirrors infra/.../api/Dtos.kt)
web/src/lib/live.ts        Server-Sent Events with reconnection and connection state
web/src/lib/status.ts      what the patient reads for each status, in one place
web/src/lib/government.ts  what can be taken of a government prescription, and what the pharmacy does not carry of it
web/src/lib/checkin.ts     the id a check-in is sent with; polling.ts: polling without piling up; util.ts
web/src/lib/components/    TopBar, QuantityStepper, ProgressSteps, LiveIndicator
```

### Domain

`Prescription` is a small state machine. Every transition is a method that refuses illegal moves
(e.g. approving twice → `IllegalTransitionException` → HTTP 409):

```mermaid
stateDiagram-v2
    [*] --> RECEIVED: check in
    RECEIVED --> AWAITING_APPROVAL: stock reserved
    RECEIVED --> OUT_OF_STOCK: not enough stock
    AWAITING_APPROVAL --> AWAITING_PACKAGING: pharmacist approves
    AWAITING_APPROVAL --> REJECTED: pharmacist rejects
    AWAITING_PACKAGING --> READY_FOR_PICKUP: packed
    READY_FOR_PICKUP --> CALLED: number called
    CALLED --> CALLED: called again
    CALLED --> COLLECTED: handed over
    CALLED --> NO_SHOW: nobody answers, packs go back on the shelf
    COLLECTED --> [*]
    OUT_OF_STOCK --> [*]
    REJECTED --> [*]
    NO_SHOW --> [*]
```

### Use cases

| Use case | Actor | What it does |
|---|---|---|
| `SubmitPrescription` | Patient | Validates, assigns the next ticket number, saves as `RECEIVED`. A check-in sent again with the same id returns its ticket; with other details it is refused (409) |
| `ReserveStock` | System (RabbitMQ consumer) | Reserves every item atomically → pharmacist queue, or `OUT_OF_STOCK`. One stock check at a time, and each first checks the patients ahead of it that still wait: the last pack goes to the earliest ticket |
| `StaffActions` | Pharmacist, packager | approve, reject (puts stock back), packed, call, no show (a called number nobody answers: puts stock back), hand over (queues the government commit, if any), retry stock check |
| `LookUpGovernmentPrescription` | Patient | What a government prescription number lets them collect, from the government system, minus what was used |
| `TrackPrescription` | Patient | Status, people ahead (in the approval and packing queues), delay flag (streamed live by `infra`'s `LiveUpdates`) |
| `WaitingRoomBoard` | Waiting room | Ticket numbers by stage; also live |
| `ListWorkQueues`, `MedicationCatalog` | Staff | Work queues in FIFO order; stock levels and restock |

Every state change goes through one path (`PrescriptionUpdater`): load, apply the domain transition, save with an
optimistic version check, record the event in the outbox. The door that called the use case runs all of it in one
transaction.

### How the processes coordinate

```mermaid
sequenceDiagram
    actor P as Patient phone
    participant API as Quarkus API
    participant DB as PostgreSQL
    participant R as OutboxRelay
    participant MQ as RabbitMQ (pharmacy.events)
    participant S as StockCheckConsumer
    actor Staff

    P->>API: POST /api/prescriptions
    API->>DB: insert prescription (RECEIVED) + outbox event, one transaction
    API-->>P: 201 ticket #17
    P->>API: GET /api/prescriptions/{id}/live (SSE, stays open)
    R->>DB: read outbox
    R->>MQ: publish prescription.received (publisher confirms), then delete from outbox
    MQ->>S: pharmacy.stock-check queue
    S->>DB: reserve stock, AWAITING_APPROVAL + outbox event
    MQ-->>API: prescription.* to this instance's own queue
    API-->>P: push: "Waiting for a pharmacist, 2 ahead of you"
    Staff->>API: approve / packed / call / hand over
    API->>DB: transition + outbox event
    MQ-->>API: prescription.*
    API-->>P: push: ... "It's your turn!"
```

**RabbitMQ** carries one event type, `PrescriptionStatusChanged`, on a topic exchange `pharmacy.events`. The routing
key is the new status (`prescription.received`, `prescription.called`, ...). Two kinds of consumers use it:

1. **`pharmacy.stock-check`**: a durable quorum queue bound to `prescription.received`. This is the automated
   inventory verification. It runs asynchronously, so check-in answers instantly and keeps working if this step is
   slow or down. While the database is unreachable the consumer holds its message instead of using up its retries;
   anything else that fails is retried 5 times (6 attempts in all), then parked in a dead-letter queue. Whatever happens to the
   message, the **stock check sweeper** runs, from the database, every stock check that has waited more than 15
   seconds: the message is the fast path, the sweeper the guarantee.
2. **One temporary queue per app instance**, bound to `prescription.#`. Every instance hears every change and pushes it
   to the phones connected to *it*, whichever instance made the change. This is what makes the SSE layer scale out.

New consumers (SMS notifications, analytics, automatic re-ordering of stock) can bind to the same exchange without
touching the existing code.

**Why the pharmacist and packaging queues live in the database, not in RabbitMQ.** These queues are worked by
people. People need to *see* the whole queue in order, pick from it, and take minutes per item. A broker queue only
hands out one message at a time and holds it unacknowledged meanwhile. So the human work queues are a query
(`status = AWAITING_APPROVAL ORDER BY ticket`), and RabbitMQ does what it is good at: moving work and events between
machine processes.

### Government prescriptions

A patient with a prescription from the government system types its number at check-in. The backend asks the government
system what is left to collect, the patient chooses from that, and once they have their medication the backend tells
the government what they took, so the next visit finds only what is left.

```mermaid
sequenceDiagram
    actor P as Patient phone
    participant API as Quarkus API
    participant G as Government system
    participant DB as PostgreSQL
    participant R as GovernmentCommitRelay
    actor Staff

    P->>API: GET /api/government-prescriptions/GOV-1001
    API->>G: GET /prescriptions/GOV-1001
    G-->>API: amoxicillin 1, ibuprofen 2 (maximum)
    API-->>P: what is left to collect
    P->>API: POST /api/prescriptions {governmentNumber, ibuprofen: 1, amoxicillin: 1}
    API->>G: GET /prescriptions/GOV-1001 again, and check the choice against it
    API->>DB: prescription with its government number
    Staff->>API: hand over
    API->>DB: COLLECTED + government_commits row (PENDING), one transaction
    R->>DB: claim the commits that are due
    R->>G: POST /prescriptions/GOV-1001/commits {commitId, ibuprofen: 1, amoxicillin: 1}
    G-->>R: 201
    R->>DB: COMMITTED
```

**What the government returns** is a list of `{productId, quantity, maximum}`, where `quantity` is what is left. With
`maximum: true` the quantity is a ceiling: the patient may take anything from 1 pack up to it (a maximum of 2, and
they take 1: 1 is committed). With `maximum: false` it is what the doctor prescribed, taken as it is or left out.
The check-in is held to this (`GovernmentPrescription.check`, in the domain), and a government prescription's
check-in can only list its medication. The government system is asked again at check-in rather than trusting the
screen, and before the transaction opens, so no database connection or ticket counter lock waits for another system.

**Two phones, one number.** The government is told what was taken only after the hand-over, so until then its figures do
not show what other tickets on the same prescription are going to take. The check-in counts them itself: inside the
transaction, once it holds the ticket counter (which every check-in queues on, so the tickets before it have committed),
it subtracts what tickets on that number hold (`PrescriptionRepository.promisedOn`) from what the government has left.
A ticket holds its packs from check-in until it ends without the medication (out of stock, rejected, no show) or until
the government has accepted its commit, which it then counts itself. A commit the government accepted *after* it was
asked (`GovernmentPrescription.readAt`) is not in its figures yet and still counts; one it accepted before is counted by
the government only. In the moment between the government accepting a commit and the pharmacy noting that, a ticket can
be counted twice, which refuses a little too much for a few milliseconds and never too little. The lookup page still
shows the government's own figures: a second phone is told at check-in, in words, what the first one holds.

**The commit goes through an outbox.** `StaffActions.handOver` writes a row to `government_commits` in the same
transaction as the hand-over (`COLLECTED`): both exist or neither does, and the commit lists the quantities of the
prescription as handed over. Nothing at the counter calls the government. `GovernmentCommitRelay` (every 2s, on every
instance) sends what is due and records the outcome:

- **Accepted**: `COMMITTED`. The row stays: what was reported to the government is worth keeping.
- **Unreachable, slow or failing** (timeout, 5xx, 408, 429): stays `PENDING` and is tried again after 5s, 10s, 20s...
  up to 5 minutes. Nothing waits on it: the patient has long gone home.
- **Refused for good** (any other 4xx, e.g. more than what is left): `REJECTED`, logged as an error and shown on the
  staff console and `/health`. Sending it again cannot help, so someone has to sort it out with the government.

Sending is at least once: the app can die after the government accepted a commit and before it noted that. So a commit
carries the prescription's id as its `commitId`, and the government recognizes a repeat and changes nothing (the mock
does). The relay holds no database connection while it calls the government: it claims commits with a lease
(`FOR UPDATE SKIP LOCKED`, due again in a minute), calls, then records the outcome, so several instances share the work
and a commit claimed by an instance that dies is simply sent again.

Everything about it is on the **Pharmacy · Integrations** dashboard (see Observability), including the commits waiting
and how long the oldest has waited.

`gov-api/` is a mock of the government system, for development and the demo (see its README for the API and the
numbers it knows). The backend reaches it at `GOVERNMENT_API_URL`.

### Technology choices

| Choice | Why |
|---|---|
| **Quarkus** | REST with Jackson, CDI, Flyway and JTA transactions out of the box; starts in about a second; dev mode with live reload. Server-Sent Events are a Mutiny `Multi` of named events. |
| **jOOQ** | Type-safe SQL, with classes generated at build time *from the Flyway migrations themselves* (no database needed to build). Records are mapped to domain objects by hand, so persistence never leaks into the domain. Atomic statements stay plain SQL, e.g. `UPDATE ... SET stock = stock - ? WHERE stock >= ?`. |
| **PostgreSQL** | Row-level locking lets several app instances share it. Concurrent reservations can't oversell (conditional decrement), and an advisory lock (`pg_advisory_xact_lock`, released with the transaction) makes stock checks run one at a time across instances, so none overtakes an earlier ticket. Ticket numbers come from a counter row, so a failed check-in rolls its number back and the numbers called out have no gaps. The outbox relay reads with `FOR UPDATE SKIP LOCKED`, so several instances can relay at once without publishing the same event twice. |
| **RabbitMQ**, plain Java client | Topic exchange for fan-out by status; quorum queue with `x-delivery-limit` for bounded retries; dead-letter exchange; publisher confirms. The plain client rather than SmallRye Reactive Messaging, because the design needs things the connector makes awkward: a server-named exclusive queue per instance, starting without a broker, and publishing driven by the outbox with confirms. |
| **Server-Sent Events** over WebSockets | Updates only flow server → phone. SSE is plain HTTP, reconnects by itself and passes proxies. |
| **Micrometer + Prometheus, Loki, Grafana** | Quarkus' Micrometer extension serves `/q/metrics`, which Prometheus pulls. Logs go the other way round: a JSON file that Alloy ships to Loki, because Prometheus stores numbers, not text. Grafana shows both, so a spike on a graph is one click from the log lines behind it. Plain files rather than a logging library pushing to Loki: the app never waits for, or fails because of, the observability stack. |
| **SvelteKit** (Svelte 5, TypeScript) | Small reactive components and file-based routes for the four screens. Built with `adapter-static` as a single-page app (SSR off: every screen is live data): static files that any web server can host (the image uses nginx), no Node.js at runtime. It is built and deployed apart from the backend, which allows its origin through CORS. |

## Failure handling

The guiding rule: **the database is the source of truth, RabbitMQ is how work and news travel.** Losing the broker
slows things down but never loses a prescription or blocks a patient. Losing the database stops anything from being
saved, but nothing hangs, nothing is lost, and everything resumes by itself when it is back.

Two pieces in `infra/.../persistence` carry most of it. **`Transactions`** is the only way the doors into the domain
touch the database: one transaction per change, lost races (deadlock, serialization failure, lock timeout) retried
right away, and an unreachable or too slow database turned into a `DatabaseUnavailableException` (HTTP 503 with
`Retry-After`) rather than an unexpected error. **`DatabaseHealth`** is a circuit breaker fed by every call: once the
database is unreachable, requests fail fast instead of each waiting for its own timeout, background work pauses, and a
background probe closes the circuit as soon as the database answers.

| What fails | What happens | Where it surfaces |
|---|---|---|
| RabbitMQ is down at check-in | The prescription and its event are committed together (**transactional outbox**). The patient gets their ticket. The relay publishes when the broker is back. | Staff console banner, `/health` (`broker: down`, `pendingEvents`, age of the oldest) |
| RabbitMQ goes down mid-day | The client reconnects automatically and subscribes the consumers again. The app also starts without a broker and keeps retrying in the background. | Logs: *Lost the publisher connection... / Reconnected...*, a reminder every 30s with the backlog |
| Stock check delayed (broker down, message lost or parked) | The **sweeper** runs, from the database, every stock check that waited more than 15s, every 10s, on every instance (idempotent, optimistically locked). Staff can also **Retry** by hand. | Usually nothing: done before the patient's 30s *"taking longer than usual"*. Staff banner while any wait longer |
| RabbitMQ low on memory or disk (publishers blocked) | Publishing and consuming use separate connections, so the consumers keep draining the queue. The relay pauses instead of holding a database transaction open; the sweeper does the stock checks. | Staff banner, `/health` (`brokerBlocked`), logs |
| The broker drops a consumer (queue deleted, channel error) | `SupervisedConsumer` subscribes again, declaring the queue, with backoff. Auto-recovery alone only covers lost connections. | Logs: *the broker cancelled the subscription... subscribed again* |
| Stock check keeps failing | Rejected back to the queue with a 1s backoff. After 5 redeliveries (the 6th failed attempt) the quorum queue dead-letters it to `pharmacy.stock-check.dead-letter`; the sweeper keeps retrying the prescription, with growing pauses. Unreadable messages go to the dead-letter queue immediately. | Staff banner with the dead-letter count; logs; RabbitMQ UI |
| Database down or restarting | Requests answer 503 at once (the check-in page retries by itself, with the same id). The stock check consumer *holds* its message until the database answers (handing it back every couple of minutes, which RabbitMQ does not count as a failed delivery, so the messages waiting behind it never outwait RabbitMQ's 30-minute limit), so an outage uses up no delivery attempts and parks nothing. The relay and the sweeper pause. Live screens stay connected, say updates are delayed, and catch up when it is back. The app also waits for the database at startup (up to 5 minutes). | Patient: *"Updates are delayed..."*. Staff banner, `/health` (`database: down`, still answering), `/q/health/ready` down, logs: outage start, reminder every 30s, its length when over |
| Database hangs (frozen, network partition) | Nothing waits forever: connect 3s, login 5s, socket 15s, statements 10s, lock waits 5s, idle transactions 60s, pool wait 5s. The first failure opens the circuit; after that, requests fail in milliseconds. | As above |
| Government system down while a patient checks in | The lookup fails with a 502 (not a 503, which the web app retries as a database outage) and a message that says to try again or enter the medication by hand, which still works. Nothing is saved, so nothing is half done. | Check-in page; logs |
| Government system down at hand-over | The hand-over works, and the commit waits in the database (**outbox**), retried with growing pauses until the government answers. | Staff console banner once one waits a minute; `/health` (`pendingGovernmentCommits`); logs |
| Government commit refused | Marked `REJECTED` and never sent again: it needs a person. | Staff console banner, `/health` (`rejectedGovernmentCommits`), error in the log with the government's reason |
| Government commit sent twice (the app died before noting the answer) | The prescription id travels as the commit's id, so the government recognizes the repeat. | Nothing to see |
| The same number checked in on two phones at once | Check-ins queue on the ticket counter, and each counts what the tickets before it hold on that government prescription: one gets the packs, the other is told, in words, that they are on a ticket still in progress. | Check-in page |
| A check-in id used again for other details | Refused with a 409 (*"This check-in was already sent with other details..."*) instead of handing back the first ticket. The check-in page asks for a new id when the patient changed what they ask for after a failed attempt. | Check-in page |
| Message delivered twice (relay retry, redelivery, sweeper) | At-least-once delivery is the price of the outbox, so consumers are idempotent. A stock check only acts on `RECEIVED` prescriptions. | Logs: *Stock check for #17 skipped: already AWAITING_APPROVAL* |
| Live update event lost | Patient screens also re-read the database every 10s, and only send when something changed. A failing re-read does not close the stream. Live screens use at most 4 database connections, whatever their number. | Nothing to see: worst case a 10s delay |
| A background loop gets stuck (outbox relay, stock check sweeper, government commit relay) | The liveness check fails, so an orchestrator restarts the instance. | `/q/health/live`, logs |
| Not enough stock | All or nothing: items already reserved for that prescription are put back in the same transaction. Concurrent reservations cannot oversell (conditional decrement). Two patients after the last pack: stock checks run one at a time, oldest ticket first (a check for a later ticket serves the earlier ones that still wait before its own), so the pack goes to the earlier ticket and the other is told it is short, whichever message, sweeper run or instance gets there first. | Patient: which medication, and what to do. Logs: *Stock check for #3: #2 checked in earlier and still waits, so it is checked first* |
| Two staff act on the same prescription | Optimistic locking (`version`) plus domain rules → HTTP 409 with a readable message (*"Prescription #7 is already waiting to be packed: someone else may have just done this"*). | Staff console error banner |
| Patient's network drops, or the response is lost | Check-in carries a client-generated id, so a retry returns the same ticket, even when the retry arrives while the first is still running. SSE reconnects by itself, also when a phone wakes a suspended tab. | *"Reconnecting... your place in line is safe"* |
| Bad input | Validation in the domain → HTTP 400 with a message written for patients. A body that is not what the endpoint takes (a null or a word where a number goes, 1.5 packs) is a 400 too, in the same `{"error": "..."}` shape, saying which value is wrong. A missing value (no quantity to restock, no reason to reject) is told in words. Unknown routes and wrong methods answer 404 and 405 in the same shape, never in the framework's words. | Check-in form |
| A request that stalls (a phone on a bad connection) | The web app gives up on a request after 15s and treats it like a network failure: reads and the check-in are tried again by themselves. | Check-in page |
| The check-in page cannot load the medication (backend or database down) | The page says so and tries again by itself every few seconds, so it works again as soon as the backend does, without a reload. | Check-in page |
| Unexpected error | Logged with the stack trace. The client gets a generic 500 message, never internals. | Logs |

### Logging

Every line names a prescription by its ticket number (and id), never by the patient's name.

- **`pharmacy.domain`**: the business events. Check-ins, every status change (`#17 RECEIVED -> AWAITING_APPROVAL`)
  and every refused one with the reason, what a stock check reserved or found short, stock put back, restocks. The
  domain logs through the JDK's `System.Logger`, so it still depends on nothing.
- **`pharmacy.infra`**: one line per API request (method, path, status, time, and the error the client got);
  database outages (when, why, how long) and slow or retried work; RabbitMQ connections, blocks, consumers subscribing
  again, the relay pausing and catching up (with its backlog), each stock check (time, delivery attempt, how long it
  waited for the database), the sweeper, dead-lettered messages; a startup summary of the timeouts in force.
- Repetitive trouble is throttled: an outage gets one warning, then a reminder every 30 seconds, and a line when it is
  over. DEBUG (on in dev mode) adds every published batch, live stream and broker check; TRACE every request and
  database call.
- Besides the console, every line goes to a JSON file (`logs/pharmacy.log`), each marked **`business`** (what the
  domain logs: `pharmacy.domain`) or **`system`** (everything else), for the observability stack below.

### Observability

`docker compose up -d` also starts Prometheus, Loki, Alloy and Grafana (open **http://localhost:3000**, `pharmacy` /
`pharmacy`; it lands on the business dashboard). The backend needs no setup: Prometheus pulls its metrics from
`/q/metrics`, and Alloy ships its log file to Loki. Prometheus is at http://localhost:9090.

| Dashboard | Shows |
|---|---|
| **Pharmacy · Business** | Prescriptions checked in, in the pharmacy now, collected, out of stock, rejected, no shows, average turnaround; prescriptions at each stage and how long the longest has waited; status changes over time; stock per medication; and from the business logs, the refusals, stock shortages and conflicting changes. |
| **Pharmacy · System** | Is the API, the database and RabbitMQ up; outbox backlog, dead letters, stalled stock checks; request rate and latency (p50, p95, p99); JVM, CPU, database pool, RabbitMQ; and the system warnings and errors from the logs. |
| **Pharmacy · Integrations** | The systems outside the pharmacy: today the government prescription system. Whether it answers, how many calls succeed, fail or are refused, how long they take, the commits waiting for it (and for how long) or refused by it, and the log lines about it. |
| **Pharmacy · Logs** | Every log line, filtered by type (business or system), level and text. |

**Business and system logs.** The mark is the `log_type` field of each JSON line, and a label in Loki, so
`{log_type="business"}` is what happened to prescriptions and stock, and `{log_type="system"}` is requests, the
database and RabbitMQ. The rule is the package: whatever is logged through the domain's `DomainLog` is business, the
rest is system (`infra/.../logging/LogType.kt`). The labels are `log_type`, `level` and `service_name`; the logger and
thread are structured metadata. Some LogQL to start from:

```
{log_type="business"} |= "refused"                                  # what the rules refused, and why
{log_type="business", level="WARN"}                                 # stock checks that found too little
{service_name="pharmacy-api"} |~ "(?i)government"                   # everything about the government system
sum by (level) (count_over_time({log_type="system"} [5m]))          # system noise by level
```

**Metrics.** Prometheus keeps 15 days, Loki 7. The `pharmacy_*` ones are the app's own; the rest come from Quarkus
(JVM, `http_server_requests_seconds_*` with latency buckets, `agroal_*` for the connection pool) and RabbitMQ itself.

| Metric | |
|---|---|
| `pharmacy_prescription_status_changes_total{status}` | Business. Counter of prescriptions moving into each status; `RECEIVED` is a check-in. Counted when the transaction commits, so retries and rollbacks do not count. Starts at 0 on every restart: use `increase()` or `rate()`. |
| `pharmacy_prescriptions{status}` | Business. Prescriptions in each status right now. |
| `pharmacy_prescription_oldest_wait_seconds{status}` | Business. How long the longest-waiting prescription has been at each open stage. |
| `pharmacy_medication_stock{medication}` | Business. Packs in stock. |
| `pharmacy_turnaround_seconds` | Business. Average time from check-in to hand-over, for what was collected in the last hour. |
| `pharmacy_database_up`, `pharmacy_broker_up`, `pharmacy_broker_blocked` | System. The same signals as `/health`. |
| `pharmacy_outbox_pending_events`, `pharmacy_outbox_oldest_age_seconds` | System. Events waiting for the broker, and the age of the oldest. |
| `pharmacy_broker_dead_letters`, `pharmacy_broker_stock_checks_queued`, `pharmacy_stock_checks_stalled` | System. Stock check messages parked, queued, and late. |
| `pharmacy_live_streams_open` | System. Live screens connected to this instance. |
| `pharmacy_government_requests_total{operation,outcome}` | System. Calls to the government system: `lookup` ends `found`, `not_found` or `failed`; `commit` ends `accepted`, `refused` (it said no for good) or `failed` (unreachable, slow or erroring: tried again later). Starts at 0 on every restart. |
| `pharmacy_government_request_seconds{operation}` | System. How long those calls take, as a histogram. A call that times out counts as the timeout (5s). |
| `pharmacy_government_up` | System. 1 if the last call to the government system was answered, 0 if it failed. No value before the first call: nothing asks it while nobody looks anything up or collects. |
| `pharmacy_government_commits_pending`, `pharmacy_government_commits_oldest_pending_age_seconds`, `pharmacy_government_commits_rejected` | System. Commits waiting for the government system and the age of the oldest, and commits it refused for good. The same figures as `/health`. |

The ones that need the database (the outbox backlogs, stock, prescriptions) are read in the background every 10 seconds,
never during a scrape, and have **no value** (not a stale one) while the database cannot be read.

Notes:

- The stack is for development and small deployments: single processes, local disks, a fixed Grafana login, no alert
  rules. `/q/metrics` is on the API's port without authentication, like the rest of the API (see What I would do next); in
  production, keep it inside the network.
- Prometheus reaches a backend running on the host through `host.docker.internal:8080` (`observability/prometheus`).
  Docker Desktop reaches a dev mode backend listening on localhost; on Linux start it with `QUARKUS_HTTP_HOST=0.0.0.0`.
  A backend running in a compose service needs its own target there, and its log folder mounted into Alloy.
- The backend writes the file whether or not the stack runs (20 MB, five files kept). In tests it is off.

## API

| Method | Path | |
|---|---|---|
| GET | `/api/medications` | Catalog with available stock |
| GET | `/api/government-prescriptions/{number}` | What a government prescription lets the patient collect: `{number, items: [{medicationId, quantity, maximum}]}`. 404 unknown number, 400 already used in full, 502 government system unreachable |
| POST | `/api/prescriptions` | Check in `{id?, patientName, items: [{medicationId, quantity}], governmentNumber?}` → `201 {id, ticketNumber}`. With a `governmentNumber`, the items must be covered by it, less what other tickets on it hold. The same `id` again returns the same ticket; with other details, 409 |
| GET | `/api/prescriptions/{id}` | Tracking view (status, people ahead, delayed, items) |
| GET | `/api/prescriptions/{id}/live` | SSE stream of the tracking view (`event: status`) |
| GET | `/api/board`, `/api/board/live` | Waiting-room board (ticket numbers only), and its SSE stream |
| GET | `/api/staff/queues` | Stock check, pharmacist, packaging and pickup queues |
| POST | `/api/staff/prescriptions/{id}/approve` · `reject {reason}` · `packed` · `call` · `no-show` · `hand-over` · `retry-stock-check` | Staff actions. `reject` needs a reason of at most 200 characters, which the patient reads. `no-show` only for a called number: 409 otherwise |
| POST | `/api/staff/medications/{id}/restock {quantity}` | Add stock |
| GET | `/health` | For the staff console: database and broker status, undelivered events, stalled stock checks, dead letters, government commits waiting (and for how long) or refused. Answers even when the database does not |
| GET | `/q/health/live`, `/q/health/ready`, `/q/health/well` | For orchestrators: background loops running; database reachable; RabbitMQ (informational only: the app works without it) |

## Out of scope

- **No authentication or authorization.** Nobody signs in. The staff console and every `/api/staff` endpoint are open
  to anyone who can reach the server, and so are `/health` and the government lookup. There are no roles (a packager
  can approve, a pharmacist can restock), and nothing records which staff member took an action. Patients are not
  identified either: whoever has a tracking link (an unguessable UUID) sees that prescription, and the public board
  shows numbers only. In production the staff side would sit behind a login, with roles.
- **No multitenancy.** One deployment is one pharmacy: one catalog and stock, one queue, one ticket number sequence,
  one government-system identity. No table, queue, event or request carries a pharmacy id, so two pharmacies on the
  same deployment would share stock and tickets. Serving several would mean a tenant id through the schema, the
  queries, the live streams and the government calls, not a setting.

## What could be next

- Add remote check-in with customers able to send the prescription beforehand and getting a code for pickup
- Add history of transactions (prescription status) so some analysis may run on it (it may be done from logs already).
- Replace RabbitMQ by Postgres queueing extensions and reduce infrastructure if the scale allows it
- If the application keeps simple like this, replace SvelteKit by pure HTML + Alpinejs