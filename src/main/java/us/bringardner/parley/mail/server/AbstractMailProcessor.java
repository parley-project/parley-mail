package us.bringardner.parley.mail.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import us.bringardner.parley.mail.SaslPrep;
import us.bringardner.parley.net.sasl.ScramCredentials;
import us.bringardner.parley.net.server.AbstractCommandProcessor;
import us.bringardner.parley.net.server.IAccessControlList;
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

	/**
	 * The principal of a user a SASL mechanism has proven without a password check (SCRAM).
	 * The name is prepared with SASLprep, as for {@link #authenticate(String, String)}.
	 *
	 * @return the principal, or null if the name can't be prepared or the user isn't there
	 * @since 1.1.0
	 */
	protected IPrincipal verifiedPrincipal(String user) {
		user = SaslPrep.prepare(user, false);
		if (user == null || user.isEmpty()) {
			return null;
		}
		return getServer().getVerifiedPrincipal(user);
	}

	/**
	 * @param hash "SHA-1" or "SHA-256"
	 * @return true if the server's access control list can serve SCRAM with that hash
	 * @since 1.1.0
	 */
	public boolean supportsScram(String hash) {
		IAccessControlList acl = getServer().getAccessControl();
		return acl != null && acl.supportsScram(hash);
	}

	/**
	 * The user's SCRAM credentials from the server's access control list, for the SCRAM mechanism.
	 * The name is prepared with SASLprep first.
	 *
	 * @return the credentials, or null if there are none (the login then fails like a wrong password)
	 * @since 1.1.0
	 */
	public ScramCredentials getScramCredentials(String user, String hash) {
		IAccessControlList acl = getServer().getAccessControl();
		user = SaslPrep.prepare(user, false);
		if (acl == null || user == null || user.isEmpty()) {
			return null;
		}
		return acl.getScramCredentials(user, hash);
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
