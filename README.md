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

1. On the host computer, visit [Owner PIN setup](http://localhost:8080/owner/setup) and choose a four-digit PIN. Access through localhost already has owner permissions.
2. On another device on the same network, open the address shown under **Connect another device**, or scan its QR code.
3. Enter the owner PIN to access **Send files** and **Receive files**. Owner access includes uploading, viewing, downloading, sharing, and deleting files.
4. Choose or drop files in **Send files** to upload automatically. Use **Receive files** to download or manage them.

For someone who only needs to send files, expand **Let someone send files (optional)** and create a guest upload link. It can last 15 minutes, one hour, or 24 hours, and you can stop guest uploads early. Guests using this link cannot browse or download your stored files.

To share downloads, select files in **Receive files** and choose **Create share link**. Keep the host computer running and connected while links are in use.

Owner login allows five attempts per client before a one-minute cooldown. Repeated bursts double the cooldown up to 15 minutes; 30 minutes without an admitted attempt resets the client history. All attempts, including successful and malformed submissions, count. Across all clients, at most 30 attempts per minute and two simultaneous PIN checks are allowed. Blocked requests return HTTP 429 with a retry time. Limits use the direct connection address, are held in memory, and reset when the app restarts. Clients behind one proxy share a limit. Existing authenticated sessions and local dashboard access remain available during cooldowns.

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

- **Database file is locked:** stop the other Quick Drop instance, then run the app again. A subsequent Hibernate dialect error can be a consequence of that database lock.
- **Port 8080 is already in use:** stop the process using it, or set `server.port` in `application.properties` and use that port in your browser.
- **Another device cannot connect:** check that the host is running and both devices are on the same network. Allow Java through Windows Firewall on your trusted Private network. Guest Wi-Fi or client isolation can prevent devices from connecting to each other.
- **The displayed address does not work:** a VPN or virtual network adapter may affect address detection. Check the host's local network address and use the address reachable from your other device.
- **Changes are not visible:** rebuild or restart the app, then refresh the browser. A packaged JAR must be rebuilt to include source changes.

## Built with

Java, Spring Boot, Spring MVC, Thymeleaf, Spring Data JPA, H2, ZXing, and browser JavaScript.
