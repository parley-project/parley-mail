package us.bringardner.parley.mail.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.mail.store.MailLocks;
import us.bringardner.parley.net.lock.Sharing;
import us.bringardner.parley.net.server.ServerMain;

/** A mail server run by its own main has its store's bean while it runs, however it is run (15.3) */
public class TestMailServerMain {

	@TempDir
	File dir;

	@Test
	public void theStoreBeanIsThereWhileTheServerRuns() throws Exception {
		FileSource root = FileSourceFactory.getDefaultFactory().createFileSource(new File(dir, "store").getPath());
		MailLocks.writeStoreSettings(root, Sharing.OFF);
		TestSharedSettings.Plain server = new TestSharedSettings.Plain("parley.mail.a");
		server.setMaildropRoot(root);
		server.setProcessorFactory(() -> null); // never connected to
		MBeanServer mbeans = MBeanServerFactory.newMBeanServer();
		ObjectName n = new ObjectName("us.bringardner.parley:type=MailStore,name=plain");

		assertNull(server.getStoreAdmin(), "not running");
		ServerMain.Service s = MailServerMain.service(server, "Plain server", "plain", mbeans);
		s.startAndWait(10000);
		try {
			assertNotNull(server.getStoreAdmin());
			assertTrue(mbeans.isRegistered(n), mbeans.queryNames(null, null).toString());
		} finally {
			s.stop(10000);
		}
		assertFalse(mbeans.isRegistered(n), "unregistered when it stops");
		assertNull(server.getStoreAdmin());
	}
}
