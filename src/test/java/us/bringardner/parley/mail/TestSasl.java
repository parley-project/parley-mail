package us.bringardner.parley.mail;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class TestSasl {

	@Test
	void plainRoundTrips() {
		String ir = Sasl.encodePlain("tim", "tanstaaftanstaaf");
		assertEquals("AHRpbQB0YW5zdGFhZnRhbnN0YWFm", ir);
		assertArrayEquals(new String[] { "tim", "tanstaaftanstaaf" }, Sasl.parsePlain(Sasl.decodeResponse(ir)));
	}

	@Test
	void emptyResponseIsEqualsSign() {
		assertEquals(0, Sasl.decodeResponse("=").length);
	}

	@Test
	void invalidBase64IsRejected() {
		assertThrows(IllegalArgumentException.class, () -> Sasl.decodeResponse("***"));
	}

	@Test
	void authorizationIdentityMustMatchOrBeEmpty() {
		assertArrayEquals(new String[] { "a", "pw" }, Sasl.parsePlain("a\0a\0pw".getBytes(StandardCharsets.UTF_8)));
		assertNull(Sasl.parsePlain("other\0a\0pw".getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void malformedResponsesAreRejected() {
		assertNull(Sasl.parsePlain("a\0pw".getBytes(StandardCharsets.UTF_8)));
		assertNull(Sasl.parsePlain("\0\0pw".getBytes(StandardCharsets.UTF_8)));
		assertNull(Sasl.parsePlain(new byte[0]));
	}
}
