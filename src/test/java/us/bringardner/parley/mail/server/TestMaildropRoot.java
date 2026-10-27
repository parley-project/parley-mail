package us.bringardner.parley.mail.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;

import javax.management.MBeanServerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.net.server.ServerContext;

/**
 * How a server finds a mail store that isn't on its own disk, the same for any file system:
 * <ol>
 * <li>parley.mail.fileSource names the file system, and each parley.mail.fileSource.KEY setting is
 * its connection setting KEY (a secret as a reference, such as @file:...)</li>
 * <li>or parley.mail.root is a filesource: URL that says it all</li>
 * </ol>
 * A root that can't be opened stops the start, saying why, without the URL's settings.
 */
public class TestMaildropRoot {

	private final ServerContext context = new ServerContext(MBeanServerFactory.newMBeanServer());

	@AfterEach
	public void close() {
		context.close();
		RecordingFileSourceFactory.last = null;
	}

	private TestSharedSettings.Plain server(String... settings) throws Exception {
		Properties c = new Properties();
		for (int i = 0; i < settings.length; i += 2) {
			c.setProperty(settings[i], settings[i + 1]);
		}
		TestSharedSettings.Plain p = AbstractMailServer.makeIn(context, c, () -> new TestSharedSettings.Plain("parley.mail.a"));
		p.initMaildropRoot(AbstractMailServer.MAIL_FILE_SOURCE_PROP, null, AbstractMailServer.MAIL_ROOT_PROP, null, null, null);
		return p;
	}

	@Test
	public void theFileSystemGetsItsConnectionSettings() throws Exception {
		TestSharedSettings.Plain p = server(
				AbstractMailServer.MAIL_ROOT_PROP, "/mail",
				AbstractMailServer.MAIL_FILE_SOURCE_PROP, "Recording",
				AbstractMailServer.MAIL_FILE_SOURCE_PROP + ".host", " db1.example.test ",
				AbstractMailServer.MAIL_FILE_SOURCE_PROP + ".password", "@file:/etc/parley/store.secret",
				"parley.mail.fileSourceOther", "not a connection setting",
				"parley.mail.smtp.port", "25");
		FileSource root = p.getMaildropRoot();
		assertEquals(RecordingFileSourceFactory.ID, root.getFileSourceFactory().getTypeId());
		assertEquals("/mail", root.getAbsolutePath());
		Properties expected = new Properties();
		expected.setProperty("host", "db1.example.test");
		expected.setProperty("password", "@file:/etc/parley/store.secret"); // the reference: the file system reads it
		assertEquals(expected, RecordingFileSourceFactory.last.settings);
		assertTrue(root.getFileSourceFactory().isConnected());
	}

	@Test
	public void withoutConnectionSettingsTheFileSystemHasItsOwn() throws Exception {
		TestSharedSettings.Plain p = server(AbstractMailServer.MAIL_ROOT_PROP, "/mail", AbstractMailServer.MAIL_FILE_SOURCE_PROP, "recording");
		assertEquals(RecordingFileSourceFactory.ID, p.getMaildropRoot().getFileSourceFactory().getTypeId());
		assertNull(RecordingFileSourceFactory.last.settings, "nothing set");
	}

	@Test
	public void aFilesourceUrlRootSaysItAll() throws Exception {
		TestSharedSettings.Plain p = server(AbstractMailServer.MAIL_ROOT_PROP, "filesource:/mail?sourcetype=recording&host=db2.example.test",
				// a URL decides: this is not used
				AbstractMailServer.MAIL_FILE_SOURCE_PROP, "nosuch");
		FileSource root = p.getMaildropRoot();
		assertEquals(RecordingFileSourceFactory.ID, root.getFileSourceFactory().getTypeId());
		assertEquals("/mail", root.getAbsolutePath());
		assertTrue(RecordingFileSourceFactory.last.url.getQuery().contains("host=db2.example.test"));
	}

	@Test
	public void anUnknownFileSystemStopsTheStartSayingWhy() throws Exception {
		TestSharedSettings.Plain p = server(AbstractMailServer.MAIL_ROOT_PROP, "/mail", AbstractMailServer.MAIL_FILE_SOURCE_PROP, "nosuch");
		java.io.UncheckedIOException e = assertThrows(java.io.UncheckedIOException.class, p::start);
		assertTrue(e.getMessage().contains("There is no file system nosuch (" + AbstractMailServer.MAIL_FILE_SOURCE_PROP + ")"), e.getMessage());
	}

	@Test
	public void aUrlThatCantBeOpenedIsReportedWithoutItsSettings() throws Exception {
		TestSharedSettings.Plain p = server(AbstractMailServer.MAIL_ROOT_PROP, "filesource:/mail?sourcetype=nosuch&password=hunter2");
		java.io.IOException e = assertThrows(java.io.IOException.class, p::getMaildropRoot);
		assertTrue(e.getMessage().startsWith("Can't open the maildrop root filesource:/mail?..."), e.getMessage());
		assertFalse(e.getMessage().contains("hunter2"), e.getMessage());
	}
}
