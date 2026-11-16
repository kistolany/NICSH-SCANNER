-- V29 (step 10.1, one approvals inbox): a decision shows at once but takes effect 10 s later, so Undo within that time
-- cancels it with nothing to reverse. effective_at = when the effect runs; applied_at = when it ran (null while it can
-- still be undone); effect_error = why the effect could not run (the decision stays).
-- Late / early reasons approved by an admin are excused: the minutes stay on the record, they no longer count.

ALTER TABLE approval
    ADD COLUMN effective_at  timestamptz,
    ADD COLUMN applied_at    timestamptz,
    ADD COLUMN effect_error  varchar(200);

-- decided before this step: applied when decided
UPDATE approval SET effective_at = decided_at, applied_at = decided_at WHERE status IN ('APPROVED', 'REJECTED');

ALTER TABLE approval ADD CONSTRAINT approval_effect_check
    CHECK ((status IN ('APPROVED', 'REJECTED')) = (effective_at IS NOT NULL));

-- the effects job: decisions whose 10 s are over
CREATE INDEX approval_due_idx ON approval (effective_at) WHERE applied_at IS NULL AND effective_at IS NOT NULL;
CREATE INDEX approval_decided_idx ON approval (school_id, decided_at DESC) WHERE status IN ('APPROVED', 'REJECTED');

ALTER TABLE attendance_record
    ADD COLUMN late_excused   boolean NOT NULL DEFAULT false,
    ADD COLUMN early_excused  boolean NOT NULL DEFAULT false;
