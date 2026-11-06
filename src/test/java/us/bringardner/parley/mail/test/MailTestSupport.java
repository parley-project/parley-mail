package us.bringardner.parley.mail.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.iam.AuthenticationResult;
import us.bringardner.parley.iam.AuthorizationResult;
import us.bringardner.parley.iam.Credential;
import us.bringardner.parley.iam.EntityId;
import us.bringardner.parley.iam.ParleyPermission;
import us.bringardner.parley.iam.ParleyPrincipal;
import us.bringardner.parley.iam.ProtectedResource;
import us.bringardner.parley.iam.Requester;
import us.bringardner.parley.iam.StoredCredential;
import us.bringardner.parley.iam.StoredCredentialType;
import us.bringardner.parley.iam.UserRegistry;
import us.bringardner.parley.iam.UserRegistryUnavailableException;
import us.bringardner.parley.net.server.FileUserRegistry;

/**
 * What the mail protocols' tests share, published in parley-mail's tests jar
 * ({@code <type>test-jar</type><scope>test</scope>}) so IMAP, POP3 and SMTP never copy it or depend
 * on each other: the test users, user registries for SCRAM and for a store that is down, and
 * file helpers. TLS test material is in parley-net's {@code ServerTestSupport}.
 */
public final class MailTestSupport {

	/**
	 * The test users, a {@link FileUserRegistry} file on the class path: set
	 * {@code <property prefix>.userFile} to it. Users (password {@code secret} unless noted):
	 * tony (READ, WRITE), reader (READ), team1 and team2 (READ, WRITE, maildrop team), nopop (WRITE),
	 * hashed (password hashedpw, {PBKDF2}), jösé (pässwörd, READ, WRITE).
	 */
	public static final String ACL_FILE = "MailTestAcl.txt";

	private MailTestSupport() {
	}

	/**
	 * Users who all have SCRAM credentials, so a server offers SCRAM: carol (password carolpw,
	 * READ and WRITE), noread (pw, WRITE only) and reader (pw, READ only).
	 *
	 * @return a registry of these users, for {@code setUserRegistry}
	 */
	public static UserRegistry scramAcl() {
		final String carol = FileUserRegistry.hashPasswordWithScram("carolpw".toCharArray());
		final String pw = FileUserRegistry.hashPasswordWithScram("pw".toCharArray());
		return new FileUserRegistry() {
			{
				parseLine("carol, " + carol + ", READ|WRITE");
				parseLine("noread, " + pw + ", WRITE");
				parseLine("reader, " + pw + ", READ");
			}
		};
	}

	/**
	 * A user registry whose store is down: every login and lookup is UNAVAILABLE, and it claims to
	 * serve every kind of credential (SCRAM, APOP), so a server offers them.
	 *
	 * @return the registry, for {@code setUserRegistry}
	 */
	public static UserRegistry downAcl() {
		return new UserRegistry() {
			private static final String DOWN = "the database is down";

			@Override
			public boolean supports(Class<? extends Credential> kind, StoredCredentialType stored) {
				return true;
			}

			@Override
			public AuthenticationResult authenticate(Credential credential) {
				return AuthenticationResult.unavailable(DOWN);
			}

			@Override
			public StoredCredential findStoredCredential(String user, StoredCredentialType type) throws UserRegistryUnavailableException {
				throw new UserRegistryUnavailableException(DOWN);
			}

			@Override
			public ParleyPrincipal findPrincipal(String name) throws UserRegistryUnavailableException {
				throw new UserRegistryUnavailableException(DOWN);
			}

			@Override
			public ParleyPrincipal findPrincipal(EntityId id) throws UserRegistryUnavailableException {
				throw new UserRegistryUnavailableException(DOWN);
			}

			@Override
			public java.util.Set<ParleyPrincipal> findGroups(ParleyPrincipal principal) throws UserRegistryUnavailableException {
				throw new UserRegistryUnavailableException(DOWN);
			}

			@Override
			public java.util.Set<ParleyPrincipal> findMembers(ParleyPrincipal group) throws UserRegistryUnavailableException {
				throw new UserRegistryUnavailableException(DOWN);
			}

			@Override
			public AuthorizationResult checkPermission(Requester requester, ParleyPermission needed, ProtectedResource resource) {
				return AuthorizationResult.unavailable(DOWN);
			}
		};
	}

	/** Delete a file, or a directory and everything in it; nothing for null or a file that isn't there. */
	public static void deleteAll(FileSource f) throws IOException {
		if (f == null || !f.exists()) {
			return;
		}
		if (f.isDirectory()) {
			for (FileSource kid : f.listFiles()) {
				deleteAll(kid);
			}
		}
		f.delete();
	}

	/**
	 * A file in a new folder of this run's own under the temp directory, so tests don't depend on
	 * target/ or the working directory.
	 */
	public static String scratchFile(String name) {
		try {
			return Files.createTempDirectory("parley-mail-tests").resolve(name).toString();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
