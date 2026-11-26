package io.github.kamaje122.calwidget;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Lightweight trampoline activity invoked from desktop widget PendingIntentTemplates.
 * Routes widget taps directly into KM Calendar's own MainActivity (jumping to the selected date
 * and optionally opening the tapped Event Detail Card or Create Event sheet).
 */
public class OpenActivity extends Activity {

    static final String EXTRA_DAY_MILLIS = "dayMillis";
    static final String EXTRA_EPOCH_DAY = "epochDay";
    static final String EXTRA_OPEN_CREATE = "openCreate";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent src = getIntent();

        Intent target = new Intent(this, MainActivity.class);
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        if (src != null) {
            if (src.hasExtra(EXTRA_EPOCH_DAY)) {
                target.putExtra(MainActivity.EXTRA_TARGET_EPOCH_DAY,
                        src.getLongExtra(EXTRA_EPOCH_DAY, LocalDate.now().toEpochDay()));
            } else if (src.hasExtra(EXTRA_DAY_MILLIS)) {
                long ms = src.getLongExtra(EXTRA_DAY_MILLIS, System.currentTimeMillis());
                long epochDay = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
                        .toLocalDate().toEpochDay();
                target.putExtra(MainActivity.EXTRA_TARGET_EPOCH_DAY, epochDay);
            }
            if (src.getBooleanExtra(EXTRA_OPEN_CREATE, false)) {
                target.putExtra(MainActivity.EXTRA_OPEN_CREATE_EVENT, true);
            }
            CalendarEvent ev = CalendarEvent.fromIntent(src);
            if (ev != null) {
                ev.writeToIntent(target);
                if (!target.hasExtra(MainActivity.EXTRA_TARGET_EPOCH_DAY)) {
                    target.putExtra(MainActivity.EXTRA_TARGET_EPOCH_DAY, ev.startEpochDay);
                }
            }
        }

        startActivity(target);
        finish();
    }
}
