package us.bringardner.parley.mail.server;

import us.bringardner.parley.iam.AuthenticationResult;
import us.bringardner.parley.net.sasl.SaslPrep;
import us.bringardner.parley.net.sasl.ScramCredentials;
import us.bringardner.parley.net.sasl.ServerSaslAuthenticator;

/**
 * parley-net's {@link ServerSaslAuthenticator} for a mail session, with the user names and passwords
 * prepared with SASLprep (RFC 4013; RFC 4616 for PLAIN, RFC 6856 for POP3) as the mail protocols'
 * other logins are:
 * <ul>
 * <li>PLAIN and LOGIN ({@link #checkPassword}) log the session in through
 * {@link AbstractMailProcessor#authenticate(String, String)};</li>
 * <li>SCRAM's credentials are looked up under the prepared name, and after the exchange succeeds
 * {@link #verified(String)} logs the session in (the credentials are asked for again; changed
 * meanwhile, the login is refused);</li>
 * <li>{@link #getResult()} says how the last login ended; {@link #isUnavailable()} is true when the
 * user registry couldn't answer (a temporary failure, not a wrong password);</li>
 * <li>an exchange that failed without a login check (a wrong SCRAM proof) is counted as a refused
 * login with {@link #exchangeFailed(String)}.</li>
 * </ul>
 * One per AUTH command ({@link AbstractMailProcessor#newSaslAuthenticator()}).
 *
 * @since 2.0.0
 */
public class MailSaslAuthenticator extends ServerSaslAuthenticator {

	private final AbstractMailProcessor session;
	/** The result of a login refused here (text that can't be prepared), until the next check */
	private volatile AuthenticationResult refusedHere;

	/** @param session the session to log in */
	public MailSaslAuthenticator(AbstractMailProcessor session) {
		super(session.getServer(), session);
		this.session = session;
	}

	/** PLAIN, LOGIN: log the session in with the SASLprep'd name and password. */
	@Override
	public boolean checkPassword(String user, String password) {
		String u = SaslPrep.prepare(user, false);
		String p = SaslPrep.prepare(password, false);
		if (u == null || p == null || u.isEmpty()) {
			refusedHere = session.authenticate(user, password); // refused and counted there
			return false;
		}
		refusedHere = null;
		return super.checkPassword(u, p);
	}

	/** SCRAM: the credentials of the SASLprep'd name; null (an unknown user) when it can't be prepared. */
	@Override
	public ScramCredentials getScramCredentials(String user, String hash) {
		String u = SaslPrep.prepare(user, false);
		if (u == null || u.isEmpty()) {
			return null;
		}
		return super.getScramCredentials(u, hash);
	}

	/** After SCRAM succeeded: log the session in as the SASLprep'd name. */
	@Override
	public AuthenticationResult verified(String user) {
		String u = SaslPrep.prepare(user, false);
		if (u == null || u.isEmpty()) {
			AuthenticationResult r = session.refuse("the user name can't be prepared (SASLprep)");
			refusedHere = r;
			return r;
		}
		refusedHere = null;
		return super.verified(u);
	}

	/**
	 * The exchange failed (the outcome wasn't a success): how the login ended. When no login was
	 * checked during it (a wrong SCRAM proof is found by the mechanism itself), it is counted here as
	 * a refused login, so password guessing over SCRAM is limited like any other; when the user
	 * registry couldn't answer, it is UNAVAILABLE and not counted.
	 *
	 * @param reason why, for the log
	 * @return REFUSED or UNAVAILABLE (or the result of the login that was checked)
	 */
	public AuthenticationResult exchangeFailed(String reason) {
		AuthenticationResult r = getResult();
		if (r != null) {
			return r;
		}
		if (isUnavailable()) {
			r = AuthenticationResult.unavailable("the user registry can't answer now");
		} else {
			r = session.refuse(reason);
		}
		refusedHere = r;
		return r;
	}

	@Override
	public AuthenticationResult getResult() {
		AuthenticationResult r = refusedHere;
		return r != null ? r : super.getResult();
	}
}
