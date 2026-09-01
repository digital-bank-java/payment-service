create table payment_instructions (
    id uuid not null,
    idempotency_key text not null,
    correlation_id text not null,
    amount numeric(19, 4) not null,
    currency varchar(3) not null,
    description text not null,
    status varchar(30) not null,
    failure_reason text,
    version bigint not null default 0,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint pk_payment_instructions primary key (id),
    constraint uq_payment_instructions_idempotency_key unique (idempotency_key),
    constraint ck_payment_instructions_idempotency_key_non_blank check (length(btrim(idempotency_key)) > 0),
    constraint ck_payment_instructions_correlation_id_non_blank check (length(btrim(correlation_id)) > 0),
    constraint ck_payment_instructions_amount_positive check (amount > 0),
    constraint ck_payment_instructions_currency_format check (currency ~ '^[A-Z]{3}$'),
    constraint ck_payment_instructions_status check (status in ('PENDING', 'COMPLETED', 'FAILED')),
    constraint ck_payment_instructions_failure_reason check (
        (status = 'FAILED' and failure_reason is not null and length(btrim(failure_reason)) > 0)
        or (status <> 'FAILED' and failure_reason is null)
    ),
    constraint ck_payment_instructions_version_non_negative check (version >= 0),
    constraint ck_payment_instructions_timestamps check (updated_at >= created_at)
);
