# SFTP

## What it is

**SFTP** (SSH File Transfer Protocol) is a way to move files between two computers over an encrypted connection. It runs on top of **SSH** (Secure Shell), the protocol used to log in to remote machines safely. An SFTP **server** listens on a port (22 by default) and shows each user a folder; an SFTP **client** logs in with a user name and a password (or a key), lists files, downloads them and uploads them. SFTP is not FTP with encryption added: it is a different protocol that happens to do a similar job. The authenticity of the server is checked with its **host key**, a fingerprint the client either has remembered or is told to trust.

In the lesson, the partner's system "drops" a nightly CSV file on an SFTP server, and the company's old batch job picks it up the next morning. That drop is the integration point this repository replaces.

## Why this repository uses it

The partner's interface cannot change: they upload files by SFTP. The new flow therefore has to read from the same folder, and the legacy loader reads from it too, so old and new run side by side on the same input. Everything else in the design (the broker, the idempotent receiver, the reconciliation) is built on top of that unchanged edge.

## Where it appears

| Path | What it does with SFTP |
| --- | --- |
| `${S}/SftpConfiguration.java` | The SFTP session factory (the client's connections). |
| `${S}/SftpPartnerDrop.java` | Uploads, lists and removes files, as the partner's system would. |
| `src/main/java/plainround/orderflow/infrastructure/flow/OrderFlowConfiguration.java` | The inbound adapter that polls the folder (see the Spring Integration note). |
| `src/main/java/plainround/orderflow/infrastructure/jdbc/LegacyBatchLoader.java` | Downloads files for the legacy loader. |
| `docker-compose.yml` | The `sftp` service: a throwaway SFTP server for the lesson. |
| `scripts/demo_week.sh`, `scripts/demo_restart.sh`, `Makefile` | Run `docker compose exec sftp ...` to clear the folder. |

## How it works underneath

An SFTP client opens a TCP connection to the server, agrees on encryption keys with SSH, proves who it is (a password here), and opens an SFTP **session** on a channel. Commands travel as small binary requests: open a directory, read its entries, open a file for reading or writing, read or write a block, close, remove. Opening a connection involves several round trips and key computations, so clients keep sessions in a pool and reuse them.

This repository uses the **Apache MINA SSHD** library through Spring Integration (the types `SftpClient`, `DirEntry` come from `org.apache.sshd.sftp.client`). Spring Integration wraps it in a **session factory** (it makes sessions) and a **remote file template** (list, get, send, remove), and in an **inbound adapter** that turns "new file in the folder" into a message.

## Walkthrough of this repository's use

```java
package plainround.orderflow.infrastructure.sftp;

import org.apache.sshd.sftp.client.SftpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.file.remote.session.CachingSessionFactory;
import org.springframework.integration.file.remote.session.SessionFactory;
import org.springframework.integration.sftp.session.DefaultSftpSessionFactory;
import org.springframework.integration.sftp.session.SftpRemoteFileTemplate;
import plainround.orderflow.infrastructure.OrderflowProperties;

/** The SFTP client side: one cached session factory and a template over it. */
@Configuration
@EnableConfigurationProperties(OrderflowProperties.class)
public class SftpConfiguration {

  @Bean
  SessionFactory<SftpClient.DirEntry> sftpSessions(OrderflowProperties properties) {
    DefaultSftpSessionFactory factory = new DefaultSftpSessionFactory();
    factory.setHost(properties.sftp().host());
    factory.setPort(properties.sftp().port());
    factory.setUser(properties.sftp().user());
    factory.setPassword(properties.sftp().password());
    // The local container generates a new host key every time it starts; production pins the
    // partner's key.
    factory.setAllowUnknownKeys(true);
    return new CachingSessionFactory<>(factory, 4);
  }

  @Bean
  SftpRemoteFileTemplate sftpTemplate(SessionFactory<SftpClient.DirEntry> sftpSessions) {
    return new SftpRemoteFileTemplate(sftpSessions);
  }
}
```

`@Configuration` and `@EnableConfigurationProperties(OrderflowProperties.class)` register the typed settings (the Bean Validation note). `sftpSessions` builds a `DefaultSftpSessionFactory` and sets the host, port, user and password from the settings. `setAllowUnknownKeys(true)` tells the client to accept a server whose host key it has never seen; the comment explains why this is acceptable here and not in production: the throwaway container generates a new host key every time it starts, while production pins the partner's key so that nobody can impersonate the server. `new CachingSessionFactory<>(factory, 4)` keeps up to four sessions open for reuse, so each poll does not log in again. `sftpTemplate` wraps the factory in an `SftpRemoteFileTemplate`.

```java
/** Uploads a file into the partner's folder, as the partner's own system would. */
public class SftpPartnerDrop implements PartnerDrop {

  private final SftpRemoteFileTemplate template;
  private final String directory;

  public SftpPartnerDrop(SftpRemoteFileTemplate template, OrderflowProperties properties) {
    this.template = template;
    this.directory = properties.sftp().remoteDirectory();
    this.template.setRemoteDirectoryExpression(
        new LiteralExpression(properties.sftp().remoteDirectory()));
    this.template.setAutoCreateDirectory(false);
  }

  @Override
  public void upload(Path file) {
    template.send(MessageBuilder.<File>withPayload(file.toFile()).build(), "");
  }
```

`SftpPartnerDrop` plays the partner's own system. The constructor stores the template and sets the remote directory with `new LiteralExpression(...)` (Spring's way to give a fixed text where an expression is expected) and `setAutoCreateDirectory(false)` (the folder must already exist; the container creates it). `upload` builds a message whose payload is the file (`MessageBuilder.<File>withPayload(file.toFile()).build()`) and calls `template.send(message, "")`, which uploads it into the configured folder under its own name; the empty text is the sub-directory.

