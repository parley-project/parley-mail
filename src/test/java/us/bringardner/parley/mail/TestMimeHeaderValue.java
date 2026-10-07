package us.bringardner.parley.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * RFC 2231 parameter encoding, RFC 2047 encoded words and quoted-printable.
 */
public class TestMimeHeaderValue {

	// ---- the examples from RFC 2231 itself

	@Test
	public void testRfc2231Continuation() {
		// RFC 2231 section 3
		MimeHeaderValue v = MimeHeaderValue.parse("message/external-body; access-type=URL;\r\n"
				+ " URL*0=\"ftp://\";\r\n URL*1=\"cs.utk.edu/pub/moore/bulk-mailer/bulk-mailer.tar\"");
		assertEquals("message/external-body", v.getValue());
		assertEquals("URL", v.getParameter("access-type"));
		assertEquals("ftp://cs.utk.edu/pub/moore/bulk-mailer/bulk-mailer.tar", v.getParameter("url"));
	}

	@Test
	public void testRfc2231CharsetAndLanguage() {
		// RFC 2231 section 4
		MimeHeaderValue v = MimeHeaderValue.parse("application/x-stuff;\r\n title*=us-ascii'en-us'This%20is%20%2A%2A%2Afun%2A%2A%2A");
		assertEquals("This is ***fun***", v.getParameter("title"));
		assertEquals("en-us", v.getParameterInfo("title").getLanguage());
		assertEquals("us-ascii", v.getParameterInfo("title").getCharset());
	}

	@Test
	public void testRfc2231Combined() {
		// RFC 2231 section 4.1
		MimeHeaderValue v = MimeHeaderValue.parse("application/x-stuff;\r\n"
				+ " title*0*=us-ascii'en'This%20is%20even%20more%20;\r\n"
				+ " title*1*=%2A%2A%2Afun%2A%2A%2A%20;\r\n"
				+ " title*2=\"isn't it!\"");
		assertEquals("This is even more ***fun*** isn't it!", v.getParameter("title"));
		assertEquals("en", v.getParameterInfo("title").getLanguage());
	}

	@Test
	public void testRfc2231EncodedWordLanguage() {
		// RFC 2231 section 5
		assertEquals("Keith Moore", EncodedWord.decode("=?US-ASCII*EN?Q?Keith_Moore?="));
		assertEquals("EN", EncodedWord.getLanguage("=?US-ASCII*EN?Q?Keith_Moore?="));
	}

	// ---- parsing details

	@Test
	public void testMultiByteSplitAcrossSections() {
		// the UTF-8 bytes of 日 are split between section 0 and 1
		MimeHeaderValue v = MimeHeaderValue.parse("attachment; filename*0*=UTF-8''%E6%97; filename*1*=%A5%E6%9C%AC.txt");
		assertEquals("日本.txt", v.getParameter("filename"));
	}

	@Test
	public void testSectionsOutOfOrderAndCase() {
		MimeHeaderValue v = MimeHeaderValue.parse("Text/Plain; NAME*1=\"world\"; Name*0=\"hello \"; CHARSET=\"utf-8\"");
		assertEquals("hello world", v.getParameter("name"));
		assertEquals("utf-8", v.getParameter("charset"));
		assertEquals("Text/Plain", v.getValue());
	}

	@Test
	public void testExtendedWinsOverPlain() {
		MimeHeaderValue v = MimeHeaderValue.parse("attachment; filename=\"fallback.txt\"; filename*=UTF-8''r%C3%A9sum%C3%A9.txt");
		assertEquals("résumé.txt", v.getParameter("filename"));
	}

	@Test
	public void testQuotedStringsAndComments() {
		MimeHeaderValue v = MimeHeaderValue.parse("text/plain (comment; with semicolon); charset=us-ascii (Plain text); name=\"a \\\"quoted\\\" ; name\"");
		assertEquals("text/plain", v.getValue());
		assertEquals("us-ascii", v.getParameter("charset"));
		assertEquals("a \"quoted\" ; name", v.getParameter("name"));
	}

	@Test
	public void testUnquotedParenthesesKept() {
		assertEquals("report(1).pdf", MimeHeaderValue.parse("attachment; filename=report(1).pdf").getParameter("filename"));
	}

	@Test
	public void testEncodedWordInPlainParameter() {
		// not standard, but common (Outlook, Gmail)
		MimeHeaderValue v = MimeHeaderValue.parse("attachment; filename=\"=?UTF-8?B?w6l0w6kucGRm?=\"");
		assertEquals("été.pdf", v.getParameter("filename"));
	}

	@Test
	public void testUnknownCharsetKeepsBytes() {
		MimeHeaderValue v = MimeHeaderValue.parse("attachment; filename*=x-unknown''a%E9b");
		assertEquals("aéb", v.getParameter("filename"));
	}

	@Test
	public void testMalformedIsLenient() {
		MimeHeaderValue v = MimeHeaderValue.parse("text/plain; ; =x; novalue; charset=utf-8; bad%=%ZZ");
		assertEquals("utf-8", v.getParameter("charset"));
		assertNull(v.getParameter("novalue"));
		assertEquals("%ZZ", v.getParameter("bad%"));
	}

	// ---- encoding

	@Test
	public void testEncodePlain() {
		MimeHeaderValue v = new MimeHeaderValue("text/plain").setParameter("charset", "UTF-8").setParameter("name", "my file.txt");
		assertEquals("text/plain; charset=UTF-8; name=\"my file.txt\"", v.toString());
	}

