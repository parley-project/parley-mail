package us.bringardner.parley.mail.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.iam.AuthenticationResult;
import us.bringardner.parley.iam.AuthenticationResult.Status;
import us.bringardner.parley.net.sasl.ISaslChannel;
import us.bringardner.parley.net.sasl.ISaslClient;
import us.bringardner.parley.net.sasl.PlainMechanism;
import us.bringardner.parley.net.sasl.SaslEncoding;
import us.bringardner.parley.net.sasl.SaslException;
import us.bringardner.parley.net.sasl.SaslOutcome;
import us.bringardner.parley.net.sasl.SaslServerDriver;
import us.bringardner.parley.net.sasl.ScramMechanism;
import us.bringardner.parley.net.server.FileUserRegistry;
import us.bringardner.parley.mail.test.MailTestSupport;

/**
 * The mail sessions' logins: passwords through the session (SASLprep'd), SASL through
 * {@link MailSaslAuthenticator}, the three outcomes, and failed logins counted by parley-net.
 */
public class TestMailLogin {

	/** A mail server that is only made, never started */
	static class Plain extends AbstractMailServer {
		Plain() {
			super(0, "Plain", "parley.mail.logintest", false);
			finishInit();
		}

		@Override
		protected String getRootProperty() {
			return MAIL_ROOT_PROP;
		}
	}

	/** A session with no connection, for its login methods */
	static class Session extends AbstractMailProcessor {
		private static final long serialVersionUID = 1L;

		AuthenticationResult login(String user, String password) {
			return authenticate(user, password);
		}

		int failed() {
			return getLoginFailures().getFailed();
		}

		@Override
		public String translateResponseCode(int code) {
			return String.valueOf(code);
		}
	}

	private Plain server;
	private Session session;

	@BeforeEach
	public void setUp() throws IOException {
		server = new Plain();
		server.setLoginFailureDelay(0);
		server.setMaxLoginAttempts(3);
		FileUserRegistry users = new FileUserRegistry() {
			{
				parseLine("tony, secret, READ|WRITE");
				parseLine("jösé, pässwörd, READ|WRITE");
			}
		};
		server.setUserRegistry(users);
		session = new Session();
		session.setServer(server);
	}

	@Test
	public void aPasswordLogsTheSessionIn() {
		AuthenticationResult r = session.login("tony", "secret");
		assertEquals(Status.AUTHENTICATED, r.getStatus(), r.getReason());
		assertEquals("tony", session.getPrincipal().getName());
		session.logout();
		assertNull(session.getPrincipal());
	}

	@Test
	public void namesAndPasswordsArePreparedWithSaslPrep() {
		// U+00A0 (no-break space) is mapped to a space by SASLprep; NFKC composes the decomposed ö
		AuthenticationResult r = session.login("jösé".replace("ö", "ö").replace("é", "é"), "pässwörd");
		assertEquals(Status.AUTHENTICATED, r.getStatus(), r.getReason());
	}

	@Test
	public void refusalsAreCountedByTheFramework() {
		assertEquals(Status.REFUSED, session.login("tony", "wrong").getStatus());
		assertEquals(1, session.failed());
		// text SASLprep can't prepare (a control character) never reaches the registry, but is counted
		assertEquals(Status.REFUSED, session.login("to\u0007ny", "secret").getStatus());
		assertEquals(Status.REFUSED, session.login("", "secret").getStatus());
		assertEquals(3, session.failed());
		assertTrue(session.isTooManyLoginFailures());
		assertNull(session.getPrincipal());
	}

	@Test
	public void aRegistryThatIsDownIsUnavailableAndNotCounted() {
		server.setUserRegistry(MailTestSupport.downAcl());
		AuthenticationResult r = session.login("tony", "secret");
		assertEquals(Status.UNAVAILABLE, r.getStatus());
		assertEquals(0, session.failed());
	}

