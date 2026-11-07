package us.bringardner.parley.mail.server;

import java.io.IOException;

import us.bringardner.parley.iam.AuthenticationResult;
import us.bringardner.parley.iam.PasswordCredential;
import us.bringardner.parley.iam.ProofCredential;
import us.bringardner.parley.iam.UserRegistry;
import us.bringardner.parley.net.sasl.SaslPrep;
import us.bringardner.parley.net.sasl.ScramMechanism;
import us.bringardner.parley.net.server.AbstractCommandProcessor;
import us.bringardner.parley.net.server.Server;

/**
 * Base for the mail protocol sessions (POP3, SMTP, IMAP): logging in with a SASLprep'd user name and
 * password through the session's {@link #authenticate(us.bringardner.parley.iam.Credential)}, SASL
 * through {@link #newSaslAuthenticator()}, and upgrading the connection to TLS.
 * <p>
 * Every login gives an {@link AuthenticationResult}: AUTHENTICATED, REFUSED (a wrong password, an
 * unknown user, text SASLprep can't prepare) or UNAVAILABLE (the server's user registry can't answer
 * now: answer with a temporary failure, never "wrong password"). Failed logins are counted, and a
 * refusal is answered late, by parley-net (the session's login limits); after too many the command
 * processor closes the connection once the protocol has replied
 * ({@link #replyTooManyLoginFailures()}).
 */
public abstract class AbstractMailProcessor extends AbstractCommandProcessor {

	private static final long serialVersionUID = 1L;

	protected AbstractMailProcessor() {
		super();
	}

	/**
	 * Log this session in with a user name and password. Both are prepared with SASLprep (RFC 4013)
	 * first; text that can't be prepared, or an empty user name, is refused without asking the
	 * registry, and counted as a failed login like a wrong password.
	 *
	 * @return AUTHENTICATED (the session is logged in, {@link #getPrincipal()}), REFUSED, or
	 * UNAVAILABLE when the user registry can't answer now
	 * @throws IllegalStateException when the server has no user registry
	 * @since 2.0.0 (returned the principal or null)
	 */
	protected AuthenticationResult authenticate(String user, String password) {
		String preparedUser = SaslPrep.prepare(user, false);
		String preparedPassword = SaslPrep.prepare(password, false);
		if (preparedUser == null || preparedPassword == null || preparedUser.isEmpty()) {
			getServer().requireUserRegistry(); // no registry is a mistake in the configuration, not a refusal
			return refuseLogin("the user name or password can't be prepared (SASLprep)");
		}
		PasswordCredential credential = new PasswordCredential(preparedUser, preparedPassword.toCharArray(), getRequester());
		try {
			return authenticate(credential);
		} finally {
			credential.clear();
		}
	}

	/**
	 * A SASL authenticator for this session (AUTHENTICATE / AUTH): PLAIN and LOGIN log the session in
	 * with SASLprep'd names and passwords; SCRAM looks the credentials up under the SASLprep'd name,
	 * and after it succeeds {@link MailSaslAuthenticator#verified(String)} logs the session in. Use
	 * one per AUTH command.
	 *
	 * @since 2.0.0
	 */
	public MailSaslAuthenticator newSaslAuthenticator() {
		return new MailSaslAuthenticator(this);
	}

	/**
	 * @param hash "SHA-1" or "SHA-256"
	 * @return true if the server's user registry can serve SCRAM with that hash for every user, so
	 * the mechanism may be offered; false with no registry
	 * @since 1.1.0 (asked the access control list)
	 */
	public boolean supportsScram(String hash) {
		ScramMechanism m = ScramMechanism.SHA_256.getHash().equalsIgnoreCase(hash) ? ScramMechanism.SHA_256
				: ScramMechanism.SHA_1.getHash().equalsIgnoreCase(hash) ? ScramMechanism.SHA_1 : null;
		UserRegistry registry = getServer().getUserRegistry();
		return m != null && registry != null && registry.supports(ProofCredential.class, m.getStoredType());
	}

	/** True if logins must wait for TLS on this connection. */
	public boolean isLoginBlockedUntilTls() {
		return ((Server) getServer()).isRequireTls() && !isTls();
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
