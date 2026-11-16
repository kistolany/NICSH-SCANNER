-- Step 7.2: what a refused scan was told (the time already checked in, how far outside, …), so a scan sent again
-- (its answer was lost) gets the very same answer. Written once with the row, like the rest of it.
alter table scan_event add column details jsonb;
