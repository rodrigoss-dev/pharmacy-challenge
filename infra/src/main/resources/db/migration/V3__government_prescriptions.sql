-- The government prescription a patient checked in with, if any: what they take is committed to it once they collect.
ALTER TABLE prescriptions ADD COLUMN government_number varchar;

-- Transactional outbox for the government system: a row is written in the transaction that hands a prescription over,
-- and GovernmentCommitRelay sends it afterwards. Unlike the event outbox, sent rows stay: what the pharmacy reported to
-- the government is worth keeping.
--
-- One commit per prescription, ever: the prescription id is also the key the government recognizes a repeat by.
-- status: PENDING (to be sent, or sent and not acknowledged yet), COMMITTED, or REJECTED (the government refused it for
-- good: sending it again cannot help, so someone has to look).
CREATE TABLE government_commits (
    prescription_id   uuid        PRIMARY KEY REFERENCES prescriptions (id),
    government_number varchar     NOT NULL,
    items             jsonb       NOT NULL,
    status            varchar     NOT NULL,
    attempts          integer     NOT NULL,
    next_attempt_at   timestamptz NOT NULL,
    last_error        varchar,
    created_at        timestamptz NOT NULL,
    sent_at           timestamptz
);

CREATE INDEX government_commits_due ON government_commits (status, next_attempt_at);
