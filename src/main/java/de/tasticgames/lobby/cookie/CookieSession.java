package de.tasticgames.lobby.cookie;

import de.tasticgames.lobby.cookie.domain.model.CookieProfile;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runtime session of one player's cookie profile (main-thread mutations, async saves).
 */
public final class CookieSession {

    private final UUID player;
    private final CookieProfile profile;
    private volatile Instant lastTickAt = Instant.now();
    private volatile Instant lastSavedAt = Instant.now();
    private volatile Instant dirtySince;
    private final AtomicBoolean saving = new AtomicBoolean(false);
    private final AtomicInteger saveFailures = new AtomicInteger();
    private volatile boolean paused;
    private volatile boolean offlineDialogShown;
    private volatile long lastActionbarAt;
    private volatile int clicksSinceReport;
    private volatile Instant sessionStartedAt = Instant.now();
    private volatile java.util.concurrent.CompletableFuture<Boolean> inFlightSave;

    public CookieSession(UUID player, CookieProfile profile) {
        this.player = player;
        this.profile = profile;
    }

    public UUID player() { return player; }
    public CookieProfile profile() { return profile; }
    public Instant lastTickAt() { return lastTickAt; }
    public void lastTickAt(Instant value) { lastTickAt = value; }
    public Instant lastSavedAt() { return lastSavedAt; }
    public void lastSavedAt(Instant value) { lastSavedAt = value; dirtySince = null; }
    public Instant dirtySince() { return dirtySince; }
    public void touchDirty() { if (dirtySince == null) dirtySince = Instant.now(); }
    public AtomicBoolean saving() { return saving; }
    public AtomicInteger saveFailures() { return saveFailures; }
    public boolean paused() { return paused; }
    public void paused(boolean value) { paused = value; }
    public boolean offlineDialogShown() { return offlineDialogShown; }
    public void offlineDialogShown(boolean value) { offlineDialogShown = value; }
    public long lastActionbarAt() { return lastActionbarAt; }
    public void lastActionbarAt(long value) { lastActionbarAt = value; }
    public int takeClicks() { int c = clicksSinceReport; clicksSinceReport = 0; return c; }
    public void countClick() { clicksSinceReport++; }
    public Instant sessionStartedAt() { return sessionStartedAt; }
    public java.util.concurrent.CompletableFuture<Boolean> inFlightSave() { return inFlightSave; }
    public void inFlightSave(java.util.concurrent.CompletableFuture<Boolean> value) { inFlightSave = value; }
}
