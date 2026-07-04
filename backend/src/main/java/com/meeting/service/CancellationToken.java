package com.meeting.service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<Runnable> cancelAction = new AtomicReference<>();

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            Runnable action = cancelAction.get();
            if (action != null) {
                action.run();
            }
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void setCancelAction(Runnable action) {
        cancelAction.set(action);
        // If already cancelled, execute immediately
        if (cancelled.get()) {
            Runnable a = cancelAction.getAndSet(null);
            if (a != null) a.run();
        }
    }
}
