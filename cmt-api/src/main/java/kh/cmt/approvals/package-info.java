/**
 * approvals module — see docs/BACKEND ARCHITECTURE - Spring Boot.md, section 4 and 7.3. One {@code approval} table for
 * every request that waits for an admin. Step 7.4 sends LATE_REASON requests ({@link kh.cmt.approvals.api.Approvals});
 * the inbox that decides them (handlers per type, limits, escalation, undo) is phase 10.
 */
package kh.cmt.approvals;
