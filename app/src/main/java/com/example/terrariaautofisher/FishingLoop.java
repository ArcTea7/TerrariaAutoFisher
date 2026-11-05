package com.example.terrariaautofisher;

/**
 * Serial reel/recast coordinator. Call every method and completion on the main thread.
 * Tracking can stay lost indefinitely without cancelling an already owed recast.
 * Start assumes the player has already cast. Recovery needs a separate request
 * after the observer has confirmed a missing line.
 */
public final class FishingLoop {
    public static final long REEL_TO_CAST_MS = 650;
    public static final long CAST_SETTLE_MS = 400;
    public static final long RETRY_MS = 500;
    public static final long CALLBACK_TIMEOUT_MS = 1500;

    public enum Stage { STOPPED, WATCHING, REELING, WAITING_TO_CAST, CASTING, SETTLING,
        RETRYING_REEL, RETRYING_CAST }
    public interface Cancellation { void cancel(); }
    public interface Scheduler { Cancellation schedule(long delayMs, Runnable action); }
    public interface Completion { void finish(boolean completed); }
    public interface Tapper { void tap(Completion completion); }
    public interface Listener { void changed(Stage stage); }

    private final Scheduler scheduler;
    private final Tapper tapper;
    private final Listener listener;
    private Cancellation pending, watchdog;
    private Stage stage = Stage.STOPPED;
    private boolean active;
    private long session, attempt;

    public FishingLoop(Scheduler scheduler, Tapper tapper, Listener listener) {
        this.scheduler=scheduler;this.tapper=tapper;this.listener=listener;
    }
    public Stage stage() { return stage; }
    public boolean active() { return active; }
    public boolean busy() { return active && stage!=Stage.WATCHING; }

    public void startWatching() {
        stop();
        active=true;
        setStage(Stage.WATCHING);
    }
    public void stop() {
        active=false;session++;attempt++;
        cancelPending();cancelWatchdog();
        setStage(Stage.STOPPED);
    }
    public boolean bite() {
        if(!active || stage!=Stage.WATCHING)return false;
        sendTap(false,session);
        return true;
    }
    /** One cast attempt, not a reel/cast pair. Shares normal tap serialization. */
    public boolean recoverCast() {
        if(!active || stage!=Stage.WATCHING)return false;
        sendTap(true,session);
        return true;
    }
    private boolean current(long token) { return active && session==token; }
    private void setStage(Stage value) { stage=value;listener.changed(value); }
    private void cancelPending() { if(pending!=null){pending.cancel();pending=null;} }
    private void cancelWatchdog() { if(watchdog!=null){watchdog.cancel();watchdog=null;} }

    private void sendTap(boolean cast,long token) {
        if(!current(token))return;
        pending=null;
        setStage(cast?Stage.CASTING:Stage.REELING);
        // A listener may synchronously stop the run (e.g. service teardown).
        if(!current(token))return;
        final long tapAttempt=++attempt;
        watchdog=scheduler.schedule(CALLBACK_TIMEOUT_MS,()->complete(cast,token,tapAttempt,false));
        try {
            tapper.tap(success->complete(cast,token,tapAttempt,success));
        } catch(RuntimeException error) {
            complete(cast,token,tapAttempt,false);
        }
    }
    private void complete(boolean cast,long token,long tapAttempt,boolean success) {
        if(!current(token)||tapAttempt!=attempt)return;
        attempt++; // Late or duplicate callbacks cannot advance this or a later attempt.
        cancelWatchdog();
        if(!success){
            setStage(cast?Stage.RETRYING_CAST:Stage.RETRYING_REEL);
            if(current(token))pending=scheduler.schedule(RETRY_MS,()->sendTap(cast,token));
            return;
        }
        if(!cast){
            setStage(Stage.WAITING_TO_CAST);
            if(current(token))pending=scheduler.schedule(REEL_TO_CAST_MS,()->sendTap(true,token));
        }else{
            setStage(Stage.SETTLING);
            if(current(token))pending=scheduler.schedule(CAST_SETTLE_MS,()->{
                if(!current(token))return;
                pending=null;setStage(Stage.WATCHING);
            });
        }
    }
}