	@Test
	public void noRegistryIsAnError() {
		Plain none = new Plain();
		Session s = new Session();
		s.setServer(none);
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> s.login("tony", "secret"));
		assertTrue(e.getMessage().contains("authenticationProvider"), e.getMessage());
		assertThrows(IllegalStateException.class, () -> s.login("to\u0007ny", "secret"), "also for text that can't be prepared");
		assertFalse(s.supportsScram("SHA-256"));
	}

	@Test
	public void scramIsOfferedWhenTheRegistryCanServeIt() {
		final String hash = FileUserRegistry.hashPassword("x".toCharArray());
		server.setUserRegistry(new FileUserRegistry() {
			{
				parseLine("hashed, " + hash + ", READ");
			}
		});
		assertFalse(session.supportsScram("SHA-256"), "a hashed password without SCRAM credentials: no SCRAM");
		server.setUserRegistry(MailTestSupport.scramAcl());
		assertTrue(session.supportsScram("SHA-256"));
		assertTrue(session.supportsScram("sha-1"));
		assertFalse(session.supportsScram("MD5"));
	}

	@Test
	public void saslPlainLogsTheSessionIn() throws IOException {
		MailSaslAuthenticator auth = session.newSaslAuthenticator();
		String ir = SaslEncoding.encode("\0tony\0secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		SaslOutcome o = SaslServerDriver.authenticate(new PlainMechanism(), auth, ir, null);
		assertTrue(o.isSuccess(), o.toString());
		assertEquals(Status.AUTHENTICATED, auth.getResult().getStatus());
		assertEquals("tony", session.getPrincipal().getName());
	}

	@Test
	public void saslPlainWithTextThatCantBePreparedIsRefused() throws IOException {
		MailSaslAuthenticator auth = session.newSaslAuthenticator();
		String ir = SaslEncoding.encode("\0to\u0007ny\0secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		SaslOutcome o = SaslServerDriver.authenticate(new PlainMechanism(), auth, ir, null);
		assertFalse(o.isSuccess());
		assertEquals(Status.REFUSED, auth.getResult().getStatus());
		assertEquals(1, session.failed());
	}

	@Test
	public void scramLogsInAfterTheExchangeAndAWrongProofIsCounted() throws IOException {
		server.setUserRegistry(MailTestSupport.scramAcl());

		MailSaslAuthenticator auth = session.newSaslAuthenticator();
		SaslOutcome o = scram(auth, "carol", "carolpw");
		assertTrue(o.isSuccess(), o.toString());
		assertNull(session.getPrincipal(), "not before verified");
		AuthenticationResult r = auth.verified(o.getUser());
		assertEquals(Status.AUTHENTICATED, r.getStatus(), r.getReason());
		assertEquals("carol", session.getPrincipal().getName());
		session.logout();

		MailSaslAuthenticator wrong = session.newSaslAuthenticator();
		o = scram(wrong, "carol", "nope");
		assertFalse(o.isSuccess());
		assertNull(wrong.getResult(), "the mechanism found it; no login was checked");
		assertEquals(Status.REFUSED, wrong.exchangeFailed(o.getReason()).getStatus());
		assertEquals(1, session.failed(), "counted");
		assertEquals(Status.REFUSED, wrong.getResult().getStatus());
	}

	@Test
	public void scramWithARegistryThatIsDownIsUnavailable() throws IOException {
		server.setUserRegistry(MailTestSupport.downAcl());
		MailSaslAuthenticator auth = session.newSaslAuthenticator();
		SaslOutcome o = scram(auth, "carol", "carolpw");
		assertFalse(o.isSuccess());
		assertTrue(auth.isUnavailable());
		assertEquals(Status.UNAVAILABLE, auth.exchangeFailed(o.getReason()).getStatus());
		assertEquals(0, session.failed(), "not counted");
	}

	/** Group names for $group ACL entries come from the registry's findGroups, when it has one. */
	@Test
	public void groupNamesFromTheRegistry() throws IOException {
		FileUserRegistry users = new FileUserRegistry() {
			{
				parseLine("carol, pw, READ, groups=sales@x.us;support@x.us");
				parseLine("dave, pw, READ");
			}
		};
		server.setUserRegistry(users);
		assertEquals(java.util.List.of("sales@x.us", "support@x.us"),
				new java.util.ArrayList<>(server.findGroupNames(users.findPrincipal("carol"))));
		assertTrue(server.findGroupNames(users.findPrincipal("dave")).isEmpty());
		assertTrue(server.findGroupNames(null).isEmpty());

		// a registry without findGroups: no groups; with no registry: none either
		server.setUserRegistry(MailTestSupport.downAcl());
		assertTrue(server.findGroupNames(users.findPrincipal("carol")).isEmpty());
		server.setUserRegistry(null);
		assertTrue(server.findGroupNames(users.findPrincipal("carol")).isEmpty());
	}

	/** Run a SCRAM-SHA-256 exchange between a client and the authenticator. */
	private static SaslOutcome scram(MailSaslAuthenticator auth, String user, String password) throws IOException {
		final ISaslClient client = ScramMechanism.SHA_256.client(user, password);
		final String[] next = new String[1];
		String ir = SaslEncoding.encode(client.initialResponse());
		ISaslChannel channel = new ISaslChannel() {
			@Override
			public void sendChallenge(String base64) throws IOException {
				try {
					byte[] answer = client.evaluateChallenge(SaslEncoding.decode(base64));
					next[0] = SaslEncoding.encode(answer == null ? new byte[0] : answer);
				} catch (SaslException e) {
					next[0] = "*";
				}
			}

			@Override
			public String readResponse() {
				return next[0];
			}
		};
		SaslOutcome o = SaslServerDriver.authenticate(ScramMechanism.SHA_256, auth, ir, channel);
		assertNotNull(o);
		return o;
	}
}
