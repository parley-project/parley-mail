package us.bringardner.parley.mail;

import java.io.Serializable;
import java.text.ParseException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An immutable date-time that conforms to the date-time structure of
 * RFC 5322 (section 3.3), which supersedes RFC 2822.
 * <p>
 * Parsing accepts the standard form plus the common obsolete and
 * non-standard variants seen in real mail:
 * <ul>
 * <li>{@code Fri, 16 Jan 2026 07:54:55 -0500} (standard)</li>
 * <li>{@code 27 Oct 81 15:01:01 PST} (no day of week, 2-digit year, named zone)</li>
 * <li>{@code Fri, 16 Jan 2026 07:54 -0500} (no seconds)</li>
 * <li>{@code Fri, 16 Jan 2026 07:54:55 -05:00} (offset with colon)</li>
 * <li>{@code Tue, 15 Oct 2013 11:49:42 +0000 (UTC)} (trailing comment)</li>
 * <li>{@code August 29, 2013, 7:29:35 AM EDT} and
 *     {@code March 18, 2014 at 1:05:37 PM EDT} (Apple Mail style)</li>
 * </ul>
 * Formatting always produces the standard form with a numeric offset,
 * e.g. {@code Fri, 16 Jan 2026 07:54:55 -0500}.
 */
public final class Rfc2822Date implements Serializable, Comparable<Rfc2822Date> {

	private static final long serialVersionUID = 2L;

	private static final DateTimeFormatter OUTPUT_FORMAT =
			DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss xx", Locale.US);

	private static final String[] MONTHS =
		{"JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};

	/**
	 * Named zones from RFC 5322 section 4.3. These are fixed offsets:
	 * "EDT" always means -0400 regardless of the date.
	 */
	private static final Map<String, ZoneOffset> NAMED_ZONES = new HashMap<>();
	static {
		NAMED_ZONES.put("UT",  ZoneOffset.UTC);
		NAMED_ZONES.put("UTC", ZoneOffset.UTC);
		NAMED_ZONES.put("GMT", ZoneOffset.UTC);
		NAMED_ZONES.put("Z",   ZoneOffset.UTC);
		NAMED_ZONES.put("EST", ZoneOffset.ofHours(-5));
		NAMED_ZONES.put("EDT", ZoneOffset.ofHours(-4));
		NAMED_ZONES.put("CST", ZoneOffset.ofHours(-6));
		NAMED_ZONES.put("CDT", ZoneOffset.ofHours(-5));
		NAMED_ZONES.put("MST", ZoneOffset.ofHours(-7));
		NAMED_ZONES.put("MDT", ZoneOffset.ofHours(-6));
		NAMED_ZONES.put("PST", ZoneOffset.ofHours(-8));
		NAMED_ZONES.put("PDT", ZoneOffset.ofHours(-7));
	}

	private static final String TIME =
			"(?<hour>\\d{1,2}):(?<minute>\\d{2})(?::(?<second>\\d{2}))?";
	private static final String ZONE =
			"(?<zone>[+-]\\d{2}:?\\d{2}|[A-Z]{1,5})";

	/** [day-of-week ","] day month year time zone */
	private static final Pattern RFC_PATTERN = Pattern.compile(
			"(?:(?<dayOfWeek>[A-Z]{3,9})\\s*,?\\s*)?"
			+ "(?<day>\\d{1,2})\\s+"
			+ "(?<month>[A-Z]{3,9})\\.?\\s+"
			+ "(?<year>\\d{2,4})\\s+"
			+ TIME + "\\s*"
			+ ZONE);

	/** month day "," year ("," | "at") time AM|PM zone */
	private static final Pattern ALT_PATTERN = Pattern.compile(
			"(?<month>[A-Z]{3,9})\\.?\\s+"
			+ "(?<day>\\d{1,2})\\s*,\\s*"
			+ "(?<year>\\d{4})\\s*(?:,|\\s+AT)\\s*"
			+ TIME + "\\s*"
			+ "(?<ampm>AM|PM)\\s+"
			+ ZONE);

	private final OffsetDateTime dateTime;

	/**
	 * Parse an RFC 5322 date-time (or one of the variants listed in the
	 * class documentation).
	 *
	 * @throws ParseException if the text is not a recognizable, valid date-time
	 */
	public static Rfc2822Date parseDate(String text) throws ParseException {
		if (text == null) {
			throw new ParseException("null date", 0);
		}
		String str = normalize(text);

		Matcher m = RFC_PATTERN.matcher(str);
		boolean twelveHour = false;
		if (!m.matches()) {
			m = ALT_PATTERN.matcher(str);
			if (!m.matches()) {
				throw new ParseException("Unrecognized date: " + text, 0);
			}
			twelveHour = true;
		}

		try {
			int day = Integer.parseInt(m.group("day"));
			int month = parseMonth(m.group("month"), text);
			int year = parseYear(m.group("year"));
			int hour = Integer.parseInt(m.group("hour"));
			int minute = Integer.parseInt(m.group("minute"));
			String sec = m.group("second");
			int second = sec == null ? 0 : Integer.parseInt(sec);

			if (twelveHour) {
				if (hour < 1 || hour > 12) {
					throw new ParseException("Invalid 12-hour value in: " + text, 0);
				}
				boolean pm = "PM".equals(m.group("ampm"));
				hour = hour % 12 + (pm ? 12 : 0);
			}

			// RFC 5322 allows a leap second (60); java.time does not, so clamp it.
			if (second == 60) {
				second = 59;
			}

			LocalDateTime local = LocalDateTime.of(year, month, day, hour, minute, second);
			return new Rfc2822Date(resolveZone(local, m.group("zone"), text));
		} catch (DateTimeException | NumberFormatException e) {
			ParseException pe = new ParseException("Invalid date: " + text + " (" + e.getMessage() + ")", 0);
			pe.initCause(e);
			throw pe;
		}
	}

	private static String normalize(String text) {
		String str = text.trim().toUpperCase(Locale.US);
		// drop trailing comments such as "(UTC)" or "(PDT)"
		str = str.replaceAll("\\s*\\([^)]*\\)\\s*$", "");
		return str.replaceAll("\\s+", " ");
	}

	private static int parseMonth(String name, String text) throws ParseException {
		if (name.length() >= 3) {
			String prefix = name.substring(0, 3);
			for (int i = 0; i < MONTHS.length; i++) {
				if (MONTHS[i].equals(prefix)) {
					return i + 1;
				}
			}
		}
		throw new ParseException("Unknown month '" + name + "' in: " + text, 0);
	}

	/** RFC 5322 section 4.3: 2-digit years 00-49 are 20xx, 50-99 are 19xx; 3-digit years add 1900. */
	private static int parseYear(String text) {
		int year = Integer.parseInt(text);
		if (text.length() == 2) {
			year += year < 50 ? 2000 : 1900;
		} else if (text.length() == 3) {
			year += 1900;
		}
		return year;
	}

	private static OffsetDateTime resolveZone(LocalDateTime local, String zone, String text) throws ParseException {
		char first = zone.charAt(0);
		if (first == '+' || first == '-') {
			String digits = zone.replace(":", "");
			int hours = Integer.parseInt(digits.substring(1, 3));
			int minutes = Integer.parseInt(digits.substring(3, 5));
			if (minutes > 59) {
				throw new ParseException("Invalid zone offset '" + zone + "' in: " + text, 0);
			}
			int sign = first == '-' ? -1 : 1;
			return OffsetDateTime.of(local, ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes));
		}

		ZoneOffset named = NAMED_ZONES.get(zone);
		if (named != null) {
			return OffsetDateTime.of(local, named);
		}

		// Single-letter military zones: RFC 5322 says treat as -0000 (unknown).
		if (zone.length() == 1 && zone.charAt(0) != 'J') {
			return OffsetDateTime.of(local, ZoneOffset.UTC);
		}

		// Last resort: a zone Java knows by name (e.g. CET, BST via short IDs).
		try {
			ZoneId id = ZoneId.of(zone, ZoneId.SHORT_IDS);
			return local.atZone(id).toOffsetDateTime();
		} catch (DateTimeException e) {
			throw new ParseException("Unknown time zone '" + zone + "' in: " + text, 0);
		}
	}

