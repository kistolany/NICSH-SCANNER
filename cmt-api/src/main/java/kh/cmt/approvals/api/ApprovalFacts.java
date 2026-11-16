package kh.cmt.approvals.api;

import java.util.Map;
import java.util.UUID;

/**
 * What a module adds to a request's detail sheet, as of now (the balance after a leave, "Late 3 times this month"…):
 * every module that knows something about the person may answer, for any type; keys are the module's own, nothing
 * (an empty map) when it has nothing to say.
 */
@FunctionalInterface
public interface ApprovalFacts {

    Map<String, Object> facts(Approvals.Type type, UUID personId, UUID subjectId, Map<String, Object> details);
}
