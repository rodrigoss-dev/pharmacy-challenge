# gov-api: a mock of the government prescription system

The pharmacy lets a patient check in with the number of a government prescription. This is the system it asks. A mock,
for development and the demo: in memory (a restart brings back the seed data), no authentication, Node.js 22+ with no
dependencies.

```bash
npm start      # http://localhost:4000 (PORT to change it)
npm test
```

From the repository root, `make gov-api` does the same, and `make dev` starts it with everything else. The backend
reaches it at `GOVERNMENT_API_URL` (`http://localhost:4000`).

## API

### `GET /prescriptions/{number}`

The prescription, with what is left to collect. The number is not case sensitive.

```json
{
  "number": "GOV-1001",
  "items": [
    { "productId": "amoxicillin", "quantity": 1, "maximum": false },
    { "productId": "ibuprofen", "quantity": 2, "maximum": true }
  ]
}
```

- `productId` is the id of the medication in the pharmacy's catalog.
- `quantity` is what is left to collect. `0` means used in full.
- `maximum: true`: `quantity` is a ceiling, the patient may take anything from 1 up to it. `maximum: false`:
  `quantity` is what was prescribed, taken as it is.

`404` for an unknown number.

### `POST /prescriptions/{number}/commits`

Marks quantities as used, once the patient has their medication. `commitId` makes it safe to send twice.

```json
{ "commitId": "74dc665c-9f3d-4106-b00d-dddd9e92b9c1", "items": [{ "productId": "ibuprofen", "quantity": 1 }] }
```

A maximum of 2 and the patient took 1: commit 1, and the next `GET` shows `"quantity": 1`.

| Answer | When |
|---|---|
| `201` | Recorded. The body is the commit. |
| `200` | The same `commitId` with the same items again: nothing changes, the answer is the same. |
| `400` | Malformed: no `commitId`, no items, a quantity that is not a whole number of at least 1, a product listed twice, or a bad `%` escape in the number. |
| `404` | Unknown prescription. |
| `409` | A product that is not on the prescription, more than what is left, a product prescribed without a maximum used in part (it is used whole), or a `commitId` already used for something else. Nothing is recorded: all or nothing. |

### Also

| | |
|---|---|
| `GET /prescriptions/{number}/commits` | The commits received for a prescription, to see what the pharmacy sent. |
| `POST /admin/reset` | Back to the seed data. |
| `GET /health` | `{"status":"ok"}` |

## Prescriptions it knows

| Number | Medication | What it is for |
|---|---|---|
| `GOV-1001` | amoxicillin 1, ibuprofen up to 2 | Both kinds: taken as prescribed, and a maximum. |
| `GOV-1002` | metformin up to 3, lisinopril 1 | The same, with a larger maximum. |
| `GOV-1003` | atorvastatin up to 5, cetirizine 1 | The pharmacy has 3 atorvastatin in stock: the patient can take at most 3. |
| `GOV-1004` | omeprazole 1, already used | Looking it up says it has been used in full. |

Try the outbox: stop this server, hand a prescription over in the staff console, start it again. The commit arrives by
itself, within a few minutes at most.
