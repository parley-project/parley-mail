# Changelog

## parley-mail 1.0.0 (unreleased)

The message model and mailbox store of BjlEmail (`us.bringardner:bjl_email` 1.0.0-SNAPSHOT, never
released) are now **parley-mail**, part of the Parley library family. The SMTP, IMAP and POP3
code moves to `parley-smtp`, `parley-imap` and `parley-pop3`, which build on this module.

### Changed (needs a code change)

- Packages: `us.bringardner.net.email` is now `us.bringardner.parley.mail`, and
  `us.bringardner.net.imap.server.store` is now `us.bringardner.parley.mail.store`.
- The store's constants moved from `us.bringardner.net.imap.IMAP` to
  `us.bringardner.parley.mail.store.MailboxConstants` (`DELIMITER`, `INBOX`, `SENT`, `DRAFTS`,
  `TRASH`, `JUNK`, `ARCHIVE` and the `CODE_*` response codes), with the same values, so the store
  no longer depends on IMAP.
- Module name (`Automatic-Module-Name`): `us.bringardner.parley.mail`.
- Dependencies: only `parley-files` (and through it `parley-core` and `parley-io`); no DNS or
  network framework.

### Unchanged

- Mailboxes on disk: same directory layout, name encoding and `.imap-index` format
  (header `BJLIMAP 1`), so existing mail stores keep working.
