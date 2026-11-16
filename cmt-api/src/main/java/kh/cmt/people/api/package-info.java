/**
 * What other modules may use from the people module: {@link kh.cmt.people.api.PeopleQueries}. Departments of people
 * (record scope) and people counts are reached through the shared kernel ({@code PersonDepartments},
 * {@code PeopleCounts}), because the modules asking (iam, org) are used by people themselves.
 */
@NamedInterface("api")
package kh.cmt.people.api;

import org.springframework.modulith.NamedInterface;
