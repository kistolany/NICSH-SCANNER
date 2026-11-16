package kh.cmt.org.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** A job title such as "Accountant". (Table job_position: "position" is an SQL keyword.) */
@Entity
@Table(name = "job_position")
public class Position extends LocalizedOrgItem {
}
