create table payment_instruction_outbox (
    event_id uuid not null,
    instruction_id uuid not null,
    aggregate_id uuid not null,
    event_type varchar(100) not null,
    schema_version varchar(20) not null,
    producer varchar(100) not null,
    occurred_at timestamp with time zone not null,
    correlation_id text not null,
    causation_id text not null,
    idempotency_key text not null,
    amount numeric(19, 4) not null,
    currency varchar(3) not null,
    event_status varchar(30) not null,
    failure_reason text,
    payload jsonb not null,
    publication_status varchar(20) not null default 'PENDING',
    attempt_count integer not null default 0,
    next_attempt_at timestamp with time zone not null,
    claim_id uuid,
    claim_until timestamp with time zone,
    published_at timestamp with time zone,
    last_error text,
    created_at timestamp with time zone not null,
    constraint pk_payment_instruction_outbox primary key (event_id),
    constraint fk_payment_instruction_outbox_instruction foreign key (instruction_id)
        references payment_instructions (id),
    constraint uq_payment_instruction_outbox_instruction_status unique (instruction_id, event_status),
    constraint ck_payment_instruction_outbox_event_type check (event_type = 'PaymentInstructionStateChanged.v1'),
    constraint ck_payment_instruction_outbox_schema_version check (schema_version = '1.0.0'),
    constraint ck_payment_instruction_outbox_producer check (producer = 'payment-service'),
    constraint ck_payment_instruction_outbox_correlation_non_blank check (length(btrim(correlation_id)) > 0),
    constraint ck_payment_instruction_outbox_causation_non_blank check (length(btrim(causation_id)) > 0),
    constraint ck_payment_instruction_outbox_idempotency_non_blank check (length(btrim(idempotency_key)) > 0),
    constraint ck_payment_instruction_outbox_amount_positive check (amount > 0),
    constraint ck_payment_instruction_outbox_currency_format check (currency ~ '^[A-Z]{3}$'),
    constraint ck_payment_instruction_outbox_event_status check (event_status in ('PENDING', 'COMPLETED', 'FAILED')),
    constraint ck_payment_instruction_outbox_failure_reason check (
        (event_status = 'FAILED' and failure_reason is not null and length(btrim(failure_reason)) > 0)
        or (event_status <> 'FAILED' and failure_reason is null)
    ),
    constraint ck_payment_instruction_outbox_publication_status check (publication_status in ('PENDING', 'PUBLISHED', 'FAILED')),
    constraint ck_payment_instruction_outbox_attempt_count check (attempt_count >= 0),
    constraint ck_payment_instruction_outbox_claim_pair check ((claim_id is null) = (claim_until is null)),
    constraint ck_payment_instruction_outbox_published_at check (
        (publication_status = 'PUBLISHED' and published_at is not null)
        or (publication_status <> 'PUBLISHED' and published_at is null)
    ),
    constraint ck_payment_instruction_outbox_timestamps check (occurred_at <= created_at)
);

create index ix_payment_instruction_outbox_eligible
    on payment_instruction_outbox (next_attempt_at, created_at)
    where publication_status = 'PENDING';
