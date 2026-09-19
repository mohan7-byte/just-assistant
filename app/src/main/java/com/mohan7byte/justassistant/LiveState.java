package com.mohan7byte.justassistant;

public final class LiveState {
    public static final String ACTION = "com.mohan7byte.justassistant.LIVE_STATE";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_MESSAGE = "message";

    public static final String CONNECTING = "connecting";
    public static final String READY = "ready";
    public static final String SPEAKING = "speaking";
    public static final String IDLE = "idle";
    public static final String ERROR = "error";
    public static final String STOPPED = "stopped";

    private LiveState() {}
}
