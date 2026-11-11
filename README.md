# Quick Drop

Share files between computers, phones, and tablets on your local network. Run Quick Drop on one computer, then connect your other devices through a browser using a link or QR code.

## Features

- **Send and receive:** drag and drop files, track upload progress, and retry failed uploads.
- **Download together:** select multiple files to download as a ZIP.
- **Connect your devices:** unlock the dashboard with a four-digit owner PIN.
- **Share selected files:** generate download links and QR codes.
- **Accept guest uploads:** create temporary upload-only links without sharing your owner PIN.
- **Set file expiration:** choose one hour, 24 hours, seven days, or no expiration.
- **Stay up to date:** file lists and connection status refresh automatically.
- **Dark theme:** consistent styling across the dashboard, guest upload page, and PIN pages.

## Requirements

- A Java Development Kit (JDK), version 21 or newer.
- Internet access for the initial Maven and dependency downloads.
- Devices connected to the same local network for transfers.

The Maven Wrapper is included. H2 stores the database locally, so no separate database server is needed. Node.js is only needed to run the JavaScript tests.

## Getting started

Clone or download this repository and open a terminal in its root directory. Make sure `java -version` works and `JAVA_HOME` points to your JDK installation if required by the Maven Wrapper.

### Windows

```powershell
.\mvnw.cmd spring-boot:run
```

### macOS and Linux

```sh
sh ./mvnw spring-boot:run
```

