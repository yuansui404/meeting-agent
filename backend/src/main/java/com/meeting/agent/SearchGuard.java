package com.meeting.agent;

public class SearchGuard {
    private static final ThreadLocal<Boolean> SEARCHED = ThreadLocal.withInitial(() -> false);

    public static boolean hasSearched() { return SEARCHED.get(); }
    public static void markSearched() { SEARCHED.set(true); }
    public static void reset() { SEARCHED.remove(); }
}
