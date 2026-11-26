package io.github.kamaje122.calwidget;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentUris;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.text.TextUtils;

public class OpenActivity extends Activity {

    static final String EXTRA_EVENT_ID = "eventId";
    static final String EXTRA_BEGIN = "begin";
    static final String EXTRA_END = "end";
    static final String EXTRA_DAY_MILLIS = "dayMillis";
    static final String EXTRA_NOTION_URL = "notionUrl";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent src = getIntent();

        String notionUrl = src.getStringExtra(EXTRA_NOTION_URL);
        Intent target;
        if (!TextUtils.isEmpty(notionUrl)) {
            target = new Intent(Intent.ACTION_VIEW, Uri.parse(notionUrl));
        } else {
            long eventId = src.getLongExtra(EXTRA_EVENT_ID, -1L);
            if (eventId >= 0L) {
                Uri uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId);
                target = new Intent(Intent.ACTION_VIEW, uri)
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, src.getLongExtra(EXTRA_BEGIN, 0L))
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, src.getLongExtra(EXTRA_END, 0L));
            } else {
                long dayMillis = src.getLongExtra(EXTRA_DAY_MILLIS, System.currentTimeMillis());
                Uri uri = ContentUris.withAppendedId(
                        CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build(),
                        dayMillis);
                target = new Intent(Intent.ACTION_VIEW, uri);
            }
        }

        try {
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(target);
        } catch (ActivityNotFoundException ignored) {
        }
        finish();
    }
}
