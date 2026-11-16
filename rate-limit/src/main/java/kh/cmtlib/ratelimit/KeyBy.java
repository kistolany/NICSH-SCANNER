package kh.cmtlib.ratelimit;

/** What a limit is counted per. */
public enum KeyBy {

    /** The caller's IP address. For login-type endpoints, where nobody is signed in yet. */
    IP,

    /** The signed-in user; callers that are not signed in are counted per IP instead. */
    USER
}
