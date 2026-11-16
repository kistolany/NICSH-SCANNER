-- V12: pay of a person (Add / Edit person > Schedule & pay > Pay, and the bank block). Payroll module.
--
-- History = rows: every change is a new row with the date it applies from (effective_from); a row is never changed
-- afterwards (the trigger below refuses it). The row in force on a day is the latest one with effective_from <= that day.
--   pay_type  monthly (amount = monthly salary, payment_day, allowances)
--             hourly (amount = hourly rate, overtime x multiplier or a fixed overtime rate)
--             teaching (amount = rate per teaching hour, rates per subject / grade, rate for substitute / extra hours)
--             none (volunteers, people paid outside the system)
--   currency  USD | KHR (riel amounts are whole numbers)
CREATE TABLE pay_rate (
    id                    uuid PRIMARY KEY,
    school_id             uuid          NOT NULL REFERENCES school (id),
    person_id             uuid          NOT NULL REFERENCES person (id),
    pay_type              varchar(10)   NOT NULL CHECK (pay_type IN ('monthly', 'hourly', 'teaching', 'none')),
    amount                numeric(14, 2),
    currency              varchar(3)    CHECK (currency IN ('USD', 'KHR')),
    payment_day           int           CHECK (payment_day BETWEEN 1 AND 31),
    allowances            jsonb         NOT NULL DEFAULT '[]',
    overtime_multiplier   numeric(4, 2),
    overtime_rate         numeric(14, 2),
    subject_rates         jsonb         NOT NULL DEFAULT '[]',
    substitute_rate       numeric(14, 2),
    effective_from        date          NOT NULL,
    bank_name             varchar(100),
    account_number        varchar(30),
    account_name          varchar(150),
    tax_number            varchar(30),
    created_at            timestamptz   NOT NULL,
    created_by            uuid,
    updated_at            timestamptz   NOT NULL,
    updated_by            uuid,
    version               bigint        NOT NULL DEFAULT 0,
    CHECK ((pay_type = 'none') = (amount IS NULL))
);

CREATE UNIQUE INDEX pay_rate_person_date_idx ON pay_rate (person_id, effective_from);

CREATE FUNCTION pay_rate_never_changed() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'pay_rate rows are never changed: add a new row with its effective date';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER pay_rate_never_changed BEFORE UPDATE ON pay_rate FOR EACH ROW EXECUTE FUNCTION pay_rate_never_changed();
