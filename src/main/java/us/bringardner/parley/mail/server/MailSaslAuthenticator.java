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
 * <li>text that can't be prepared is refused here, counted like a wrong password
 * ({@link us.bringardner.parley.net.server.LoginSession#refuseLogin(String)});</li>
 * <li>{@link #getResult()} says how the last login ended, including a refusal here.</li>
 * </ul>
 * A protocol's AUTH command runs the whole exchange with
 * {@link #authenticate(us.bringardner.parley.net.sasl.ISaslMechanism, String, us.bringardner.parley.net.sasl.ISaslChannel)},
 * which also counts a wrong SCRAM proof. One per AUTH command
 * ({@link AbstractMailProcessor#newSaslAuthenticator()}).
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
			session.getServer().requireUserRegistry(); // no registry is a mistake in the configuration, not a refusal
			refusedHere = session.refuseLogin("the user name or password can't be prepared (SASLprep)");
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
			AuthenticationResult r = session.refuseLogin("the user name can't be prepared (SASLprep)");
			refusedHere = r;
			return r;
		}
		refusedHere = null;
		return super.verified(u);
	}

	@Override
	public AuthenticationResult getResult() {
		AuthenticationResult r = refusedHere;
		return r != null ? r : super.getResult();
	}
}
