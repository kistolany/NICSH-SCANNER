-- A person can have one app login (the staff app) and one web login (a web user linked to them). The old index allowed
-- only one account per person, so linking a web user to someone who already had an app login failed.
DROP INDEX IF EXISTS account_person_idx;
CREATE UNIQUE INDEX account_person_idx ON account (person_id, type) WHERE person_id IS NOT NULL;
