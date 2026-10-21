package us.bringardner.parley.mail.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.net.sasl.ScramCredentials;
import us.bringardner.parley.net.server.AccessControlUnavailableException;
import us.bringardner.parley.net.server.FileBasedAcl;
import us.bringardner.parley.net.server.IAccessControlList;
import us.bringardner.parley.net.server.IPermission;
import us.bringardner.parley.net.server.IPrincipal;
import us.bringardner.parley.net.server.IServer;

/**
 * What the mail protocols' tests share, published in parley-mail's tests jar
 * ({@code <type>test-jar</type><scope>test</scope>}) so IMAP, POP3 and SMTP never copy it or depend
 * on each other: the test users, access control lists for SCRAM and for a store that is down, and
 * file helpers. TLS test material is in parley-net's {@code ServerTestSupport}.
 */
public final class MailTestSupport {

	/**
	 * The test users, a {@link FileBasedAcl} file on the class path: set
	 * {@code <server name>.userFile} to it. Users (password {@code secret} unless noted):
	 * tony (READ, WRITE), reader (READ), team1 and team2 (READ, WRITE, maildrop team), nopop (WRITE),
	 * hashed (password hashedpw, {PBKDF2}), jösé (pässwörd, READ, WRITE).
	 */
	public static final String ACL_FILE = "MailTestAcl.txt";

	private MailTestSupport() {
	}

	/**
	 * Users who all have SCRAM credentials, so a server offers SCRAM: carol (password carolpw,
	 * READ and WRITE), noread (pw, WRITE only) and reader (pw, READ only).
	 */
	public static IAccessControlList scramAcl() {
		final String carol = FileBasedAcl.hashPasswordWithScram("carolpw".toCharArray());
		final String pw = FileBasedAcl.hashPasswordWithScram("pw".toCharArray());
		return new FileBasedAcl() {
			{
				parseLine("carol, " + carol + ", READ|WRITE");
				parseLine("noread, " + pw + ", WRITE");
				parseLine("reader, " + pw + ", READ");
			}
		};
	}

	/** An access control list whose store is down: every lookup throws, and it claims to serve SCRAM. */
	public static IAccessControlList downAcl() {
		return new IAccessControlList() {
			@Override
			public void initialize(IServer server) {
			}

			@Override
			public boolean checkPermission(IPrincipal user, IPermission action) {
				return false;
			}

			@Override
			public IPrincipal getPrincipal(String user) {
				return null;
			}

			@Override
			public IPrincipal findPrincipal(String user) throws AccessControlUnavailableException {
				throw new AccessControlUnavailableException("the database is down");
			}

			@Override
			public boolean supportsScram(String hash) {
				return true;
			}

			@Override
			public ScramCredentials getScramCredentials(String user, String hash) throws AccessControlUnavailableException {
				throw new AccessControlUnavailableException("the database is down");
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
