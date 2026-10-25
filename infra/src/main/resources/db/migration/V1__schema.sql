-- Strings are varchar (no length): the same type as text in PostgreSQL, but jOOQ's code generator can index it.

CREATE TABLE medications (
    id       varchar PRIMARY KEY,
    name     varchar NOT NULL,
    category varchar NOT NULL,
    stock    integer NOT NULL CHECK (stock >= 0)
);

-- The last ticket number handed out: a single row. Unlike a sequence it rolls back with a failed check-in, so the
-- numbers called out have no gaps; concurrent check-ins wait on its row lock instead of getting the same number.
CREATE TABLE ticket_counter (
    last_number integer NOT NULL
);

-- [jooq ignore start]
INSERT INTO ticket_counter (last_number) VALUES (0);
-- [jooq ignore stop]

CREATE TABLE prescriptions (
    id            uuid        PRIMARY KEY,
    ticket_number integer     NOT NULL UNIQUE,
    patient_name  varchar     NOT NULL,
    status        varchar     NOT NULL,
    status_reason varchar,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    version       integer     NOT NULL
);

CREATE INDEX prescriptions_by_status ON prescriptions (status, ticket_number);

CREATE TABLE prescription_items (
    prescription_id uuid    NOT NULL REFERENCES prescriptions (id),
    medication_id   varchar NOT NULL REFERENCES medications (id),
    medication_name varchar NOT NULL,
    quantity        integer NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (prescription_id, medication_id)
);

-- Transactional outbox: events written in the same transaction as the state change, relayed to RabbitMQ afterwards.
CREATE TABLE outbox (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    routing_key varchar     NOT NULL,
    payload     jsonb       NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);
