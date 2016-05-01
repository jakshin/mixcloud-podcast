/*
 * Copyright (C) 2016 Jason Jackson
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package jakshin.mixcaster.logging;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.logging.*;

/**
 * A log-record formatter geared towards output to a log file.
 * It tries to present logged info in a way which is both machine-parsable and readable for humans.
 */
class LogFileFormatter extends Formatter {
    /**
     * Creates a new instance of the class.
     */
    LogFileFormatter() {
        this.dateFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ENGLISH);
    }

    /**
     * Formats the given log record and returns the formatted string.
     *
     * @param record The log record to be formatted.
     * @return A string containing the formatted log record.
     */
    @Override
    public String format(LogRecord record) {
        String msg = this.formatMessage(record);
        if (msg == null || msg.isEmpty()) return msg;

        StringBuilder sb = new StringBuilder(200);

        long logged = record.getMillis();
        sb.append(this.dateFormatter.format(new Date(logged)));
        sb.append('\t');

        Level level = record.getLevel();
        String levelStr = level.toString();

        if (level == Level.SEVERE) {
            levelStr = "ERROR";
        }
        else if (level == Level.FINE) {
            levelStr = "DEBUG";
        }

        sb.append(levelStr);
        sb.append('\t');

        // XXX handle embedded newlines?
        sb.append(msg.trim());
        sb.append('\t');

        sb.append(String.format("[thread %d]%n", record.getThreadID()));

        Throwable thrown = record.getThrown();
        if (thrown != null) {
            sb.append(this.formatThrowable(thrown, false));
        }

        return sb.toString();
    }

    /**
     * Formats a Throwable, including the exception class name and a stack trace,
     * and returns the formatted string.
     *
     * @param ex The Throwable to format.
     * @param isCause Whether this Throwable is the cause of another.
     * @return A string containing the formatted Throwable.
     */
    private String formatThrowable(Throwable ex, boolean isCause) {
        StringBuilder sb = new StringBuilder(500);

        String prefix = (isCause) ? "    CAUSE: " : "    ERROR: ";
        sb.append(String.format("%s%s: %s%n", prefix, ex.getClass().getCanonicalName(), ex.getMessage().trim()));

        StackTraceElement[] stack = ex.getStackTrace();
        for (StackTraceElement el : stack) {
            sb.append(String.format("\tat %s%n", el.toString()));
        }

        Throwable cause = ex.getCause();
        if (cause != null) {
            sb.append(this.formatThrowable(cause, true));
        }

        return sb.toString();
    }

    /** The thingy which formats dates. */
    private final SimpleDateFormat dateFormatter;
}
