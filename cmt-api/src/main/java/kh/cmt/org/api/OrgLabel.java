package kh.cmt.org.api;

import java.util.UUID;
import kh.cmt.shared.i18n.LocalizedName;

/**
 * How another module shows an org item: its {@code name}, the colour where the kind has one, and whether it is
 * inactive (still shown on old records).
 */
public record OrgLabel(UUID id, LocalizedName name, String color, boolean inactive) {
}
