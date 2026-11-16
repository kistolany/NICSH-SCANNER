-- V30: how early a check-in is taken. checkin_opens_minutes = minutes before a shift starts from which a scan checks in
-- ("opens 60 min before 07:45" = from 06:45); earlier scans are refused (TOO_EARLY). Null = no limit (as before).
ALTER TABLE shift_template
    ADD COLUMN checkin_opens_minutes int CHECK (checkin_opens_minutes BETWEEN 0 AND 720);
