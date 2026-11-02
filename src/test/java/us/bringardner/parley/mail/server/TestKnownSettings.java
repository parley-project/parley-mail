package us.bringardner.parley.mail.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.net.server.KnownSettings;

/**
 * To-Do 2.16: parley-mail's list of settings (META-INF/parley/settings.list) has every name its code
 * spells out. The names the mail servers read under their prefixes are checked by the server
 * modules (parley-pop3, parley-imap, parley-smtp), which run them.
 */
public class TestKnownSettings {

	@Test
	public void everyConstantIsListed() throws Exception {
		List<String> found = KnownSettings.settingConstants(AbstractMailServer.class);
		assertFalse(found.isEmpty());
		assertEquals(List.of(), KnownSettings.load().undeclared(found));
	}
}
