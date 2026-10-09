package us.bringardner.parley.mail.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import us.bringardner.parley.mail.SaslPrep;
import us.bringardner.parley.net.server.AbstractCommandProcessor;
import us.bringardner.parley.net.server.IPrincipal;
import us.bringardner.parley.net.server.Server;

/**
 * Base for the mail protocol sessions (POP3, SMTP, IMAP): logging in with a SASLprep'd user name and
 * password, counting failed logins, and upgrading the connection to TLS.
 */
public abstract class AbstractMailProcessor extends AbstractCommandProcessor {

	private static final long serialVersionUID = 1L;

	private int loginFailures;

	protected AbstractMailProcessor() {
		super();
	}

	/**
	 * Check a user name and password. Both are prepared with SASLprep (RFC 4013) first; text that
	 * can't be prepared fails.
	 *
	 * @return the principal, or null if the login failed
	 */
	protected IPrincipal authenticate(String user, String password) {
		user = SaslPrep.prepare(user, false);
		password = SaslPrep.prepare(password, false);
		if (user == null || password == null || user.isEmpty()) {
			return null;
		}
		return getServer().authenticate(user, password.getBytes(StandardCharsets.UTF_8));
	}

	/** True if logins must wait for TLS on this connection. */
	public boolean isLoginBlockedUntilTls() {
		return ((Server) getServer()).isRequireTls() && !isTls();
	}

	/** Count a failed login; true if there have been too many and the connection should close. */
	protected boolean tooManyLoginFailures() {
		return ((Server) getServer()).isTooManyLoginFailures(++loginFailures);
	}

	/**
	 * STARTTLS / STLS: the response has been sent; negotiate TLS. Anything the client sent before TLS
	 * began is forgotten (RFC 3207, RFC 9051 section 11.1).
	 */
	public void startTls() throws IOException {
		beforeTls();
		getConnection().negotiateSecureSocket("TLS");
		afterTls();
	}

	/** Flush output buffered for the plain connection. */
	protected void beforeTls() throws IOException {
	}

	/** Reopen the streams on the TLS connection and reset the session state. */
	protected void afterTls() throws IOException {
	}
}
