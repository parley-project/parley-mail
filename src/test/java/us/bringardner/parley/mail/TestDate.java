package us.bringardner.parley.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;


public class TestDate  {

	// Locale.US so day/month names are English on any machine; "Z" so UTC prints as +0000 (XX prints "Z")
	public static final String PATTERN = "EEE, dd MMM yyyy HH:mm:ss Z";

	private static void assertParse(String input, String expectedOutput) throws ParseException {
		assertEquals(expectedOutput, Rfc2822Date.parseDate(input).toString(), input);
	}

	private static void assertInstant(String input, String expectedUtc) throws ParseException {
		assertEquals(Instant.parse(expectedUtc), Rfc2822Date.parseDate(input).getDate().toInstant(), input);
	}

	@Test()
	public void testDate01() throws ParseException {
		SimpleDateFormat fmt = new SimpleDateFormat(PATTERN, Locale.US);
		Calendar cal = Calendar.getInstance();

		String expect = fmt.format(cal.getTime());

		Rfc2822Date date = Rfc2822Date.parseDate(expect);
		String dateString = date.toString();
		assertEquals(expect, dateString);

		assertParse("27 Oct 81 15:01:01 PST", "Tue, 27 Oct 1981 15:01:01 -0800");
	}

	@Test()
	public void testDate01_1() throws ParseException {
		// previously came back as -0600: the offset was mapped to a zone that observes DST
		assertParse("Tue, 16 Jul 2013 13:52:53 -0700", "Tue, 16 Jul 2013 13:52:53 -0700");
		assertInstant("Tue, 16 Jul 2013 13:52:53 -0700", "2013-07-16T20:52:53Z");
	}

	@Test()
	public void testDate02() throws ParseException {
		// EDT is -0400 (previously converted to EST and output as -0500)
		assertParse("August 29, 2013, 7:29:35  AM EDT", "Thu, 29 Aug 2013 07:29:35 -0400");
		assertInstant("August 29, 2013, 7:29:35 AM EDT", "2013-08-29T11:29:35Z");
		assertParse("March 18, 2014 at 1:05:37 PM EDT", "Tue, 18 Mar 2014 13:05:37 -0400");
	}

