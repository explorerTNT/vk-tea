package com.vktea.util;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class RuDate {

    private static final String[] MONTHS = {
            "января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря"
    };

    public static String format(long epochSeconds) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(epochSeconds * 1000L);
        Calendar now = Calendar.getInstance();
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(c.getTime());
        boolean sameDay = c.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                && c.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR);
        if (sameDay) {
            return "сегодня в " + time;
        }
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        boolean sameYesterday = c.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR)
                && c.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR);
        if (sameYesterday) {
            return "вчера в " + time;
        }
        int day = c.get(Calendar.DAY_OF_MONTH);
        String month = MONTHS[c.get(Calendar.MONTH)];
        int year = c.get(Calendar.YEAR);
        if (year == now.get(Calendar.YEAR)) {
            return day + " " + month + " в " + time;
        }
        return day + " " + month + " " + year;
    }
}
