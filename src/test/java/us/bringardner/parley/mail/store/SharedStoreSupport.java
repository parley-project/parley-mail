package us.bringardner.parley.mail.store;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Set;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.net.lock.LockDomain;
import us.bringardner.parley.net.lock.LockOptions;
import us.bringardner.parley.net.lock.Locks;
import us.bringardner.parley.net.lock.Sharing;

/** "Servers" sharing a store: each a Locks of its own with the store as domain "mail". */
final class SharedStoreSupport {

	private SharedStoreSupport() {
	}

	static FileSource newRoot() throws IOException {
		File d = Files.createTempDirectory("parley-mail-shared").toFile();
		d.deleteOnExit();
		FileSource root = FileSourceFactory.getDefaultFactory().createFileSource(d.getCanonicalPath());
		MailLocks.writeStoreSettings(root, Sharing.CLUSTER); // made by an administrator
		return root;
	}

	/** A server: leases on a local directory (they work between Locks in one JVM, like servers). */
	static LockDomain server(FileSource root, String id) throws IOException {
		return Locks.open(LockOptions.sharing(Sharing.CLUSTER).withOwnerId(id).withLease(Duration.ofSeconds(2), Duration.ofMillis(400))
				.withTimeout(Duration.ofSeconds(20))).domain("mail", root);
	}

	/** This server's Mailbox object for a directory (as its registry would make it). */
	static Mailbox open(LockDomain d, FileSource dir) throws IOException {
		if (!dir.isDirectory()) {
			dir.mkdirs();
		}
		return Mailbox.open(dir, MailboxRegistry.keyOf(dir), d, MailboxRegistry.lockName("mailbox", d, dir));
	}

	static MessageInfo append(Mailbox m, String text) throws IOException {
		return m.append(new ByteArrayInputStream(("Subject: " + text + "\r\n\r\n" + text + "\r\n").getBytes(StandardCharsets.UTF_8)),
				Set.of(), 0);
	}
}