	@Test()
	public void testDate03() throws ParseException, FileNotFoundException, IOException {
		File dir = new File("TestFiles").getAbsoluteFile();
		File file = new File(dir,"Date.txt");
		String text = null;

		try(InputStream in = new FileInputStream(file)) {
			text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		SimpleDateFormat fmt = new SimpleDateFormat(PATTERN, Locale.US);
		SimpleDateFormat alt = new SimpleDateFormat("MMMM dd, yyyy, hh:mm:ss a z", Locale.US);
		SimpleDateFormat alt2 = new SimpleDateFormat("dd MMM yyyy, hh:mm:ss a z", Locale.US);
		// strict, so invalid dates in the sample (e.g. hour 25) are skipped rather than rolled over
		fmt.setLenient(false);
		alt.setLenient(false);
		alt2.setLenient(false);

		int checked = 0;
		String [] lines = text.split("\n");
		for (int idx = 0; idx < lines.length; idx++) {
			String line = lines[idx].trim();
			if( line.startsWith("Date: ")) {
				// SimpleDateFormat can't produce a reference value for these
				if( line.contains(" at ") || line.contains(" AT ")) {
					continue;
				}
				// SimpleDateFormat resolves "EDT" differently depending on the date
				if( line.endsWith("DT")) {
					continue;
				}
				// some test data have extraneous data
				if( line.indexOf('=')>=0) {
					continue;
				}

				String dateString = line.substring(6).trim();
				if( dateString.indexOf('(')>0) {
					dateString = dateString.substring(0,dateString.indexOf('(')).trim();
				}
				long expectTime;

				try {
					expectTime = fmt.parse(dateString).getTime();
				} catch (Exception e) {
					try {
						expectTime = alt.parse(dateString).getTime();
					} catch (Exception e2) {
						try {
							expectTime = alt2.parse(dateString).getTime();
						} catch (Exception e3) {
							continue;
						}
					}
				}

				Rfc2822Date date = Rfc2822Date.parseDate(dateString);
				assertEquals(expectTime, date.getTime(), "line " + (idx + 1) + ": " + dateString);
				checked++;
			}
		}
		assertTrue(checked > 100, "only checked " + checked + " dates");
	}

	@Test
	public void testHalfHourOffsets() throws ParseException {
		// previously ArrayIndexOutOfBoundsException
		assertParse("Fri, 16 Jan 2026 10:00:00 +0530", "Fri, 16 Jan 2026 10:00:00 +0530");
		assertInstant("Fri, 16 Jan 2026 10:00:00 +0530", "2026-01-16T04:30:00Z");
		assertParse("Fri, 16 Jan 2026 10:00:00 +1345", "Fri, 16 Jan 2026 10:00:00 +1345");
		assertParse("Fri, 16 Jan 2026 10:00:00 -0330", "Fri, 16 Jan 2026 10:00:00 -0330");
	}

	@Test
	public void testUtc() throws ParseException {
		// previously StringIndexOutOfBoundsException for "Z", and output used "Z" instead of +0000
		assertParse("Fri, 16 Jan 2026 10:00:00 Z", "Fri, 16 Jan 2026 10:00:00 +0000");
		assertParse("Fri, 16 Jan 2026 10:00:00 +0000", "Fri, 16 Jan 2026 10:00:00 +0000");
		assertParse("Fri, 16 Jan 2026 10:00:00 UT", "Fri, 16 Jan 2026 10:00:00 +0000");
		assertParse("Fri, 16 Jan 2026 10:00:00 GMT", "Fri, 16 Jan 2026 10:00:00 +0000");
		assertParse("Tue, 15 Oct 2013 11:49:42 +0000 (UTC)", "Tue, 15 Oct 2013 11:49:42 +0000");
	}

	@Test
	public void testNoon() throws ParseException {
		// previously 12:30 PM became 00:30 the next day
		assertParse("August 29, 2013, 12:30:00 PM EDT", "Thu, 29 Aug 2013 12:30:00 -0400");
		assertParse("August 29, 2013, 12:30:00 AM EDT", "Thu, 29 Aug 2013 00:30:00 -0400");
		assertParse("August 29, 2013, 11:59:59 PM EDT", "Thu, 29 Aug 2013 23:59:59 -0400");
	}

	@Test
	public void testNamedZonesAreFixedOffsets() throws ParseException {
		// previously "PST" in July was treated as PDT
		assertParse("Thu, 16 Jul 2026 10:00:00 PST", "Thu, 16 Jul 2026 10:00:00 -0800");
		assertParse("Fri, 16 Jan 2026 10:00:00 EDT", "Fri, 16 Jan 2026 10:00:00 -0400");
		assertParse("Fri, 16 Jan 2026 10:00:00 CDT", "Fri, 16 Jan 2026 10:00:00 -0500");
		assertParse("Fri, 16 Jan 2026 10:00:00 MST", "Fri, 16 Jan 2026 10:00:00 -0700");
	}

	@Test
	public void testTwoDigitYears() throws ParseException {
		// previously 26 became 1926
		assertParse("16 Jan 26 10:00:00 GMT", "Fri, 16 Jan 2026 10:00:00 +0000");
		assertParse("16 Jan 49 10:00:00 GMT", "Sat, 16 Jan 2049 10:00:00 +0000");
		assertParse("16 Jan 50 10:00:00 GMT", "Mon, 16 Jan 1950 10:00:00 +0000");
		assertParse("16 Jan 126 10:00:00 GMT", "Fri, 16 Jan 2026 10:00:00 +0000");
	}

	@Test
	public void testOptionalForms() throws ParseException {
		assertParse("Fri, 16 Jan 2026 10:00:00 -05:00", "Fri, 16 Jan 2026 10:00:00 -0500");
		assertParse("Fri, 16 Jan 2026 10:00 -0500", "Fri, 16 Jan 2026 10:00:00 -0500");
		assertParse("16 Jan 2026 10:00:00 -0500", "Fri, 16 Jan 2026 10:00:00 -0500");
		assertParse("  fri,  6 jan 2026   10:00:00   -0500  ", "Tue, 06 Jan 2026 10:00:00 -0500");
	}

	@Test
	public void testInvalid() {
		// previously an unknown month silently rolled back to December of the previous year
		assertThrows(ParseException.class, () -> Rfc2822Date.parseDate("Fri, 16 Foo 2026 10:00:00 -0500"));
		assertThrows(ParseException.class, () -> Rfc2822Date.parseDate("Fri, 31 Feb 2026 10:00:00 -0500"));
		assertThrows(ParseException.class, () -> Rfc2822Date.parseDate("Fri, 16 Jan 2026 25:00:00 -0500"));
		assertThrows(ParseException.class, () -> Rfc2822Date.parseDate("Fri, 16 Jan 2026 10:00:00 -0575"));
		assertThrows(ParseException.class, () -> Rfc2822Date.parseDate("Fri, 16 Jan 2026 10:00:00 XYZZY"));
		assertThrows(ParseException.class, () -> Rfc2822Date.parseDate("August 29, 2013, 13:00:00 PM EDT"));
		assertThrows(ParseException.class, () -> Rfc2822Date.parseDate("not a date"));
		assertThrows(ParseException.class, () -> Rfc2822Date.parseDate(""));
		assertThrows(ParseException.class, () -> Rfc2822Date.parseDate(null));
	}

	@Test
	public void testImmutable() throws ParseException {
		Rfc2822Date date = Rfc2822Date.parseDate("Fri, 16 Jan 2026 10:00:00 -0500");
		String before = date.toString();

		Calendar cal = date.getCalendar();
		cal.add(Calendar.YEAR, 5);
		assertNotSame(cal, date.getCalendar());
		date.getDate().setTime(0);
		assertEquals(before, date.toString());

		Calendar source = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"));
		source.setTimeInMillis(0);
		Rfc2822Date fromCal = new Rfc2822Date(source);
		source.add(Calendar.YEAR, 5);
		assertEquals(0, fromCal.getTime());
		assertEquals("Wed, 31 Dec 1969 19:00:00 -0500", fromCal.toString());
	}

	@Test
	public void testEqualsAndCompare() throws ParseException {
		Rfc2822Date a = Rfc2822Date.parseDate("Fri, 16 Jan 2026 10:00:00 -0500");
		Rfc2822Date b = Rfc2822Date.parseDate("Fri, 16 Jan 2026 10:00:00 -0500");
		Rfc2822Date sameInstant = Rfc2822Date.parseDate("Fri, 16 Jan 2026 15:00:00 +0000");
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		assertNotEquals(a, sameInstant);
		assertEquals(0, a.compareTo(sameInstant));
		assertEquals(a.getTime(), sameInstant.getTime());
	}

	@Test
	public void testCalendarMatchesInstant() throws ParseException {
		Rfc2822Date date = Rfc2822Date.parseDate("Tue, 16 Jul 2013 13:52:53 -0700");
		Calendar cal = date.getCalendar();
		assertEquals(13, cal.get(Calendar.HOUR_OF_DAY));
		assertEquals(date.getTime(), cal.getTimeInMillis());
		assertEquals(new Date(date.getTime()), date.getDate());
		ZonedDateTime z = ZonedDateTime.ofInstant(date.getDate().toInstant(), ZoneId.of("UTC"));
		assertEquals(20, z.getHour());
	}
}
