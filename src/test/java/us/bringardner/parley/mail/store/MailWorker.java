package us.bringardner.parley.mail.store;

import java.time.Duration;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.net.lock.LockDomain;
import us.bringardner.parley.net.lock.LockOptions;
import us.bringardner.parley.net.lock.Locks;
import us.bringardner.parley.net.lock.Sharing;

/** Another server in another JVM: appends N messages to a mailbox in a shared store, then prints DONE. */
public final class MailWorker {
	private MailWorker() {
	}

	/** Arguments: the store's root, the mailbox directory (relative), how many, the sharing. */
	public static void main(String[] args) throws Exception {
		FileSource root = FileSourceFactory.getDefaultFactory().createFileSource(args[0]);
		try (Locks locks = Locks.open(LockOptions.sharing(Sharing.valueOf(args[3])).withOwnerId("worker").withTimeout(Duration.ofSeconds(60)))) {
			LockDomain d = locks.domain("mail", root);
			Mailbox m = SharedStoreSupport.open(d, root.getChild(args[1]));
			int n = Integer.parseInt(args[2]);
			for (int i = 0; i < n; i++) {
				SharedStoreSupport.append(m, "worker " + i);
			}
		}
		System.out.println("DONE");
	}
}
