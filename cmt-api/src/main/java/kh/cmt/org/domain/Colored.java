package kh.cmt.org.domain;

/** An org item that has a colour (departments), shown as a dot or bar in the apps. */
public interface Colored {

    String getColor();

    void setColor(String color);
}