	/** The current time in the system default zone. */
	public Rfc2822Date() {
		this(OffsetDateTime.now());
	}

	/** The given epoch milliseconds, in the system default zone. */
	public Rfc2822Date(long epochMillis) {
		this(OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()));
	}

	/** The calendar's instant and offset. The calendar is not retained. */
	public Rfc2822Date(Calendar cal) {
		this(OffsetDateTime.ofInstant(cal.toInstant(), cal.getTimeZone().toZoneId()));
	}

	public Rfc2822Date(OffsetDateTime dateTime) {
		this.dateTime = Objects.requireNonNull(dateTime, "dateTime");
	}

	public OffsetDateTime getOffsetDateTime() {
		return dateTime;
	}

	/** Milliseconds since the epoch. */
	public long getTime() {
		return dateTime.toInstant().toEpochMilli();
	}

	/** A new Calendar in this date's offset; changes to it do not affect this object. */
	public Calendar getCalendar() {
		return GregorianCalendar.from(dateTime.toZonedDateTime());
	}

	/** A new Date; changes to it do not affect this object. */
	public Date getDate() {
		return Date.from(dateTime.toInstant());
	}

	@Override
	public String toString() {
		return OUTPUT_FORMAT.format(dateTime);
	}

	/** Equal when both the instant and the offset are the same. */
	@Override
	public boolean equals(Object obj) {
		return obj instanceof Rfc2822Date && dateTime.equals(((Rfc2822Date) obj).dateTime);
	}

	@Override
	public int hashCode() {
		return dateTime.hashCode();
	}

	/** Orders by instant. */
	@Override
	public int compareTo(Rfc2822Date other) {
		return dateTime.toInstant().compareTo(other.dateTime.toInstant());
	}
}