	@Test
	public void testEncodeNonAscii() {
		MimeHeaderValue v = new MimeHeaderValue("attachment").setParameter("filename", "résumé.pdf");
		assertEquals("attachment; filename*=UTF-8''r%C3%A9sum%C3%A9.pdf", v.toString());
		assertEquals("résumé.pdf", MimeHeaderValue.parse(v.toString()).getParameter("filename"));
	}

	@Test
	public void testEncodeLanguage() {
		MimeHeaderValue v = new MimeHeaderValue("attachment").setParameter("filename", "report.txt", "en");
		assertEquals("attachment; filename*=UTF-8'en'report.txt", v.toString());
		assertEquals("en", MimeHeaderValue.parse(v.toString()).getParameterInfo("filename").getLanguage());
	}

	@Test
	public void testEncodeLongValuesUseContinuations() {
		String longAscii = "a very long file name that goes on and on and on, well past sixty characters.txt";
		String longUtf8 = "日本語のとても長いファイル名はRFC2231の継続パラメータで分割されます.pdf";
		MimeHeaderValue v = new MimeHeaderValue("attachment").setParameter("filename", longUtf8).setParameter("x", longAscii);
		String text = v.toString();
		assertTrue(text.contains("filename*0*=UTF-8''"), text);
		assertTrue(text.contains("filename*1*="), text);
		assertTrue(text.contains("x*0=\""), text);
		for (String section : text.split("; ")) {
			assertTrue(section.length() <= 80, section);
		}
		MimeHeaderValue back = MimeHeaderValue.parse(text);
		assertEquals(longUtf8, back.getParameter("filename"));
		assertEquals(longAscii, back.getParameter("x"));
	}

	@Test
	public void testSetReplacesAndRemoves() {
		MimeHeaderValue v = MimeHeaderValue.parse("text/plain; Charset=us-ascii; format=flowed");
		v.setParameter("charset", "UTF-8").removeParameter("FORMAT");
		assertEquals("text/plain; Charset=UTF-8", v.toString());
	}

	// ---- RFC 2047

	@Test
	public void testEncodedWordsRfc2047Examples() {
		assertEquals("Keith Moore", EncodedWord.decode("=?US-ASCII?Q?Keith_Moore?="));
		assertEquals("Keld Jørn Simonsen", EncodedWord.decode("=?ISO-8859-1?Q?Keld_J=F8rn_Simonsen?="));
		assertEquals("André Pirard", EncodedWord.decode("=?ISO-8859-1?Q?Andr=E9?= Pirard"));
		assertEquals("If you can read this you understand the example.", EncodedWord.decode(
				"=?ISO-8859-1?B?SWYgeW91IGNhbiByZWFkIHRoaXMgeW8=?=\r\n =?ISO-8859-2?B?dSB1bmRlcnN0YW5kIHRoZSBleGFtcGxlLg==?="));
		// whitespace between adjacent encoded words is removed, other whitespace kept
		assertEquals("ab", EncodedWord.decode("=?ISO-8859-1?Q?a?= =?ISO-8859-1?Q?b?="));
		assertEquals("a b", EncodedWord.decode("=?ISO-8859-1?Q?a_b?="));
		assertEquals("(a b)", EncodedWord.decode("(=?ISO-8859-1?Q?a?= b)"));
		// unknown charset is left alone
		assertEquals("=?x-nope?Q?a?=", EncodedWord.decode("=?x-nope?Q?a?="));
	}

	@Test
	public void testEncodedWordEncode() {
		assertEquals("plain ascii", EncodedWord.encode("plain ascii"));
		String text = "Grüße aus Köln — this subject is long enough to need several encoded words 日本";
		String enc = EncodedWord.encode(text);
		assertTrue(enc.chars().allMatch(c -> c >= 32 && c < 127), enc);
		for (String word : enc.split(" ")) {
			assertTrue(word.length() <= 75, word);
		}
		assertEquals(text, EncodedWord.decode(enc));
		assertTrue(EncodedWord.encode("hi", "en").startsWith("=?UTF-8*en?B?"));
	}

	// ---- quoted-printable

	@Test
	public void testQuotedPrintable() {
		String text = "Café = good\r\ntrailing space \r\n"
				+ "a long line that is definitely more than seventy six characters long, so it has to wrap somewhere";
		byte[] enc = QuotedPrintable.encode(text.getBytes(StandardCharsets.UTF_8), true);
		String encoded = new String(enc, StandardCharsets.US_ASCII);
		assertTrue(encoded.startsWith("Caf=C3=A9 =3D good\r\ntrailing space=20\r\n"), encoded);
		for (String line : encoded.split("\r\n")) {
			assertTrue(line.length() <= 76, line);
		}
		assertEquals(text, new String(QuotedPrintable.decode(enc), StandardCharsets.UTF_8));

		// decoding: soft breaks, trailing whitespace, bad escapes, LF line endings
		assertEquals("hello world", new String(QuotedPrintable.decode("hello =\r\nworld".getBytes()), StandardCharsets.US_ASCII));
		assertEquals("a\nb", new String(QuotedPrintable.decode("a   \nb".getBytes()), StandardCharsets.US_ASCII));
		assertEquals("a=Zb", new String(QuotedPrintable.decode("a=Zb".getBytes()), StandardCharsets.US_ASCII));
		assertEquals("ab", new String(QuotedPrintable.decode("a=  \nb".getBytes()), StandardCharsets.US_ASCII));

		// binary mode encodes line breaks
		byte[] bin = {0, '\r', '\n', (byte) 0xff};
		assertEquals("=00=0D=0A=FF", new String(QuotedPrintable.encode(bin, false), StandardCharsets.US_ASCII));
		assertEquals(4, QuotedPrintable.decode(QuotedPrintable.encode(bin, false)).length);
	}
}
