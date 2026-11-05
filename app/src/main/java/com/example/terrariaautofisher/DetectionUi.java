package com.example.terrariaautofisher;

/** User-facing presentation only. No detector state or thresholds live here. */
public final class DetectionUi {
    private DetectionUi() {}
    /** Display conversion only. Never apply this to the line matching input. */
    public static int grayscalePixel(int rgb) {
        int r=(rgb>>>16)&255,g=(rgb>>>8)&255,b=rgb&255;
        int gray=(77*r+150*g+29*b+128)>>>8;
        return 0xff000000|(gray<<16)|(gray<<8)|gray;
    }
    public static String status(boolean line, String phase, String reason) {
        if ("PAUSED".equals(phase)) return "Paused";
        if ("STOPPED".equals(phase)) return "Screen sharing stopped";
        if ("BITE".equals(phase)) return "Bite detected";
        if ("SNAPSHOT".equals(phase)) return "Taking capture…";
        if ("READY".equals(phase)) return "Ready";
        if ("NEEDS_REFERENCE".equals(phase)) return "Capture needed";
        if ("LINE_OBSCURED".equals(reason)) return "Line obscured";
        if ("LINE_AREA_NOT_FOUND".equals(reason)) return "Line not found";
        if ("RECAST".equals(phase)) return line ? "Waiting for line…" : "Waiting for bobber…";
        if ("SEARCHING".equals(phase) || "LOST".equals(phase) || "STARTING".equals(phase))
            return line ? "Finding line…" : "Finding bobber…";
        return "Watching";
    }
    public static String message(boolean line,String phase,String raw){
        if("BITE".equals(phase)||"WATCHING".equals(phase)||"SEARCHING".equals(phase)||"RECAST".equals(phase)||"STARTING".equals(phase))
            return status(line,phase,"");
        if("NEEDS_REFERENCE".equals(phase))return line?"Capture the line again.":"Capture the bobber again.";
        return raw.replace("Play","Start").replace("snapshot","capture").replace("Snapshot","Capture")
            .replace("Editing the frozen capture","Editing capture")
            .replace("No fresh screen frame arrived. Tap Capture to retry","Couldn't capture the screen. Try again.")
            .replace(". Close controls to end screen sharing.",".");
    }
    public static String metric(boolean line, float value) {
        int percent = Math.round(Math.max(0, Math.min(1, value)) * 100);
        return (line ? "Line visible: " : "Bobber visible: ") + percent + "%";
    }
    public static String count(int value) { return "Bites detected: " + Math.max(0, value); }
    public static String primary(boolean running, boolean paused) {
        return running ? "Pause" : paused ? "Resume" : "Start";
    }
}