```java
  /** Names of the files in the partner's folder, for the status command. */
  public java.util.List<String> list() {
    return java.util.Arrays.stream(template.list(directory))
        .map(SftpClient.DirEntry::getFilename)
        .filter(name -> name.endsWith(".csv"))
        .sorted()
        .toList();
  }

  /** Removes every CSV from the folder (the tests start from an empty one). */
  public void clear() {
    list().forEach(name -> template.remove(directory + "/" + name));
  }
}
```

`list` returns the CSV names in the folder, sorted: `template.list(directory)` returns an array of directory entries, `DirEntry::getFilename` takes the names, `filter(name -> name.endsWith(".csv"))` keeps CSV files. `clear` removes every CSV (the end-to-end tests start from an empty folder, and `make reset` does the same from the shell).

```yaml
  sftp:
    image: atmoz/sftp:alpine
    # user:password:uid:gid:folder - the folder "upload" is created inside the user's home and is writable.
    command: ["partner:${SFTP_PASSWORD:?copy .env.example to .env first}:::upload"]
    ports:
      - "${SFTP_PORT:-56222}:22"
    # No volume on the upload folder: a named volume mounts root-owned and the partner user could not write to it.
    # The folder lives in the container's own layer, which survives `docker restart`.
```

The server is the image `atmoz/sftp:alpine`. Its `command` is `user:password:uid:gid:folder`: the user `partner`, the password from the environment (`${SFTP_PASSWORD:?copy .env.example to .env first}` refuses to start without one), empty uid and gid (defaults), and a folder `upload` created inside the user's home, which the user can write. Port 22 inside the container is published on the host port 56222 by default. The comments explain a real problem found while building: a named volume on the upload folder is mounted as owned by root, so the partner user could not write; the folder therefore lives in the container's own layer, which survives `docker restart` (and is deleted with `make down`).

## Try it yourself

1. Start the server: `make up`. Check it: `docker compose ps sftp` shows it `Up`.
2. Upload a file as the partner: `make partner` prints `uploaded orders-1.csv with 9997 valid distinct orders`.
3. List the folder from inside the container: `docker compose exec -T sftp ls -l /home/partner/upload`.
4. Use the command-line SFTP client with the password from `.env`: `sftp -P 56222 partner@localhost` then `ls upload` and `exit`. The first connection asks to trust the host key.
5. Empty the folder: `make reset`.

## Mistakes people make

- **Trusting every host key in production.** `setAllowUnknownKeys(true)` is for the throwaway container only.
- **Treating the file name as the file's identity.** The partner may reuse names; compare the modification time too (the flow's filter does).
- **Reading a file while the partner is still writing it.** A common fix is for the partner to upload under a temporary name and rename it when complete.
- **A named volume owned by root.** The upload user cannot write to it.
- **Opening a new connection for every operation.** Use a cached session factory.

## Check yourself

1. What does SFTP run on top of? *SSH.*
2. Why does the client keep four cached sessions? *Opening a session is slow; reuse avoids a login per poll.*
3. What does `setAllowUnknownKeys(true)` risk? *Accepting an impostor's server; fine only for a throwaway local container.*
4. Why does `SftpPartnerDrop` exist in this repository? *To play the partner's system for the demos and tests.*
5. Why is there no volume on the upload folder? *A named volume is root-owned and the partner user could not write to it.*

## Further reading

- Spring Integration SFTP: https://docs.spring.io/spring-integration/reference/6.5/sftp.html
- Apache MINA SSHD: https://mina.apache.org/sshd-project/documentation.html
- The SSH File Transfer Protocol draft: https://datatracker.ietf.org/doc/html/draft-ietf-secsh-filexfer-02
- The `atmoz/sftp` image: https://hub.docker.com/r/atmoz/sftp