Open [http://localhost:8080](http://localhost:8080) on the host computer. Keep the app running while transferring files; press **Ctrl+C** in the terminal to stop it.

### IntelliJ IDEA

1. Open the repository as a Maven project and let its dependencies load.
2. Select a JDK version 21 or newer as the project SDK.
3. Run `com.example.quickdrop.QuickDropApplication`, with the repository root as the working directory.
4. Open [http://localhost:8080](http://localhost:8080).

Run only one instance against the local database at a time. Stop a terminal instance before starting another in IntelliJ.

## Using Quick Drop

The default run configuration uses **unencrypted HTTP** for trusted local networks. To protect PINs, session cookies, and files in transit, use the HTTPS configuration below.

1. On the host computer, visit [Owner PIN setup](http://localhost:8080/owner/setup) and choose a four-digit PIN. Access through localhost already has owner permissions.
2. On another device on the same network, open the address shown under **Connect another device**, or scan its QR code.
3. Enter the owner PIN to access **Send files** and **Receive files**. Owner access includes uploading, viewing, downloading, sharing, and deleting files.
4. Choose or drop files in **Send files** to upload automatically. Use **Receive files** to download or manage them.

For someone who only needs to send files, expand **Let someone send files (optional)** and create a guest upload link. It can last 15 minutes, one hour, or 24 hours, and you can stop guest uploads early. Guests using this link cannot browse or download your stored files.

To share downloads, select files in **Receive files** and choose **Create share link**. Keep the host computer running and connected while links are in use.

Replacing a file invalidates its previous download link and any combined links containing that version. To invalidate links without deleting files, select the files and choose **Revoke download links**. This also revokes combined links containing those files; their contents and expiration settings are preserved. Create fresh links afterward if needed. Revocation cannot remove copies already downloaded or retract bytes from a download that has already started.

Owner login allows five attempts per client before a one-minute cooldown. Repeated bursts double the cooldown up to 15 minutes; 30 minutes without an admitted attempt resets the client history. All attempts, including successful and malformed submissions, count. Across all clients, at most 30 attempts per minute and two simultaneous PIN checks are allowed. Blocked requests return HTTP 429 with a retry time. Limits use the direct connection address, are held in memory, and reset when the app restarts. Clients behind one proxy share a limit. Existing authenticated sessions and local dashboard access remain available during cooldowns.

PIN changes require the current PIN once setup is complete. Forms and browser uploads include a session-bound CSRF token; requests with missing tokens or a foreign browser origin are rejected. After restarting the server or signing out, refresh old tabs before submitting another action. API clients must first load a page, retain its session cookie, and send its token as `X-CSRF-Token` (or `_csrf` for URL-encoded forms).

## HTTPS and public addresses

Quick Drop supports direct HTTPS through its embedded server. The `https` profile uses port `8443`, TLS 1.2/1.3, and Secure, HttpOnly session cookies. It serves HTTPS only; the default HTTP listener is not started alongside it. Generated device, guest, and download links use the same configured address.

### Certificate requirements

Provide a **PKCS12 keystore** containing the server's private key and certificate chain. Its certificate must be trusted by every connecting device and include the hostname or IP address you use in its Subject Alternative Names. Include `localhost` for PIN setup on the host computer. A certificate from your organization's CA or a local CA trusted by your devices is suitable for LAN use.

Put the keystore in `certs/quickdrop.p12` or another private location. `certs/` and common private-key/keystore formats are ignored by Git. Keep keystore passwords out of committed files.

For development testing, you can generate a self-signed certificate with the JDK's `keytool`. Replace `192.168.1.20` with this PC's actual LAN IP first; the command prompts for a keystore password:

```powershell
New-Item -ItemType Directory -Force certs
keytool -genkeypair -alias quickdrop -keyalg RSA -keysize 3072 -validity 365 -storetype PKCS12 -keystore certs/quickdrop.p12 -dname "CN=Quick Drop" -ext "SAN=dns:localhost,ip:127.0.0.1,ip:192.168.1.20"
```

Generating a certificate does not make other devices trust it. Establish certificate trust on those devices before using HTTPS for PINs or file transfers; do not treat clicking through a browser certificate warning as a finished setup.

### Run with HTTPS on Windows

From the repository root, with your trusted keystore ready:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'https'
$env:QUICKDROP_KEYSTORE = ([Uri](Resolve-Path '.\certs\quickdrop.p12').Path).AbsoluteUri
$password = Read-Host 'Keystore password' -AsSecureString
$env:QUICKDROP_KEYSTORE_PASSWORD = [System.Net.NetworkCredential]::new('', $password).Password
$env:QUICKDROP_PUBLIC_BASE_URL = 'https://192.168.1.20:8443'
.\mvnw.cmd spring-boot:run
```

Replace the sample IP with the certificate-covered address reachable by your devices. Open [https://localhost:8443](https://localhost:8443) on the host. Other devices use the configured public address. Set `QUICKDROP_HTTPS_PORT` to change the listening port, and update the public address to match. In IntelliJ, select the `https` active profile and supply the same `QUICKDROP_*` environment variables in the run configuration. For a packaged JAR, the environment variables work with `java -jar target/quick-drop-0.0.1-SNAPSHOT.jar` too.

`quickdrop.public-base-url` (or `QUICKDROP_PUBLIC_BASE_URL`) overrides automatic network address selection. It must be an HTTP(S) origin without a path, query, credentials, or fragment, and its scheme must match the server's TLS mode. Without an override, Quick Drop uses its detected LAN IP, current listening port, and TLS mode. Make sure that detected IP is covered by the certificate, or configure a fixed certificate-covered address.

This configuration uses direct TLS, not TLS termination at a reverse proxy. Forwarded headers are ignored; setting a public URL alone does not encrypt HTTP traffic. The HTTPS profile needs valid certificate configuration to start. Existing HTTP sessions do not carry over automatically: reopen the HTTPS address and unlock it again.

See Spring Boot's [SSL configuration documentation](https://docs.spring.io/spring-boot/reference/features/ssl.html) for certificate and keystore options.

## Storage and configuration

Settings live in [`src/main/resources/application.properties`](src/main/resources/application.properties).

| Setting | Default |
| --- | --- |
| HTTP port | `8080` |
| Maximum file size | `3GB` |
| Maximum multipart request size | `3073MB` |
| Database | `data/quickdrop.mv.db` |
| Owner PIN hash | `data/owner-auth.properties` |
| Uploaded files | `uploads/` |

Storage paths are relative to the working directory. Always launch from the repository root to use the same database and uploads. The `data/` and `uploads/` directories are ignored by Git. Stop the app before backing up these directories together.

New uploads are stored under unique internal IDs in `uploads/.versions/`; original filenames remain in the database and download headers. Existing files directly under `uploads/` with database records remain readable and move to the new layout when replaced. Preserve the database and uploads together when backing up or moving the app. Set `quickdrop.storage-directory` to use a different upload directory.

Uploads, replacement, deletion, expiration, and link revocation share one storage lifecycle coordinator. File contents are staged before publication; database changes commit before superseded or deleted files are removed. Pending deletions are recorded in the database and retried if a file is temporarily locked. A deleted file can therefore remain on disk briefly while already being unavailable through the app.

Recovery runs at startup and every minute. It retries pending deletions, clears abandoned partial uploads, and removes metadata for expired or definitively missing files. Completed files in `.versions/` without database records are moved to `uploads/.recovery/` for manual inspection, not automatically shared or deleted. Untracked legacy files at the upload root are left untouched and are not automatically listed. File-list reads never recreate metadata or reset expiration dates. Files awaiting cleanup and quarantined files still count toward storage usage.

## Build and test

On Windows:

```powershell
.\mvnw.cmd verify
```

On macOS or Linux:

```sh
sh ./mvnw verify
```

This runs the Java tests and builds the executable JAR. Launch it from the repository root:

```sh
java -jar target/quick-drop-0.0.1-SNAPSHOT.jar
```

Run the browser logic tests separately with Node.js:

```sh
node --test src/test/javascript/*.test.cjs
```

## Troubleshooting

Access requires owner permissions by default, including newly added routes and resources.
Only the explicit guest routes and current static assets in `OwnerAccessFilter` bypass
owner authentication, for their listed HTTP methods. Guest links still enforce their
token checks, setup remains localhost-only, and mutations still require CSRF verification.
When adding a public page or asset, review and update that allowlist explicitly.

Live file notifications recheck the subscribing session and PIN version before each
event. Logout, session expiry, or a changed PIN closes the stream before sending
another filename. A heartbeat checks idle streams every 15 seconds. Streams also
expire after five minutes; the browser reconnects through normal owner authentication.
Localhost streams retain the same local-owner access as the dashboard.

Storage lookups and listings can run concurrently. Mutations and recovery retain an
exclusive lock through database commit and disk cleanup to protect file consistency
and the shared storage quota. Upload copying happens outside that lock, and live-event
notifications are sent after the outermost storage lock is released. Slow notification
delivery can delay its own request, but does not hold the storage lock.

Uploads are admitted before multipart parsing: owner authentication, CSRF verification,
and an active receiving link are required where applicable. The retired sender route
and multipart bodies sent to other routes are rejected. Uploads allow at most two
concurrent requests globally and one per direct client IP (forwarded headers are ignored).
Busy requests return HTTP 429 with a retry delay.

Temporary multipart files live in `uploads/.incoming`, on the same volume as staged
files. The default temporary budget is 7 GiB, with 512 MiB of free disk space reserved.
Admission accounts for both the multipart spool and the staging copy; chunked uploads
reserve the maximum request size. Configure these with `quickdrop.upload.temp-budget`,
`quickdrop.upload.minimum-free-space`, and `quickdrop.upload.max-concurrent`.
The temporary budget must cover twice `spring.servlet.multipart.max-request-size`.
Disk checks are conservative and cannot reserve space against other applications.
Abandoned multipart files count against the budget; if necessary, stop Quick Drop
before removing files from `.incoming`. Never remove completed files in `.versions`.
Tomcat closes uploads after 30 seconds without incoming data; this is an idle timeout,
not a total upload duration limit. Rejected bodies are not drained, so some clients
may report a connection error instead of displaying the rejection message.

- **Database file is locked:** stop the other Quick Drop instance, then run the app again. A subsequent Hibernate dialect error can be a consequence of that database lock.
- **Port 8080 is already in use:** stop the process using it, or set `server.port` in `application.properties` and use that port in your browser.
- **Another device cannot connect:** check that the host is running and both devices are on the same network. Allow Java through Windows Firewall on your trusted Private network. Guest Wi-Fi or client isolation can prevent devices from connecting to each other.
- **The displayed address does not work:** a VPN or virtual network adapter may affect address detection. Check the host's local network address and use the address reachable from your other device.
- **Changes are not visible:** rebuild or restart the app, then refresh the browser. A packaged JAR must be rebuilt to include source changes.

## Built with

Java, Spring Boot, Spring MVC, Thymeleaf, Spring Data JPA, H2, ZXing, and browser JavaScript.
