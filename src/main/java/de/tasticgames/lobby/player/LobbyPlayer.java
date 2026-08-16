package de.tasticgames.lobby.player;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-player lobby runtime state (never persisted; persistent state lives in Core settings / API).
 */
public final class LobbyPlayer {

    public enum Mode { LOBBY, COOKIE_WORLD }

    private final UUID uuid;
    private final String name;
    private final Instant joinedAt = Instant.now();
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private volatile boolean buildMode;
    private volatile boolean teleporting;
    private volatile Mode mode = Mode.LOBBY;
    private volatile Instant launchProtectionUntil = Instant.EPOCH;
    private final AtomicLong lastLaunchAt = new AtomicLong();
    private final AtomicLong lastTeleportPadAt = new AtomicLong();
    private final AtomicLong lastItemUseAt = new AtomicLong();
    private final AtomicLong lastDoubleJumpAt = new AtomicLong();
    private volatile boolean doubleJumpArmed;
    private volatile String currentZoneId;
    private volatile String currentPoiId;
    private volatile Instant lastPositionSampleAt = Instant.EPOCH;

    public LobbyPlayer(UUID uuid, String name) {
        this.uuid = Objects.requireNonNull(uuid);
        this.name = Objects.requireNonNull(name);
    }

    public UUID uuid() { return uuid; }
    public String name() { return name; }
    public Instant joinedAt() { return joinedAt; }
    public boolean initialized() { return initialized.get(); }
    public boolean markInitialized() { return initialized.compareAndSet(false, true); }
    public boolean buildMode() { return buildMode; }
    public void buildMode(boolean value) { buildMode = value; }
    public boolean teleporting() { return teleporting; }
    public void teleporting(boolean value) { teleporting = value; }
    public Mode mode() { return mode; }
    public void mode(Mode value) { mode = Objects.requireNonNull(value); }
    public boolean inCookieWorld() { return mode == Mode.COOKIE_WORLD; }
    public Instant launchProtectionUntil() { return launchProtectionUntil; }
    public void launchProtectionUntil(Instant value) { launchProtectionUntil = value; }
    public boolean launchProtected() { return Instant.now().isBefore(launchProtectionUntil); }
    public String currentZoneId() { return currentZoneId; }
    public void currentZoneId(String value) { currentZoneId = value; }
    public String currentPoiId() { return currentPoiId; }
    public void currentPoiId(String value) { currentPoiId = value; }
    public Instant lastPositionSampleAt() { return lastPositionSampleAt; }
    public void lastPositionSampleAt(Instant value) { lastPositionSampleAt = value; }
    public boolean doubleJumpArmed() { return doubleJumpArmed; }
    public void doubleJumpArmed(boolean value) { doubleJumpArmed = value; }

    /** @return true when the cooldown passed (and records the use) */
    public boolean tryCooldown(AtomicLong last, long cooldownMillis) {
        long now = System.currentTimeMillis();
        long previous = last.get();
        if (now - previous < cooldownMillis) {
            return false;
        }
        return last.compareAndSet(previous, now);
    }

    public boolean tryLaunch(long cooldownMillis) { return tryCooldown(lastLaunchAt, cooldownMillis); }
    public boolean tryTeleportPad(long cooldownMillis) { return tryCooldown(lastTeleportPadAt, cooldownMillis); }
    public boolean tryItemUse(long cooldownMillis) { return tryCooldown(lastItemUseAt, cooldownMillis); }
    public boolean tryDoubleJump(long cooldownMillis) { return tryCooldown(lastDoubleJumpAt, cooldownMillis); }
}
