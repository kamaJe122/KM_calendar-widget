package io.github.kamaje122.calwidget;

import android.icu.util.ChineseCalendar;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fast, zero-dependency Traditional Chinese Lunar Calendar helper backed by Android ICU.
 * Matches Google Calendar Taiwan formatting (e.g., "丙午年九月 - 十月", "26 (十八)", "9 (初一)").
 */
public final class LunarCalendarHelper {

    private static final String[] STEMS = {
            "甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸"
    };
    private static final String[] BRANCHES = {
            "子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥"
    };
    private static final String[] LUNAR_MONTHS = {
            "正月", "二月", "三月", "四月", "五月", "六月",
            "七月", "八月", "九月", "十月", "十一月", "臘月"
    };
    private static final String[] LUNAR_DAYS = {
            "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
            "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
            "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"
    };

    public static final class LunarInfo {
        public final String yearGanZhi; // e.g. "丙午年"
        public final String monthName;  // e.g. "十月" or "閏六月"
        public final String dayName;    // e.g. "十八", "初一"
        public final int dayOfMonth;

        LunarInfo(String yearGanZhi, String monthName, String dayName, int dayOfMonth) {
            this.yearGanZhi = yearGanZhi;
            this.monthName = monthName;
            this.dayName = dayName;
            this.dayOfMonth = dayOfMonth;
        }
    }

    private static final Map<Long, LunarInfo> CACHE = new ConcurrentHashMap<>(512);
    private static final ThreadLocal<ChineseCalendar> THREAD_CAL =
            ThreadLocal.withInitial(ChineseCalendar::new);

    private LunarCalendarHelper() {
    }

    public static LunarInfo of(LocalDate date) {
        long key = date.toEpochDay();
        LunarInfo cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        if (CACHE.size() > 2500) {
            CACHE.clear();
        }
        ChineseCalendar cc = THREAD_CAL.get();
        cc.clear();
        // Set Gregorian fields: Calendar.MONTH is 0-indexed
        cc.set(date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth());

        int extYear = cc.get(ChineseCalendar.EXTENDED_YEAR);
        int cycleIdx = Math.floorMod(extYear - 1, 60);
        String ganZhi = STEMS[cycleIdx % 10] + BRANCHES[cycleIdx % 12] + "年";

        int monthIdx = Math.max(0, Math.min(11, cc.get(ChineseCalendar.MONTH)));
        boolean isLeap = cc.get(ChineseCalendar.IS_LEAP_MONTH) == 1;
        String mName = (isLeap ? "閏" : "") + LUNAR_MONTHS[monthIdx];

        int dom = Math.max(1, Math.min(30, cc.get(ChineseCalendar.DAY_OF_MONTH)));
        String dName = LUNAR_DAYS[dom - 1];

        LunarInfo info = new LunarInfo(ganZhi, mName, dName, dom);
        CACHE.put(key, info);
        return info;
    }

    /** Returns compact lunar day string such as "十八" or "初一". */
    public static String getDayShort(LocalDate date) {
        return of(date).dayName;
    }

    /**
     * Returns header subtitle like "丙午年九月 - 十月" or "丙午年十月" matching Google Calendar.
     */
    public static String getRangeHeaderSubtitle(LocalDate start, LocalDate end) {
        LunarInfo a = of(start);
        LunarInfo b = of(end);
        if (a.yearGanZhi.equals(b.yearGanZhi)) {
            if (a.monthName.equals(b.monthName)) {
                return a.yearGanZhi + a.monthName;
            }
            return a.yearGanZhi + a.monthName + " - " + b.monthName;
        }
        return a.yearGanZhi + a.monthName + " - " + b.yearGanZhi + b.monthName;
    }

    /** Returns full lunar date string such as "農曆丙午年十月十八". */
    public static String getFullDateLabel(LocalDate date) {
        LunarInfo i = of(date);
        return "農曆" + i.yearGanZhi + i.monthName + i.dayName;
    }
}
