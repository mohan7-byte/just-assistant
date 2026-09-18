package com.mohan7byte.justassistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class StopReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        context.stopService(new Intent(context, LiveAssistantService.class));
    }
}
